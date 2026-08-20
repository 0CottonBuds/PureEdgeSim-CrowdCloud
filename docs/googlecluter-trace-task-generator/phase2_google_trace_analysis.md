# Phase 2 — Comprehensive Dataset Analysis: Google Cluster Trace v3 (Borg 2019)

**Document Version:** 2.0 (Deep Analysis)  
**Date:** 2026-08-18  
**Scope:** In-Depth Schema Deconstruction, State Machine Semantics, Resource Normalization, Workload Unit Definition, Information Gap Analysis, and 4-Part Data Classification.

---

## 1. Google Cluster Trace v3 Architecture & Schema Deconstruction

The Google Cluster Trace v3 dataset (2019 release) documents compute cell workloads managed by Google's Borg cluster manager. The trace is distributed across 8 cell datasets (`2019-05-a` through `2019-05-h`), each describing a specific Borg cell over approximately 1 month.

### 1.1 Trace Obfuscation Techniques
To preserve confidentiality while enabling research, Google applied five consistent obfuscation transformations:
1. `unchanged`: Raw integer or string values.
2. `hashed`: Keyed cryptographic hashes (opaque base64-encoded strings).
3. `ordered`: Categorical items sorted and mapped to 0-indexed sequential integers.
4. `rescaled`: Floating-point resource values linearly transformed into $[0.0, 1.0]$ by dividing by dataset-wide maximum constants:
   $$\text{Granularity} = \max\left(\frac{1.0}{2^{10}}, \frac{\text{max\_value}}{2^{20}}\right) \approx 10\text{ bits binary precision}$$
5. `special`: Reserved values representing special system states (e.g., `machine_id = -1` for dedicated machines, `time = 0` for pre-existing events, `time = 2^63 - 1` for post-trace events).

---

### 1.2 Comprehensive Table Schema & Field Breakdown

#### Table 1: `InstanceEvents` (Primary Workload Table)
Contains life cycle state transitions and resource specifications for individual instances (tasks and alloc instances).

| Field Name | Data Type | Obfuscation | Description & Replay Significance |
| :--- | :--- | :--- | :--- |
| `time` | `int64` | `rescaled` offset | Event timestamp in **microseconds ($\mu s$)** since 600s prior to trace start. |
| `type` | `int32` | `ordered` | Event transition type (SUBMIT, SCHEDULE, FINISH, FAIL, KILL, etc.). |
| `collection_id` | `int64` | `hashed` ID | 64-bit ID of parent collection (job or alloc set). |
| `instance_index` | `int32` | `unchanged` | 0-based task index within the collection. |
| `collection_type` | `int32` | `unchanged` | **`0 = Job Task`** (app workload), **`1 = Alloc Instance`** (infrastructure). |
| `machine_id` | `int64` | `hashed` ID | Host machine ID (`0` if unscheduled/pending, `-1` if dedicated host). |
| `alloc_collection_id`| `int64` | `hashed` ID | ID of alloc set hosting this task (`0` if running directly on machine). |
| `alloc_instance_index`| `int32` | `unchanged` | Index of alloc instance hosting this task (`-1` if none). |
| `scheduling_class` | `int32` | `unchanged` | Latency sensitivity tier ($0 = \text{non-prod/batch}, 3 = \text{latency-sensitive/user}$). |
| `priority` | `int32` | `unchanged` | Priority tier ($0 \dots 450+$). Determines preemption and eviction precedence. |
| `resource_request` | `Struct` | `rescaled` | Requested CPU (`cpus` in NCUs) and RAM (`memory` in rescaled bytes). |
| `constraint` | `Array` | `hashed` | Hard placement constraints against machine attributes. |
| `missing_type` | `int32` | `ordered` | Data synthesis reason (`0` = clean trace event, `>0` = synthesized record). |

#### Table 2: `CollectionEvents` (Job Metadata Table)
Describes top-level job and alloc set properties.

| Field Name | Data Type | Obfuscation | Description & Replay Significance |
| :--- | :--- | :--- | :--- |
| `collection_id` | `int64` | `hashed` ID | Unique 64-bit collection identifier. |
| `user` | `string` | `hashed` | Base64 hashed username/service owner. Used for user-to-device mapping. |
| `collection_name` | `string` | `hashed` | Hash of original full job name. |
| `collection_logical_name`| `string` | `hashed` | Normalized job template name (groups recurring MapReduce/service jobs). |
| `parent_collection_id` | `int64` | `hashed` ID | Parent job ID (`0` if none). Child jobs are killed when parent exits. |
| `start_after_collection_ids`| `Array` | `hashed` IDs | Pipeline execution dependencies (job must wait for parent completion). |
| `vertical_scaling` | `int32` | `ordered` | Autoscaling policy (OFF, CONSTRAINED, FULLY_AUTOMATED). |
| `scheduler` | `int32` | `ordered` | Scheduler engine (`DEFAULT` vs `BATCH`). |

