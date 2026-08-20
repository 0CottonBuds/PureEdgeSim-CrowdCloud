#!/usr/bin/env python3
"""
translate_to_pureedgesim.py — Milestone 4: PureEdgeSim Task Spec Translation
=============================================================================
Reads the intermediate JSON Lines file (M3 output) and produces a fully
PureEdgeSim-compatible task specification JSON Lines file ready for ingestion
by StreamedTraceTaskGenerator (M5).

Translation rules applied (from phase3_translation_mapping_design.md):

  DIRECTLY DERIVED (from observed/derived data):
    id                  : 1-based sequential integer
    time                : t_arrival (seconds)
    length              : round(req_cpus × MIPS_BASE × delta_t_exec)  [MI]
    containerSizeInBits : round(req_memory × MAX_CELL_RAM_BYTES × 8)  [bits]

  SYNTHETICALLY MODELED (defensible domain assumptions):
    fileSizeInBits      : round(containerSizeInBits × 0.25 × LogNormal(μ=0, σ=0.5))
    outputSizeInBits    : round(fileSizeInBits × Uniform(0.05, 0.20))
    maxLatency          : delta_t_exec × (1.0 + SlackFactor(scheduling_class, priority))
    edgeDevice          : abs(hash(user)) % N_EDGE_DEVICES

  SlackFactor formula (κ=0.5, λ=0.5, P_max=450):
    SlackFactor = 0.5 × (3 - scheduling_class) + 0.5 × (1.0 - priority / 450.0)
    → Sc=3, P=450: SlackFactor=0.0  → maxLatency = 1.0 × delta_t_exec (strict)
    → Sc=0, P=0:   SlackFactor=2.0  → maxLatency = 3.0 × delta_t_exec (relaxed)

  METADATA (preserved for RL scheduler):
    priority, scheduling_class, finish_status, collection_logical_name,
    delta_t_exec, delta_t_queue, t_arrival, req_cpus, req_memory

Usage:
  python3 python/scripts/translate_to_pureedgesim.py
  python3 python/scripts/translate_to_pureedgesim.py \\
    --input  data/processed/google_v3_cell_a_15min_intermediate.jsonl \\
    --output data/processed/pureedgesim_tasks_15min.json \\
    --n-edge-devices 10

Arguments:
  --input          PATH   Intermediate JSONL (M3 output)
  --output         PATH   PureEdgeSim task JSON Lines (default: data/processed/pureedgesim_tasks_<stem>.json)
  --n-edge-devices INT    Number of edge devices in simulation (default: 10)
  --mips-base      INT    Baseline core MIPS for Length_MI calculation (default: 2000)
  --max-cell-ram-gb FLOAT Max cell RAM in GB for unscaling req_memory (default: 64.0)
  --seed           INT    RNG seed for reproducibility (default: 42)
  --log            PATH   Log file (default: docs/logs/m4_translation.md)
  --no-log                Skip log file
"""

import argparse
import json
import math
import sys
from datetime import datetime, timezone
from pathlib import Path

import numpy as np

# ---------------------------------------------------------------------------
# Constants (matching PureEdgeSim simulation configuration)
# ---------------------------------------------------------------------------
PROJECT_ROOT = Path(__file__).resolve().parents[2]

DEFAULT_INPUT         = PROJECT_ROOT / "data" / "processed" / "google_v3_cell_a_15min_intermediate.jsonl"
DEFAULT_LOG           = PROJECT_ROOT / "docs" / "logs" / "m4_translation.md"

# Processing parameters
DEFAULT_MIPS_BASE     = 2000          # MIPS of 1 baseline PureEdgeSim core
DEFAULT_MAX_CELL_RAM_GB = 64.0        # Max Borg cell RAM for unscaling req_memory [0,1]
DEFAULT_N_EDGE_DEVICES = 10           # Must match PureEdgeSim edge_devices count
DEFAULT_SEED          = 42

# Synthetic modeling parameters
ALPHA_INPUT           = 0.25          # fileSizeInBits = containerSizeInBits × α × LogNormal
LOGNORMAL_MU          = 0.0           # LogNormal μ
LOGNORMAL_SIGMA       = 0.5           # LogNormal σ
BETA_OUTPUT_MIN       = 0.05          # outputSizeInBits = fileSizeInBits × Uniform(0.05, 0.20)
BETA_OUTPUT_MAX       = 0.20
SLACK_KAPPA           = 0.5           # SlackFactor weight for scheduling_class term
SLACK_LAMBDA          = 0.5           # SlackFactor weight for priority term
P_MAX                 = 450.0         # Maximum Borg priority


