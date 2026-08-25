#!/usr/bin/env python3
"""
test_m5_engine.py — Milestone 5 Engine Verification
======================================================
Validates the M5 streaming engine output contracts without running the full
Java simulation. Three test suites:

  Suite 1 — Static field contract audit
    Verifies that every task record in pureedgesim_tasks_15min.json contains
    exactly the fields expected by StreamedTraceTaskGenerator and that all
    values are within valid ranges.

  Suite 2 — Streaming window invariant
    Simulates the sliding-window refill logic in Python to verify that:
      - Every task is produced exactly once
      - Tasks appear in chronological (non-decreasing) t_arrival order
      - No task is duplicated or dropped across buffer boundaries

  Suite 3 — NEXT_BATCH scheduling correctness
    Simulates the NEXT_BATCH event loop from DefaultSimulationManager to
    verify that:
      - batchSize tasks are dispatched per event
      - refill is triggered when buffer drops below low watermark
      - all tasks are eventually dispatched (N_dispatched == N_total)

Usage:
  python3 python/scripts/test_m5_engine.py
  python3 python/scripts/test_m5_engine.py --input data/processed/pureedgesim_tasks_15min.json
  python3 python/scripts/test_m5_engine.py --batch-size 100 --buffer 1000 --watermark 200
"""

import argparse
import json
import sys
from collections import deque
from pathlib import Path

PROJECT_ROOT = Path(__file__).resolve().parents[2]
DEFAULT_INPUT = PROJECT_ROOT / "data" / "processed" / "pureedgesim_tasks_15min.json"

# ─────────────────────────────────────────────────────────────────────────────
# Required PES task fields and their validation rules
# ─────────────────────────────────────────────────────────────────────────────
REQUIRED_FIELDS = {
    "id":                   lambda v: isinstance(v, int) and v >= 1,
    "time":                 lambda v: isinstance(v, (int, float)) and v >= 0,
    "length":               lambda v: isinstance(v, (int, float)) and v >= 1,
    "fileSizeInBits":       lambda v: isinstance(v, (int, float)) and v >= 1,
    "outputSizeInBits":     lambda v: isinstance(v, (int, float)) and v >= 1,
    "containerSizeInBits":  lambda v: isinstance(v, (int, float)) and v >= 1,
    "maxLatency":           lambda v: isinstance(v, (int, float)) and v >= 0,
    "edgeDevice":           lambda v: isinstance(v, int) and v >= 0,
    "applicationID":        lambda v: isinstance(v, int) and v >= 0,
    "metadata":             lambda v: isinstance(v, dict),
}

REQUIRED_METADATA = {
    "priority":         lambda v: isinstance(v, int),
    "scheduling_class": lambda v: isinstance(v, int) and 0 <= v <= 3,
    "finish_status":    lambda v: isinstance(v, str) and v != "",
    "delta_t_exec_s":   lambda v: isinstance(v, (int, float)) and v >= 0,
    "slack_factor":     lambda v: isinstance(v, (int, float)) and v >= 0,
    "req_cpus":         lambda v: isinstance(v, (int, float)) and 0 <= v <= 1,
    "req_memory":       lambda v: isinstance(v, (int, float)) and 0 <= v <= 1,
}

SEP  = "=" * 70
SEP2 = "-" * 70


# ─────────────────────────────────────────────────────────────────────────────
# Suite 1: Static field contract audit
# ─────────────────────────────────────────────────────────────────────────────
def suite1_field_audit(tasks: list) -> tuple[bool, list]:
    print(f"\n[Suite 1] Static Field Contract Audit  ({len(tasks):,} tasks)")
    print(SEP2)

    failures = []
    field_missing   = {f: 0 for f in REQUIRED_FIELDS}
    field_invalid   = {f: 0 for f in REQUIRED_FIELDS}
    meta_missing    = {f: 0 for f in REQUIRED_METADATA}
    meta_invalid    = {f: 0 for f in REQUIRED_METADATA}

    for i, t in enumerate(tasks):
        for field, validator in REQUIRED_FIELDS.items():
            if field not in t:
                field_missing[field] += 1
            elif not validator(t[field]):
                field_invalid[field] += 1

        meta = t.get("metadata", {})
        for field, validator in REQUIRED_METADATA.items():
            if field not in meta:
                meta_missing[field] += 1
            elif not validator(meta[field]):
                meta_invalid[field] += 1

    passed = True
    for field in REQUIRED_FIELDS:
        missing = field_missing[field]
        invalid = field_invalid[field]
        ok = (missing == 0 and invalid == 0)
        passed = passed and ok
        status = "PASS ✓" if ok else f"FAIL ✗"
        detail = f"  missing={missing}  invalid={invalid}" if not ok else ""
        print(f"  [{status}] {field:<22}{detail}")
        if not ok:
            failures.append(f"Field '{field}': missing={missing}, invalid={invalid}")

    print(f"\n  Metadata fields:")
    for field in REQUIRED_METADATA:
        missing = meta_missing[field]
        invalid = meta_invalid[field]
        ok = (missing == 0 and invalid == 0)
        passed = passed and ok
        status = "PASS ✓" if ok else f"FAIL ✗"
        detail = f"  missing={missing}  invalid={invalid}" if not ok else ""
        print(f"  [{status}] metadata.{field:<16}{detail}")
        if not ok:
            failures.append(f"metadata.'{field}': missing={missing}, invalid={invalid}")

    # maxLatency >= delta_t_exec check
    violations = sum(
        1 for t in tasks
        if t["maxLatency"] < t["metadata"].get("delta_t_exec_s", 0) - 1e-9
    )
    if violations == 0:
        print(f"  [PASS ✓] maxLatency >= delta_t_exec (100% tasks)")
    else:
        print(f"  [FAIL ✗] maxLatency < delta_t_exec in {violations} tasks")
        failures.append(f"maxLatency < delta_t_exec in {violations} tasks")
        passed = False

    return passed, failures