#### Table 3: `InstanceUsage` (Resource Consumption Table)
Contains periodic (typically 5-minute / 300s window) telemetry sampled at ~1 Hz.

| Field Name | Data Type | Obfuscation | Description & Replay Significance |
| :--- | :--- | :--- | :--- |
| `start_time` / `end_time`| `int64` | `rescaled` offset | Measurement window bounds in microseconds. |
| `average_usage.cpu` | `float` | `rescaled` | Average CPU consumption rate over window in NCUs ($\sum U_{\text{cpu}} / T_{\text{window}}$). |
| `average_usage.memory`| `float` | `rescaled` | Average memory consumption (RAM area under curve / window duration). |
| `maximum_usage.cpu` | `float` | `rescaled` | Peak CPU usage sample rate ($\max(U_{\text{cpu}} / T_{\text{sample}})$). |
| `maximum_usage.memory`| `float` | `rescaled` | Peak memory usage observed in window. |
| `assigned_memory` | `float` | `rescaled` | Memory upper limit assigned by Borglet agent to Linux cgroup container. |
| `cycles_per_instruction` (CPI)| `float`| `unchanged` | Hardware counter mean CPI (CPU cycles / instructions executed). |
| `memory_accesses_per_instruction` (MAI)| `float`| `unchanged`| Hardware counter mean MAI (Last-Level Cache misses / instructions). |

#### Table 4: `MachineEvents` & `MachineAttributes` (Infrastructure Context)
- `MachineEvents`: Tracks host additions (`ADD`), removals (`REMOVE`), resource capacity updates (`UPDATE`), rack switch IDs (`switch_id`), and platform hardware microarchitectures (`platform_id`).
- `MachineAttributes`: Key-value pairs representing kernel versions, clock rates, or specialized GPU/accelerator hardware presence.

---

## 2. Borg Entity Hierarchy & Life Cycle State Machine

### 2.1 Entity Hierarchy & Identifiers
- **Collection (Job)**: A collection of tasks representing a distributed program or service. Identified by 64-bit `collection_id`.
- **Instance (Task)**: A single Linux process container. Identified by the compound key `(collection_id, instance_index)`.
- **Task Identity Nuance**: Tasks with the exact same `(collection_id, instance_index)` can be evicted, failed, or killed and subsequently **restarted multiple times** without generating a new `collection_id` or `instance_index`.

### 2.2 Event Transition State Machine

```
                   ┌────────────────┐
                   │     SUBMIT     │ (Submitted to Borg)
                   └───────┬────────┘
                           │
                           v
                   ┌────────────────┐
                   │    SCHEDULE    │ (Placed & Execution Begins)
                   └───────┬────────┘
                           │
      ┌────────────────────┼────────────────────┐
      │                    │                    │
      v                    v                    v
┌───────────┐        ┌───────────┐        ┌───────────┐
│  FINISH   │        │   FAIL    │        │   KILL    │ / EVICT
│ (Success) │        │  (Error)  │        │(Cancelled)│
└───────────┘        └───────────┘        └───────────┘
```

1. **`SUBMIT`**: Task submitted to cluster manager queue.
2. **`QUEUE` / `ENABLE`**: Task queued and marked eligible for scheduling.
3. **`SCHEDULE`**: Task bound to a physical machine and container execution starts.
4. **`FINISH`**: Task completes execution successfully.
5. **`FAIL`**: Task descheduled due to application crash, segfault, or exceeding memory limit.
6. **`EVICT`**: Task descheduled due to higher priority task preemption or machine maintenance.
7. **`KILL`**: Task cancelled by user, driver program, or parent job termination.

---

## 3. Defining a Meaningful Unit of Workload for PureEdgeSim

### What Represents a PureEdgeSim Task?
A meaningful unit of workload in PureEdgeSim is an **Instance Execution Lifecycle Instance**:
- Represented by a task instance (`collection_type = 0`).
- Uniquely bound to a specific execution run of a compound key `(collection_id, instance_index, run_sequence)`.
- Characterized by:
  1. **Submission Arrival Time ($t_{\text{arrival}}$)**: Timestamp of the `SUBMIT` event.
  2. **Scheduling Delay ($\Delta T_{\text{wait}}$)**: Duration spent in queue before placement ($t_{\text{SCHEDULE}} - t_{\text{SUBMIT}}$).
  3. **Execution Duration ($\Delta T_{\text{exec}}$)**: Active compute duration on host ($t_{\text{FINISH}} - t_{\text{SCHEDULE}}$).
  4. **Requested Compute Capacity ($C_{\text{NCU}}$)**: Requested normalized CPU rate.
  5. **Requested RAM Footprint ($M_{\text{RAM}}$)**: Requested container memory size.

