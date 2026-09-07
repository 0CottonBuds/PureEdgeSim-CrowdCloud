# PureEdgeSim Metrics Architecture
## Phase 1 Analysis — Existing Metrics & Extension Points

> **Purpose**: Document how PureEdgeSim currently collects, calculates, stores, and reports metrics.
> Identify exact extension points for adding research metrics **without touching existing code**.

---

## 1. End-to-End Metrics Flow

```
Simulation Event (discrete event engine)
  │
  ├─► DefaultSimulationManager.processEvent()   [event dispatch & failure gating]
  │     │
  │     ├─► SimLog.incrementTasksSent()
  │     ├─► SimLog.incrementTasksFailed*(task)
  │     ├─► SimLog.incrementTasksFailedLatency(task)
  │     ├─► SimLog.incrementTasksFailedMobility(task)
  │     ├─► SimLog.incrementTasksFailedLackOfRessources(task)
  │     ├─► SimLog.incrementFailedBeacauseDeviceDead(task)
  │     ├─► SimLog.getTasksExecutionInfos(task)    ← totalExecutionTime, totalWaitingTime
  │     └─► SimLog.taskSentFromOrchToDest(task)   ← per-tier counters
  │
  ├─► NetworkLink.transferFinished()
  │     └─► SimLog.updateNetworkUsage(transfer)   ← LAN/MAN/WAN/bandwidth totals
  │
  └─► PRINT_LOG event (at simulationDuration)
        └─► SimLog.showIterationResults(finishedTasks)
              ├─► printTasksRelatedResults()     → appends row to resultsList
              ├─► printNetworkRelatedResults()   → appends to resultsList
              ├─► printCPUUtilizationResults()   → appends to resultsList
              └─► printPowerConsumptionResults() → appends to resultsList
                    │
                    └─► SimLog.saveLog()
                          ├─► writeFile(*.csv, resultsList)
                          └─► writeFile(*.txt, log)
```

---

## 2. Key Classes and Responsibilities

### 2.1 `SimLog`
**Package**: `com.mechalikh.pureedgesim.simulationmanager`
**File**: `SimLog.java`

The **single metric accumulator and reporter** for the whole simulation.
One `SimLog` instance exists per simulation iteration.

#### Fields (raw counters)

| Field | Type | Meaning |
|---|---|---|
| `generatedTasksCount` | `int` | Total tasks created by task generator |
| `tasksSent` | `int` | Tasks successfully handed to the orchestrator |
| `tasksFailed` | `int` | Total failed tasks (all reasons) |
| `tasksFailedLatency` | `int` | Failed because `totalDelay >= maxLatency` |
| `tasksFailedMobility` | `int` | Failed because edge device moved out of range |
| `tasksFailedRessourcesUnavailable` | `int` | Failed because no offloading destination found **or** insufficient storage/RAM |
| `tasksFailedBeacauseDeviceDead` | `int` | Failed because source device, orchestrator, or destination died (battery) |
| `notGeneratedBecDeviceDead` | `int` | Tasks skipped at generation time (device already dead) |
| `totalExecutionTime` | `Double` | Sum of `task.getActualCpuTime()` across all executed tasks |
| `totalWaitingTime` | `Double` | Sum of `task.getWatingTime()` across all executed tasks |
| `executedTasksCount` | `int` | Tasks that reached the `TRANSFER_RESULTS_TO_ORCH` event |
| `tasksExecutedOnCloud/Edge/Mist` | `int` | Per-tier submission counter |
| `tasksFailedCloud/Edge/Mist` | `int` | Per-tier failure counter |
| `totalLanUsage` | `Double` | Sum of LAN transfer time (seconds) |
| `totalManUsage` | `Double` | Sum of MAN transfer time (seconds) |
| `totalWanUsage` | `Double` | Sum of WAN transfer time (seconds) |
| `totalBandwidth` | `Double` | Sum of average bandwidth per transfer (Mbps) |
| `totalTraffic` | `Double` | Sum of data transferred (MBytes) |
| `transfersCount` | `int` | Total number of completed network transfers |

#### Accumulator methods (called from `DefaultSimulationManager`)