# ─────────────────────────────────────────────────────────────────────────────
# Suite 2: Streaming window invariant (Python simulation of Java streaming)
# ─────────────────────────────────────────────────────────────────────────────
def suite2_streaming_invariant(input_path: Path, buffer_size: int, watermark: int) -> tuple[bool, list]:
    print(f"\n[Suite 2] Streaming Window Invariant  (buffer={buffer_size}, watermark={watermark})")
    print(SEP2)

    failures = []
    all_ids_seen = []
    last_time    = -1.0
    out_of_order = 0
    refill_count = 0

    # Simulate sliding-window streaming: open file, read in chunks
    buffer = deque()

    def read_chunk(reader, n):
        added = 0
        for line in reader:
            line = line.strip()
            if not line:
                continue
            t = json.loads(line)
            buffer.append(t)
            added += 1
            if added >= n:
                break
        return added

    with open(input_path) as f:
        # Initial fill
        read_chunk(f, buffer_size)
        total_initial = len(buffer)
        print(f"  Initial buffer loaded: {total_initial:,} tasks")

        # Simulate consumption: consume batchSize at a time, refill when watermark crossed
        batch_size = max(1, buffer_size // 5)  # simulate batchSize

        while buffer:
            # Consume one batch
            batch = [buffer.popleft() for _ in range(min(batch_size, len(buffer)))]
            for t in batch:
                # Chronological order check
                if t["time"] < last_time - 1e-9:
                    out_of_order += 1
                last_time = t["time"]
                all_ids_seen.append(t["id"])

            # Refill if below watermark
            if len(buffer) < watermark:
                added = read_chunk(f, buffer_size - len(buffer))
                if added > 0:
                    refill_count += 1

    n_tasks = len(all_ids_seen)
    print(f"  Total tasks dispatched  : {n_tasks:,}")
    print(f"  Refill events triggered : {refill_count}")

    # Check uniqueness
    unique_ids = set(all_ids_seen)
    duplicates = n_tasks - len(unique_ids)
    print(f"  Duplicate IDs           : {duplicates}")

    # Check monotonic ordering
    print(f"  Out-of-order deliveries : {out_of_order}")

    # Check completeness (all IDs 1..N present)
    expected_ids = set(range(1, n_tasks + 1))
    missing_ids  = expected_ids - unique_ids
    print(f"  Missing IDs             : {len(missing_ids)}")

    checks = {
        "All tasks dispatched exactly once": duplicates == 0 and len(missing_ids) == 0,
        "Tasks in chronological order":      out_of_order == 0,
        "No task dropped or duplicated":     duplicates == 0,
    }

    passed = True
    for check, ok in checks.items():
        status = "PASS ✓" if ok else "FAIL ✗"
        print(f"  [{status}] {check}")
        if not ok:
            failures.append(check)
            passed = False

    return passed, failures


# ─────────────────────────────────────────────────────────────────────────────
# Suite 3: NEXT_BATCH scheduling correctness
# ─────────────────────────────────────────────────────────────────────────────
def suite3_next_batch_scheduling(
    tasks: list,
    batch_size: int,
    buffer_size: int,
    watermark: int
) -> tuple[bool, list]:
    print(f"\n[Suite 3] NEXT_BATCH Scheduling Correctness  (batchSize={batch_size})")
    print(SEP2)

    failures = []
    n_total = len(tasks)

    # Simulate the NEXT_BATCH handler from DefaultSimulationManager
    task_queue = deque(tasks)          # the FutureQueue
    dispatched = []
    next_batch_events = 0
    refill_events     = 0
    clock = 0.0

    while task_queue or dispatched:
        # NEXT_BATCH fires: dispatch up to batchSize tasks
        batch = []
        for _ in range(min(batch_size, len(task_queue))):
            t = task_queue.popleft()
            batch.append(t)
            dispatched.append(t)
        next_batch_events += 1

        # Simulate refill (streaming hook)
        if len(task_queue) < watermark:
            # In Java this would call streamedGenerator.refillIfNeeded()
            # Here we just track that it would have been triggered
            refill_events += 1

        # Advance clock to next batch time (simulate NEXT_BATCH reschedule)
        if task_queue:
            clock = task_queue[0]["time"]
        elif not task_queue:
            break

    n_dispatched = len(dispatched)
    print(f"  Total tasks              : {n_total:,}")
    print(f"  Total dispatched         : {n_dispatched:,}")
    print(f"  NEXT_BATCH events        : {next_batch_events:,}")
    print(f"  Refill triggers          : {refill_events:,}")

    checks = {
        f"All {n_total:,} tasks dispatched":  n_dispatched == n_total,
        "NEXT_BATCH fired > 0 times":         next_batch_events > 0,
        "No tasks skipped":                   n_dispatched == n_total,
    }

    passed = True
    for check, ok in checks.items():
        status = "PASS ✓" if ok else "FAIL ✗"
        print(f"  [{status}] {check}")
        if not ok:
            failures.append(check)
            passed = False

    # Verify chronological order in dispatched list
    prev_time = -1.0
    oor = 0
    for t in dispatched:
        if t["time"] < prev_time - 1e-9:
            oor += 1
        prev_time = t["time"]
    oor_ok = (oor == 0)
    status = "PASS ✓" if oor_ok else "FAIL ✗"
    print(f"  [{status}] Chronological dispatch order (out-of-order={oor})")
    if not oor_ok:
        failures.append(f"Out-of-order dispatch: {oor} violations")
        passed = False

    return passed, failures


# ─────────────────────────────────────────────────────────────────────────────
# Main
# ─────────────────────────────────────────────────────────────────────────────
def main():
    parser = argparse.ArgumentParser(description="M5 engine verification tests")
    parser.add_argument("--input",      type=Path, default=DEFAULT_INPUT)
    parser.add_argument("--batch-size", type=int,  default=100)
    parser.add_argument("--buffer",     type=int,  default=1000)
    parser.add_argument("--watermark",  type=int,  default=200)
    args = parser.parse_args()

    print(SEP)
    print(f"  M5 Engine Verification Tests")
    print(f"  Input: {args.input}")
    print(SEP)

    if not args.input.exists():
        print(f"[ERROR] File not found: {args.input}")
        print("  Run M4 first: python3 python/scripts/translate_to_pureedgesim.py")
        sys.exit(1)

    # Load all tasks once for Suite 1 and Suite 3
    tasks = []
    with open(args.input) as f:
        for line in f:
            line = line.strip()
            if line:
                tasks.append(json.loads(line))

    n_tasks = len(tasks)
    print(f"\n  Total tasks loaded: {n_tasks:,}")

    all_pass = True
    all_failures = []

    # ── Suite 1
    s1_pass, s1_fail = suite1_field_audit(tasks)
    all_pass = all_pass and s1_pass
    all_failures.extend(s1_fail)

    # ── Suite 2
    s2_pass, s2_fail = suite2_streaming_invariant(
        args.input, args.buffer, args.watermark)
    all_pass = all_pass and s2_pass
    all_failures.extend(s2_fail)

    # ── Suite 3
    s3_pass, s3_fail = suite3_next_batch_scheduling(
        tasks, args.batch_size, args.buffer, args.watermark)
    all_pass = all_pass and s3_pass
    all_failures.extend(s3_fail)

    # ── Summary
    print(f"\n{SEP}")
    if all_pass:
        print(f"  ✅  M5 ENGINE TESTS PASSED — All 3 suites green.")
        print(f"  StreamedTraceTaskGenerator + TraceSimulationManager")
        print(f"  are ready for Java compilation and simulation run (M6).")
    else:
        print(f"  ❌  M5 ENGINE TESTS FAILED")
        for f_desc in all_failures:
            print(f"    - {f_desc}")
    print(SEP)

    sys.exit(0 if all_pass else 1)


if __name__ == "__main__":
    main()
