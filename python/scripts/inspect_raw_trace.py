#!/usr/bin/env python3
"""
inspect_raw_trace.py — Milestone 2: Raw Extracted Record Inspection & Audit
============================================================================
Reads the extracted Google Cluster Trace v3 Parquet file and produces a
comprehensive telemetry report covering:

  1. Schema & dtype verification
  2. Null / missing value audit (M2 pass criteria)
  3. Descriptive statistics (min, max, mean, std)
  4. Quantile analysis  (P1, P5, P25, P50, P75, P90, P95, P99)
  5. finish_status distribution
  6. priority & scheduling_class categorical breakdown
  7. Time delta sanity checks (queue_time_us, exec_time_us, total_residence_time_us)
  8. Resource request distribution (req_cpus, req_memory)
  9. M2 validation checklist (pass / FAIL)
  10. Automatic log write → docs/logs/m2_inspection.md

Usage:
  python3 python/scripts/inspect_raw_trace.py
  python3 python/scripts/inspect_raw_trace.py --input data/raw/google_v3_cell_a_15min.parquet
  python3 python/scripts/inspect_raw_trace.py --input data/raw/google_v3_cell_a_1h.parquet --no-log

Arguments:
  --input    PATH   Parquet file to inspect (default: data/raw/google_v3_cell_a_15min.parquet)
  --log      PATH   Output log file (default: docs/logs/m2_inspection.md)
  --no-log          Skip writing the log file (print to console only)
"""

import argparse
import os
import sys
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
import pandas as pd

# ---------------------------------------------------------------------------
# Constants
# ---------------------------------------------------------------------------
PROJECT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_INPUT = PROJECT_ROOT / "data" / "raw" / "google_v3_cell_a_15min.parquet"
DEFAULT_LOG   = PROJECT_ROOT / "docs" / "logs" / "m2_inspection.md"

QUANTILES = [0.01, 0.05, 0.25, 0.50, 0.75, 0.90, 0.95, 0.99]
QUANTILE_LABELS = ["P1", "P5", "P25", "P50", "P75", "P90", "P95", "P99"]

# Fields that MUST be non-null for a valid trace record (M2 pass criteria)
REQUIRED_NON_NULL = [
    "collection_id",
    "instance_index",
    "submit_time_us",
    "req_cpus",
    "req_memory",
    "priority",
    "scheduling_class",
    "finish_status",
]

# Numeric fields for quantile analysis
NUMERIC_FIELDS = [
    "submit_time_us",
    "schedule_time_us",
    "finish_time_us",
    "queue_time_us",
    "exec_time_us",
    "total_residence_time_us",
    "req_cpus",
    "req_memory",
    "priority",
    "scheduling_class",
]

# Time fields (in µs) — shown in both µs and human-readable seconds
TIME_US_FIELDS = [
    "queue_time_us",
    "exec_time_us",
    "total_residence_time_us",
]


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
SEP  = "=" * 72
SEP2 = "-" * 72

def _us_to_s(us: float) -> str:
    """Format microseconds as human-readable seconds/minutes/hours."""
    if pd.isna(us):
        return "N/A"
    s = us / 1_000_000
    if s < 1:
        return f"{us:.0f} µs"
    if s < 60:
        return f"{s:.3f} s"
    if s < 3600:
        return f"{s/60:.2f} min"
    return f"{s/3600:.2f} h"


def _print_and_collect(lines: list, *args, **kwargs):
    """Print to stdout and append to lines list for log writing."""
    text = " ".join(str(a) for a in args)
    print(text, **kwargs)
    lines.append(text)


