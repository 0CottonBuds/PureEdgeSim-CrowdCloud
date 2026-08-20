# Trace-Driven Workload Generation for Reinforcement Learning-Based Edge Computing Orchestration
## Master Thesis Methodology & Architectural Reference Guide

**Document Version:** 1.0 (Thesis Reference Document)  
**Date:** 2026-08-18  
**Target Audience:** Thesis Chapter Author, Research Advisors, and Peer Reviewers.  
**Scope:** Complete Methodology for Extracting, Translating, Stream-Replaying, and Validating Google Cluster Trace v3 (Borg 2019) Workloads inside PureEdgeSim.

---

# Abstract & Methodology Overview

Evaluating Reinforcement Learning (RL) placement and orchestration algorithms in edge computing environments requires realistic, reproducible, and heavy-tailed workload patterns. Standard synthetic workload generators (e.g. uniform or Poisson process generators) fail to capture the complex temporal dynamics, resource heterogeneity, priority tiers, and burstiness observed in real-world industrial cloud datacenters.

This document presents a complete, mathematically rigorous methodology for translating real-world **Google Cluster Trace v3 (Borg 2019)** dataset records into native **PureEdgeSim** task instances. It establishes an end-to-end pipeline covering:

1. **Single-pass, budget-optimized BigQuery SQL extraction** that aggregates raw event logs into consolidated task lifecycles ($3\times \sim 5\times$ data reduction).
2. **Formal resource and timing translation models** mapping Google's Normalized Compute Units (NCUs) and rescaled RAM fractions into Million Instructions (MI), container memory footprints, and network payloads.
3. **Domain-sound synthetic modeling** for missing trace attributes (input/output payload sizes, SLO deadlines, and origin edge devices).
4. **Streamed sliding-window task ingestion (`StreamedTraceTaskGenerator`)** enforcing a bounded $\mathcal{O}(N_{\text{buffer}})$ JVM heap memory footprint to prevent memory explosion during 24-hour trace replays ($200,000+$ tasks).
5. **A 6-stage statistical validation suite** (including Two-Sample Kolmogorov-Smirnov tests and Chi-Square goodness-of-fit tests) to prove translation fidelity.

---

# Chapter 1: Background & System Architecture

## 1.1 PureEdgeSim System & Task Generation Architecture

PureEdgeSim is a discrete-event simulator built on CloudSim concepts to model Cloud, Edge, and Mist computing infrastructures. The simulation lifecycle is coordinated by the discrete-event engine (`PureEdgeSim` / `SimEntity`), `SimulationThread`, and `DefaultSimulationManager`.

```
┌─────────────────────────────────────────────────────────────────────────────────────────┐
│                                PureEdgeSim Simulation                                   │
│                                                                                         │
│  ┌──────────────────────┐   creates   ┌─────────────────────┐   populates  ┌─────────┐  │
│  │   SimulationThread   │ ----------> │    TaskGenerator    │ -----------> │taskList │  │
│  └──────────┬───────────┘             └─────────────────────┘              └────┬────┘  │
│             │ launches                                                          │       │
│             v                                                                   │       │
│  ┌──────────────────────┐    schedules SEND_TO_ORCH at task.getTime()          │       │
│  │  DefaultSimManager   │ <------------------------------------------------─────┘       │
│  └──────────┬───────────┘                                                               │
└─────────────┼───────────────────────────────────────────────────────────────────────────┘
              │ findComputingNode(task)
              v
┌───────────────────────────┐      Unix Domain Socket (JSON)      ┌─────────────────────────┐
│    PythonOrchestrator     │ <─────────────────────────────────> │   Python Orchestrator   │
│  (Java Orchestrator)      │        DECISION_REQUEST             │ (PyTorch / RL / Policy) │
└───────────────────────────┘                                     └─────────────────────────┘
```