---

## 4. Comprehensive Information Gap Analysis

PureEdgeSim's discrete-event simulation model requires precise computational, network, and spatial attributes. The table below details what Google Cluster Trace v3 directly provides versus what is missing:

| PureEdgeSim Task Parameter | Google Trace v3 Equivalent | Direct Availability | Gap Description & Resolution Strategy |
| :--- | :--- | :--- | :--- |
| **`id`** | `(collection_id, instance_index)` | **Partial** | Trace uses compound 64-bit keys; requires mapping to 1-based sequential integer `id`. |
| **`time`** | `time` (SUBMIT event) | **Direct** | Provided in $\mu s$; requires conversion to relative simulation seconds ($s$). |
| **`length` (MI)** | *None* (Only CPU NCUs & Duration) | **MISSING** | Google records normalized CPU rates, not instructions. Must be computed via MIPS scaling: $\text{Length}_{\text{MI}} = \text{NCU} \times \text{CoreMIPS}_{\text{base}} \times \Delta T_{\text{exec}}$. |
| **`fileSizeInBits`** | *None* | **MISSING** | Network input size is omitted in Borg traces. Must be synthetically modeled or derived from container RAM footprint. |
| **`outputSizeInBits`** | *None* | **MISSING** | Network return payload size is omitted. Must be modeled via payload ratio distributions. |
| **`containerSizeInBits`**| `resource_request.memory` | **Partial** | Provided as normalized fraction $[0, 1]$; requires unscaling by max cluster RAM capacity ($M_{\text{bits}} = \text{NormRAM} \times \text{MaxRAM}_{\text{bits}}$). |
| **`maxLatency`** | `priority` & `scheduling_class` | **MISSING** | Explicit latency deadlines are omitted. Must be inferred from priority SLO tiers ($0 \dots 450$) and scheduling class ($0 \dots 3$). |
| **`edgeDevice`** | `user` hash / `collection_id` | **MISSING** | Borg is an internal cloud datacenter trace without client edge positions. Users/collections must be mapped to simulated edge/mist nodes. |
| **`registry`** | *None* | **MISSING** | Image registry location omitted; defaults to Cloud Registry node. |

---

## 5. Strict 4-Part Data Classification

To ensure research rigor, all data fields are explicitly divided into four distinct categories:

```
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                                 Data Classification Pipeline                                │
├───────────────────────────┬───────────────────────────┬─────────────────────────────────────┤
│ 1. Directly Observed Data │ 2. Derived Data           │ 3. Data Requiring Transformation    │
│    (Raw Trace Fields)     │    (Computed Metrics)     │    (Unit Conversions / Unscaling)   │
├───────────────────────────┴───────────────────────────┴─────────────────────────────────────┤
│ 4. Data Requiring Modeling Assumptions                                                       │
│    (Missing Parameters: MI Length, Network File Sizes, Deadlines, Edge Device Mapping)       │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
```

### Category 1: Directly Observed Data
Raw, unaltered fields extracted directly from trace records:
- `collection_id` (64-bit int) & `instance_index` (int).
- `collection_type` (`0 = Job Task`, `1 = Alloc Instance`).
- Event `time` for `SUBMIT`, `QUEUE`, `ENABLE`, `SCHEDULE`, `FINISH`, `FAIL`, `KILL`, `EVICT`.
- `resource_request.cpus` (NCUs in $[0.0, 1.0]$) & `resource_request.memory` (Normalized RAM in $[0.0, 1.0]$).
- `priority` (Integer tier $0 \dots 450+$).
- `scheduling_class` (Integer $0, 1, 2, 3$).
- `user` (Hashed base64 string) & `collection_logical_name` (Hashed base64 string).
- Telemetry: `average_usage.cpu`, `average_usage.memory`, `maximum_usage.cpu`, `maximum_usage.memory`, `assigned_memory`, `cycles_per_instruction` (CPI), `memory_accesses_per_instruction` (MAI).

### Category 2: Derived Data
Exact mathematical properties calculated from combinations of observed raw fields:
- **Relative Task Arrival Time ($t_{\text{arrival}}$)**:
  $$t_{\text{arrival}} = \frac{t_{\text{SUBMIT}} - t_{\text{trace\_start}}}{1,000,000} \quad (\text{seconds})$$