# ---------------------------------------------------------------------------
# Main inspection logic
# ---------------------------------------------------------------------------
def inspect(input_path: Path, log_path: Path | None) -> bool:
    """
    Run all M2 inspection checks. Returns True if all validation criteria pass.
    """
    out: list[str] = []
    p = lambda *args: _print_and_collect(out, *args)

    timestamp = datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")

    p(SEP)
    p(f"  M2 Raw Trace Inspection Report")
    p(f"  Input : {input_path}")
    p(f"  Time  : {timestamp}")
    p(SEP)

    # -----------------------------------------------------------------------
    # Load Parquet
    # -----------------------------------------------------------------------
    if not input_path.exists():
        print(f"[ERROR] File not found: {input_path}", file=sys.stderr)
        print("  Run M1 extraction first: ./scripts/bq_extract.sh --window 15min", file=sys.stderr)
        return False

    df = pd.read_parquet(input_path)
    p(f"\n[1] SCHEMA & SHAPE")
    p(SEP2)
    p(f"  Rows    : {len(df):,}")
    p(f"  Columns : {len(df.columns)}")
    p(f"  File    : {input_path.stat().st_size / 1024:.1f} KB")
    p("")
    p(f"  {'Column':<35} {'DType':<15} {'Nulls':>8}   {'Null %':>7}")
    p(f"  {'-'*35} {'-'*15} {'-'*8}   {'-'*7}")
    for col in df.columns:
        null_count = int(df[col].isna().sum())
        null_pct   = null_count / len(df) * 100
        p(f"  {col:<35} {str(df[col].dtype):<15} {null_count:>8,}   {null_pct:>6.1f}%")

    # -----------------------------------------------------------------------
    # 2. Null Audit
    # -----------------------------------------------------------------------
    p(f"\n[2] NULL AUDIT (M2 Validation Pass Criteria)")
    p(SEP2)
    null_failures = []
    for field in REQUIRED_NON_NULL:
        if field not in df.columns:
            p(f"  MISSING COLUMN : {field}")
            null_failures.append(f"Column missing: {field}")
            continue
        n = int(df[field].isna().sum())
        status = "PASS ✓" if n == 0 else f"FAIL ✗  ({n:,} nulls)"
        p(f"  {field:<35} {status}")
        if n > 0:
            null_failures.append(f"{field}: {n} nulls")

    # -----------------------------------------------------------------------
    # 3. exec_time_us > 0 for FINISH tasks
    # -----------------------------------------------------------------------
    p(f"\n[3] EXEC_TIME_US SANITY CHECK")
    p(SEP2)
    finish_mask = df["finish_status"] == "FINISH"
    n_finish    = int(finish_mask.sum())
    if n_finish > 0 and "exec_time_us" in df.columns:
        bad_exec = int((df.loc[finish_mask, "exec_time_us"].fillna(0) <= 0).sum())
        p(f"  FINISH tasks                 : {n_finish:,}")
        p(f"  FINISH tasks with exec <= 0  : {bad_exec}")
        exec_pass = (bad_exec == 0)
        p(f"  Validation                   : {'PASS ✓' if exec_pass else 'FAIL ✗'}")
        if not exec_pass:
            null_failures.append(f"exec_time_us <= 0 in {bad_exec} FINISH tasks")
    else:
        exec_pass = True
        p(f"  No FINISH tasks in window or exec_time_us column missing — skipped.")

    # -----------------------------------------------------------------------
    # 4. finish_status distribution
    # -----------------------------------------------------------------------
    p(f"\n[4] FINISH STATUS DISTRIBUTION")
    p(SEP2)
    status_counts = df["finish_status"].value_counts()
    total = len(df)
    for status, count in status_counts.items():
        bar = "█" * int(count / total * 40)
        p(f"  {status:<10} {count:>6,}  ({count/total*100:5.1f}%)  {bar}")

    # -----------------------------------------------------------------------
    # 5. Priority & Scheduling Class breakdown
    # -----------------------------------------------------------------------
    p(f"\n[5] PRIORITY & SCHEDULING CLASS")
    p(SEP2)

    # Priority tier groups (Borg convention)
    priority_tiers = {
        "Free (0–99)":        (0,   99),
        "Best-Effort (100–119)": (100, 119),
        "Mid-priority (120–359)": (120, 359),
        "Production (360+)":  (360, 999),
    }
    p(f"  Priority range : {int(df['priority'].min())} – {int(df['priority'].max())}")
    p(f"  {'Priority Tier':<30} {'Count':>8}  {'%':>6}")
    p(f"  {'-'*30} {'-'*8}  {'-'*6}")
    for tier_name, (lo, hi) in priority_tiers.items():
        n = int(((df["priority"] >= lo) & (df["priority"] <= hi)).sum())
        p(f"  {tier_name:<30} {n:>8,}  {n/total*100:>5.1f}%")

    p("")
    p(f"  Scheduling class distribution:")
    sc_desc = {0: "Batch/Non-prod", 1: "Mid-priority", 2: "Production", 3: "Latency-sensitive"}
    sc_counts = df["scheduling_class"].value_counts().sort_index()
    for sc, count in sc_counts.items():
        desc = sc_desc.get(int(sc), "Unknown")
        p(f"  Class {sc} ({desc:<20}) : {count:>6,}  ({count/total*100:5.1f}%)")

    # -----------------------------------------------------------------------
    # 6. Descriptive statistics for numeric fields
    # -----------------------------------------------------------------------
    p(f"\n[6] DESCRIPTIVE STATISTICS (numeric fields)")
    p(SEP2)
    for field in NUMERIC_FIELDS:
        if field not in df.columns:
            continue
        series = pd.to_numeric(df[field], errors="coerce").dropna()
        if series.empty:
            continue
        p(f"\n  {field}")
        p(f"    Count  : {len(series):,}")
        p(f"    Mean   : {series.mean():.4f}")
        p(f"    Std    : {series.std():.4f}")
        p(f"    Min    : {series.min():.4f}")
        p(f"    Max    : {series.max():.4f}")

    # -----------------------------------------------------------------------
    # 7. Quantile analysis — TIME fields (key for thesis)
    # -----------------------------------------------------------------------
    p(f"\n[7] QUANTILE ANALYSIS — TIME FIELDS (µs → human-readable)")
    p(SEP2)
    p(f"  {'Field':<30} {'P1':>12} {'P5':>12} {'P25':>12} {'P50':>12} {'P75':>12} {'P90':>12} {'P95':>12} {'P99':>12}")
    p(f"  {'-'*30} {'-'*12} {'-'*12} {'-'*12} {'-'*12} {'-'*12} {'-'*12} {'-'*12} {'-'*12}")
    for field in TIME_US_FIELDS:
        if field not in df.columns:
            continue
        series = pd.to_numeric(df[field], errors="coerce").dropna()
        qs = series.quantile(QUANTILES).values
        row = f"  {field:<30}"
        for q in qs:
            row += f" {_us_to_s(q):>12}"
        p(row)

    # Also print raw µs table for exec_time_us and queue_time_us
    p(f"\n  --- Raw µs values ---")
    p(f"  {'Field':<30} {'P1':>14} {'P25':>14} {'P50':>14} {'P90':>14} {'P99':>14}")
    p(f"  {'-'*30} {'-'*14} {'-'*14} {'-'*14} {'-'*14} {'-'*14}")
    for field in ["exec_time_us", "queue_time_us", "total_residence_time_us"]:
        if field not in df.columns:
            continue
        series = pd.to_numeric(df[field], errors="coerce").dropna()
        q_vals = series.quantile([0.01, 0.25, 0.50, 0.90, 0.99]).values
        row = f"  {field:<30}"
        for q in q_vals:
            row += f" {int(q):>14,}"
        p(row)

    # -----------------------------------------------------------------------
    # 8. Resource request quantile analysis
    # -----------------------------------------------------------------------
    p(f"\n[8] QUANTILE ANALYSIS — RESOURCE REQUESTS (NCU / Norm RAM)")
    p(SEP2)
    for field in ["req_cpus", "req_memory"]:
        if field not in df.columns:
            continue
        series = pd.to_numeric(df[field], errors="coerce").dropna()
        qs = series.quantile(QUANTILES).values
        p(f"  {field}:")
        p(f"    {'Pct':<6}" + "".join(f"{lbl:>12}" for lbl in QUANTILE_LABELS))
        p(f"    {'':6}" + "".join(f"{q:>12.6f}" for q in qs))
        p(f"    Mean={series.mean():.6f}  Std={series.std():.6f}  "
          f"Min={series.min():.6f}  Max={series.max():.6f}")
        p("")

    # -----------------------------------------------------------------------
    # 9. Time window consistency check
    # -----------------------------------------------------------------------
    p(f"\n[9] TIME WINDOW CONSISTENCY CHECK")
    p(SEP2)
    if "submit_time_us" in df.columns:
        submit = pd.to_numeric(df["submit_time_us"], errors="coerce").dropna()
        p(f"  submit_time_us range : [{int(submit.min()):,}  →  {int(submit.max()):,}] µs")
        p(f"                         ({_us_to_s(float(submit.min()))} → {_us_to_s(float(submit.max()))})")
        span_s = (submit.max() - submit.min()) / 1_000_000
        p(f"  Submission span      : {span_s:.1f} s")

    if "queue_time_us" in df.columns and "exec_time_us" in df.columns:
        neg_queue = int((pd.to_numeric(df["queue_time_us"], errors="coerce") < 0).sum())
        neg_exec  = int((pd.to_numeric(df["exec_time_us"],  errors="coerce") < 0).sum())
        p(f"  Negative queue_time_us : {neg_queue}")
        p(f"  Negative exec_time_us  : {neg_exec}")
        if neg_exec > 0 or neg_queue > 0:
            p(f"  NOTE: Negative times indicate Borg race-condition artifacts (evict-before-schedule).")
            p(f"        These should have been filtered in M1. Presence here is informational.")

    # -----------------------------------------------------------------------
    # 10. Heavy-tail characterization (for thesis)
    # -----------------------------------------------------------------------
    p(f"\n[10] HEAVY-TAIL CHARACTERIZATION (exec_time_us)")
    p(SEP2)
    if "exec_time_us" in df.columns:
        exec_s = pd.to_numeric(df["exec_time_us"], errors="coerce").dropna() / 1_000_000
        p99    = float(exec_s.quantile(0.99))
        p50    = float(exec_s.quantile(0.50))
        p90    = float(exec_s.quantile(0.90))
        ratio_99_50 = p99 / p50 if p50 > 0 else float("inf")
        ratio_90_50 = p90 / p50 if p50 > 0 else float("inf")
        p(f"  P99/P50 ratio : {ratio_99_50:.1f}×  (>10× indicates heavy tail)")
        p(f"  P90/P50 ratio : {ratio_90_50:.1f}×")
        p(f"  Coefficient of Variation (CV = std/mean) : {exec_s.std() / exec_s.mean():.3f}")
        p(f"  Skewness : {float(exec_s.skew()):.3f}  (>1 = right-skewed / heavy-tailed)")
        if ratio_99_50 > 10:
            p(f"  → Heavy-tailed distribution CONFIRMED. Supports use of real trace vs Poisson.")

    # -----------------------------------------------------------------------
    # 11. M2 Validation Summary
    # -----------------------------------------------------------------------
    p(f"\n[11] M2 VALIDATION CHECKLIST")
    p(SEP2)
    checks = {
        "All required fields non-null"      : (len(null_failures) == 0),
        "exec_time_us > 0 for FINISH tasks" : exec_pass,
        "submit_time_us present"            : ("submit_time_us" in df.columns),
        "req_cpus in [0, 1]"               : (df["req_cpus"].between(0, 1).all()
                                               if "req_cpus" in df.columns else False),
        "req_memory in [0, 1]"             : (df["req_memory"].between(0, 1).all()
                                               if "req_memory" in df.columns else False),
        "scheduling_class in {0,1,2,3}"    : (df["scheduling_class"].isin([0, 1, 2, 3]).all()
                                               if "scheduling_class" in df.columns else False),
        "Total rows > 0"                   : (len(df) > 0),
    }

    all_pass = True
    for check, passed in checks.items():
        mark = "PASS ✓" if passed else "FAIL ✗"
        p(f"  [{mark}] {check}")
        if not passed:
            all_pass = False

    if null_failures:
        p(f"\n  Null failures:")
        for f_desc in null_failures:
            p(f"    - {f_desc}")

    p("")
    p(SEP)
    if all_pass:
        p(f"  ✅  M2 PASSED — Dataset is clean and ready for M3 preprocessing.")
    else:
        p(f"  ❌  M2 FAILED — Fix the above issues before proceeding to M3.")
    p(SEP)

    # -----------------------------------------------------------------------
    # Write log
    # -----------------------------------------------------------------------
    if log_path is not None:
        log_path.parent.mkdir(parents=True, exist_ok=True)
        log_content = _build_markdown_log(
            timestamp, input_path, df, checks, null_failures, exec_pass, all_pass, out
        )
        mode = "a" if log_path.exists() else "w"
        with open(log_path, mode) as f:
            f.write(log_content)
        print(f"\n[INFO] Log written → {log_path}")

    return all_pass