| Method | Trigger Point |
|---|---|
| `incrementTasksSent()` | `SEND_TO_ORCH` event → `sendTaskToOrchestrator()` |
| `incrementTasksFailed(task)` | Called internally by all `incrementTasksFailed*` methods |
| `incrementTasksFailedLatency(task)` | Phase 3 check: `totalDelay >= maxLatency` |
| `incrementTasksFailedMobility(task)` | Phase 1/2 check: device moved |
| `incrementTasksFailedLackOfRessources(task)` | Phase 2 check: OOM/storage or no destination found |
| `incrementFailedBeacauseDeviceDead(task)` | Any phase: source/orch/dest device is dead |
| `getTasksExecutionInfos(task)` | `sendResultsToOchestrator()` — after execution finishes |
| `taskSentFromOrchToDest(task)` | `sendFromOrchToDestination()` |
| `updateNetworkUsage(transfer)` | `NetworkLink.transferFinished()` — after each hop completes |

#### Output files

| File | Content |
|---|---|
| `<outputFolder>/<startTime>/Sequential_simulation.csv` | One CSV row per iteration |
| `<outputFolder>/<startTime>/Sequential_simulation.txt` | Full timestamped log |

The CSV header row (defined in `SimLog` constructor when `isFirstIteration == true`):

```
Orchestration architecture, Orchestration algorithm, Edge devices count,
Total tasks execution delay (s), Average execution delay (s),
Total tasks waiting time (s), Average waiting time (s),
Number of generated tasks, Tasks successfully executed,
Task not executed (No resources available or long waiting time),
Tasks failed (delay), Tasks failed (device dead), Tasks failed (mobility),
Tasks not generated due to the death of devices,
Total tasks executed (Cloud), Tasks successfully executed (Cloud),
Total tasks executed (Edge), Tasks successfully executed (Edge),
Total tasks executed (Mist), Tasks successfully executed (Mist),
Network usage (s), Wan usage (s), Lan usage (s),
Total network traffic (MBytes), Containers wan usage (s), Containers lan usage (s),
Average bandwidth per task (Mbps), Average CPU usage (%),
Average CPU usage (Cloud) (%), Average CPU usage (Edge) (%), Average CPU usage (Mist) (%),
Energy consumption of computing nodes (Wh), Average energy consumption (Wh/Computing node),
Cloud energy consumption (Wh), Average Cloud energy consumption (Wh/Data center),
Edge energy consumption (Wh), Average Edge energy consumption (Wh/Data center),
Mist energy consumption (Wh), Average Mist energy consumption (Wh/Device),
WAN energy consumption (Wh), MAN energy consumption (Wh), LAN energy consumption (Wh),
WiFi energy consumption (Wh), LTE energy consumption (Wh), Ethernet energy consumption (Wh),
Dead devices count, Average remaining power (Wh), Average remaining power (%),
First edge device death time (s),
List of remaining power (%), List of the time when each device died (s)
```

---

### 2.2 `DefaultSimulationManager`
**Package**: `com.mechalikh.pureedgesim.simulationmanager`
**File**: `DefaultSimulationManager.java`

The **event dispatcher and failure detector**. All task lifecycle events pass through here.

#### Event tags (constants on `SimulationManager`)

| Tag constant | Int | Meaning |
|---|---|---|
| `SEND_TO_ORCH` | 6 | Task dequeued from `taskList`; sent to orchestrator |
| `SEND_TASK_FROM_ORCH_TO_DESTINATION` | 8 | Orchestration decision made; task forwarded to destination node |
| `EXECUTE_TASK` | 3 | Task arrived at destination; execute or fail (OOM/storage/dead) |
| `TRANSFER_RESULTS_TO_ORCH` | 4 | Execution finished; collect execution info, send results |
| `RESULT_RETURN_FINISHED` | 5 | Results delivered back to edge device; latency check |
| `PRINT_LOG` | 1 | Simulation time reached; report all metrics |
| `NEXT_BATCH` | 9 | Schedule next batch of tasks from `taskList` |

#### `taskFailed(Task, int phase)` — failure gating logic