Workload generation in PureEdgeSim is governed by the `TaskGenerator` abstract class:
```java
public abstract class TaskGenerator {
    protected FutureQueue<Task> taskList;
    protected List<ComputingNode> devicesList;
    protected SimulationManager simulationManager;

    public abstract FutureQueue<Task> generate();
}
```
Tasks are enqueued in `taskList` (`FutureQueue<Task>`), a priority queue sorted chronologically by task arrival timestamp (`task.getTime()`).

---

## 1.2 PureEdgeSim Task Data Model

A valid PureEdgeSim task is represented by the `Task` interface (`DefaultTask`). The table below outlines all core attributes required by the simulator:

| Task Field | Data Type | Physical / Simulation Unit | Role in Discrete-Event Engine |
| :--- | :--- | :--- | :--- |
| `id` | `int` | 1-based Integer | Unique identifier for event correlation and logging. |
| `time` | `double` | Simulation Seconds ($s$) | Arrival timestamp relative to simulation zero ($t_{\text{arrival}} \ge 0.0$). |
| `length` | `long` | Million Instructions (MI) | Computational workload size. Determines execution duration on host. |
| `fileSizeInBits` | `long` | Bits | Input network payload transmitted from edge device to target node. |
| `outputSizeInBits` | `long` | Bits | Result network payload transmitted back from target node to edge device. |
| `containerSizeInBits`| `long` | Bits | Container image size / RAM footprint checked during placement & cold start. |
| `maxLatency` | `double` | Seconds ($s$) | Maximum allowable latency (SLO deadline). Task fails if total latency $> \text{maxLatency}$. |
| `edgeDevice` | `ComputingNode`| Java Reference | Generating source edge/mist device where task originates. |
| `applicationID` | `int` | Integer ID | Category identifier mapping to application profile. |
| `metadata` | `Map` | Key-Value Pairs | Preserves RL state features (priority, scheduling class, CPI, MAI). |

---

## 1.3 Python Orchestrator IPC Bridge Integration

When executing custom RL placement algorithms (written in PyTorch / Gymnasium), PureEdgeSim uses the `PythonOrchestrator` bridge:

1. **Synchronous Placement Request**: At simulation time $t = \text{task.getTime()}$, event tag `SEND_TO_ORCH` fires. `PythonOrchestrator` intercepts the `Task` and constructs a JSON `DECISION_REQUEST` payload transmitted over a Unix Domain Socket to Python (`pureedgesim._bridge.server`).
2. **Feature Vectorization**: Python's `task_to_array(task)` flattens the `Task` object into a 1D `float32` NumPy array of shape `(9,)`:
   $$\mathbf{x}_{\text{task}} = \begin{bmatrix} \text{length\_mi}, \text{input\_mb}, \text{output\_mb}, \text{container\_mb}, \text{deadline}, \text{app\_id}, \text{loc\_x}, \text{loc\_y}, \text{cpu\_util} \end{bmatrix}^T$$
3. **Asynchronous Feedback Loop**: Upon task completion or failure in Java, `PythonOrchestrator.resultsReturned(task)` sends a fire-and-forget `TASK_RESULT` payload to Python. Python invokes `on_task_complete(outcome)` with latency, energy, and failure metrics to compute RL step rewards and populate replay buffers.

---

# Chapter 2: Google Cluster Trace v3 Analysis & Taxonomy

## 2.1 Borg Cluster Terminology & Schema

Google Cluster Trace v3 (2019 release) documents workloads executed on Google compute cells managed by Borg.

```
┌────────────────────────────────────────────────────────────────────────┐
│               Collection (Job / Alloc Set)                             │
│               - collection_id: 64-bit unique integer                   │
│               - priority: [0 .. 450]                                   │
│               - scheduling_class: [0 .. 3]                             │
└──────────────────────────────────┬─────────────────────────────────────┘
                                   │ 1-to-N
                                   v
┌────────────────────────────────────────────────────────────────────────┐
│               Instance (Task / Alloc Instance)                         │
│               - Primary Key: (collection_id, instance_index)           │
│               - collection_type: 0 (Job Task) vs 1 (Alloc Instance)    │
│               - resource_request: { cpu: NCUs, memory: float }         │
└────────────────────────────────────────────────────────────────────────┘
```

