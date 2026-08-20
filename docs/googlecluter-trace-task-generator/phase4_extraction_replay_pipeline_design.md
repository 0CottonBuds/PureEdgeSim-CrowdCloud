# Phase 4 — Pipeline Architecture Design: Ultra-Optimized BigQuery Extraction, Preprocessing & Stream Replay

**Document Version:** 2.0 (Budget-Optimized & Analytics-Complete)  
**Date:** 2026-08-18  
**Scope:** Cost-Optimized BigQuery SQL Extraction, Task Lifecycle Aggregation, Complete Telemetry Storage (Arrival, Queue, Execution Times), Local Parquet Schema, and PureEdgeSim Stream Replay Architecture.

---

## 1. End-to-End Pipeline Overview

This document specifies the end-to-end pipeline for extracting, preprocessing, translating, and replaying Google Cluster Trace v3 workloads inside PureEdgeSim. The pipeline is designed for **extreme BigQuery cost efficiency**, **complete analytical telemetry**, and **bounded memory streaming**.

```
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                                 End-to-End Pipeline Flow                                    │
│                                                                                             │
│  1. Google Cluster BigQuery Dataset (google.com:google-cluster-data:clusterdata_2019_a)     │
│                         │                                                                   │
│                         ▼ Single-Pass Lifecycle Aggregation SQL Query (Minimal Scanned Bytes)│
│  2. Consolidated Local Dataset (Parquet / data/raw/google_v3_cell_a_24h.parquet)            │
│     - Contains: submit_time, schedule_time, finish_time, queue_time_us, exec_time_us,     │
│                 priority, scheduling_class, req_cpus, req_memory, finish_status, user       │
│                         │                                                                   │
│                         ▼ Python Preprocessing & Translation Script                         │
│  3. Preprocessed Workload (JSON / data/processed/pureedgesim_tasks_24h.json)                 │
│                         │                                                                   │
│                         ▼ Streamed Ingestion (StreamedTraceTaskGenerator)                   │
│  4. PureEdgeSim Discrete-Event Engine (FutureQueue Buffer: O(N_buffer) Constant Memory)     │
│                         │                                                                   │
│                         ▼ Unix Domain Socket IPC (DECISION_REQUEST / TASK_RESULT)           │
│  5. Python Orchestrator / PyTorch RL Scheduler                                              │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Budget-Optimized BigQuery Extraction Strategy

### 2.1 The BigQuery Cost Problem & Solution
- **Problem**: Querying raw `instance_events` generates multiple event records per task (SUBMIT, QUEUE, ENABLE, SCHEDULE, UPDATE, FINISH). Exporting un-aggregated rows wastes BigQuery scan quota, produces massive raw datasets, and requires complex local join passes.
- **Solution: Single-Pass SQL Lifecycle Aggregation**:
  We aggregate all lifecycle events for each task instance directly inside BigQuery using `GROUP BY collection_id, instance_index`.
  - Reduces extracted row count by **$3\times \sim 5\times$** (1 consolidated row per task instance execution).
  - Pre-computes **Arrival Time**, **Queue Wait Time**, **Execution Time**, and **Total Turnaround Time** inside BigQuery.
  - Reduces local storage overhead and minimizes GCP billing.

---

### 2.2 Complete Borg Event Type Mapping

Google Cluster Trace v3 uses standard integer codes for task event transitions:

| Event Code | Event Type | Description & Lifecycle Significance |
| :--- | :--- | :--- |
| `0` | `SUBMIT` | Task submitted to Borg queue $\rightarrow$ **Arrival Time ($t_{\text{submit}}$)** |
| `1` | `QUEUE` | Task placed in pending queue |
| `2` | `ENABLE` | Task marked eligible for scheduling |
| `3` | `SCHEDULE` | Task bound to machine & starts $\rightarrow$ **Schedule Time ($t_{\text{schedule}}$)** |
| `4` | `EVICT` | Descheduled due to preemption or host maintenance |
| `5` | `FAIL` | Task failed due to segfault / OOM error |
| `6` | `FINISH` | Task completed execution successfully $\rightarrow$ **Finish Time ($t_{\text{finish}}$)** |
| `7` | `KILL` | Task cancelled by user or parent job |
| `8` | `LOST` | Task state lost / machine failure |

---

### 2.3 Production BigQuery SQL Query (Single-Pass Lifecycle Aggregation)

```sql
-- Phase 4 Ultra-Optimized BigQuery Lifecycle Aggregation Query
-- Target: google.com:google-cluster-data.clusterdata_2019_a
-- Output: 1 consolidated lifecycle row per task instance

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
    collection_type = 0 -- Job tasks only (exclude alloc sets)
    AND time >= @start_time_us
    AND time < @end_time_us
),
aggregated_tasks AS (
  SELECT
    collection_id,
    instance_index,
    -- Extract first submission timestamp (Arrival Time)
    MIN(CASE WHEN type = 0 THEN time END) AS submit_time_us,
    -- Extract first schedule timestamp (Execution Start Time)
    MIN(CASE WHEN type = 3 THEN time END) AS schedule_time_us,
    -- Extract final completion/termination timestamp (End Time)
    MIN(CASE WHEN type IN (4, 5, 6, 7, 8) THEN time END) AS finish_time_us,
    -- Extract final termination status code
    MAX(CASE WHEN type IN (4, 5, 6, 7, 8) THEN type END) AS final_event_type,
    -- Extract machine ID where task was scheduled
    MAX(CASE WHEN type = 3 THEN machine_id END) AS scheduled_machine_id,
    -- Take maximum resource request observed for task instance
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
  -- Pre-computed Queue Wait Time in microseconds (schedule_time - submit_time)
  (t.schedule_time_us - t.submit_time_us) AS queue_time_us,
  -- Pre-computed Execution Duration in microseconds (finish_time - schedule_time)
  (t.finish_time_us - t.schedule_time_us) AS exec_time_us,
  -- Pre-computed Total Residence Time in microseconds (finish_time - submit_time)
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
  t.submit_time_us IS NOT NULL; -- Ensure task has valid submission arrival
```

---

## 3. Extracted Parquet Schema & Analytics Utility

The extracted local dataset (`data/raw/google_v3_cell_a_24h.parquet`) contains 18 comprehensive fields:

| Field Name | Type | Unit / Range | Analytical & Replay Purpose |
| :--- | :--- | :--- | :--- |
| `collection_id` | `int64` | 64-bit ID | Job identifier. Groups tasks belonging to same job. |
| `instance_index` | `int32` | 0, 1, 2... | Task index within job. Compound key with `collection_id`. |
| `submit_time_us` | `int64` | Microseconds ($\mu s$) | **Arrival Time ($t_{\text{arrival}}$)**. Base timeline for trace replay. |
| `schedule_time_us` | `int64` | Microseconds ($\mu s$) | **Placement Start Time**. Marks transition from Queue to Execution. |
| `finish_time_us` | `int64` | Microseconds ($\mu s$) | **Termination Time**. Marks task exit. |
| `queue_time_us` | `int64` | Microseconds ($\mu s$) | **Ground-Truth Queue Wait Delay**. Used for baseline scheduling latency comparison. |
| `exec_time_us` | `int64` | Microseconds ($\mu s$) | **Ground-Truth Execution Duration**. Baseline for MI length calculation. |
| `total_residence_time_us`| `int64` | Microseconds ($\mu s$) | **Total Turnaround Time** ($\Delta T_{\text{queue}} + \Delta T_{\text{exec}}$). |
| `final_event_type` | `int32` | 4, 5, 6, 7 | Numeric termination code (FINISH=6, FAIL=5, KILL=7, EVICT=4). |
| `finish_status` | `string` | FINISH, FAIL... | Human-readable execution outcome. |
| `req_cpus` | `float64` | $[0.0, 1.0]$ NCUs | Requested CPU capacity rate. Used for MI length scaling. |
| `req_memory` | `float64` | $[0.0, 1.0]$ NormRAM | Requested container RAM footprint. |
| `priority` | `int32` | $0 \dots 450+$ | Priority tier. Key feature for RL drop penalty calculation. |
| `scheduling_class` | `int32` | $0, 1, 2, 3$ | Latency sensitivity class ($3 = \text{latency-sensitive}, 0 = \text{batch}$). |
| `scheduled_machine_id` | `int64` | 64-bit ID | Ground-truth Borg host placement. |
| `alloc_collection_id` | `int64` | 64-bit ID | Alloc set container ID (`0` if none). |
| `user` | `string` | Base64 Hash | User/service owner. Deterministic mapping to Edge Device ID. |
| `collection_logical_name`| `string` | Base64 Hash | Workload template type for RL workload categorization. |

---

## 4. Cost Optimization & Safeguard Protocols

To prevent exceeding GCP BigQuery quota budgets:

1. **Dry-Run Pre-flight Command**:
   - Always run `bq query --dry_run` before submitting any extraction query.
   - Example shell command:
     ```bash
     bq query --use_legacy_sql=false --dry_run \
       --parameter=start_time_us:INT64:600000000 \
       --parameter=end_time_us:INT64:900000000 \
       "$(cat sql/extract_lifecycle.sql)"
     ```
2. **Partition & Time Bounds Enforcement**:
   - Restrict microsecond bounds to exact time windows (`time >= @start_time_us AND time < @end_time_us`).
3. **Column Projection Discipline**:
   - Never use `SELECT *`. Query only the 18 specific columns defined in Section 3.

---

## 5. Progressive Scaling Strategy

```
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                             Progressive Scaling Strategy                                    │
├─────────────────────────┬──────────────────────────┬──────────────────────┬─────────────────┤
│ Target Stage            │ Time Window              │ Est. Task Count      │ Est. Scan Size  │
├─────────────────────────┼──────────────────────────┼──────────────────────┼─────────────────┤
│ Stage 1: Dev Subset     │ 15 Minutes (900s)        │ ~500 - 1,000 tasks   │ ~50 - 100 MB    │
│ Stage 2: Micro Milestone│ 1 Hour (3,600s)          │ ~5,000 - 15,000 tasks│ ~500 MB         │
│ Stage 3: Half-Day Replay│ 12 Hours (43,200s)       │ ~100,000 tasks       │ ~5 - 10 GB      │
│ Stage 4: Full Day Target│ 24 Hours (86,400s)       │ ~200,000+ tasks      │ ~15 - 25 GB     │
└─────────────────────────┴──────────────────────────┴──────────────────────┴─────────────────┘
```

1. **Stage 1 (Development Subset — 15 Minutes)**:
   - Window: $t \in [600 \times 10^6, 1500 \times 10^6] \mu s$.
   - Output: ~500 tasks. Zero-cost local testing, pipeline validation, and rapid iteration.
2. **Stage 2 (Micro Milestone — 1 Hour)**:
   - Window: $t \in [600 \times 10^6, 4200 \times 10^6] \mu s$.
   - Output: ~10,000 tasks. Verifies multi-batch stream ingestion and Python IPC socket stability.
3. **Stage 3 (Half-Day Replay — 12 Hours)**:
   - Window: $t \in [600 \times 10^6, 43800 \times 10^6] \mu s$.
   - Output: ~100,000 tasks. Validates RL scheduler memory efficiency over extended execution.
4. **Stage 4 (Full Day Target — 24 Hours)**:
   - Window: $t \in [600 \times 10^6, 87000 \times 10^6] \mu s$.
   - Output: Full 24-hour reproducible workload replay for final RL scheduler experiments.

---

## 6. Preprocessing & Translation Pipeline (`preprocess_google_trace.py`)

Python script `python/scripts/preprocess_google_trace.py` reads `data/raw/google_v3_cell_a_24h.parquet` and generates `data/processed/pureedgesim_tasks_24h.json`:

1. **Calculates Relative Arrival Time**:
   $$t_{\text{arrival}} = \frac{\text{submit\_time\_us} - t_{\text{window\_start\_us}}}{1,000,000} \quad (\text{seconds})$$
2. **Calculates Execution Duration**:
   $$\Delta T_{\text{exec}} = \frac{\text{exec\_time\_us}}{1,000,000} \quad (\text{seconds})$$
3. **Translates Workload Length (MI)**:
   $$\text{Length}_{\text{MI}} = \text{round}\left( \text{req\_cpus} \times \text{MIPS}_{\text{nominal}} \times \Delta T_{\text{exec}} \right)$$
4. **Translates Container Size**:
   $$\text{containerSizeInBits} = \text{round}\left( \text{req\_memory} \times \text{MaxRAM}_{\text{Bytes}} \times 8 \right)$$
5. **Synthesizes Missing Fields**:
   - `fileSizeInBits` = $\text{containerSizeInBits} \times 0.25 \times \text{LogNormal}(0, 0.5)$
   - `outputSizeInBits` = $\text{fileSizeInBits} \times \text{Uniform}(0.05, 0.20)$
   - `maxLatency` = $\Delta T_{\text{exec}} \times (1.0 + \text{SlackFactor}(\text{scheduling\_class}, \text{priority}))$
   - `edgeDevice` = $\left| \text{hashCode}(\text{user}) \right| \pmod{N_{\text{edge}}}$
6. **Preserves Analytics Metadata**:
   - Embeds `queue_time_us`, `exec_time_us`, `priority`, `scheduling_class`, `finish_status`, `user`, and `collection_logical_name` inside `metadata` for RL state representation and post-simulation data analysis.

---

## 7. PureEdgeSim Stream Replay Mechanism

To prevent JVM memory explosion when processing 24-hour workloads ($200,000+$ tasks), task ingestion uses a **Streamed Sliding-Window Generator** (`StreamedTraceTaskGenerator`).

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

### 7.1 Generator Implementation Class (`StreamedTraceTaskGenerator`)
- Package: `com.mechalikh.pureedgesim.taskgenerator`
- Extends: `TaskGenerator`
- Parameters (configured in `simulation_parameters.properties`):
  - `trace.file.path = data/processed/pureedgesim_tasks_24h.json`
  - `trace.buffer.size = 1000`
  - `trace.low.watermark = 200`

### 7.2 Stream Replay Execution Flow
1. **Startup (`generate()`)**:
   - `StreamedTraceTaskGenerator` opens a `BufferedReader` / `JsonReader` over `pureedgesim_tasks_24h.json`.
   - Reads the first $N_{\text{buffer}} = 1000$ task JSON objects.
   - Instantiates standard PureEdgeSim `Task` objects, sets fields (`id`, `time`, `length`, `containerSizeInBits`, `fileSizeInBits`, `outputSizeInBits`, `maxLatency`, `edgeDevice`, `metadata`).
   - Populates `FutureQueue<Task> taskList` and returns to `SimulationManager`.

2. **On-Demand Buffer Replenishment**:
   - `DefaultSimulationManager` dequeues tasks from `taskList` into discrete event queue.
   - On `NEXT_BATCH` event processing, `StreamedTraceTaskGenerator.checkAndRefillBuffer()` checks `taskList.size()`.
   - When `taskList.size() < 200`, it reads the next chunk of lines from `JsonReader` until `taskList.size() == 1000`.

3. **Transparent Python IPC Dispatch**:
   - As tasks fire at $t = \text{task.getTime()}$, `PythonOrchestrator` formats `DECISION_REQUEST` payloads.
   - Python PyTorch RL agent selects placement node.
   - Finished tasks are garbage collected, maintaining a strict upper bound on JVM memory usage ($\mathcal{O}(N_{\text{buffer}})$).