# ---------------------------------------------------------------------------
# Translation helpers
# ---------------------------------------------------------------------------
def slack_factor(scheduling_class: int, priority: int) -> float:
    """
    Compute SlackFactor from Borg scheduling_class and priority.
    SlackFactor = κ(3 - Sc) + λ(1 - P/P_max)
    Range: [0.0 (strict), 2.0 (relaxed)]
    """
    sc = max(0, min(3, int(scheduling_class)))
    p  = max(0, min(P_MAX, float(priority)))
    return SLACK_KAPPA * (3 - sc) + SLACK_LAMBDA * (1.0 - p / P_MAX)


def compute_length_mi(req_cpus: float, mips_base: int, delta_t_exec: float) -> int:
    """
    Length_MI = round(req_cpus × MIPS_base × ΔT_exec)
    Ensures execution on un-contended baseline host takes exactly ΔT_exec seconds.
    Minimum 1 MI to avoid zero-length tasks.
    """
    return max(1, round(req_cpus * mips_base * delta_t_exec))


def compute_container_bits(req_memory: float, max_cell_ram_bytes: int) -> int:
    """
    containerSizeInBits = round(req_memory × MaxCellRAM_bytes × 8)
    Unscales normalized [0,1] RAM fraction back to physical bits.
    Minimum 1 bit to avoid zero-size containers.
    """
    return max(1, round(req_memory * max_cell_ram_bytes * 8))


def user_to_device_index(user: str, n_edge_devices: int) -> int:
    """
    DeviceIndex = abs(hashCode(user)) % N_edge_devices
    Deterministic mapping: same user always maps to same edge device.
    Uses Python's hash() with a consistent fallback for empty strings.
    """
    if not user or user in ("None", "nan", ""):
        return 0
    # Use Java-compatible hash-like approach: sum of char * 31^i
    h = 0
    for ch in user:
        h = (31 * h + ord(ch)) & 0xFFFFFFFFFFFFFFFF
    # Convert to signed 64-bit
    if h >= (1 << 63):
        h -= (1 << 64)
    return abs(h) % n_edge_devices