```
phase 0 (SEND_TO_ORCH):
  - device.isDead()          → FAILED_BECAUSE_DEVICE_DEAD  → incrementFailedBeacauseDeviceDead

phase 1 (SEND_TASK_FROM_ORCH_TO_DESTINATION):
  - device.isDead()          → FAILED_BECAUSE_DEVICE_DEAD
  - orchestrator.isDead()    → FAILED_BECAUSE_DEVICE_DEAD
  - out-of-range             → FAILED_DUE_TO_DEVICE_MOBILITY  → incrementTasksFailedMobility

phase 2 (EXECUTE_TASK):
  - device.isDead()          → FAILED_BECAUSE_DEVICE_DEAD
  - destination.isDead()     → FAILED_BECAUSE_DEVICE_DEAD
  - availableStorage < containerSize
    OR availableRam < containerSize
                             → INSUFFICIENT_RESOURCES         → incrementTasksFailedLackOfRessources
  - out-of-range             → FAILED_DUE_TO_DEVICE_MOBILITY

phase 3 (RESULT_RETURN_FINISHED):
  - totalDelay >= maxLatency → FAILED_DUE_TO_LATENCY          → incrementTasksFailedLatency
```

> **Note**: `NO_OFFLOADING_DESTINATIONS` (no destination found by orchestrator) is also mapped to `incrementTasksFailedLackOfRessources`. Both OOM/storage failure and "no node available" are collapsed into the same counter `tasksFailedRessourcesUnavailable`.

---

### 2.3 `Task` interface / `TaskAbstract` / `DefaultTask`
**Package**: `com.mechalikh.pureedgesim.taskgenerator`
**Files**: `Task.java`, `TaskAbstract.java`, `DefaultTask.java`

Each `Task` carries all timing data needed to derive new metrics:

| Field / Method | Type | Meaning |
|---|---|---|
| `offloadingTime` / `getTime()` | `double` | **Scheduled submission time** (seconds) — when this task is due to be sent to the orchestrator |
| `arrivalTime` / `setArrivalTime(clock)` | `double` | **Actual arrival time** at the simulation engine — set to `simulation.clock()` |
| `execStartTime` / `setExecutionStartTime(clock)` | `double` | Timestamp when the computing node **started executing** the task |
| `execFinishTime` / `setExecutionFinishTime(clock)` | `double` | Timestamp when execution finished |
| `actualNetworkTime` / `addActualNetworkTime(dt)` | `double` | **Accumulated network transfer delay** (incremented per hop, per link) |
| `getActualCpuTime()` | `double` | `execFinishTime - execStartTime` |
| `getWatingTime()` | `double` | `execStartTime - arrivalTime` — queue waiting time at the destination |
| `getTotalDelay()` | `double` | `actualNetworkTime + waitingTime + cpuTime` |
| `getMaxLatency()` | `double` | Deadline (from application config, in seconds) |
| `getFailureReason()` | `FailureReason` | Enum of the failure category |
| `getStatus()` | `Status` | `SUCCESS` or `FAILED` |
| `getOffloadingDestination()` | `ComputingNode` | The node that executed (or failed to execute) the task |

#### `Task.FailureReason` enum

```java
enum FailureReason {
    FAILED_DUE_TO_LATENCY,
    FAILED_BECAUSE_DEVICE_DEAD,
    FAILED_DUE_TO_DEVICE_MOBILITY,
    NOT_GENERATED_BECAUSE_DEVICE_DEAD,
    NO_OFFLOADING_DESTINATIONS,
    INSUFFICIENT_RESOURCES,
    INSUFFICIENT_POWER    // defined but NOT triggered in DefaultSimulationManager
}
```

> `INSUFFICIENT_POWER` is defined in the enum but not currently used by `DefaultSimulationManager`. Battery-drain failures are recorded as `FAILED_BECAUSE_DEVICE_DEAD`.

---

### 2.4 `DefaultTaskGenerator` / `StreamedTraceTaskGenerator`
**Package**: `com.mechalikh.pureedgesim.taskgenerator`
**Files**: `DefaultTaskGenerator.java`, `StreamedTraceTaskGenerator.java`

Task generation happens **before** the simulation starts. Tasks are placed in a `FutureQueue<Task>` ordered by `task.getTime()`.

- `DefaultTaskGenerator`: Synthetic workloads based on `simulation_parameters.properties`.
- `StreamedTraceTaskGenerator`: Replays Google Cluster Trace v3 JSON Lines. Reads in chunks to bound memory usage.

**How `arrivalTime` is set**: `setArrivalTime(clock)` is called on a task when it arrives at a computing node's submission queue. This sets both `arrivalTime = clock` and `execStartTime = clock`. This means `arrivalTime` captures when the task was submitted to the destination, not when the task generator created it.

> **Key timing implication**: `getWatingTime() = execStartTime - arrivalTime` captures the time the task spends **queued at the destination node**, not the full end-to-end latency from initial submission to the orchestrator.

