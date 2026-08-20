
## M2 Inspection Run — 2026-08-20T09:28:52Z

**Input**: `/home/cotton/Projects/ML/Thesis/PureEdgeSim/data/raw/google_v3_cell_a_15min.parquet`  
**Total rows**: 6,258  
**Overall result**: ✅ M2 PASSED

### Validation Checklist

| Result | Check |
|---|---|
| ✅ PASS | All required fields non-null |
| ✅ PASS | exec_time_us > 0 for FINISH tasks |
| ✅ PASS | submit_time_us present |
| ✅ PASS | req_cpus in [0, 1] |
| ✅ PASS | req_memory in [0, 1] |
| ✅ PASS | scheduling_class in {0,1,2,3} |
| ✅ PASS | Total rows > 0 |

### Finish Status Distribution

| Status | Count | % |
|---|---|---|
| KILL | 3,309 | 52.9% |
| LOST | 992 | 15.9% |
| FINISH | 982 | 15.7% |
| EVICT | 711 | 11.4% |
| FAIL | 264 | 4.2% |

### Time Field Quantiles (human-readable)

| Field | P1 | P5 | P25 | P50 | P75 | P90 | P95 | P99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| queue_time_us | -502275176 µs | -223271031 µs | 93563 µs | 190591 µs | 735003 µs | 3.196 s | 17.587 s | 1.82 min |
| exec_time_us | 111 µs | 8.058 s | 24.536 s | 53.070 s | 2.04 min | 4.49 min | 6.50 min | 9.57 min |
| total_residence_time_us | -11 µs | -8 µs | 9.269 s | 33.844 s | 1.36 min | 3.76 min | 6.17 min | 9.27 min |

> P99/P50 exec_time ratio: **10.8×** (>10× confirms heavy-tailed distribution)  
> Coefficient of Variation: **1.246**  
> Skewness: **2.180**

### Resource Request Quantiles (normalized NCU / RAM fraction)

| Field | P1 | P5 | P25 | P50 | P75 | P90 | P95 | P99 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- |
| req_cpus | 0.000810 | 0.001999 | 0.008102 | 0.019806 | 0.039734 | 0.039734 | 0.039734 | 0.065187 |
| req_memory | 0.000210 | 0.001093 | 0.002285 | 0.003662 | 0.008770 | 0.018219 | 0.018219 | 0.028656 |

### Priority Tier Breakdown

| Priority Tier | Count | % |
|---|---|---|
| Free (0–99) | 851 | 13.6% |
| Best-Effort (100–119) | 1,186 | 19.0% |
| Mid-priority (120–359) | 3,611 | 57.7% |
| Production (360+) | 610 | 9.7% |

### Scheduling Class Breakdown

| Class | Count | % |
|---|---|---|
| 0 (Batch/Non-prod) | 913 | 14.6% |
| 1 (Mid-priority) | 3,393 | 54.2% |
| 2 (Production) | 1,368 | 21.9% |
| 3 (Latency-sensitive) | 584 | 9.3% |

---

### Additional Data Quality Notes

#### Negative queue_time_us (1,344 rows — 21.5%)

These rows have `schedule_time_us < submit_time_us` (i.e., the SCHEDULE event precedes the SUBMIT event in the trace). This is a known Borg trace artifact for tasks that were previously running and re-queued: their last SCHEDULE timestamp predates their latest SUBMIT timestamp within the 15-min window.

**Impact on M3/M4**: None — `task.time` (arrival) uses `submit_time_us` exclusively, which is always ≥ `window_start_us`. The `queue_time_us` field is preserved in `metadata` for analytical purposes but is **not** used in PureEdgeSim task translation. These rows are **kept** — their exec_time_us is positive and valid.

**Breakdown of affected tasks:**
- Finish status: KILL (590), EVICT (454), FAIL (259), LOST (41)
- Scheduling class: mostly class 2 (Production, 912 / 67.9%)
- exec_time_us range: 0 s – 847.5 s (P50 = 72.5 s)

