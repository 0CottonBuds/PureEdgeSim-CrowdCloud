#!/usr/bin/env bash
# =============================================================================
# bq_extract.sh — M1: BigQuery Extraction Script
# =============================================================================
# Extracts a time-windowed subset of Google Cluster Trace v3 (cell: 2019_a)
# into a local Parquet file via the Google Cloud BigQuery CLI.
#
# Usage:
#   ./scripts/bq_extract.sh [OPTIONS]
#
# Options:
#   --window  <15min|1h|12h|24h>   Time window preset (default: 15min)
#   --start   <int>                Start time in microseconds (overrides --window)
#   --end     <int>                End time in microseconds   (overrides --window)
#   --cell    <a|b|c|...>          Borg cell letter (default: a)
#   --dry-run                      Estimate scan cost only, do not run query
#   --help                         Show this help message
#
# Examples:
#   ./scripts/bq_extract.sh                        # 15-min dev subset
#   ./scripts/bq_extract.sh --window 1h            # 1-hour milestone
#   ./scripts/bq_extract.sh --window 12h           # 12-hour scaling test
#   ./scripts/bq_extract.sh --dry-run              # Cost estimate only
#   ./scripts/bq_extract.sh --start 600000000 --end 1500000000
#
# Prerequisites:
#   - gcloud CLI authenticated:  gcloud auth login
#   - GCP project set:           gcloud config set project <your-project>
#   - bq CLI available:          which bq
#   - pandas + pyarrow installed for Parquet conversion
#
# =============================================================================

set -euo pipefail

# ---------------------------------------------------------------------------
# Defaults
# ---------------------------------------------------------------------------
WINDOW="15min"
CELL="a"
DRY_RUN=false
CUSTOM_START=""
CUSTOM_END=""

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(cd "${SCRIPT_DIR}/.." && pwd)"

SQL_FILE="${PROJECT_ROOT}/sql/extract_lifecycle_events.sql"
DATA_RAW_DIR="${PROJECT_ROOT}/data/raw"
DATA_META_DIR="${PROJECT_ROOT}/data/metadata"
LOGS_DIR="${PROJECT_ROOT}/docs/logs"

BQ_PROJECT="google.com:google-cluster-data"

# ---------------------------------------------------------------------------
# Trace epoch offset reference
# The trace starts 600 seconds (600,000,000 µs) before the first real event.
# All time windows are defined relative to this offset.
# ---------------------------------------------------------------------------
TRACE_EPOCH_OFFSET_US=600000000   # 600s = 600,000,000 µs (trace epoch base)

declare -A WINDOW_PRESETS=(
  ["15min"]="900000000"             # 900s   = 900,000,000 µs
  ["1h"]="3600000000"               # 3600s  = 3,600,000,000 µs
  ["12h"]="43200000000"             # 43200s = 43,200,000,000 µs
  ["24h"]="86400000000"             # 86400s = 86,400,000,000 µs
)

# ---------------------------------------------------------------------------
# Argument parsing
# ---------------------------------------------------------------------------
while [[ $# -gt 0 ]]; do
  case "$1" in
    --window)  WINDOW="$2";       shift 2 ;;
    --cell)    CELL="$2";         shift 2 ;;
    --start)   CUSTOM_START="$2"; shift 2 ;;
    --end)     CUSTOM_END="$2";   shift 2 ;;
    --dry-run) DRY_RUN=true;      shift   ;;
    --help)
      head -40 "${BASH_SOURCE[0]}" | grep -E "^#" | sed 's/^# //; s/^#//'
      exit 0 ;;
    *) echo "[ERROR] Unknown argument: $1" >&2; exit 1 ;;
  esac
done

# ---------------------------------------------------------------------------
# Resolve time window
# ---------------------------------------------------------------------------
if [[ -n "${CUSTOM_START}" && -n "${CUSTOM_END}" ]]; then
  START_US="${CUSTOM_START}"
  END_US="${CUSTOM_END}"
  LABEL="custom_${START_US}_${END_US}"
