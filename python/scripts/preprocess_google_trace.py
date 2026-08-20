#!/usr/bin/env python3
"""
preprocess_google_trace.py — Milestone 3: Intermediate Trace Representation
============================================================================
Reads the raw extracted Parquet file (M1 output) and produces a clean
intermediate JSON Lines representation with all timing fields converted to
simulation seconds and derived fields computed.

This intermediate layer is the canonical "ground truth" record — it contains
ONLY directly observed and deterministically derived values. No synthetic
modeling occurs here (that is M4's job).

Output fields per record:
  Identity:
    - collection_id (int)        : Borg job identifier
    - instance_index (int)       : Task index within job
    - user (str)                 : Hashed user for edge device mapping (M4)
    - collection_logical_name (str): Job family template for RL features

  Directly Derived Timing (seconds):
    - t_arrival (float)          : (submit_time_us - window_start_us) / 1e6
    - delta_t_exec (float)       : exec_time_us / 1e6
    - delta_t_queue (float)      : queue_time_us / 1e6  (may be negative — preserved)

  Raw Timestamps (µs, for audit):
    - submit_time_us (int)
    - schedule_time_us (int)
    - finish_time_us (int)

  Resource Requests:
    - req_cpus (float)           : Normalized CPU in NCUs [0, 1]
    - req_memory (float)         : Normalized RAM fraction [0, 1]

  Categorical / RL Features:
    - priority (int)             : Borg priority 0–450+
    - scheduling_class (int)     : 0–3 (3 = latency-sensitive)
    - finish_status (str)        : FINISH, FAIL, KILL, EVICT, LOST, UNKNOWN

Usage:
  python3 python/scripts/preprocess_google_trace.py
  python3 python/scripts/preprocess_google_trace.py --input data/raw/google_v3_cell_a_1h.parquet
  python3 python/scripts/preprocess_google_trace.py --window-start 600000000

Arguments:
  --input         PATH   Parquet file (default: data/raw/google_v3_cell_a_15min.parquet)
  --output        PATH   Output JSON Lines file (default: data/processed/<stem>_intermediate.jsonl)
  --window-start  INT    Trace window start in µs (default: 600000000 = 600s offset)
  --log           PATH   Log file (default: docs/logs/m3_intermediate.md)
  --no-log               Skip writing log file
"""

import argparse
import json
import sys
from datetime import datetime, timezone
from pathlib import Path

import pandas as pd

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------
PROJECT_ROOT   = Path(__file__).resolve().parents[2]
DEFAULT_INPUT  = PROJECT_ROOT / "data" / "raw" / "google_v3_cell_a_15min.parquet"
DEFAULT_LOG    = PROJECT_ROOT / "docs" / "logs" / "m3_intermediate.md"

# Borg trace epoch: all timestamps are microseconds since 600s before trace start
# window_start_us = 600_000_000 µs means t=0 in our simulation = the epoch offset
DEFAULT_WINDOW_START_US = 600_000_000