- **Task Execution Duration ($\Delta T_{\text{exec}}$)**:
  $$\Delta T_{\text{exec}} = \frac{t_{\text{FINISH}} - t_{\text{SCHEDULE}}}{1,000,000} \quad (\text{seconds})$$
- **Queue Wait Latency ($\Delta T_{\text{wait}}$)**:
  $$\Delta T_{\text{wait}} = \frac{t_{\text{SCHEDULE}} - t_{\text{SUBMIT}}}{1,000,000} \quad (\text{seconds})$$
- **Task Execution Outcome Status**:
  $$\text{Status} = \begin{cases} \text{SUCCESS} & \text{if final event is } \text{FINISH} \\ \text{FAILED} & \text{if final event is } \text{FAIL}, \text{KILL}, \text{ or } \text{EVICT} \end{cases}$$
- **Resource Utilization Efficiency Factor ($\eta_{\text{cpu}}$)**:
  $$\eta_{\text{cpu}} = \frac{\text{average\_usage.cpu}}{\text{resource\_request.cpus}}$$

### Category 3: Data Requiring Transformation
Fields requiring deterministic unit conversions, scaling, or schema mapping:
- **Timestamp Unit Conversion**: Microseconds ($\mu s$) $\rightarrow$ Simulation Seconds ($s$).
- **Requested CPU Capacity Unscaling**:
  $$N_{\text{cores}} = \text{resource\_request.cpus} \times \text{MaxCellGCU}$$
- **Requested Container Memory Unscaling**:
  $$M_{\text{MB}} = \text{resource\_request.memory} \times \text{MaxCellRAM}_{\text{MB}}$$
- **Primary Key Flattening**:
  $$\text{TaskID} = f_{\text{hash}}(collection\_id, instance\_index, run\_seq) \rightarrow \text{unique } \texttt{int32}$$

### Category 4: Data Requiring Modeling Assumptions
Parameters not present in the Google Trace dataset that must be synthesized using defensible domain models:

1. **Workload Computational Length ($\text{Length}_{\text{MI}}$ in Million Instructions)**:
   - *Assumption*: Model task compute demand as a function of allocated CPU capacity, baseline machine instruction execution rate ($\text{MIPS}_{\text{nominal}}$), and trace execution duration:
     $$\text{Length}_{\text{MI}} = \text{resource\_request.cpus} \times \text{MIPS}_{\text{nominal}} \times \Delta T_{\text{exec}}$$
   - *Alternative (Refined)*: Use actual CPU usage rate and CPI hardware counters where `InstanceUsage` telemetry is available:
     $$\text{Length}_{\text{MI}} = \frac{\text{average\_usage.cpu} \times f_{\text{cpu\_clock}} \times \Delta T_{\text{exec}}}{\text{CPI} \times 10^6}$$

2. **Input File Size (`fileSizeInBits`)**:
   - *Assumption*: Model input payload size as a function of task container memory requirement combined with a domain sampling distribution (e.g. log-normal data processing distribution):
     $$\text{fileSizeInBits} = M_{\text{bits}} \times \alpha_{\text{input\_ratio}} \times X_{\text{sample}}$$

3. **Output File Size (`outputSizeInBits`)**:
   - *Assumption*: Model return result payload size as a ratio of input payload size:
     $$\text{outputSizeInBits} = \text{fileSizeInBits} \times \beta_{\text{output\_ratio}}$$

4. **Task Max Latency / Deadline (`maxLatency`)**:
   - *Assumption*: Infer strictness of deadline from Borg `scheduling_class` ($S_c \in \{0, 1, 2, 3\}$) and `priority` tier ($P$):
     $$\text{maxLatency} = \Delta T_{\text{exec}} \times \left(1.0 + \gamma \cdot (3 - S_c)\right)$$
     - Latency-sensitive tasks ($S_c = 3$) receive tight deadlines ($\text{maxLatency} \approx 1.1 \times \Delta T_{\text{exec}}$).
     - Non-production batch tasks ($S_c = 0$) receive loose deadlines ($\text{maxLatency} \ge 3.0 \times \Delta T_{\text{exec}}$).

5. **Origin Edge Device Assignment (`edgeDevice`)**:
   - *Assumption*: Borg trace events originate from user service calls. Map unique `user` hashes or `collection_id` hashes modulo the count of available simulated Edge/Mist devices ($N_{\text{edge}}$):
     $$\text{DeviceIndex} = \left| \text{hashCode}(user) \right| \pmod{N_{\text{edge}}}$$