else
  if [[ -z "${WINDOW_PRESETS[${WINDOW}]+_}" ]]; then
    echo "[ERROR] Unknown --window '${WINDOW}'. Valid: 15min, 1h, 12h, 24h" >&2
    exit 1
  fi
  DURATION_US="${WINDOW_PRESETS[${WINDOW}]}"
  START_US="${TRACE_EPOCH_OFFSET_US}"
  END_US="$(( START_US + DURATION_US ))"
  LABEL="${WINDOW}"
fi

DATASET_ID="clusterdata_2019_${CELL}"
DATASET_FULL="${BQ_PROJECT}.${DATASET_ID}"
OUTPUT_BASE="google_v3_cell_${CELL}_${LABEL}"
OUTPUT_PARQUET="${DATA_RAW_DIR}/${OUTPUT_BASE}.parquet"
MANIFEST_FILE="${DATA_META_DIR}/extraction_manifest.json"
LOG_FILE="${LOGS_DIR}/m1_extraction.md"

# ---------------------------------------------------------------------------
# Banner
# ---------------------------------------------------------------------------
echo "============================================================"
echo "  Google Cluster Trace v3 — BigQuery Extraction (M1)"
echo "============================================================"
echo "  Cell         : ${CELL} (${DATASET_FULL})"
echo "  Window       : ${LABEL}"
echo "  Start (µs)   : ${START_US}  (= $(( START_US / 1000000 ))s)"
echo "  End   (µs)   : ${END_US}  (= $(( END_US / 1000000 ))s)"
echo "  Duration (s) : $(( (END_US - START_US) / 1000000 ))"
echo "  Output       : ${OUTPUT_PARQUET}"
echo "  Dry-run      : ${DRY_RUN}"
echo "============================================================"

# ---------------------------------------------------------------------------
# Validate prerequisites
# ---------------------------------------------------------------------------
command -v bq      >/dev/null 2>&1 || { echo "[ERROR] bq CLI not found." >&2; exit 1; }
command -v gcloud  >/dev/null 2>&1 || { echo "[ERROR] gcloud CLI not found." >&2; exit 1; }
command -v python3 >/dev/null 2>&1 || { echo "[ERROR] python3 not found." >&2; exit 1; }

[[ -f "${SQL_FILE}" ]] || { echo "[ERROR] SQL file not found: ${SQL_FILE}" >&2; exit 1; }

mkdir -p "${DATA_RAW_DIR}" "${DATA_META_DIR}" "${LOGS_DIR}"

# ---------------------------------------------------------------------------
# Step 1: Dry-run cost estimate
# ---------------------------------------------------------------------------
echo ""
echo "[Step 1] Running dry-run cost estimate..."
DRY_OUTPUT=$(bq query \
  --use_legacy_sql=false \
  --dry_run \
  --parameter="start_time_us:INT64:${START_US}" \
  --parameter="end_time_us:INT64:${END_US}" \
  < "${SQL_FILE}" 2>&1)

echo "${DRY_OUTPUT}"

# Extract estimated bytes processed (format: "...will process N bytes of data")
BYTES_PROCESSED=$(echo "${DRY_OUTPUT}" | grep -oP '[0-9]+ bytes' | grep -oP '[0-9]+' | head -1 || echo "0")
BYTES_GB=$(echo "scale=1; ${BYTES_PROCESSED} / 1073741824" | bc 2>/dev/null || echo "?")

echo ""
echo "[INFO] Estimated scan size: ~${BYTES_GB} GB (${BYTES_PROCESSED} bytes)"
echo "[NOTE] Google Cluster Trace instance_events is not time-partitioned, so the"
echo "       full table (~147 GB) is scanned regardless of time window. This is"
echo "       expected. First 1 TB/month is free under BigQuery free tier."