# ---------------------------------------------------------------------------
# Markdown log builder
# ---------------------------------------------------------------------------
def _build_markdown_log(
    timestamp: str,
    input_path: Path,
    df: pd.DataFrame,
    checks: dict,
    null_failures: list,
    exec_pass: bool,
    all_pass: bool,
    console_lines: list,
) -> str:
    total = len(df)

    # Status counts
    status_counts = df["finish_status"].value_counts()

    # Quantile tables
    def _q_table_md(fields: list, convert_fn=None) -> str:
        headers = ["Field"] + QUANTILE_LABELS
        rows = [headers, ["---"] * len(headers)]
        for field in fields:
            if field not in df.columns:
                continue
            series = pd.to_numeric(df[field], errors="coerce").dropna()
            qs = series.quantile(QUANTILES).values
            if convert_fn:
                row = [field] + [convert_fn(q) for q in qs]
            else:
                row = [field] + [f"{q:.6f}" for q in qs]
            rows.append(row)
        return "\n".join("| " + " | ".join(r) + " |" for r in rows)

    time_q_table = _q_table_md(TIME_US_FIELDS, convert_fn=_us_to_s)
    res_q_table  = _q_table_md(["req_cpus", "req_memory"])

    # Heavy-tail stats
    exec_s    = pd.to_numeric(df["exec_time_us"], errors="coerce").dropna() / 1_000_000
    p50_exec  = float(exec_s.quantile(0.50))
    p90_exec  = float(exec_s.quantile(0.90))
    p99_exec  = float(exec_s.quantile(0.99))
    cv_exec   = float(exec_s.std() / exec_s.mean()) if exec_s.mean() > 0 else 0
    skew_exec = float(exec_s.skew())
    r99_50    = p99_exec / p50_exec if p50_exec > 0 else float("inf")

    # Priority breakdown
    priority_tiers = {
        "Free (0–99)":              (0,   99),
        "Best-Effort (100–119)":    (100, 119),
        "Mid-priority (120–359)":   (120, 359),
        "Production (360+)":        (360, 999),
    }
    pri_rows = []
    for tier_name, (lo, hi) in priority_tiers.items():
        n = int(((df["priority"] >= lo) & (df["priority"] <= hi)).sum())
        pri_rows.append(f"| {tier_name} | {n:,} | {n/total*100:.1f}% |")
    pri_table = "\n".join(pri_rows)

    sc_desc = {0: "Batch/Non-prod", 1: "Mid-priority", 2: "Production", 3: "Latency-sensitive"}
    sc_rows = []
    for sc, count in df["scheduling_class"].value_counts().sort_index().items():
        desc = sc_desc.get(int(sc), "Unknown")
        sc_rows.append(f"| {sc} ({desc}) | {count:,} | {count/total*100:.1f}% |")
    sc_table = "\n".join(sc_rows)

    check_rows = "\n".join(
        f"| {'✅ PASS' if v else '❌ FAIL'} | {k} |"
        for k, v in checks.items()
    )

    status_rows = "\n".join(
        f"| {s} | {c:,} | {c/total*100:.1f}% |"
        for s, c in status_counts.items()
    )

    overall = "✅ M2 PASSED" if all_pass else "❌ M2 FAILED"

    md = f"""
## M2 Inspection Run — {timestamp}

**Input**: `{input_path}`  
**Total rows**: {total:,}  
**Overall result**: {overall}

### Validation Checklist

| Result | Check |
|---|---|
{check_rows}

### Finish Status Distribution

| Status | Count | % |
|---|---|---|
{status_rows}

### Time Field Quantiles (human-readable)

{time_q_table}

> P99/P50 exec_time ratio: **{r99_50:.1f}×** (>10× confirms heavy-tailed distribution)  
> Coefficient of Variation: **{cv_exec:.3f}**  
> Skewness: **{skew_exec:.3f}**

### Resource Request Quantiles (normalized NCU / RAM fraction)

{res_q_table}

### Priority Tier Breakdown

| Priority Tier | Count | % |
|---|---|---|
{pri_table}

### Scheduling Class Breakdown

| Class | Count | % |
|---|---|---|
{sc_table}

---
"""
    return md


# ---------------------------------------------------------------------------
# Entry point
# ---------------------------------------------------------------------------
def main():
    parser = argparse.ArgumentParser(
        description="M2: Inspect raw Google Cluster Trace Parquet file."
    )
    parser.add_argument(
        "--input", type=Path, default=DEFAULT_INPUT,
        help=f"Parquet file to inspect (default: {DEFAULT_INPUT})"
    )
    parser.add_argument(
        "--log", type=Path, default=DEFAULT_LOG,
        help=f"Markdown log output path (default: {DEFAULT_LOG})"
    )
    parser.add_argument(
        "--no-log", action="store_true",
        help="Skip writing log file (console output only)"
    )
    args = parser.parse_args()

    log_path = None if args.no_log else args.log
    passed = inspect(args.input, log_path)
    sys.exit(0 if passed else 1)


if __name__ == "__main__":
    main()