# ---------------------------------------------------------------------------
# Core preprocessing
# ---------------------------------------------------------------------------
def preprocess(
    input_path: Path,
    output_path: Path,
    window_start_us: int,
    log_path: Path | None,
) -> bool:
    timestamp = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

    print("=" * 70)
    print(f"  M3 — Intermediate Trace Preprocessing")
    print(f"  Input  : {input_path}")
    print(f"  Output : {output_path}")
    print(f"  Window start: {window_start_us:,} µs  (= {window_start_us / 1e6:.1f} s)")
    print("=" * 70)

    # -----------------------------------------------------------------------
    # Load raw Parquet
    # -----------------------------------------------------------------------
    if not input_path.exists():
        print(f"[ERROR] Input not found: {input_path}", file=sys.stderr)
        print("  Run M1 first: ./scripts/bq_extract.sh --window 15min", file=sys.stderr)
        return False

    df = pd.read_parquet(input_path)
    n_raw = len(df)
    print(f"\n[1] Loaded {n_raw:,} rows from Parquet.")

    # -----------------------------------------------------------------------
    # Derive timing fields
    # -----------------------------------------------------------------------
    print(f"\n[2] Computing derived timing fields...")

    # t_arrival: relative arrival time in simulation seconds
    df["t_arrival"] = (
        pd.to_numeric(df["submit_time_us"], errors="coerce") - window_start_us
    ) / 1_000_000.0

    # delta_t_exec: ground-truth execution duration in seconds
    df["delta_t_exec"] = pd.to_numeric(df["exec_time_us"], errors="coerce") / 1_000_000.0

    # delta_t_queue: ground-truth queue wait in seconds (may be negative — preserved)
    df["delta_t_queue"] = pd.to_numeric(df["queue_time_us"], errors="coerce") / 1_000_000.0

    # -----------------------------------------------------------------------
    # Validation: t_arrival >= 0 for ALL tasks
    # -----------------------------------------------------------------------
    neg_arrival = int((df["t_arrival"] < 0).sum())
    if neg_arrival > 0:
        print(f"  [WARNING] {neg_arrival} tasks have t_arrival < 0 — filtering out.")
        df = df[df["t_arrival"] >= 0.0]

    n_after = len(df)
    invariant_pass = (n_after == n_raw)

    print(f"  t_arrival range   : {df['t_arrival'].min():.3f}s – {df['t_arrival'].max():.3f}s")
    print(f"  delta_t_exec range: {df['delta_t_exec'].min():.6f}s – {df['delta_t_exec'].max():.3f}s")
    print(f"  delta_t_queue range (may be neg): {df['delta_t_queue'].min():.3f}s – {df['delta_t_queue'].max():.3f}s")

    # -----------------------------------------------------------------------
    # Sort chronologically by arrival time (deterministic ordering)
    # -----------------------------------------------------------------------
    df = df.sort_values("t_arrival").reset_index(drop=True)
    print(f"\n[3] Sorted {n_after:,} records chronologically by t_arrival.")

    # -----------------------------------------------------------------------
    # Build output records
    # -----------------------------------------------------------------------
    print(f"\n[4] Building intermediate JSON records...")

    output_path.parent.mkdir(parents=True, exist_ok=True)

    records_written = 0
    with open(output_path, "w") as f:
        for _, row in df.iterrows():
            record = {
                # Identity
                "collection_id":           _safe_int(row.get("collection_id")),
                "instance_index":          _safe_int(row.get("instance_index")),
                "user":                    str(row.get("user", "") or ""),
                "collection_logical_name": str(row.get("collection_logical_name", "") or ""),

                # Derived timing (seconds) — the core M3 output
                "t_arrival":   round(float(row["t_arrival"]),    9),
                "delta_t_exec": round(float(row["delta_t_exec"]), 9),
                "delta_t_queue": round(float(row["delta_t_queue"]), 9),

                # Raw timestamps (µs) — for downstream audit
                "submit_time_us":   _safe_int(row.get("submit_time_us")),
                "schedule_time_us": _safe_int(row.get("schedule_time_us")),
                "finish_time_us":   _safe_int(row.get("finish_time_us")),

                # Resource requests
                "req_cpus":    round(float(row.get("req_cpus",   0) or 0), 9),
                "req_memory":  round(float(row.get("req_memory", 0) or 0), 9),

                # Categorical / RL features
                "priority":         _safe_int(row.get("priority", 0)),
                "scheduling_class": _safe_int(row.get("scheduling_class", 0)),
                "finish_status":    str(row.get("finish_status", "UNKNOWN") or "UNKNOWN"),
            }
            f.write(json.dumps(record, separators=(",", ":")) + "\n")
            records_written += 1

    print(f"  Records written: {records_written:,}")

    # -----------------------------------------------------------------------
    # Verify record count invariant: N_intermediate == N_raw
    # -----------------------------------------------------------------------
    print(f"\n[5] Record count invariant check:")
    print(f"  Raw Parquet rows  : {n_raw:,}")
    print(f"  Filtered (neg arr): {n_raw - n_after:,}")
    print(f"  Written to JSONL  : {records_written:,}")
    count_pass = (records_written == n_after)
    print(f"  Invariant pass    : {'✅ PASS' if count_pass else '❌ FAIL'}")
    if not invariant_pass:
        print(f"  [WARNING] {n_raw - n_after} rows were dropped (t_arrival < 0).")
        print(f"            This is expected only if M1 failed to pre-filter all artifacts.")

    # -----------------------------------------------------------------------
    # Final validation summary
    # -----------------------------------------------------------------------
    all_pass = count_pass and (neg_arrival == 0 or n_raw == n_after)
    print(f"\n{'='*70}")
    if all_pass:
        print(f"  ✅  M3 PASSED — {records_written:,} records written to {output_path.name}")
    else:
        print(f"  ⚠️   M3 COMPLETED WITH NOTES — check warnings above.")
    print(f"{'='*70}")

    # -----------------------------------------------------------------------
    # Write log
    # -----------------------------------------------------------------------
    if log_path is not None:
        _write_log(
            log_path, timestamp, input_path, output_path,
            n_raw, n_after, records_written, window_start_us, df, all_pass
        )
        print(f"\n[INFO] Log written → {log_path}")

    return all_pass


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
def _safe_int(val):
    try:
        if pd.isna(val):
            return None
        return int(val)
    except (TypeError, ValueError):
        return None


