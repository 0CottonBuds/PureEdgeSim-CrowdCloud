
## M4 Translation Run — 2026-08-20T09:35:55Z

**Input**: `/home/cotton/Projects/ML/Thesis/PureEdgeSim/data/processed/google_v3_cell_a_15min_intermediate.jsonl`  
**Output**: `/home/cotton/Projects/ML/Thesis/PureEdgeSim/data/processed/pureedgesim_tasks_15min.json`  
**Overall result**: ✅ M4 PASSED

### Parameters

| Parameter | Value |
|---|---|
| MIPS_base | 2000 MIPS |
| MaxCellRAM | 64.0 GB |
| N_edge_devices | 10 |
| RNG seed | 42 |
| α_input (fileSizeInBits ratio) | 0.25 |
| LogNormal(μ, σ) | (0.0, 0.5) |
| Uniform(β_min, β_max) | (0.05, 0.2) |
| SlackFactor κ, λ | 0.5, 0.5 |

### Validation Checklist

| Result | Check |
|---|---|
| ✅ PASS | length > 0 for all tasks |
| ✅ PASS | containerSizeInBits > 0 for all |
| ✅ PASS | maxLatency >= delta_t_exec for all |
| ✅ PASS | Record count invariant (N_out==N_in) |
| ✅ PASS | edgeDevice in [0, N_edge-1] |

### Translation Formulas

```
length_mi        = round(req_cpus × 2000 × delta_t_exec)
containerBits    = round(req_memory × 68,719,476,736 × 8)
fileSizeInBits   = round(containerBits × 0.25 × LogNormal(μ=0.0, σ=0.5))
outputSizeInBits = round(fileSizeInBits × Uniform(0.05, 0.2))
SlackFactor      = 0.5(3 - Sc) + 0.5(1 - P / 450)
maxLatency       = delta_t_exec × (1.0 + SlackFactor)
edgeDevice       = |hashCode(user)| mod 10
```

### Output Field Quantiles

| Field | P1 | P25 | P50 | P75 | P90 | P99 |
|---|---|---|---|---|---|---|
| length_mi [MI] | 1.0 | 503.0 | 2186.0 | 4633.0 | 7171.6 | 28229.4 |
| containerSize [MB] | 13.750 | 149.750 | 240.000 | 574.750 | 1194.000 | 1878.000 |
| fileSize [MB] | 3.191 | 33.739 | 70.525 | 155.567 | 303.562 | 826.015 |
| maxLatency [s] | 0.000 | 50.440 | 115.074 | 251.810 | 619.414 | 1360.286 |
| slackFactor | 0.278 | 0.876 | 1.278 | 1.383 | 1.778 | 2.000 |

### Edge Device Assignment Distribution

| Device Index | Task Count | % |
|---|---|---|
| 0 | 1,444 | 23.1% |
| 1 | 927 | 14.8% |
| 2 | 142 | 2.3% |
| 3 | 77 | 1.2% |
| 4 | 1,821 | 29.1% |
| 5 | 431 | 6.9% |
| 6 | 306 | 4.9% |
| 7 | 796 | 12.7% |
| 8 | 187 | 3.0% |
| 9 | 127 | 2.0% |

---