if [[ "${DRY_RUN}" == "true" ]]; then
  echo "[INFO] --dry-run mode: skipping actual query execution."
  exit 0
fi

# ---------------------------------------------------------------------------
# Step 2: Execute BigQuery query → JSON Lines temp file
# ---------------------------------------------------------------------------
TEMP_JSON="${DATA_RAW_DIR}/${OUTPUT_BASE}_tmp.jsonl"
echo ""
echo "[Step 2] Executing BigQuery query → ${TEMP_JSON}..."

START_TS=$(date +%s)

bq query \
  --use_legacy_sql=false \
  --format=json \
  --max_rows=2000000 \
  --parameter="start_time_us:INT64:${START_US}" \
  --parameter="end_time_us:INT64:${END_US}" \
  < "${SQL_FILE}" > "${TEMP_JSON}"

END_TS=$(date +%s)
QUERY_DURATION=$(( END_TS - START_TS ))
# bq --format=json outputs a JSON array on one line; use Python to count elements
RAW_BQ_ROW_COUNT=$(python3 -c "import json; data=json.load(open('${TEMP_JSON}')); print(len(data) if isinstance(data,list) else 0)" 2>/dev/null || echo "0")
ROW_COUNT="${RAW_BQ_ROW_COUNT}"  # will be updated after Python filtering

echo "[INFO] Query completed in ${QUERY_DURATION}s — ${ROW_COUNT} raw rows from BigQuery."

if [[ "${ROW_COUNT}" -eq 0 ]]; then
  echo "[ERROR] No rows returned. Check time window parameters or BigQuery access." >&2
  rm -f "${TEMP_JSON}"
  exit 1
fi

# ---------------------------------------------------------------------------
# Step 3: Convert JSON Lines → Parquet via Python
# ---------------------------------------------------------------------------
echo ""
echo "[Step 3] Converting JSON Lines → Parquet (${OUTPUT_PARQUET})..."

python3 - <<PYTHON_EOF
import json
import sys
import pandas as pd

input_file  = "${TEMP_JSON}"
output_file = "${OUTPUT_PARQUET}"

print(f"[Python] Reading JSON from {input_file}...")
with open(input_file, "r") as f:
    raw = f.read().strip()

# bq --format=json outputs a JSON array
data = json.loads(raw) if raw.startswith("[") else [json.loads(l) for l in raw.splitlines() if l.strip()]

if not data:
    print("[Python] ERROR: No records to convert.", file=sys.stderr)
    sys.exit(1)

df = pd.DataFrame(data)

# Enforce correct dtypes
int64_cols  = ["collection_id", "instance_index", "submit_time_us",
               "schedule_time_us", "finish_time_us", "queue_time_us",
               "exec_time_us", "total_residence_time_us", "final_event_type",
               "scheduled_machine_id", "alloc_collection_id", "alloc_instance_index",
               "priority", "scheduling_class"]
float64_cols = ["req_cpus", "req_memory"]
str_cols    = ["finish_status", "user", "collection_logical_name"]

for col in int64_cols:
    if col in df.columns:
        df[col] = pd.to_numeric(df[col], errors="coerce").astype("Int64")
for col in float64_cols:
    if col in df.columns:
        df[col] = pd.to_numeric(df[col], errors="coerce").astype("float64")
for col in str_cols:
    if col in df.columns:
        df[col] = df[col].astype(str)

print(f"[Python] Raw rows (before filtering): {len(df)}")

# Data quality filter: remove tasks where exec_time_us <= 0
# This happens for EVICT/FAIL tasks where finish_time < schedule_time (Borg race condition)
if "exec_time_us" in df.columns:
    pre_count = len(df)
    df = df[df["exec_time_us"].fillna(0) > 0]
    removed = pre_count - len(df)
    if removed > 0:
        print(f"[Python] Filtered out {removed} rows with exec_time_us <= 0 (race-condition artifacts)")