- **Collection**: A logical grouping of work representing a **Job** (`collection_type = 0`) or an **Alloc Set** (`collection_type = 1`). Identified by 64-bit `collection_id`.
- **Instance**: An individual Linux program execution unit (**Task**) within a job. Identified by compound key `(collection_id, instance_index)`.

---

## 2.2 Obfuscation & Resource Normalization Scaling

Google applies five obfuscation transformations:
1. `unchanged`: Raw integer/string values.
2. `hashed`: Cryptographic hash to opaque base64 string.
3. `ordered`: Categorical items sorted and mapped to 0-based sequential integers.
4. `rescaled`: Floating-point values linearly transformed into $[0.0, 1.0]$:
   $$\text{Granularity} = \max\left(\frac{1.0}{2^{10}}, \frac{\text{max\_value}}{2^{20}}\right) \approx 10\text{ bits binary precision}$$
5. `special`: Reserved states (`machine_id = -1` for dedicated hosts, `time = 0` for pre-existing events, `time = 2^63 - 1` for post-trace events).

### Resource Units
- **CPU**: Measured in Normalized Compute Units (NCUs) in $[0.0, 1.0]$, where $1.0 \text{ NCU} = \text{Max GCU Capacity}$ in cell.
- **RAM**: Measured in rescaled memory fractions in $[0.0, 1.0]$, where $1.0 = \text{Max RAM Capacity}$ in cell.

---

## 2.3 Life Cycle State Machine

Task instances transition through distinct lifecycle states:
- `0 (SUBMIT)`: Task submitted to Borg scheduler queue $\rightarrow$ **Arrival Time ($t_{\text{submit}}$)**.
- `3 (SCHEDULE)`: Task bound to machine and container starts $\rightarrow$ **Schedule Time ($t_{\text{schedule}}$)**.
- `6 (FINISH)`: Task finished successfully $\rightarrow$ **Finish Time ($t_{\text{finish}}$)**.
- `4 (EVICT)`, `5 (FAIL)`, `7 (KILL)`: Descheduled due to preemption, crash, or cancellation.

---

# Chapter 3: Formal Workload Translation & Modeling Methodology

## 3.1 Workload Unit Definition & Multi-Attempt Identity

A **workload unit** in PureEdgeSim corresponds to **a single execution attempt of a task instance** (`collection_type = 0`).
If a Borg task with `(collection_id, instance_index)` is evicted or fails and is subsequently resubmitted, each execution attempt is instantiated as a distinct PureEdgeSim task with a unique 1-based integer `TaskID`. In RL offloading experiments, every execution attempt represents an independent scheduling decision point.

---

## 3.2 Mathematical Translation Rules

### 1. Relative Arrival Time ($t_{\text{arrival}}$)

$$t_{\text{arrival}} = \frac{t_{\text{SUBMIT}} - t_{\text{window\_start}}}{1,000,000} \quad (\text{seconds})$$

*Justification*: Timestamps in Borg traces are 64-bit integers in microseconds ($\mu s$). Subtracting the window start timestamp $t_{\text{window\_start}}$ and dividing by $1,000,000$ yields relative simulation seconds ($s$), preserving exact inter-arrival timing ($\Delta t$), arrival rate variations $\lambda(t)$, and temporal burstiness.

### 2. Task Ground-Truth Execution Duration ($\Delta T_{\text{exec}}$)

$$\Delta T_{\text{exec}} = \frac{t_{\text{FINISH}} - t_{\text{SCHEDULE}}}{1,000,000} \quad (\text{seconds})$$

### 3. Computational Workload Length ($\text{Length}_{\text{MI}}$)

$$\text{Length}_{\text{MI}} = \text{round}\left( \text{req\_cpus} \times \text{MIPS}_{\text{base\_core}} \times \Delta T_{\text{exec}} \right)$$