# ---------------------------------------------------------------------------
# Main translation
# ---------------------------------------------------------------------------
def translate(
    input_path: Path,
    output_path: Path,
    n_edge_devices: int,
    mips_base: int,
    max_cell_ram_gb: float,
    seed: int,
    log_path: Path | None,
) -> bool:
    timestamp = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")
    max_cell_ram_bytes = int(max_cell_ram_gb * 1024**3)  # GB → bytes

    print("=" * 72)
    print(f"  M4 — PureEdgeSim Task Spec Translation")
    print(f"  Input          : {input_path}")
    print(f"  Output         : {output_path}")
    print(f"  N_edge_devices : {n_edge_devices}")
    print(f"  MIPS_base      : {mips_base}")
    print(f"  MaxCellRAM     : {max_cell_ram_gb:.1f} GB  ({max_cell_ram_bytes:,} bytes)")
    print(f"  RNG seed       : {seed}")
    print("=" * 72)

    if not input_path.exists():
        print(f"[ERROR] Input not found: {input_path}", file=sys.stderr)
        print("  Run M3 first: python3 python/scripts/preprocess_google_trace.py", file=sys.stderr)
        return False

    # Seed the RNG for reproducible synthetic modeling
    rng = np.random.default_rng(seed)

    # -----------------------------------------------------------------------
    # Read all intermediate records
    # -----------------------------------------------------------------------
    records = []
    with open(input_path) as f:
        for line in f:
            line = line.strip()
            if line:
                records.append(json.loads(line))
    n_input = len(records)
    print(f"\n[1] Loaded {n_input:,} intermediate records.")

    # -----------------------------------------------------------------------
    # Pre-generate random variates (vectorized for efficiency)
    # -----------------------------------------------------------------------
    print(f"\n[2] Generating synthetic random variates (seed={seed})...")
    lognormal_samples = rng.lognormal(mean=LOGNORMAL_MU, sigma=LOGNORMAL_SIGMA, size=n_input)
    uniform_samples   = rng.uniform(low=BETA_OUTPUT_MIN, high=BETA_OUTPUT_MAX, size=n_input)

    # -----------------------------------------------------------------------
    # Translate each record
    # -----------------------------------------------------------------------
    print(f"\n[3] Translating {n_input:,} records...")
    output_path.parent.mkdir(parents=True, exist_ok=True)

    # Audit counters
    n_written      = 0
    length_zero    = 0
    container_zero = 0
    deadline_violations = 0

    # Stats accumulators
    lengths         = []
    container_sizes = []
    file_sizes      = []
    max_latencies   = []
    slack_factors   = []
    device_counts   = [0] * n_edge_devices

    with open(output_path, "w") as out_f:
        for i, rec in enumerate(records):
            task_id      = i + 1  # 1-based
            req_cpus     = float(rec.get("req_cpus",     0) or 0)
            req_memory   = float(rec.get("req_memory",   0) or 0)
            delta_t_exec = float(rec.get("delta_t_exec", 0) or 0)
            sc           = int(rec.get("scheduling_class", 0) or 0)
            pri          = int(rec.get("priority",         0) or 0)
            user         = str(rec.get("user", "") or "")

            # --- Directly derived ---
            length_mi        = compute_length_mi(req_cpus, mips_base, delta_t_exec)
            container_bits   = compute_container_bits(req_memory, max_cell_ram_bytes)

            # --- Synthetically modeled ---
            file_bits        = max(1, round(container_bits * ALPHA_INPUT * lognormal_samples[i]))
            output_bits      = max(1, round(file_bits * uniform_samples[i]))
            sf               = slack_factor(sc, pri)
            max_latency      = delta_t_exec * (1.0 + sf)
            device_idx       = user_to_device_index(user, n_edge_devices)

            # Audit tracking
            if length_mi <= 0:
                length_zero += 1
            if container_bits <= 0:
                container_zero += 1
            if max_latency < delta_t_exec:
                deadline_violations += 1

            lengths.append(length_mi)
            container_sizes.append(container_bits)
            file_sizes.append(file_bits)
            max_latencies.append(max_latency)
            slack_factors.append(sf)
            device_counts[device_idx] += 1

            # Build PureEdgeSim task record
            task = {
                # Core PureEdgeSim Task fields
                "id":                  task_id,
                "time":                round(float(rec["t_arrival"]), 9),
                "length":              length_mi,
                "fileSizeInBits":      file_bits,
                "outputSizeInBits":    output_bits,
                "containerSizeInBits": container_bits,
                "maxLatency":          round(max_latency, 9),
                "edgeDevice":          device_idx,
                "applicationID":       0,  # Default; StreamedTaskGenerator maps this

                # Metadata for RL scheduler and post-sim analysis
                "metadata": {
                    "priority":               pri,
                    "scheduling_class":       sc,
                    "finish_status":          str(rec.get("finish_status", "UNKNOWN")),
                    "collection_logical_name": str(rec.get("collection_logical_name", "")),
                    "collection_id":          rec.get("collection_id"),
                    "instance_index":         rec.get("instance_index"),
                    "delta_t_exec_s":         round(delta_t_exec, 6),
                    "delta_t_queue_s":        round(float(rec.get("delta_t_queue", 0) or 0), 6),
                    "req_cpus":               round(req_cpus, 9),
                    "req_memory":             round(req_memory, 9),
                    "slack_factor":           round(sf, 6),
                },
            }
            out_f.write(json.dumps(task, separators=(",", ":")) + "\n")
            n_written += 1

    # -----------------------------------------------------------------------
    # Validation checks
    # -----------------------------------------------------------------------
    print(f"\n[4] Running M4 validation checks...")

    all_checks = {
        "length > 0 for all tasks":           (length_zero == 0),
        "containerSizeInBits > 0 for all":    (container_zero == 0),
        "maxLatency >= delta_t_exec for all": (deadline_violations == 0),
        "Record count invariant (N_out==N_in)":(n_written == n_input),
        "edgeDevice in [0, N_edge-1]":        all(0 <= d < n_edge_devices for d in range(n_edge_devices)),
    }

    all_pass = True
    for check, passed in all_checks.items():
        mark = "PASS ✓" if passed else "FAIL ✗"
        print(f"  [{mark}] {check}")
        if not passed:
            all_pass = False

    if length_zero > 0:
        print(f"  [DETAIL] {length_zero} tasks had length_mi = 0  (req_cpus=0 or delta_t_exec=0)")
    if deadline_violations > 0:
        print(f"  [DETAIL] {deadline_violations} deadline violations — CRITICAL: review SlackFactor formula")

    # -----------------------------------------------------------------------
    # Distribution summary
    # -----------------------------------------------------------------------
    print(f"\n[5] Translation summary:")
    _print_stats("length_mi (MI)",          lengths,        unit="MI")
    _print_stats("containerSizeInBits",      container_sizes, unit="bits", scale=1/8/1024/1024, unit_label="MB")
    _print_stats("fileSizeInBits",           file_sizes,      unit="bits", scale=1/8/1024/1024, unit_label="MB")
    _print_stats("maxLatency (s)",           max_latencies,   unit="s")
    _print_stats("slackFactor",              slack_factors)

    print(f"\n  edgeDevice distribution (uniformity check):")
    for dev_idx, cnt in enumerate(device_counts):
        bar = "█" * int(cnt / n_written * 40)
        print(f"    Device {dev_idx:>2}: {cnt:>5,}  ({cnt/n_written*100:5.1f}%)  {bar}")

    print(f"\n{'='*72}")
    if all_pass:
        print(f"  ✅  M4 PASSED — {n_written:,} PureEdgeSim tasks written to {output_path.name}")
    else:
        print(f"  ❌  M4 FAILED — see validation errors above")
    print(f"{'='*72}")

    # -----------------------------------------------------------------------
    # Write log
    # -----------------------------------------------------------------------
    if log_path is not None:
        _write_log(
            log_path, timestamp, input_path, output_path,
            n_input, n_written, n_edge_devices, mips_base, max_cell_ram_gb, seed,
            all_checks, all_pass, length_zero, deadline_violations,
            lengths, container_sizes, file_sizes, max_latencies, slack_factors, device_counts
        )
        print(f"\n[INFO] Log written → {log_path}")

    return all_pass


