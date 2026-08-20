# Phase 3 — Translation & Mapping Design: Google Cluster → PureEdgeSim Workload Replay

**Document Version:** 1.0  
**Date:** 2026-08-18  
**Scope:** Formal Mapping Specification, Attribute Translation, Synthetic Feature Modeling, RL Context Preservation, and Workload Fidelity Validation.

---

## 1. Executive Summary & Mapping Framework

This document specifies the formal translation layer mapping Google Cluster Trace v3 (Borg 2019) records into native PureEdgeSim `Task` objects. Every transformation is mathematically defined and explicitly justified to ensure that the translated workload preserves the temporal dynamics, resource demands, and priority distributions of the original Google cloud workload while adapting to PureEdgeSim's discrete-event simulation model.

### Complete Mapping Pipeline

```
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                                Google Trace v3 Raw Records                                  │
│  - InstanceEvents: (collection_id, instance_index, time, type, priority, scheduling_class)  │
│  - CollectionEvents: (user, logical_name)                                                   │
│  - InstanceUsage: (average_usage, CPI, MAI)                                                 │
└─────────────────────────────────────────────┬───────────────────────────────────────────────┘
                                              │
                                              v  Transformation & Derivation Rules
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                                  Intermediate Workload Unit                                 │
│  - Task Execution Attempt: (t_submit, ΔT_exec, NCU_req, RAM_req, priority, user_hash)        │
└─────────────────────────────────────────────┬───────────────────────────────────────────────┘
                                              │
                                              v  Synthetic Modeling & Metadata Preservation
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                                 Native PureEdgeSim Task                                     │
│  - id: int32 (1-based sequential)                                                           │
│  - time: double (relative seconds)                                                          │
│  - length: long (Million Instructions)                                                      │
│  - fileSizeInBits / outputSizeInBits: long (network payload bits)                           │
│  - containerSizeInBits: long (RAM / binary footprint)                                       │
│  - maxLatency: double (SLO deadline in seconds)                                             │
│  - edgeDevice: ComputingNode (assigned origin edge node)                                    │
│  - metadata: Map<String, Object> (RL state features: priority, scheduling_class, CPI)      │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Workload Mapping Specification & Field Justifications

### 2.1 Definition of a Task Unit
- **Mapping Rule**: A PureEdgeSim task corresponds to **a single execution attempt** of a Borg task instance (`collection_type = 0`).
- **Handling Restarts**: In Borg, a task with the same `(collection_id, instance_index)` can be evicted, failed, or killed and subsequently restarted. Each restart attempt (signaled by a new `SUBMIT` or `SCHEDULE` event) is assigned a new unique 1-based integer `TaskID`.
- **Justification**: In discrete-event simulation and RL offloading experimentation, every execution attempt is an independent decision point consuming network bandwidth, compute cycles, and energy.

---

### 2.2 Task Arrival Replay ($t_{\text{arrival}}$)

$$\text{PureEdgeSim Task.time} = t_{\text{arrival}} = \frac{t_{\text{SUBMIT}} - t_{\text{window\_start}}}{1,000,000} \quad (\text{seconds})$$

- **Parameter Definitions**:
  - $t_{\text{SUBMIT}}$: Timestamp of the task `SUBMIT` event in microseconds.
  - $t_{\text{window\_start}}$: Start timestamp of the selected trace replay window (microseconds).
- **Pre-existing Tasks ($t_{\text{SUBMIT}} < t_{\text{window\_start}}$)**:
  - Tasks already running at trace start ($time = 0$) are excluded from application task arrival queues to prevent zero-timestamp burst anomalies, or instantiated as pre-existing background host load.
- **Justification**: Division by $1,000,000$ converts microseconds ($\mu s$) to seconds ($s$), preserving exact inter-arrival timing ($\Delta t$), burstiness, Poisson/long-tailed arrival dynamics, and time-of-day workload fluctuations.

---

### 2.3 Task Computational Workload Length (`length` in MI)

$$\text{Length}_{\text{MI}} = \text{round}\left( \text{resource\_request.cpus} \times \text{MIPS}_{\text{base\_core}} \times \Delta T_{\text{exec}} \right)$$

- **Parameter Definitions**:
  - $\text{resource\_request.cpus}$: Requested CPU rate in Normalized Compute Units ($\text{NCU} \in [0.0, 1.0]$).
  - $\text{MIPS}_{\text{base\_core}}$: Baseline processing capacity of 1 nominal CPU core in PureEdgeSim (e.g., $M = 2000 \text{ MIPS}$).
  - $\Delta T_{\text{exec}}$: Ground-truth execution duration in seconds:
    $$\Delta T_{\text{exec}} = \frac{t_{\text{FINISH}} - t_{\text{SCHEDULE}}}{1,000,000}$$
- **Justification**: PureEdgeSim calculates task execution time on a node as $T = \frac{\text{Length}_{\text{MI}}}{\text{Host MIPS}}$. By setting $\text{Length}_{\text{MI}}$ proportional to $\text{NCU} \times \text{MIPS}_{\text{base\_core}} \times \Delta T_{\text{exec}}$, executing the task on a baseline host in PureEdgeSim takes exactly $\Delta T_{\text{exec}}$ seconds, preserving true computational demand.

---

### 2.4 Container Image & RAM Requirement (`containerSizeInBits`)

$$\text{containerSizeInBits} = \text{round}\left( \text{resource\_request.memory} \times \text{MaxCellRAM}_{\text{Bytes}} \times 8 \right)$$

- **Parameter Definitions**:
  - $\text{resource\_request.memory}$: Normalized requested RAM ($[0.0, 1.0]$).
  - $\text{MaxCellRAM}_{\text{Bytes}}$: Maximum machine memory capacity in the trace cell (e.g. 64 GB = $68,719,476,736 \text{ Bytes}$).
- **Justification**: Unscales normalized memory back to physical bits, matching PureEdgeSim's container size data model used for RAM allocation checks and cold-start image downloading.

---

## 3. Modeling Unavailable Google Trace Fields

Google Cluster Trace v3 does not record network payload sizes, explicit latency deadlines, or client device locations. The following models synthesize these parameters based on domain-established cloud/edge principles:

```
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                                Synthetic Field Modeling                                     │
├───────────────────────────────┬─────────────────────────────────────────────────────────────┤
│ Target PureEdgeSim Parameter  │ Synthesis / Modeling Formula                                │
├───────────────────────────────┼─────────────────────────────────────────────────────────────┤
│ Input Size (fileSizeInBits)   │ containerSizeInBits * α_input * LogNormal(0, σ)            │
│ Output Size (outputSizeInBits)│ fileSizeInBits * β_output (Uniform distribution)            │
│ Latency Deadline (maxLatency) │ ΔT_exec * (1.0 + SlackFactor(scheduling_class, priority))   │
│ Origin Device (edgeDevice)    │ hashCode(user_hash) mod N_edge_devices                      │
└───────────────────────────────┴─────────────────────────────────────────────────────────────┘
```

### 3.1 Network Input Payload Size (`fileSizeInBits`)

$$\text{fileSizeInBits} = \text{round}\left( \text{containerSizeInBits} \times \alpha_{\text{input}} \times X \right)$$

- **Model Specifications**:
  - $\alpha_{\text{input}} = 0.25$ (Baseline input data to container size ratio).
  - $X \sim \text{LogNormal}(\mu = 0, \sigma = 0.5)$ (Random multiplicative factor modeling heavy-tailed data payloads).
- **Justification**: Input payload size (binaries + input datasets) scales with the container memory footprint. A Log-Normal distribution reflects empirical heavy-tailed network traffic in cloud data processing jobs.

---

### 3.2 Network Output Result Payload Size (`outputSizeInBits`)

$$\text{outputSizeInBits} = \text{round}\left( \text{fileSizeInBits} \times Y \right)$$

- **Model Specifications**:
  - $Y \sim \text{Uniform}(0.05, 0.20)$ (Output result payload fraction).
- **Justification**: In edge computing task offloading, execution results (e.g. ML inference classification labels, filtered data summaries) returned to origin client devices are significantly smaller than input code/datasets.

---

### 3.3 Latency Deadline (`maxLatency`)

$$\text{maxLatency} = \Delta T_{\text{exec}} \times \left(1.0 + \text{SlackFactor}(S_c, P)\right)$$

$$\text{SlackFactor}(S_c, P) = \kappa \cdot (3 - S_c) + \lambda \cdot \left(1.0 - \frac{P}{P_{\max}}\right)$$

- **Parameter Specifications**:
  - $S_c \in \{0, 1, 2, 3\}$: Borg `scheduling_class` ($3 = \text{latency sensitive}$, $0 = \text{batch}$).
  - $P \in [0, 450]$: Borg `priority` tier ($P_{\max} = 450$).
  - Constants: $\kappa = 0.5$, $\lambda = 0.5$.
- **Deadline Strictness Examples**:
  - **Latency-Sensitive Production ($S_c = 3, P = 450$)**:
    $$\text{SlackFactor} = 0.5(0) + 0.5(0) = 0.0 \implies \text{maxLatency} = 1.0 \times \Delta T_{\text{exec}} \quad (\text{Strict Deadline})$$
  - **Non-Production Batch ($S_c = 0, P = 0$)**:
    $$\text{SlackFactor} = 0.5(3) + 0.5(1) = 2.0 \implies \text{maxLatency} = 3.0 \times \Delta T_{\text{exec}} \quad (\text{Relaxed Deadline})$$
- **Justification**: Borg scheduling classes ($0 \dots 3$) and priorities ($0 \dots 450$) directly reflect latency sensitivity and service level objectives (SLOs). Higher priority and class 3 tasks enforce strict latency limits, while batch tasks tolerate queuing delays.

---

### 3.4 Origin Edge Device Assignment (`edgeDevice`)

$$\text{DeviceIndex} = \left| \text{hashCode}(\text{user}) \right| \pmod{N_{\text{edge\_devices}}}$$

- **Model Specifications**:
  - $\text{user}$: Hashed base64 username from `CollectionEvents`.
  - $N_{\text{edge\_devices}}$: Total number of simulated edge/mist client devices in PureEdgeSim topology.
- **Justification**: In Google cells, `user` hashes identify specific engineers, microservices, or automated pipeline drivers. Hashing `user` deterministically to edge devices ensures that all tasks generated by the same service originate from the same physical edge node, preserving realistic spatial locality.

---

## 4. Metadata Preservation for Reinforcement Learning (RL) Scheduler

The Python Bridge (`PythonOrchestrator`) passes task features to PyTorch RL algorithms. To enable the RL scheduler to learn intelligent scheduling policies, the following trace attributes are explicitly preserved inside `Task.metadata` or custom feature vectors:

```
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                            RL Feature Map (Task.metadata)                                   │
├───────────────────────────────┬─────────────────────────────────────────────────────────────┤
│ Feature Key                   │ RL Scheduler Utility & Decision Significance                │
├───────────────────────────────┼─────────────────────────────────────────────────────────────┤
│ priority                      │ Weights step rewards / penalties for task drops & latency.  │
│ scheduling_class              │ Action masking for edge vs cloud placement heuristics.      │
│ collection_logical_name       │ Identifies recurring job types for execution prediction.    │
│ average_usage_cpu / memory    │ Enables RL observation of task CPU/RAM utilization ratio.   │
│ CPI & MAI                     │ Classifies CPU-bound vs memory-bound workloads.             │
└───────────────────────────────┴─────────────────────────────────────────────────────────────┘
```

1. **`priority` ($0 \dots 450+$)**:
   - *RL Utility*: Used directly in RL reward functions. E.g., failing a production task ($P \ge 120$) incurs a $10\times$ higher negative reward penalty than failing a free-tier batch task ($P \le 99$).
2. **`scheduling_class` ($0 \dots 3$)**:
   - *RL Utility*: Allows state space vectorization to distinguish latency-critical tasks ($S_c = 3$) requiring low-latency edge nodes from batch tasks ($S_c = 0$) suitable for distant cloud datacenters.
3. **`collection_logical_name`**:
   - *RL Utility*: Serves as a categorical feature encoding job family templates (e.g. MapReduce workers vs web servers), enabling RL agents to generalize learned placement policies across job instances.
4. **`average_usage.cpu` & `average_usage.memory`**:
   - *RL Utility*: Provides actual-to-requested resource ratios ($\frac{\text{usage}}{\text{request}}$) for overcommit awareness and bin-packing optimization.
5. **Hardware Performance Counters (`CPI`, `MAI`)**:
   - *RL Utility*: Distinguishes memory-bound workloads (high MAI/CPI) from compute-bound workloads (low CPI), aiding host CPU frequency and memory bandwidth scheduling.

---

## 5. Workload Fidelity Validation Methodology

To mathematically verify that the translated PureEdgeSim workload faithfully represents the original Google Cluster trace, the following four validation checks must be executed:

```
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                                Workload Replay Validation                                   │
├───────────────────────────────┬─────────────────────────────────────────────────────────────┤
│ Validation Check              │ Quantitative Metric & Acceptance Threshold                  │
├───────────────────────────────┼─────────────────────────────────────────────────────────────┤
│ 1. Temporal Arrival Match     │ Kolmogorov-Smirnov test (D_KS < 0.01, p-value > 0.05)       │
│ 2. Compute Load Conservation  │ Total Core-Seconds Trace vs Total MI PureEdgeSim (< 1% diff)│
│ 3. Memory Demand Conservation │ Total RAM-Seconds Trace vs Total Container Bits (< 1% diff) │
│ 4. Priority Tier Distribution │ Chi-Square Goodness-of-Fit Test (p-value > 0.05)            │
└───────────────────────────────┴─────────────────────────────────────────────────────────────┘
```

1. **Temporal Arrival Distribution Validation**:
   - Compute cumulative distribution functions (CDFs) of inter-arrival times $\Delta t = t_{i+1} - t_i$ for original trace events vs generated PureEdgeSim `taskList`.
   - Perform a **Two-Sample Kolmogorov-Smirnov (K-S) Test**. Target threshold: $D_{\text{KS}} < 0.01$ with $p\text{-value} > 0.05$.
2. **Total Compute Demand Conservation**:
   - Compare total requested CPU work across the replay window:
     $$\sum N_{\text{NCU}} \cdot \Delta T_{\text{exec}} \quad \text{vs} \quad \frac{\sum \text{Length}_{\text{MI}}}{\text{MIPS}_{\text{base\_core}}}$$
   - Acceptable variance: $< 1.0\%$ (attributable to integer rounding of `Length_MI`).
3. **Total Memory Demand Conservation**:
   - Compare total memory-time integrals:
     $$\sum M_{\text{rescaled}} \cdot \Delta T_{\text{exec}} \quad \text{vs} \quad \frac{\sum \text{containerSizeInBits} \cdot \Delta T_{\text{exec}}}{\text{MaxCellRAM}_{\text{bits}}}$$
   - Acceptable variance: $< 1.0\%$.
4. **Priority & Scheduling Class Distribution Preservation**:
   - Perform a **Chi-Square ($\chi^2$) Goodness-of-Fit Test** comparing priority tier histogram distributions between raw trace input and generated PureEdgeSim task stream. Target: $p\text{-value} > 0.05$.