- $\text{req\_cpus}$: Requested CPU rate in NCUs ($[0.0, 1.0]$).
- $\text{MIPS}_{\text{base\_core}}$: Processing speed of 1 baseline nominal core in PureEdgeSim (e.g. $2000 \text{ MIPS}$).

*Justification*: PureEdgeSim models processing duration on a node as $T = \frac{\text{Length}_{\text{MI}}}{\text{Host MIPS}}$. Setting $\text{Length}_{\text{MI}}$ proportional to $\text{req\_cpus} \times \text{MIPS}_{\text{base\_core}} \times \Delta T_{\text{exec}}$ ensures that execution on an un-contended baseline node in PureEdgeSim takes exactly $\Delta T_{\text{exec}}$ seconds.

### 4. Container Image / Memory Footprint (`containerSizeInBits`)

$$\text{containerSizeInBits} = \text{round}\left( \text{req\_memory} \times \text{MaxCellRAM}_{\text{Bytes}} \times 8 \right)$$

*Justification*: Unscales normalized RAM $[0.0, 1.0]$ back to physical bits, matching PureEdgeSim container size representation for RAM allocation and cold-start image transfers.

---

## 3.3 Synthetic Modeling for Missing Trace Fields

Borg cluster traces omit network payload sizes, explicit latency deadlines, and client edge device locations. The following domain-established models synthesize these parameters:

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

1. **Network Input Payload Size (`fileSizeInBits`)**:
   $$\text{fileSizeInBits} = \text{round}\left( \text{containerSizeInBits} \times 0.25 \times X \right), \quad X \sim \text{LogNormal}(\mu = 0, \sigma = 0.5)$$
   *Justification*: Input data payload correlates with container memory footprint. A Log-Normal distribution models heavy-tailed network traffic in cloud data processing jobs.

2. **Network Output Result Payload Size (`outputSizeInBits`)**:
   $$\text{outputSizeInBits} = \text{round}\left( \text{fileSizeInBits} \times Y \right), \quad Y \sim \text{Uniform}(0.05, 0.20)$$
   *Justification*: Offloading execution results (e.g. classification labels, summaries) returned to edge devices are significantly smaller than input code/dataset payloads.

3. **Latency Deadline (`maxLatency`)**:
   $$\text{maxLatency} = \Delta T_{\text{exec}} \times \left(1.0 + \text{SlackFactor}(S_c, P)\right)$$
   $$\text{SlackFactor}(S_c, P) = 0.5 \cdot (3 - S_c) + 0.5 \cdot \left(1.0 - \frac{P}{450}\right)$$
   - Latency-sensitive production ($S_c = 3, P = 450$): $\text{SlackFactor} = 0.0 \implies \text{maxLatency} = 1.0 \times \Delta T_{\text{exec}}$ (Strict Deadline).
   - Non-production batch ($S_c = 0, P = 0$): $\text{SlackFactor} = 2.0 \implies \text{maxLatency} = 3.0 \times \Delta T_{\text{exec}}$ (Relaxed Deadline).
   *Justification*: Borg scheduling classes ($0 \dots 3$) and priorities ($0 \dots 450$) reflect latency sensitivity and service level objectives (SLOs).

4. **Origin Edge Device Assignment (`edgeDevice`)**:
   $$\text{DeviceIndex} = \left| \text{hashCode}(\text{user}) \right| \pmod{N_{\text{edge\_devices}}}$$
   *Justification*: Hashing `user` deterministically to edge devices ensures all tasks generated by the same engineer or service originate from the same physical edge node, preserving spatial locality.

---

## 3.4 Reinforcement Learning Feature Preservation

To empower RL scheduling algorithms in `PythonOrchestrator` to learn optimal policies, trace metadata is embedded in `Task.metadata`:
- `priority` ($0 \dots 450+$): Scales step penalties for task drops (e.g. failing $P \ge 120$ incurs a $10\times$ higher penalty than $P \le 99$).
- `scheduling_class` ($0 \dots 3$): Enables action masking for edge vs cloud placement.
- `collection_logical_name`: Categorical feature encoding job family templates (e.g. MapReduce workers vs web servers).
- `average_usage.cpu` & `average_usage.memory`: Provides actual-to-requested resource ratios ($\frac{\text{usage}}{\text{request}}$) for overcommit awareness.
- `CPI` & `MAI`: Hardware counters distinguishing memory-bound from compute-bound workloads.