# ---------------------------------------------------------------------------
# Print helper
# ---------------------------------------------------------------------------
def _print_stats(label: str, values: list, unit: str = "", scale: float = 1.0, unit_label: str = None):
    if not values:
        return
    arr = np.array(values, dtype=float) * scale
    ul  = unit_label or unit
    print(f"\n  {label}:")
    print(f"    Min={arr.min():.3f}  P25={np.percentile(arr,25):.3f}  "
          f"P50={np.percentile(arr,50):.3f}  P90={np.percentile(arr,90):.3f}  "
          f"P99={np.percentile(arr,99):.3f}  Max={arr.max():.3f}  [{ul}]")


# ---------------------------------------------------------------------------
# Markdown log writer
# ---------------------------------------------------------------------------
def _write_log(
    log_path, timestamp, input_path, output_path,
    n_input, n_written, n_edge_devices, mips_base, max_cell_ram_gb, seed,
    all_checks, all_pass, length_zero, deadline_violations,
    lengths, container_sizes, file_sizes, max_latencies, slack_factors, device_counts
):
    log_path.parent.mkdir(parents=True, exist_ok=True)

    check_rows = "\n".join(
        f"| {'✅ PASS' if v else '❌ FAIL'} | {k} |"
        for k, v in all_checks.items()
    )

    arr_len  = np.array(lengths, dtype=float)
    arr_cont = np.array(container_sizes, dtype=float) / 8 / 1024 / 1024  # → MB
    arr_file = np.array(file_sizes, dtype=float) / 8 / 1024 / 1024        # → MB
    arr_lat  = np.array(max_latencies, dtype=float)
    arr_sf   = np.array(slack_factors, dtype=float)

    def _qtable_row(label, arr, fmt=".3f"):
        qs = [np.percentile(arr, p) for p in [1, 25, 50, 75, 90, 99]]
        return f"| {label} | " + " | ".join(f"{q:{fmt}}" for q in qs) + " |"

    device_rows = "\n".join(
        f"| {i} | {c:,} | {c/n_written*100:.1f}% |"
        for i, c in enumerate(device_counts)
    )

    content = f"""
## M4 Translation Run — {timestamp}

**Input**: `{input_path}`  
**Output**: `{output_path}`  
**Overall result**: {'✅ M4 PASSED' if all_pass else '❌ M4 FAILED'}

### Parameters

| Parameter | Value |
|---|---|
| MIPS_base | {mips_base} MIPS |
| MaxCellRAM | {max_cell_ram_gb:.1f} GB |
| N_edge_devices | {n_edge_devices} |
| RNG seed | {seed} |
| α_input (fileSizeInBits ratio) | {ALPHA_INPUT} |
| LogNormal(μ, σ) | ({LOGNORMAL_MU}, {LOGNORMAL_SIGMA}) |
| Uniform(β_min, β_max) | ({BETA_OUTPUT_MIN}, {BETA_OUTPUT_MAX}) |
| SlackFactor κ, λ | {SLACK_KAPPA}, {SLACK_LAMBDA} |

### Validation Checklist

| Result | Check |
|---|---|
{check_rows}

### Translation Formulas

```
length_mi        = round(req_cpus × {mips_base} × delta_t_exec)
containerBits    = round(req_memory × {int(max_cell_ram_gb * 1024**3):,} × 8)
fileSizeInBits   = round(containerBits × {ALPHA_INPUT} × LogNormal(μ={LOGNORMAL_MU}, σ={LOGNORMAL_SIGMA}))
outputSizeInBits = round(fileSizeInBits × Uniform({BETA_OUTPUT_MIN}, {BETA_OUTPUT_MAX}))
SlackFactor      = {SLACK_KAPPA}(3 - Sc) + {SLACK_LAMBDA}(1 - P / {int(P_MAX)})
maxLatency       = delta_t_exec × (1.0 + SlackFactor)
edgeDevice       = |hashCode(user)| mod {n_edge_devices}
```

### Output Field Quantiles

| Field | P1 | P25 | P50 | P75 | P90 | P99 |
|---|---|---|---|---|---|---|
{_qtable_row("length_mi [MI]", arr_len, ".1f")}
{_qtable_row("containerSize [MB]", arr_cont)}
{_qtable_row("fileSize [MB]", arr_file)}
{_qtable_row("maxLatency [s]", arr_lat)}
{_qtable_row("slackFactor", arr_sf)}

### Edge Device Assignment Distribution

| Device Index | Task Count | % |
|---|---|---|
{device_rows}

---
"""
    mode = "a" if log_path.exists() else "w"
    with open(log_path, mode) as f:
        f.write(content)


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------
def main():
    parser = argparse.ArgumentParser(
        description="M4: Translate intermediate JSONL to PureEdgeSim task specs."
    )
    parser.add_argument("--input", type=Path, default=DEFAULT_INPUT)
    parser.add_argument("--output", type=Path, default=None,
        help="Output JSON Lines path (default: data/processed/pureedgesim_tasks_<stem>.json)")
    parser.add_argument("--n-edge-devices", type=int, default=DEFAULT_N_EDGE_DEVICES)
    parser.add_argument("--mips-base",      type=int, default=DEFAULT_MIPS_BASE)
    parser.add_argument("--max-cell-ram-gb",type=float, default=DEFAULT_MAX_CELL_RAM_GB)
    parser.add_argument("--seed",           type=int, default=DEFAULT_SEED)
    parser.add_argument("--log",            type=Path, default=DEFAULT_LOG)
    parser.add_argument("--no-log",         action="store_true")
    args = parser.parse_args()

    # Derive output path from input stem
    if args.output is None:
        # e.g. "google_v3_cell_a_15min_intermediate" → "pureedgesim_tasks_15min"
        stem = args.input.stem  # "google_v3_cell_a_15min_intermediate"
        # Extract window label (e.g. "15min")
        parts = stem.replace("_intermediate", "").split("_")
        window_label = parts[-1] if parts else "unknown"
        args.output = PROJECT_ROOT / "data" / "processed" / f"pureedgesim_tasks_{window_label}.json"

    log_path = None if args.no_log else args.log
    passed = translate(
        args.input, args.output,
        args.n_edge_devices, args.mips_base, args.max_cell_ram_gb,
        args.seed, log_path
    )
    sys.exit(0 if passed else 1)


if __name__ == "__main__":
    main()
