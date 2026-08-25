#!/usr/bin/env bash
# =============================================================================
# run_trace_pipeline.sh — Master Google Cluster Trace Replay Pipeline CLI
# =============================================================================
# Single-command end-to-end workload generation, simulation replay, and
# statistical validation pipeline for PureEdgeSim × Google Cluster Trace v3.
#
# Pipeline Stages:
#   Stage 1: Preprocess raw Parquet trace → intermediate JSONL
#   Stage 2: Translate to PureEdgeSim JSON task stream
#   Stage 3: End-to-End Simulation Replay via Python IPC Bridge
#   Stage 4: Statistical Fidelity & Validation Audit (Phase 5 6-test suite)
#
# Flags:
#   --window <label>       Trace window label (default: 15min)
#   --orchestrator <name>  Python orchestrator class or alias (default: RoundRobin)
#                            Aliases: RoundRobin, NearestCloud, DQN
#   --seed <int>           RNG seed for synthetic modeling (default: 42)
#   --skip-prep            Skip Stage 1 & 2 preprocessing if files exist
#   --dry-run              Validate configuration and exit without running simulation
#   --help                 Display this help message
#
# Examples:
#   bash scripts/run_trace_pipeline.sh --window 15min --orchestrator RoundRobin
#   bash scripts/run_trace_pipeline.sh --orchestrator NearestCloud --seed 123
#   bash scripts/run_trace_pipeline.sh --skip-prep
# =============================================================================
set -euo pipefail

# ── Project Directory ─────────────────────────────────────────────────────────
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
cd "$PROJECT_ROOT"

# ── Options & Defaults ────────────────────────────────────────────────────────
WINDOW_LABEL="15min"
ORCHESTRATOR_ALIAS="RoundRobin"
RNG_SEED=42
SKIP_PREP=false
DRY_RUN=false
BUFFER_SIZE=""
LOW_WATERMARK=""

# ── Formatting / Colors ───────────────────────────────────────────────────────
BOLD='\033[1m'
GREEN='\033[0;32m'
CYAN='\033[0;36m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
RESET='\033[0m'

log_step() { echo -e "\n${BOLD}${CYAN}======================================================================${RESET}"; echo -e "${BOLD}${CYAN}  $*${RESET}"; echo -e "${BOLD}${CYAN}======================================================================${RESET}"; }
log_info() { echo -e "${GREEN}[INFO]${RESET} $*"; }
log_warn() { echo -e "${YELLOW}[WARN]${RESET} $*"; }
log_err()  { echo -e "${RED}[ERROR]${RESET} $*"; }

usage() {
    cat << EOF
Usage: $0 [OPTIONS]

Master pipeline script for Google Cluster Trace v3 workload replay in PureEdgeSim.

Options:
  --window <label>       Trace duration window label (default: 15min)
  --orchestrator <name>  Python orchestrator algorithm alias or full dotted path (default: RoundRobin)
                           Aliases: RoundRobin, NearestCloud, DQN
  --seed <int>           Random seed for reproducible synthetic generation (default: 42)
  --buffer-size <int>    JVM task buffer target size (default: 1000 for 15min, 2000 for scaled)
  --low-watermark <int>  JVM task buffer low watermark threshold (default: 200 for 15min, 500 for scaled)
  --skip-prep            Skip preprocessing and translation if processed files exist
  --dry-run              Verify configuration and dependencies without launching simulation
  -h, --help             Show this help message and exit
EOF
    exit 0
}

# ── Parse Command Line Arguments ──────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
    case "$1" in
        --window)
            WINDOW_LABEL="$2"; shift 2 ;;
        --orchestrator)
            ORCHESTRATOR_ALIAS="$2"; shift 2 ;;
        --seed)
            RNG_SEED="$2"; shift 2 ;;
        --buffer-size)
            BUFFER_SIZE="$2"; shift 2 ;;
        --low-watermark)
            LOW_WATERMARK="$2"; shift 2 ;;
        --skip-prep)
            SKIP_PREP=true; shift ;;
        --dry-run)
            DRY_RUN=true; shift ;;
        -h|--help)
            usage ;;
        *)
            log_err "Unknown argument: $1"
            usage ;;
    esac
done

# Set dynamic defaults for buffer size based on window scale if not specified
if [[ -z "$BUFFER_SIZE" ]]; then
    if [[ "$WINDOW_LABEL" == "15min" ]]; then BUFFER_SIZE=1000; else BUFFER_SIZE=2000; fi
fi
if [[ -z "$LOW_WATERMARK" ]]; then
    if [[ "$WINDOW_LABEL" == "15min" ]]; then LOW_WATERMARK=200; else LOW_WATERMARK=500; fi
fi

# ── Resolve Orchestrator Class ────────────────────────────────────────────────
case "$ORCHESTRATOR_ALIAS" in
    RoundRobin|round_robin|rr)
        ORCH_CLASS="examples.run_trace_round_robin.TraceRoundRobinOrchestrator" ;;
    NearestCloud|nearest_cloud|nc)
        ORCH_CLASS="examples.run_trace_nearest_cloud.TraceNearestCloudOrchestrator" ;;
    DQN|dqn)
        ORCH_CLASS="examples.run_dqn_skeleton.DQNOrchestrator" ;;
    *)
        ORCH_CLASS="$ORCHESTRATOR_ALIAS" ;;