---

# Chapter 4: Ultra-Optimized BigQuery Extraction & Stream Replay Architecture

## 4.1 Single-Pass BigQuery SQL Lifecycle Aggregation Query

To minimize GCP BigQuery scan billing and eliminate redundant raw row exports, lifecycle events are aggregated into **a single row per task instance** directly in SQL:

```sql
-- Production Single-Pass BigQuery Lifecycle Aggregation Query
-- Target: google.com:google-cluster-data.clusterdata_2019_a
WITH task_events AS (
  SELECT
    collection_id,
    instance_index,
    alloc_collection_id,
    alloc_instance_index,
    priority,
    scheduling_class,
    resource_request.cpus AS req_cpus,
    resource_request.memory AS req_memory,
    type,
    time,
    machine_id
  FROM
    `google.com:google-cluster-data.clusterdata_2019_a.instance_events`
  WHERE
    collection_type = 0
    AND time >= @start_time_us
    AND time < @end_time_us
),
aggregated_tasks AS (
  SELECT
    collection_id,
    instance_index,
    MIN(CASE WHEN type = 0 THEN time END) AS submit_time_us,
    MIN(CASE WHEN type = 3 THEN time END) AS schedule_time_us,
    MIN(CASE WHEN type IN (4, 5, 6, 7, 8) THEN time END) AS finish_time_us,
    MAX(CASE WHEN type IN (4, 5, 6, 7, 8) THEN type END) AS final_event_type,
    MAX(CASE WHEN type = 3 THEN machine_id END) AS scheduled_machine_id,
    MAX(req_cpus) AS req_cpus,
    MAX(req_memory) AS req_memory,
    MAX(priority) AS priority,
    MAX(scheduling_class) AS scheduling_class,
    MAX(alloc_collection_id) AS alloc_collection_id,
    MAX(alloc_instance_index) AS alloc_instance_index
  FROM
    task_events
  GROUP BY
    collection_id,
    instance_index
),
collection_meta AS (
  SELECT
    collection_id,
    user,
    collection_logical_name
  FROM
    `google.com:google-cluster-data.clusterdata_2019_a.collection_events`
  WHERE
    collection_type = 0
    AND type = 0
)
SELECT
  t.collection_id,
  t.instance_index,
  t.submit_time_us,
  t.schedule_time_us,
  t.finish_time_us,
  (t.schedule_time_us - t.submit_time_us) AS queue_time_us,
  (t.finish_time_us - t.schedule_time_us) AS exec_time_us,
  (t.finish_time_us - t.submit_time_us) AS total_residence_time_us,
  t.final_event_type,
  CASE
    WHEN t.final_event_type = 6 THEN 'FINISH'
    WHEN t.final_event_type = 5 THEN 'FAIL'
    WHEN t.final_event_type = 7 THEN 'KILL'
    WHEN t.final_event_type = 4 THEN 'EVICT'
    ELSE 'UNKNOWN'
  END AS finish_status,
  t.req_cpus,
  t.req_memory,
  t.priority,
  t.scheduling_class,
  t.scheduled_machine_id,
  t.alloc_collection_id,
  m.user,
  m.collection_logical_name
FROM
  aggregated_tasks AS t
LEFT JOIN
  collection_meta AS m
ON
  t.collection_id = m.collection_id
WHERE
  t.submit_time_us IS NOT NULL;
```

---

## 4.2 Streamed Sliding-Window Task Generation Architecture

Standard PureEdgeSim instantiates all tasks upfront into `FutureQueue<Task> taskList`, causing JVM heap memory explosion on 24-hour trace replays ($200,000+$ tasks). To prevent memory exhaustion, `StreamedTraceTaskGenerator` implements on-demand stream ingestion:

```
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                           PureEdgeSim Stream Replay Mechanism                               │
│                                                                                             │
│  data/processed/pureedgesim_tasks_24h.json (Disk)                                           │
│                         │                                                                   │
│                         ▼ BufferedReader / JsonReader Stream                                │
│       ┌───────────────────────────────────┐                                                 │
│       │    StreamedTraceTaskGenerator     │                                                 │
│       └─────────────────┬─────────────────┘                                                 │
│                         │ refills buffer when taskList.size() < lowWatermark (200)          │
│                         v                                                                   │
│       ┌───────────────────────────────────┐                                                 │
│       │      FutureQueue<Task> (Buffer)   │  <-- Bounded Memory Footprint O(N_buffer)        │
│       └─────────────────┬─────────────────┘                                                 │
└─────────────────────────┼───────────────────────────────────────────────────────────────────┘
                          │ NEXT_BATCH trigger
                          v
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│  DefaultSimulationManager -> PythonOrchestrator -> Unix Domain Socket (DECISION_REQUEST)    │
│  (PyTorch RL Agent receives standard Task feature vector -> returns node_index)            │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
```

1. **Buffered Prefetch**: On startup `generate()`, `StreamedTraceTaskGenerator` reads only the first $N_{\text{buffer}} = 1000$ task JSON objects from disk into `taskList`.
2. **On-Demand Replenishment**: As `DefaultSimulationManager` dequeues tasks via `NEXT_BATCH` event triggers, when `taskList.size()` drops below low-watermark ($N_{\text{low}} = 200$), the generator streams the next block from disk until `taskList.size() == N_{\text{buffer}}`.
3. **Memory Footprint Bound**:
   $$\text{JVM Memory Footprint} = \mathcal{O}(N_{\text{buffer}} + N_{\text{active\_in\_flight}})$$
   Heap memory utilization remains constant regardless of trace duration.

---

# Chapter 5: Statistical Validation Suite & Academic Defense

To prove workload replay fidelity, six formal validation protocols must be satisfied:

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

| Validation Test | Primary Metric / Test | Target Threshold | Academic Defense Rationale |
| :--- | :--- | :--- | :--- |
| **1. Arrival Process** | Two-Sample K-S Test | $D_{\text{KS}} < 0.01, p > 0.05$ | Task arrival timing dictates queue backlogs and network congestion. Non-parametric K-S testing evaluates inter-arrival CDFs, proving temporal burstiness (e.g. MapReduce spikes) is preserved without distortion. |
| **2. Resource Conservation**| Relative Sum Error | $\epsilon_{\text{cpu}} < 1.0\%, \epsilon_{\text{ram}} < 1.0\%$ | Follows conservation principles applied to workload evaluation. Guarantees total compute work (core-seconds) and RAM footprint (GB-seconds) submitted to PureEdgeSim match the physical datacenter cell. |
| **3. Duration Calibration** | Baseline Host MAPE | $\text{MAPE} < 0.1\%$ | Validates our MI length formula ($\text{Length}_{\text{MI}} = \text{NCU} \times \text{MIPS}_{\text{base}} \times \Delta T_{\text{exec}}$). Proves execution duration match is exact under un-contended baseline conditions; any variation in full simulation is strictly due to network/contention. |
| **4. Priority Preservation**| Chi-Square Test ($\chi^2$) | $p > 0.05$ | RL schedulers rely on priority tiers ($P$) and latency classes ($S_c$) to calculate drop penalties. Preserving categorical distributions guarantees the RL agent faces identical trade-off profiles as Borg. |
| **5. Synthetic Plausibility**| Deadline & Payload Audits| $0\%$ impossible deadlines, monotonic slack | Defends missing Borg trace attributes (network sizes, deadlines, edge devices) against thesis reviewer critique by proving domain bounds are strictly satisfied. |
| **6. Pipeline Losslessness** | Record Count Invariant | $N_{\text{BigQuery}} - N_{\text{Simulated}} = 0$ | Ensures 100% data integrity without silent record drops, buffer overflow truncation, or parser corruption. |

