# Phase 5 — Validation Strategy & Academic Defense Plan

**Document Version:** 1.0  
**Date:** 2026-08-18  
**Scope:** Formal Verification Plan, Mathematical Validation Protocols, Success Criteria, and Academic Defense Rationale.

---

## 1. Executive Summary: What Defines "Mapping Success"?

A workload translation from Google Cluster Trace v3 (Borg 2019) to PureEdgeSim is **successful** if and only if it satisfies four core criteria:

1. **Workload Fidelity (Statistical Equivalence)**:
   The statistical distributions of task arrival patterns, computational lengths, resource requirements, and priority classes generated in PureEdgeSim match the ground-truth Google Cluster trace distributions without temporal or quantitative distortion.
2. **Execution Timing Equivalence (Baseline Calibration)**:
   When replayed in PureEdgeSim on un-contended baseline nodes matching Borg reference hardware, simulated task execution durations ($\Delta T_{\text{sim\_exec}}$) match ground-truth trace execution times ($\Delta T_{\text{trace\_exec}}$) with $< 0.1\%$ error.
3. **Lossless RL & Analytical Expressiveness**:
   All priority tiers ($0 \dots 450+$), scheduling classes ($0 \dots 3$), user hashes, and logical job names are preserved, allowing reinforcement learning schedulers to learn realistic trade-offs between latency, energy, cost, and priority.
4. **Pipeline Losslessness & Invariant Enforcement**:
   Zero task records are lost, duplicated, or corrupted between BigQuery extraction, Parquet storage, Python preprocessing, Java stream ingestion, and simulation execution ($N_{\text{BigQuery}} = N_{\text{Simulated}}$).

---

## 2. Formal Validation Protocols & Mathematical Tests

```
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                              Comprehensive Validation Suite                                 │
├───────────────────────────┬───────────────────────────┬─────────────────────────────────────┤
│ Test 1: Arrival CDF Match │ Test 2: Demand Integrals  │ Test 3: Duration Calibration        │
│ (Two-Sample K-S Test)     │ (Compute & RAM Sums)      │ (MAPE < 0.1% under baseline host)   │
├───────────────────────────┼───────────────────────────┼─────────────────────────────────────┤
│ Test 4: Categorical Chi-Sq│ Test 5: Synthetic Plausib.│ Test 6: Pipeline Record Invariant   │
│ (Priority & S_c Fit)      │ (Deadline Slack Audit)    │ (N_BigQuery == N_Simulated)         │
└───────────────────────────┴───────────────────────────┴─────────────────────────────────────┘
```

---

### Test 1: Task Arrival Process & Temporal Pattern Validation

#### Goal
Prove that the generated PureEdgeSim task arrival stream faithfully reproduces the temporal dynamics (arrival rate $\lambda(t)$, burstiness, inter-arrival time distribution) of the real Google datacenter workload.

#### Mathematical Protocol
1. Extract ground-truth submission timestamps $t_{\text{SUBMIT}}$ from raw Parquet trace dataset.
2. Record actual generation timestamps $t_{\text{arrival}}$ of `Task` objects emitted by `StreamedTraceTaskGenerator` in PureEdgeSim.
3. Compute Inter-Arrival Times (IAT): $\Delta t_i = t_{i+1} - t_i$.
4. Perform a **Two-Sample Kolmogorov-Smirnov (K-S) Test** comparing ground-truth IAT distribution $F_{\text{trace}}(\Delta t)$ against PureEdgeSim IAT distribution $F_{\text{sim}}(\Delta t)$:
   $$D_{\text{KS}} = \sup_{\Delta t} \left| F_{\text{trace}}(\Delta t) - F_{\text{sim}}(\Delta t) \right|$$

#### Success Criteria
- K-S Statistic $D_{\text{KS}} < 0.01$.
- $p\text{-value} > 0.05$ (fail to reject the null hypothesis that distributions are identical).

#### Academic Defense & Rationale
*Why this step is mandatory*: Task arrival timing dictates queue backlogs, network congestion, and burst behavior in cloud/edge systems. Non-parametric K-S testing evaluates the entire distribution shape (including long tails and arrival spikes). Achieving $D_{\text{KS}} < 0.01$ proves that temporal burstiness (e.g. MapReduce job submission spikes) is preserved without artificial jitter or distortion.

---

### Test 2: Resource Demand Conservation (Compute & RAM Integrals)