---

### 2.5 `NetworkLink` / `TransferProgress`
**Package**: `com.mechalikh.pureedgesim.network`
**Files**: `NetworkLink.java`, `TransferProgress.java`

Network transfers are hop-by-hop. For each hop:
- The `NetworkLink.updateTransfer()` calls `task.addActualNetworkTime(transferDelay)` to accumulate network delay on the task object.
- When a transfer reaches its final destination (`vertexList.size() == 1`), `NetworkLink.transferFinished()` calls `simLog.updateNetworkUsage(transfer)`, which accumulates LAN/MAN/WAN usage seconds and traffic bytes into `SimLog`.

Transfer types tracked by `TransferProgress.Type`:

| Type | Meaning |
|---|---|
| `REQUEST` | Task request from edge device to orchestrator |
| `TASK` | Task data from orchestrator to destination |
| `CONTAINER` | Container image pull from registry |
| `RESULTS_TO_ORCH` | Execution results from destination to orchestrator |
| `RESULTS_TO_DEV` | Results from orchestrator back to edge device |

Network time accumulated on a task equals the sum of delays across **all** of these transfer phases.

---

### 2.6 `TraceSimulationManager`
**Package**: `com.mechalikh.pureedgesim.simulationmanager`
**File**: `TraceSimulationManager.java`

Our custom extension that inherits **all of** `DefaultSimulationManager`'s metric logic and only overrides the `NEXT_BATCH` event to trigger trace refills from `StreamedTraceTaskGenerator`. All metric accumulation methods on `SimLog` are called identically.

---

### 2.7 `ChartsGenerator`
**Package**: `com.mechalikh.pureedgesim.simulationmanager`
**File**: `ChartsGenerator.java`

Post-simulation chart generator. Reads the CSV file and generates XY charts from predefined column names. Charts are saved as PNG in `<outputFolder>/Final results/`.

Chart groups:
- **Tasks**: success counts, failure counts by category, per-tier counts
- **Delays**: average waiting time, average execution delay
- **Network**: LAN/WAN/MAN usage seconds
- **CPU**: utilization per tier
- **Energy**: consumption per tier and per network type

---

## 3. Simulation Time Tracking

**Clock**: `PureEdgeSim` is the discrete event engine. Current simulation time is `simulation.clock()`.

| Concept | Where |
|---|---|
| Total simulation duration | `SimulationParameters.simulationDuration` (seconds) |
| Current simulation time | `simulationManager.getSimulation().clock()` |
| Task scheduled time | `task.getTime()` — set by task generator |
| Task arrival at node queue | `task.arrivalTime` — set by `setArrivalTime(clock)` at node queue entry |
| Execution start | `task.execStartTime` — set by `setExecutionStartTime(clock)` |
| Execution finish | `task.execFinishTime` — set by `setExecutionFinishTime(clock)` |

---

## 4. Resource Failure Representation

### Battery / Dead Device
- `ComputingNode.isDead()` returns `true` when the energy model drains the battery to zero.
- `ComputingNode.getDeathTime()` returns the simulation clock at time of death.
- Tasks that fail due to this get `FAILED_BECAUSE_DEVICE_DEAD` and increment `tasksFailedBeacauseDeviceDead`.
- Reported in `printPowerConsumptionResults()` via `deadEdgeDevicesCount`, `devicesDeathTime`, `firstDeviceDeathTime`.

### OOM / Insufficient Resources
- Checked at `phase == 2` (EXECUTE_TASK): `availableStorage < containerSizeInMBytes` OR `availableRam < containerSizeInMBytes`.
- Sets `INSUFFICIENT_RESOURCES` and calls `incrementTasksFailedLackOfRessources`.

### No Available Destination
- When orchestrator returns `ComputingNode.NULL` (no node selected).
- Sets `NO_OFFLOADING_DESTINATIONS` and **also** calls `incrementTasksFailedLackOfRessources`.
- Both are **collapsed** into `tasksFailedRessourcesUnavailable` — they cannot be distinguished from the current counter alone.

---

## 5. Existing Data Available for New Metrics

The following table maps each new metric we need to **existing fields/methods** in `SimLog` and `Task`:

| New Metric | Derivation | Source |
|---|---|---|
| **Throughput (tasks/min)** | `(tasksSent - tasksFailed) / (simulationDuration / 60)` | `SimLog.tasksSent`, `SimLog.tasksFailed`, `SimulationParameters.simulationDuration` |
| **Average Latency (s)** | `totalExecutionTime / executedTasksCount` | `SimLog.totalExecutionTime`, `SimLog.executedTasksCount` — **already computed** as "Average execution delay" |
| **Queue Waiting Time (s)** | `totalWaitingTime / executedTasksCount` | `SimLog.totalWaitingTime`, `SimLog.executedTasksCount` — **already computed** as "Average waiting time" |
| **Task Failure Rate (%)** | `(tasksFailed * 100.0) / tasksSent` | `SimLog.tasksFailed`, `SimLog.tasksSent` |
| **Failure: Deadline** | `tasksFailedLatency` | `SimLog.tasksFailedLatency` — **already tracked** |
| **Failure: Battery/Dead** | `tasksFailedBeacauseDeviceDead` | `SimLog.tasksFailedBeacauseDeviceDead` — **already tracked** |
| **Failure: OOM** | **NOT separately tracked** — currently merged with `tasksFailedRessourcesUnavailable` | Requires splitting `INSUFFICIENT_RESOURCES` from `NO_OFFLOADING_DESTINATIONS` in a `SimLog` subclass |
| **Failure: Network** | **NOT tracked** as a distinct category | Closest available: `NO_OFFLOADING_DESTINATIONS` (split from OOM), or `tasksFailedMobility` (connectivity loss) |

---

## 6. Gaps — What Does NOT Exist Yet

| Gap | Details |
|---|---|
| **OOM failure counter** | `tasksFailedRessourcesUnavailable` merges two distinct failure modes: (a) `INSUFFICIENT_RESOURCES` (OOM/storage) and (b) `NO_OFFLOADING_DESTINATIONS` (no node found by orchestrator). These must be split into separate counters. |
| **Network failure counter** | There is no concept of "network failure" in the current code. The closest candidates are `FAILED_DUE_TO_DEVICE_MOBILITY` (connectivity lost due to movement) or `NO_OFFLOADING_DESTINATIONS`. A dedicated `FAILED_DUE_TO_NETWORK` failure reason does not exist. |
| **Per-task failure reason in CSV** | The CSV reports aggregate counts per iteration, not per-task records. |
| **Throughput** | Derivable from existing counters but not currently computed or exported. |

---

## 7. Extension Points

### Option A — Subclass `SimLog` (Recommended, Minimal Impact)
Add new `protected int` counters to a `SimLog` subclass:

```java
// In ResearchSimLog extends SimLog:
protected int tasksFailedOOM = 0;
protected int tasksFailedNoDestination = 0;

@Override
public void incrementTasksFailedLackOfRessources(Task task) {
    // Inspect the failure reason to split the merged counter
    if (task.getFailureReason() == Task.FailureReason.INSUFFICIENT_RESOURCES)
        tasksFailedOOM++;
    else  // NO_OFFLOADING_DESTINATIONS
        tasksFailedNoDestination++;
    super.incrementTasksFailedLackOfRessources(task);  // preserve existing counter
}

@Override
public void showIterationResults(List<Task> finishedTasks) {
    super.showIterationResults(finishedTasks);  // run all existing reporting first
    // Then write our additional research_metrics.csv
    writeResearchMetrics();
}
```

### Option B — Override `TraceSimulationManager`
Already done. Can intercept individual event tags to capture scheduling timestamps:
- Hook `SEND_TASK_FROM_ORCH_TO_DESTINATION` to capture the scheduling timestamp.
- Hook `EXECUTE_TASK` to capture execution start time from `simulation.clock()`.
- Hook `RESULT_RETURN_FINISHED` to compute per-task end-to-end latency.

### Option C — Post-Simulation CSV Processing (Python)
Since the CSV already contains `Tasks failed (delay)`, `Tasks failed (device dead)`, `Tasks failed (mobility)`, `Task not executed (No resources available...)`, many new metrics can be computed via Python post-processing without any Java changes. This is the least invasive approach but cannot split OOM from no-destination.

---

## 8. Recommended Implementation Path (Phase 2)