def _write_log(
    log_path, timestamp, input_path, output_path,
    n_raw, n_after, n_written, window_start_us, df, all_pass
):
    log_path.parent.mkdir(parents=True, exist_ok=True)

    status_counts = df["finish_status"].value_counts()
    status_rows = "\n".join(
        f"| {s} | {c:,} | {c/len(df)*100:.1f}% |"
        for s, c in status_counts.items()
    )

    exec_s  = df["delta_t_exec"]
    queue_s = df["delta_t_queue"]

    content = f"""
## M3 Preprocessing Run — {timestamp}

**Input**: `{input_path}`  
**Output**: `{output_path}`  
**Window start**: `{window_start_us:,} µs` (= {window_start_us/1e6:.1f} s)  
**Overall result**: {'✅ M3 PASSED' if all_pass else '⚠️ M3 COMPLETED WITH NOTES'}

### Record Count Invariant

| Stage | Count |
|---|---|
| Raw Parquet rows | {n_raw:,} |
| After t_arrival filter | {n_after:,} |
| Written to JSONL | {n_written:,} |
| Dropped (t_arrival < 0) | {n_raw - n_after:,} |

### Timing Field Summary (seconds)

| Field | Min | P25 | P50 | P90 | P99 | Max |
|---|---|---|---|---|---|---|
| t_arrival | {df['t_arrival'].min():.3f} | {df['t_arrival'].quantile(0.25):.3f} | {df['t_arrival'].quantile(0.50):.3f} | {df['t_arrival'].quantile(0.90):.3f} | {df['t_arrival'].quantile(0.99):.3f} | {df['t_arrival'].max():.3f} |
| delta_t_exec | {exec_s.min():.6f} | {exec_s.quantile(0.25):.3f} | {exec_s.quantile(0.50):.3f} | {exec_s.quantile(0.90):.3f} | {exec_s.quantile(0.99):.3f} | {exec_s.max():.3f} |
| delta_t_queue | {queue_s.min():.3f} | {queue_s.quantile(0.25):.3f} | {queue_s.quantile(0.50):.3f} | {queue_s.quantile(0.90):.3f} | {queue_s.quantile(0.99):.3f} | {queue_s.max():.3f} |

### Formulas Applied

```
t_arrival    = (submit_time_us - {window_start_us:,}) / 1_000_000   [seconds]
delta_t_exec = exec_time_us / 1_000_000                              [seconds]
delta_t_queue = queue_time_us / 1_000_000                            [seconds, may be negative]
```

### Finish Status Distribution (sorted)

| Status | Count | % |
|---|---|---|
{status_rows}

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
        description="M3: Preprocess raw Parquet into intermediate JSON Lines."
    )
    parser.add_argument(
        "--input", type=Path, default=DEFAULT_INPUT,
        help="Raw Parquet file (M1 output)"
    )
    parser.add_argument(
        "--output", type=Path, default=None,
        help="Output JSON Lines path (default: data/processed/<stem>_intermediate.jsonl)"
    )
    parser.add_argument(
        "--window-start", type=int, default=DEFAULT_WINDOW_START_US,
        help=f"Window start in µs (default: {DEFAULT_WINDOW_START_US})"
    )
    parser.add_argument(
        "--log", type=Path, default=DEFAULT_LOG,
        help="Log output path"
    )
    parser.add_argument(
        "--no-log", action="store_true",
        help="Skip writing log file"
    )
    args = parser.parse_args()

    # Derive default output path from input stem
    if args.output is None:
        stem = args.input.stem  # e.g. "google_v3_cell_a_15min"
        args.output = PROJECT_ROOT / "data" / "processed" / f"{stem}_intermediate.jsonl"

    log_path = None if args.no_log else args.log
    passed = preprocess(args.input, args.output, args.window_start, log_path)
    sys.exit(0 if passed else 1)


if __name__ == "__main__":
    main()