#### Goal
Verify that total requested compute work (core-seconds / MI) and total requested memory footprint (GB-seconds) are strictly conserved between trace and simulation.

#### Mathematical Protocol
1. Sum ground-truth requested CPU work:
   $$W_{\text{trace\_cpu}} = \sum_{i=1}^N \left( \text{req\_cpus}_i \times \Delta T_{\text{exec}, i} \right) \quad (\text{NCU-seconds})$$
2. Sum translated PureEdgeSim workload length:
   $$W_{\text{sim\_cpu}} = \sum_{i=1}^N \left( \frac{\text{Length}_{\text{MI}, i}}{\text{MIPS}_{\text{base\_core}}} \right) \quad (\text{Core-seconds equivalent})$$
3. Sum ground-truth memory area:
   $$M_{\text{trace\_ram}} = \sum_{i=1}^N \left( \text{req\_memory}_i \times \Delta T_{\text{exec}, i} \right)$$
4. Sum translated PureEdgeSim container memory area:
   $$M_{\text{sim\_ram}} = \sum_{i=1}^N \left( \frac{\text{containerSizeInBits}_i}{8 \cdot \text{MaxCellRAM}_{\text{Bytes}}} \times \Delta T_{\text{exec}, i} \right)$$

#### Success Criteria
- Relative Compute Error: $\epsilon_{\text{cpu}} = \frac{\left| W_{\text{sim\_cpu}} - W_{\text{trace\_cpu}} \right|}{W_{\text{trace\_cpu}}} < 0.01 \quad (< 1.0\%)$.
- Relative Memory Error: $\epsilon_{\text{ram}} = \frac{\left| M_{\text{sim\_ram}} - M_{\text{trace\_ram}} \right|}{M_{\text{trace\_ram}}} < 0.01 \quad (< 1.0\%)$.

#### Academic Defense & Rationale
*Why this step is mandatory*: Follows the conservation of energy/mass principle applied to workload evaluation. Proves that total computational work and RAM footprint submitted to the simulator match the physical reality of the Google datacenter cell without scaling drift or scaling conversion loss.

---

### Test 3: Baseline Execution Duration Calibration

#### Goal
Confirm that task execution duration in PureEdgeSim under baseline host conditions matches trace ground-truth execution duration $\Delta T_{\text{exec}}$.

#### Mathematical Protocol
1. Execute a baseline calibration simulation run where tasks are assigned to an un-contended baseline host whose processing capacity matches $\text{MIPS}_{\text{base\_core}}$.
2. Measure PureEdgeSim simulated execution time $\Delta T_{\text{sim\_exec}} = t_{\text{finish}} - t_{\text{exec\_start}}$.
3. Compute Mean Absolute Percentage Error ($\text{MAPE}$) across all tasks:
   $$\text{MAPE} = \frac{1}{N} \sum_{i=1}^N \frac{\left| \Delta T_{\text{sim\_exec}, i} - \Delta T_{\text{trace\_exec}, i} \right|}{\Delta T_{\text{trace\_exec}, i}} \times 100\%$$

#### Success Criteria
- $\text{MAPE} < 0.1\%$ (exact deterministic execution match under zero contention).

#### Academic Defense & Rationale
*Why this step is mandatory*: Validates our MI length formulation ($\text{Length}_{\text{MI}} = \text{NCU} \times \text{MIPS}_{\text{base\_core}} \times \Delta T_{\text{exec}}$). Guarantees that any departure from ground-truth execution time during full simulation is strictly caused by simulated edge network latency, queuing delay, or host contention—not by translation error.

---

### Test 4: Priority & Scheduling Class Categorical Preservation

#### Goal
Ensure categorical distributions of priority tiers ($0 \dots 450+$) and scheduling classes ($0 \dots 3$) are losslessly preserved.

#### Mathematical Protocol
1. Construct frequency histogram tables for priority tiers and scheduling classes in raw BigQuery extract vs PureEdgeSim task stream.
2. Perform a **Chi-Square ($\chi^2$) Goodness-of-Fit Test**:
   $$\chi^2 = \sum_{k=1}^K \frac{(O_k - E_k)^2}{E_k}$$

#### Success Criteria
- $p\text{-value} > 0.05$ (distributions are statistically indistinguishable).
- $0\%$ missing or misclassified priority categories.