```
Step 1: Create ResearchSimLog extends SimLog
   ├─ Add: tasksFailedOOM counter
   ├─ Add: tasksFailedNoDestination counter
   ├─ Override: incrementTasksFailedLackOfRessources() → split by task.getFailureReason()
   └─ Override: showIterationResults() → call super, then write research_metrics.csv

Step 2: Wire ResearchSimLog into TraceSimulationThread
   └─ Pass ResearchSimLog to TraceSimulationManager constructor instead of SimLog

Step 3: Research metrics CSV (separate file, no modification to existing CSV)
   research_metrics.csv columns:
     - Throughput (tasks/min)         → (tasksSent - tasksFailed) / (simulationDuration / 60)
     - Average Latency (s)            → totalExecutionTime / executedTasksCount  [reuse]
     - Queue Waiting Time (s)         → totalWaitingTime / executedTasksCount    [reuse]
     - Task Failure Rate (%)          → tasksFailed * 100.0 / tasksSent
     - Failure: Deadline              → tasksFailedLatency                       [reuse]
     - Failure: OOM                   → tasksFailedOOM                           [NEW counter]
     - Failure: Network/Reachability  → tasksFailedNoDestination                 [NEW counter]
     - Failure: Battery               → tasksFailedBeacauseDeviceDead            [reuse]
```

This approach:
- Does **not** modify any existing PureEdgeSim class
- Does **not** remove or replace any existing metric
- Uses existing `Task` fields — no new fields needed on `Task`
- Uses existing `SimLog` counters for throughput, latency, queue time, failure rate
- Requires splitting `tasksFailedRessourcesUnavailable` for accurate OOM count
- Requires a design decision on "Network Failure" category (see Section 9)

---

## 9. Network Failure — Design Decision Required

PureEdgeSim does **not** model network link failures (dropped packets, congestion timeouts). The available failure modes and their best mapping to "Network Failure" are:

| Option | Source counter | Semantics |
|---|---|---|
| 1. Mobility = Network | `tasksFailedMobility` | Task fails because destination is out of WiFi/cellular range after device movement. This IS a connectivity loss. |
| 2. No Destination = Network | `NO_OFFLOADING_DESTINATIONS` split from `tasksFailedRessourcesUnavailable` | No reachable compute node found by orchestrator. Could indicate network isolation. |
| 3. Add new failure type | New `FAILED_DUE_TO_NETWORK` FailureReason | Requires modifying `DefaultSimulationManager.taskFailed()` — breaks "no modification" rule. |

**Recommended**: Option 2 — split `NO_OFFLOADING_DESTINATIONS` from `INSUFFICIENT_RESOURCES` in the `SimLog` subclass and label it "Network/Reachability" failures. This is the lowest-risk approach and does not require modifying any existing PureEdgeSim class.

---

## 10. Summary — Answers to Analysis Questions

| Question | Answer |
|---|---|
| Where are metrics calculated? | `SimLog` (accumulation) + `DefaultSimulationManager.taskFailed()` (failure detection) |
| Which classes collect metrics? | `SimLog`, driven by `DefaultSimulationManager` and `NetworkLink` |
| Which events trigger collection? | `SEND_TO_ORCH`, `SEND_TASK_FROM_ORCH_TO_DESTINATION`, `EXECUTE_TASK`, `TRANSFER_RESULTS_TO_ORCH`, `RESULT_RETURN_FINISHED`, `NetworkLink.transferFinished()`, `PRINT_LOG` |
| How is task completion recorded? | `finishedTasks.add(task)` at `TRANSFER_RESULTS_TO_ORCH`; `tasksCount++` at `RESULT_RETURN_FINISHED` |
| How are task failures recorded? | `SimLog.incrementTasksFailed*()` methods called from `taskFailed()` |
| How is simulation time tracked? | `simulation.clock()` (discrete event engine) |
| How is task arrival time represented? | `task.arrivalTime` — set when task arrives at destination queue |
| How is scheduling/assignment time represented? | Not explicitly stored; occurs between `SEND_TO_ORCH` and `SEND_TASK_FROM_ORCH_TO_DESTINATION` |
| How are execution start/finish times represented? | `task.execStartTime`, `task.execFinishTime` |
| How is network timing represented? | `task.actualNetworkTime` (accumulated per hop), `TransferProgress.{wan,man,lan}NetworkUsage` |
| How are resource failures represented? | `Task.FailureReason.INSUFFICIENT_RESOURCES` + `NO_OFFLOADING_DESTINATIONS` (currently merged counter) |
| How are final metrics exposed? | CSV + TXT files via `SimLog.saveLog()`; PNG charts via `ChartsGenerator` |