esac

# ── Resolve File Paths ────────────────────────────────────────────────────────
PARQUET_FILE="data/raw/google_v3_cell_a_${WINDOW_LABEL}.parquet"
INTERMEDIATE_FILE="data/processed/google_v3_cell_a_${WINDOW_LABEL}_intermediate.jsonl"
TASKS_FILE="data/processed/pureedgesim_tasks_${WINDOW_LABEL}.json"
MANIFEST_FILE="data/metadata/extraction_manifest.json"
LOG_DIR="docs/logs"
PYTHON_BIN="python/.venv/bin/python"

mkdir -p data/processed data/metadata "$LOG_DIR"

log_step "PureEdgeSim × Google Cluster Trace v3 Pipeline"
log_info "Window Label      : $WINDOW_LABEL"
log_info "Orchestrator      : $ORCH_CLASS ($ORCHESTRATOR_ALIAS)"
log_info "RNG Seed          : $RNG_SEED"
log_info "Skip Preprocess   : $SKIP_PREP"
log_info "Dry Run           : $DRY_RUN"
log_info "Python Executable : $PYTHON_BIN"

# ── Environment Verification ─────────────────────────────────────────────────
if [[ ! -x "$PYTHON_BIN" ]]; then
    log_err "Python virtual environment not found at $PYTHON_BIN."
    log_err "Please ensure python/.venv is set up."
    exit 1
fi

if [[ "$DRY_RUN" == "true" ]]; then
    log_info "Dry run requested — configuration validated successfully."
    exit 0
fi

START_TIME=$(date +%s)

# ── Stage 1: Trace Preprocessing ──────────────────────────────────────────────
if [[ "$SKIP_PREP" == "true" && -f "$INTERMEDIATE_FILE" ]]; then
    log_info "Skipping Stage 1 (Preprocessing intermediate JSONL already exists)."
else
    log_step "Stage 1: Preprocessing Raw Trace → Intermediate JSONL"
    if [[ ! -f "$PARQUET_FILE" ]]; then
        log_err "Raw Parquet trace file missing: $PARQUET_FILE"
        exit 1
    fi
    "$PYTHON_BIN" python/scripts/preprocess_google_trace.py \
        --input "$PARQUET_FILE" \
        --output "$INTERMEDIATE_FILE" \
        --log "$LOG_DIR/m3_preprocess.log"
fi

# ── Stage 2: PureEdgeSim Spec Translation ─────────────────────────────────────
if [[ "$SKIP_PREP" == "true" && -f "$TASKS_FILE" ]]; then
    log_info "Skipping Stage 2 (Translated PureEdgeSim JSON tasks file already exists)."
else
    log_step "Stage 2: Translating Intermediate JSONL → PureEdgeSim Task Stream"
    "$PYTHON_BIN" python/scripts/translate_to_pureedgesim.py \
        --input "$INTERMEDIATE_FILE" \
        --output "$TASKS_FILE" \
        --n-edge-devices 10 \
        --mips-base 2000 \
        --max-cell-ram-gb 64.0 \
        --seed "$RNG_SEED" \
        --log "$LOG_DIR/m4_translate.log"
fi

# ── Manifest & SHA-256 Checksum Update ────────────────────────────────────────
log_info "Computing SHA-256 checksum of generated task dataset..."
CHECKSUM=$(sha256sum "$TASKS_FILE" | awk '{print $1}')
log_info "Dataset SHA-256: $CHECKSUM"

# ── Stage 3: End-to-End Simulation Replay ─────────────────────────────────────
log_step "Stage 3: End-to-End Simulation Replay (PureEdgeSim + Python Bridge)"
log_info "Compiling PureEdgeSim Java codebase..."
mvn compile -q

log_info "Executing simulation replay with orchestrator: $ORCH_CLASS (Buffer: $BUFFER_SIZE, Watermark: $LOW_WATERMARK)"
mvn exec:exec \
    -Dexec.executable=java \
    -Dexec.args="-Xmx2g -Dtrace.file.path=${TASKS_FILE} -Dtrace.buffer.size=${BUFFER_SIZE} -Dtrace.low.watermark=${LOW_WATERMARK} -cp %classpath examples.ExampleTraceE2E ${ORCH_CLASS}"

# ── Stage 4: Statistical Fidelity & Validation Audit ─────────────────────────
log_step "Stage 4: Executing Phase 5 Formal Validation Suite"
"$PYTHON_BIN" python/scripts/validate_workload_replay.py --window "$WINDOW_LABEL"

END_TIME=$(date +%s)
ELAPSED=$((END_TIME - START_TIME))

log_step "Pipeline Completed Successfully"
log_info "Total Pipeline Wall Time : ${ELAPSED} seconds"
log_info "Task Dataset File        : $TASKS_FILE"
log_info "Task Dataset SHA-256     : $CHECKSUM"
log_info "Validation Report        : $LOG_DIR/m7_validation_report.md"
log_info "Simulation Output        : PureEdgeSim/output/"
echo ""