#### Academic Defense & Rationale
*Why this step is mandatory*: Reinforcement learning schedulers rely heavily on priority tiers ($P$) and latency sensitivity ($S_c$) to calculate drop penalties and compute action masks. Preserving categorical distributions guarantees that the RL agent faces the exact same decision trade-off profile as Borg.

---

### Test 5: Synthetic Modeling Sensitivity & Plausibility Audit

#### Goal
Validate that synthetically modeled fields (`fileSizeInBits`, `outputSizeInBits`, `maxLatency`, `edgeDevice`) adhere to domain plausibility boundaries and do not introduce un-physical edge cases.

#### Mathematical Protocol & Audits
1. **Deadline Feasibility Audit**:
   Verify that $\text{maxLatency}_i \ge \Delta T_{\text{exec}, i}$ for $100\%$ of generated tasks (zero impossible deadlines at submission time).
2. **Deadline Slack Ordering Audit**:
   Verify that latency-sensitive tasks ($S_c = 3$) have tighter deadline slack than batch tasks ($S_c = 0$):
   $$\text{MeanSlack}(S_c = 3) < \text{MeanSlack}(S_c = 2) < \text{MeanSlack}(S_c = 1) < \text{MeanSlack}(S_c = 0)$$
3. **Payload Distribution Audit**:
   Verify log-normal input payload parameters ($\mu = 0, \sigma = 0.5$) and confirm that $\text{outputSizeInBits}_i < \text{fileSizeInBits}_i$ for $>99\%$ of tasks.
4. **Spatial Load Uniformity Audit**:
   Perform Chi-Square Uniformity Test over $N_{\text{edge\_devices}}$ for assigned `edgeDevice` nodes.

#### Success Criteria
- $0$ tasks with impossible deadlines ($\text{maxLatency} < \Delta T_{\text{exec}}$).
- Monotonic increase in deadline slack across scheduling classes ($S_c = 3 \rightarrow 0$).
- $p\text{-value} > 0.05$ for spatial edge device assignment distribution.

#### Academic Defense & Rationale
*Why this step is mandatory*: Defends synthetic modeling assumptions against reviewer criticism. Proves that missing Borg trace attributes (network sizes, deadlines, edge devices) are replaced with domain-sound models that respect physical boundaries and real-world edge/cloud characteristics.

---

### Test 6: Pipeline Record Losslessness & Invariant Check

#### Goal
Verify 100% losslessness of task counts across the entire 6-stage pipeline.

#### Mathematical Protocol
Enforce strict equality of record counts at every stage transition:
$$N_{\text{BigQuery}} = N_{\text{Parquet}} = N_{\text{Python\_Preprocessed}} = N_{\text{StreamedTaskGen}} = N_{\text{Simulated}}$$

#### Success Criteria
- Record Loss Count: $N_{\text{BigQuery}} - N_{\text{Simulated}} = 0$.

#### Academic Defense & Rationale
*Why this step is mandatory*: Guarantees data integrity and rules out silent record drops, buffer overflow truncation, or parser crashes during stream ingestion.

---

## 3. Defense Matrix: Validation Summary & Academic Justification

| Validation Test | Primary Metric / Test | Target Threshold | Academic Defense Rationale |
| :--- | :--- | :--- | :--- |
| **1. Arrival Process** | Two-Sample K-S Test | $D_{\text{KS}} < 0.01, p > 0.05$ | Proves temporal burstiness & inter-arrival CDF match ground-truth trace. |
| **2. Resource Conservation**| Relative Sum Error | $\epsilon_{\text{cpu}} < 1.0\%, \epsilon_{\text{ram}} < 1.0\%$ | Guarantees conservation of compute work (core-sec) and RAM footprint (GB-sec). |
| **3. Duration Calibration** | Baseline Host MAPE | $\text{MAPE} < 0.1\%$ | Proves execution duration match is exact; variation in sim is due to network/contention. |
| **4. Priority Preservation**| Chi-Square Test ($\chi^2$) | $p > 0.05$ | Guarantees RL agent faces identical priority drop trade-offs as real Borg scheduler. |
| **5. Synthetic Plausibility**| Deadline & Payload Audits| $0\%$ impossible deadlines, monotonic slack | Defends missing trace field assumptions against thesis review critique. |
| **6. Pipeline Losslessness** | Record Count Invariant | $N_{\text{BigQuery}} - N_{\text{Simulated}} = 0$ | Ensures 100% data integrity without silent record drops or parser corruption. |