print(f"[Python] Final DataFrame shape: {df.shape}")
print(f"[Python] Columns: {list(df.columns)}")
print(f"[Python] finish_status distribution:")
print(df["finish_status"].value_counts().to_string())

df.to_parquet(output_file, index=False, engine="pyarrow", compression="snappy")
print(f"[Python] Parquet written → {output_file}")
PYTHON_EOF

# ---------------------------------------------------------------------------
# Step 4: Compute SHA-256 checksum and final row count from Parquet
# ---------------------------------------------------------------------------
echo ""
echo "[Step 4] Computing SHA-256 checksum..."
SHA256=$(sha256sum "${OUTPUT_PARQUET}" | awk '{print $1}')
FILE_SIZE=$(du -sh "${OUTPUT_PARQUET}" | awk '{print $1}')
FINAL_ROW_COUNT=$(python3 -c "import pandas as pd; df=pd.read_parquet('${OUTPUT_PARQUET}'); print(len(df))" 2>/dev/null || echo "${ROW_COUNT}")
echo "[INFO] SHA-256: ${SHA256}"
echo "[INFO] File size: ${FILE_SIZE}"
echo "[INFO] Final row count (post-filter): ${FINAL_ROW_COUNT}"

# ---------------------------------------------------------------------------
# Step 5: Write extraction manifest (JSON)
# ---------------------------------------------------------------------------
echo ""
echo "[Step 5] Writing extraction manifest → ${MANIFEST_FILE}..."
TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")

cat > "${MANIFEST_FILE}" <<MANIFEST_EOF
{
  "extraction_timestamp": "${TIMESTAMP}",
  "cell": "${CELL}",
  "dataset": "${DATASET_FULL}",
  "window_label": "${LABEL}",
  "start_time_us": ${START_US},
  "end_time_us": ${END_US},
  "window_duration_s": $(( (END_US - START_US) / 1000000 )),
  "row_count_raw_bq": ${ROW_COUNT},
  "row_count_final": ${FINAL_ROW_COUNT},
  "output_file": "${OUTPUT_PARQUET}",
  "file_size_human": "${FILE_SIZE}",
  "sha256": "${SHA256}",
  "bytes_scanned_estimated": ${BYTES_PROCESSED},
  "query_duration_s": ${QUERY_DURATION},
  "sql_file": "${SQL_FILE}"
}
MANIFEST_EOF

echo "[INFO] Manifest written."

# ---------------------------------------------------------------------------
# Step 6: Cleanup temp JSONL
# ---------------------------------------------------------------------------
rm -f "${TEMP_JSON}"
echo "[INFO] Temp JSONL removed."

# ---------------------------------------------------------------------------
# Step 7: Write M1 log entry
# ---------------------------------------------------------------------------
cat >> "${LOG_FILE}" <<LOG_EOF

## M1 Extraction Run — ${TIMESTAMP}

| Parameter | Value |
|---|---|
| Cell | ${CELL} |
| Dataset | ${DATASET_FULL} |
| Window | ${LABEL} |
| Start µs | ${START_US} |
| End µs | ${END_US} |
| Duration | $(( (END_US - START_US) / 1000000 )) s |
| Rows extracted | ${ROW_COUNT} |
| Est. bytes scanned | ${BYTES_PROCESSED} (~${BYTES_GB} GB) |
| Query duration | ${QUERY_DURATION} s |
| Output file | ${OUTPUT_PARQUET} |
| File size | ${FILE_SIZE} |
| SHA-256 | \`${SHA256}\` |

LOG_EOF

echo "[INFO] Log appended → ${LOG_FILE}"

# ---------------------------------------------------------------------------
# Done
# ---------------------------------------------------------------------------
echo ""
echo "============================================================"
echo "  [M1 COMPLETE] Extraction successful."
echo "  Rows : ${ROW_COUNT}"
echo "  File : ${OUTPUT_PARQUET} (${FILE_SIZE})"
echo "  SHA  : ${SHA256}"
echo "============================================================"