---

# Chapter 6: Progressive Implementation Roadmap

The implementation is structured into 10 progressive milestones:

```
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                           10-Milestone Progressive Roadmap                                  │
├───────────────────────────┬───────────────────────────┬─────────────────────────────────────┤
│ M1: BigQuery Dev Subset   │ M2: Raw Record Audit      │ M3: Intermediate Preprocessing      │
│ (Reading Real Data)       │ (Reading Real Data)       │ (Transforming & Deriving Data)      │
├───────────────────────────┼───────────────────────────┼─────────────────────────────────────┤
│ M4: PureEdgeSim Task Spec │ M5: Java Stream Replay    │ M6: End-to-End Python Bridge Run    │
│ (Deriving & Assumptions)  │ (Replaying Trace - Java)  │ (Replaying Trace - Python Bridge)   │
├───────────────────────────┼───────────────────────────┼─────────────────────────────────────┤
│ M7: Validation Audit      │ M8: Reproducibility CLI   │ M9: Progressive Scale (1h & 12h)    │
│ (Statistical K-S & Integrals)| (Automated Pipeline CLI) │ (Scale & Buffer Tuning)             │
├───────────────────────────┴───────────────────────────┴─────────────────────────────────────┤
│ M10: Full 24-Hour Production Workload Replay Target (Full RL Orchestrator Benchmarking)     │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
```

1. **M1: BigQuery Dev Subset Extraction**: Extract 15-min window ($\sim 500$ tasks, $<100$ MB scan) from `google.com:google-cluster-data.clusterdata_2019_a`. (*Reading Real Data*)
2. **M2: Raw Record Audit**: Audit extracted Parquet records for timestamp non-nullness and positive durations using `inspect_raw_trace.py`. (*Reading Real Data*)
3. **M3: Intermediate Representation Preprocessing**: Compute relative arrival time $t_{\text{arrival}}$, ground-truth duration $\Delta T_{\text{exec}}$, and queue wait delay $\Delta T_{\text{queue}}$ using `preprocess_google_trace.py`. (*Transforming & Deriving Data*)
4. **M4: PureEdgeSim Task Spec Translation**: Translate intermediate records into native `Task` specifications (`length` in MI, `containerSizeInBits`), applying synthetic models for missing fields. (*Deriving & Assumptions*)
5. **M5: Core Java Engine Stream Replay**: Implement `StreamedTraceTaskGenerator.java` ($N_{\text{buffer}} = 1000$) and replay 15-minute workload in PureEdgeSim using default orchestrator. (*Replaying Trace - Java*)
6. **M6: End-to-End Simulation Run with Python RL Orchestrator**: Connect `StreamedTraceTaskGenerator` to `PythonOrchestrator` via Unix Domain Sockets to test IPC socket stability. (*Replaying Trace - Python Bridge*)
7. **M7: Statistical Fidelity & Validation Audit**: Execute the 6-stage statistical validation suite using `validate_workload_replay.py`. (*Validation & Verification*)
8. **M8: Automated Pipeline CLI & Reproducibility Packaging**: Package the entire workflow into a single CLI runner (`./scripts/run_trace_pipeline.sh`) producing SHA-256 manifest logs. (*Pipeline Automation*)
9. **M9: Progressive Scaling (1-Hour & 12-Hour Windows)**: Scale extraction and stream replay to 1-hour ($\sim 10,000$ tasks) and 12-hour ($\sim 100,000$ tasks) workload windows, verifying constant JVM heap utilization. (*Scale & Buffer Tuning*)
10. **M10: Full 24-Hour Production Workload Replay Target**: Execute full 24-hour trace replay ($\sim 200,000+$ tasks) in PureEdgeSim, generating publication-ready RL benchmark evaluations (Round-Robin vs Nearest-Node vs PyTorch RL Agent). (*Full Workload Experimentation*)
