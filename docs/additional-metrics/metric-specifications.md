# New Metrics — Specification Document
## Phase 2: Exact Calculation Design

> This document defines exactly how each new research metric is calculated from PureEdgeSim's internal data.
> No code is implemented here. All findings reference Phase 1 analysis.

---

## Precise Timing Landmarks in the PureEdgeSim Lifecycle

Before specifying metrics, we must establish precisely where each timestamp is captured.
This is the factual ground truth extracted from the codebase.

```
T0  task.getTime()          — Scheduled offloading time (set by task generator, not a real clock reading)
    ↓  [SEND_TO_ORCH event fires at T0]
T1  simulation.clock()      — The moment DefaultSimulationManager.sendTaskToOrchestrator() runs
                              → simLog.incrementTasksSent() fires here
    ↓  [network transfer: device → orchestrator (REQUEST)]
    ↓  [DefaultSimulationManager.sendFromOrchToDestination() — orchestration decision]
    ↓  [network transfer: orchestrator → destination (TASK)]
    ↓  [optionally: DOWNLOAD_CONTAINER event → container transfer (CONTAINER)]
    ↓  [EXECUTE_TASK event fires]
T2  simulation.clock()      — DefaultComputingNode.submitTask() is called
                              → task.setArrivalTime(clock)  [sets arrivalTime AND resets execStartTime]
    ↓  [if no core/RAM available: task enters tasksQueue]
T3  simulation.clock()      — DefaultComputingNode.startExecution() is called
                              → task.setExecutionStartTime(clock)  [sets execStartTime, resets execFinishTime]
    ↓  [CPU execution: duration = task.length / mipsPerCore]
T4  simulation.clock()      — DefaultComputingNode.executionFinished() fires
                              → task.setExecutionFinishTime(clock)
    ↓  [TRANSFER_RESULTS_TO_ORCH event]
    ↓  [simLog.getTasksExecutionInfos(task) called — records totalExecutionTime, totalWaitingTime]
    ↓  [network transfer: destination → orchestrator (RESULTS_TO_ORCH)]
    ↓  [network transfer: orchestrator → device (RESULTS_TO_DEV)]
    ↓  [RESULT_RETURN_FINISHED event fires]
T5  simulation.clock()      — Results returned; latency deadline check runs
                              → if task.getTotalDelay() >= task.getMaxLatency(): FAILED_DUE_TO_LATENCY
```

### Key Derived Quantities

| Symbol | Formula | Meaning |
|---|---|---|
| `waitingTime` | `T3 - T2` | Time task spent queued at computing node (`task.getWatingTime()`) |
| `cpuTime` | `T4 - T3` | Pure CPU execution time (`task.getActualCpuTime()`) |
| `networkTime` | accumulated per-hop | All network transfer delays (`task.getActualNetworkTime()`) |
| `totalDelay` | `networkTime + waitingTime + cpuTime` | End-to-end delay from T2 to T4 (`task.getTotalDelay()`) |

### Critical Finding: What `totalDelay` Does NOT Include

`task.getTotalDelay()` measures from **node arrival** (T2) to **execution finish** (T4).
It does **not** include:
- Time from T1 (SEND_TO_ORCH) to T2 (node arrival) — the orchestration + offloading network time  
  *(Correction: `actualNetworkTime` IS accumulated during all network transfers including REQUEST and TASK, so it IS partially captured)*
- Time from T4 (execution finish) to T5 (results returned) — result return network time

**Precise scope of `task.getActualNetworkTime()`**:
`addActualNetworkTime()` is called by `NetworkLink.updateTransfer()` for EVERY hop of EVERY transfer type (REQUEST, TASK, CONTAINER, RESULTS_TO_ORCH, RESULTS_TO_DEV). So the network time on the task object includes ALL network phases.

**Precise scope of `task.getWatingTime()`**:
`arrivalTime` is set at `submitTask()` (T2). `execStartTime` is set at `startExecution()` (T3).
If the task goes directly to execution (core available), T2 ≈ T3 and waiting time ≈ 0.
If the task queues, waiting time = T3 - T2 > 0.

---

## Metric Specification Table

| Metric | Required Data | Existing Source | New Instrumentation | Calculation |
|---|---|---|---|---|
| **Throughput (overall)** | `tasksSent`, `tasksFailed`, `simulationDuration` | `SimLog.tasksSent`, `SimLog.tasksFailed`, `SimulationParameters.simulationDuration` | None | `(tasksSent - tasksFailed) / (simulationDuration / 60.0)` tasks/min |
| **Throughput (over time)** | Per-task completion timestamps, window size | Not stored — only `finishedTasks` list exists at the end | Record `simulation.clock()` at `RESULT_RETURN_FINISHED` per task | Group tasks by time bucket; count successes per bucket per minute |
| **Average total latency** | `totalExecutionTime`, `executedTasksCount` | `SimLog.totalExecutionTime`, `SimLog.executedTasksCount` | None | `totalExecutionTime / executedTasksCount` seconds |
| **Latency: network component** | Sum of `task.getActualNetworkTime()` per task | `task.actualNetworkTime` per task; NOT aggregated in SimLog | Accumulate `task.getActualNetworkTime()` at `getTasksExecutionInfos()` | `totalNetworkTime / executedTasksCount` |
| **Latency: queue waiting** | `totalWaitingTime`, `executedTasksCount` | `SimLog.totalWaitingTime`, `SimLog.executedTasksCount` | None | `totalWaitingTime / executedTasksCount` |
| **Latency: computation** | Sum of `task.getActualCpuTime()` per task | `task.getActualCpuTime()` per task; NOT aggregated separately | Accumulate `cpuTime` at `getTasksExecutionInfos()` | `totalCpuTime / executedTasksCount` |
| **Latency: cold start** | Container download time | NOT captured — `actualNetworkTime` merges cold-start with all network | Record time at DOWNLOAD_CONTAINER start and finish (T_containerStart, T_containerEnd) | `(T_containerEnd - T_containerStart) / containerTasksCount` (only when registry is enabled) |
| **Queue waiting time (overall)** | `totalWaitingTime`, `executedTasksCount` | `SimLog.totalWaitingTime`, `SimLog.executedTasksCount` | None | `totalWaitingTime / executedTasksCount` — **identical to latency queue component** |
| **Queue waiting time (over time)** | Per-task `waitingTime` + completion timestamp | Not stored as a time series | Record per-task `waitingTime` + clock at T5 | Group tasks into time windows; average per window |
| **Task Failure Rate (%)** | `tasksFailed`, `tasksSent` | `SimLog.tasksFailed`, `SimLog.tasksSent` | None | `(tasksFailed * 100.0) / tasksSent` |
| **Failure: Deadline** | `tasksFailedLatency` | `SimLog.tasksFailedLatency` | None | `tasksFailedLatency` |
| **Failure: Battery** | `tasksFailedBeacauseDeviceDead` | `SimLog.tasksFailedBeacauseDeviceDead` | None | `tasksFailedBeacauseDeviceDead` |
| **Failure: OOM** | Tasks with `FailureReason.INSUFFICIENT_RESOURCES` | `task.getFailureReason()` — but NOT aggregated separately | Add `tasksFailedOOM` counter in `SimLog` subclass; increment when `FailureReason == INSUFFICIENT_RESOURCES` | `tasksFailedOOM` |
| **Failure: Network (no destination)** | Tasks with `FailureReason.NO_OFFLOADING_DESTINATIONS` | `task.getFailureReason()` — merged into `tasksFailedRessourcesUnavailable` | Add `tasksFailedNoDestination` counter; increment when `FailureReason == NO_OFFLOADING_DESTINATIONS` | `tasksFailedNoDestination` |
| **Failure: Mobility** | `tasksFailedMobility` | `SimLog.tasksFailedMobility` | None | `tasksFailedMobility` |

---

## 1. Throughput

### Definition
**Overall**: Number of successfully completed tasks per minute of simulation time.

A task is **successfully completed** when:
- It reaches `RESULT_RETURN_FINISHED`
- AND `task.getTotalDelay() < task.getMaxLatency()` (deadline not violated)
- Which in SimLog terms = `(tasksSent - tasksFailed)`

### What counts as a success
`tasksSent - tasksFailed` in `SimLog`. `tasksSent` is incremented at `SEND_TO_ORCH` (T1). `tasksFailed` is incremented at any failure phase. Tasks that succeed have `task.getStatus() == Status.SUCCESS`.

### Completion event
`RESULT_RETURN_FINISHED` in `DefaultSimulationManager`. This is when the task lifecycle is fully complete from the system's point of view.

### Handling of failed tasks
Failed tasks do **not** contribute to the throughput numerator. They are counted in `tasksFailed` and subtracted from `tasksSent`.

### Simulation duration
Use `SimulationParameters.simulationDuration` (in seconds). Convert to minutes: `simulationDuration / 60.0`.

### Overall throughput calculation
```
throughput_per_minute = (tasksSent - tasksFailed) / (simulationDuration / 60.0)
```

### Throughput over simulation time
**New instrumentation required.**

At `RESULT_RETURN_FINISHED`, if the task succeeded, record `simulation.clock()` as the completion timestamp.
At end of simulation, group completions into fixed-width time buckets (e.g., 60-second windows) and count successes per window.

```
bucket_index = floor(completionTime / windowSize)
throughput[bucket_index]++
// Final: throughput[i] / (windowSize / 60) for tasks/min
```

Store as a `List<Double> completionTimestamps` in `ResearchSimLog`.

### Edge cases
- If `tasksSent == 0`: throughput = 0 (guard against division by zero).
- If `simulationDuration == 0`: throughput is undefined; should not occur.
- Tasks completing after `simulationDuration` (due to `waitForAllTasksToFinish`): include them but note the simulation ran beyond nominal duration.

---

## 2. Average Latency

### What PureEdgeSim Can Currently Distinguish

| Latency Component | Availability | Mechanism |
|---|---|---|
| **Arrival at node** (T2) | ✅ Captured | `task.arrivalTime` set in `DefaultComputingNode.submitTask()` |
| **Queue waiting** (T3 - T2) | ✅ Captured | `task.getWatingTime()` = `execStartTime - arrivalTime` |
| **Computation** (T4 - T3) | ✅ Captured | `task.getActualCpuTime()` = `execFinishTime - execStartTime` |
| **All network phases** (total) | ✅ Captured | `task.getActualNetworkTime()` accumulated across ALL transfer types |
| **Cold start only** | ❌ NOT separate | Container download is a `CONTAINER`-type transfer; its duration is included in `actualNetworkTime` but not isolated |
| **T1 to T2 orchestration delay** | ⚠️ Partially captured | The REQUEST and TASK network transfers contribute to `actualNetworkTime`, so offloading time IS included in the network component |
| **T4 to T5 result-return network time** | ⚠️ Partially captured | RESULTS_TO_ORCH and RESULTS_TO_DEV transfers also add to `actualNetworkTime` |

### What `totalExecutionTime` in SimLog actually means
`SimLog.getTasksExecutionInfos(task)` is called at `sendResultsToOchestrator()` (TRANSFER_RESULTS_TO_ORCH event), **before** results are sent back.
```java
this.totalExecutionTime += task.getActualCpuTime();   // = execFinishTime - execStartTime
this.totalWaitingTime   += task.getWatingTime();       // = execStartTime - arrivalTime
```
So `totalExecutionTime` is **CPU time only**, NOT total end-to-end time. The label "Average execution delay" in the existing CSV is misleading — it is actually average CPU time.

### Reinterpreting existing SimLog fields

| SimLog field | What it really measures |
|---|---|
| `totalExecutionTime / executedTasksCount` | Average **CPU computation time** |
| `totalWaitingTime / executedTasksCount` | Average **node-local queue waiting time** |
| `task.getActualNetworkTime()` (per task) | Total network delay across ALL transfer phases |
| `task.getTotalDelay()` | Network + queue waiting + CPU |

### Component-level latency breakdown

| Component | Formula | Existing? | New instrumentation? |
|---|---|---|---|
| Network (all phases) | `Σ task.actualNetworkTime / executedTasksCount` | ❌ NOT aggregated | Add `totalNetworkTime += task.getActualNetworkTime()` at `getTasksExecutionInfos()` |
| Queue waiting | `totalWaitingTime / executedTasksCount` | ✅ Yes | None |
| Computation (CPU) | `totalExecutionTime / executedTasksCount` | ✅ Yes | None |
| Cold start (container) | `Σ containerNetworkTime / containerTasksCount` | ❌ NOT isolated | Must record time delta around `DOWNLOAD_CONTAINER` event |
| Total end-to-end | `Σ task.getTotalDelay() / executedTasksCount` | ❌ NOT aggregated | Add `totalEndToEndDelay += task.getTotalDelay()` |

### Cold Start — Limitation and Instrumentation

**Limitation**: `task.getActualNetworkTime()` accumulates network time for ALL transfer types. The container download duration is blended into it.

**Minimum instrumentation for cold start isolation**: In `TraceSimulationManager` (or a custom `NetworkModel` subclass), record the simulation clock at the start of `DOWNLOAD_CONTAINER` and again when `containerDownloadFinished()` fires. Store the delta on the task or in the `SimLog` subclass.

**Important**: Cold start only applies when `SimulationParameters.enableRegistry == true` AND `SimulationParameters.registryMode == "CLOUD"` AND the task is NOT offloaded to the cloud. In all other configurations, cold start time is 0.

### Average total latency calculation
```
// NEW: accumulate in ResearchSimLog
totalNetworkTime   += task.getActualNetworkTime()
totalEndToEndDelay += task.getTotalDelay()   // = networkTime + waitingTime + cpuTime

// Report:
avgNetworkLatency    = totalNetworkTime / executedTasksCount
avgQueueWaiting      = totalWaitingTime / executedTasksCount   // existing
avgCpuTime           = totalExecutionTime / executedTasksCount // existing
avgTotalLatency      = totalEndToEndDelay / executedTasksCount
```

### Relationship verification
```
avgTotalLatency ≈ avgNetworkLatency + avgQueueWaiting + avgCpuTime
(exact equality when computed per-task and then averaged — floating point may differ slightly)
```

### Edge cases
- If `executedTasksCount == 0`: all averages = 0 (guard against division by zero).
- A task that fails is typically not counted in `executedTasksCount` (it is not added via `getTasksExecutionInfos`). Latency should only be computed for tasks that reach execution finish (T4).
- When `realisticNetworkModel == false`, network transfers are instantaneous (file size set to 0 immediately). `actualNetworkTime` will still include link latency (`NetworkLink.latency`) but not propagation delay.

---

## 3. Queue Waiting Time

### Definition
Time between a task entering a computing node's execution queue and being dequeued for execution.

In PureEdgeSim:
- **Queue entry** = when `DefaultComputingNode.submitTask()` is called AND a core is **not** available → task added to `tasksQueue`.
- **Queue exit** = when `DefaultComputingNode.startExecution()` is called for that task.

`task.getWatingTime() = task.execStartTime - task.arrivalTime`

This is set correctly regardless of whether the task waited or not:
- If a core IS available: `startExecution()` is called immediately inside `submitTask()`, so `execStartTime ≈ arrivalTime` and `waitingTime ≈ 0`.
- If no core available: `startExecution()` is called later (from `executionFinished()` → dequeue), so `waitingTime > 0`.

### Overall average queue waiting time

Already exists in `SimLog`:
```
avgQueueWaitingTime = totalWaitingTime / executedTasksCount
```

This is already reported in the CSV as `"Average waiting time (s)"`.

**No new instrumentation needed for the overall average.**

### Queue waiting time over simulation time

**New instrumentation required.**

At `TRANSFER_RESULTS_TO_ORCH` (when `getTasksExecutionInfos` is called), we also have `task.getWatingTime()` and `simulation.clock()`. Record both in `ResearchSimLog`:

```java
// At the point where getTasksExecutionInfos fires:
completionTimestamps.add(simulationManager.getSimulation().clock());
perTaskWaitingTimes.add(task.getWatingTime());
```

At end of simulation, group into time windows to produce a time series of average waiting time.

### Edge cases
- Tasks that fail before reaching `EXECUTE_TASK` (phases 0, 1) never enter a computing node queue. Their "queue waiting time" is undefined. They should NOT be included in queue waiting time metrics.
- Tasks that fail AT `EXECUTE_TASK` (phase 2, OOM/storage) DID enter `submitTask()` and have `arrivalTime` set. However, they were rejected before being queued (the OOM check happens before `tasksQueue.add()`). Their waiting time = 0.
- Mobility failures at `EXECUTE_TASK` (phase 2) also bypass the queue. Waiting time = 0.

---

## 4. Task Failure Rate

### Definition
```
failureRate = (tasksFailed / tasksSent) × 100
```

Where:
- `tasksSent` = count of tasks where `simLog.incrementTasksSent()` was called (at `SEND_TO_ORCH`)
- `tasksFailed` = count of tasks where any `incrementTasksFailed*` method was called

### PureEdgeSim's failure guarantee
A task can only fail once. `taskFailed()` in `DefaultSimulationManager` returns `true` when it sets the task as failed, and the calling code returns early after any failure. The event-driven architecture prevents double-counting:
- Phase 0 failure → task never reaches phase 1.
- Phase 1 failure → task never reaches phase 2.
- Phase 2 failure → task never reaches phase 3.

### Already exists
`tasksFailed` and `tasksSent` already exist in `SimLog`. The failure rate formula is computable from existing data.

The existing CSV does not output this as a single explicit percentage, but it can be derived from `"Tasks successfully executed"` and `"Number of generated tasks"`.

### New: explicit failure rate counter
Add to `ResearchSimLog`:
```java
double failureRate = (tasksFailed * 100.0) / Math.max(1, tasksSent);
```

### Edge cases
- `tasksSent == 0`: failure rate = 0 (guard with `Math.max(1, tasksSent)`).
- `notGeneratedBecDeviceDead`: tasks that were never sent due to device death are **not** in `tasksSent`. They are not included in the failure rate (they never entered the system).
- Tasks that fail before `incrementTasksSent()` is called: this cannot happen — `incrementTasksSent()` is the very first thing called in `sendTaskToOrchestrator()`, before `taskFailed(phase=0)` is checked in the same method. Wait — actually looking at the code: `sendTaskToOrchestrator()` calls `taskFailed(task, 0)` FIRST, then `simLog.incrementTasksSent()` only if not failed. So tasks failing at phase 0 are NOT counted in `tasksSent`.

**Confirmed code path:**
```java
protected void sendTaskToOrchestrator(Task task) {
    if (taskFailed(task, 0))   // ← phase 0 check FIRST
        return;
    // ...
    simLog.incrementTasksSent();  // ← only reached if not failed at phase 0
```

So `tasksSent` = tasks that passed phase 0 check. `tasksFailed` includes phase 1, 2, 3 failures. The denominator for failure rate should be `tasksSent` (tasks that entered the network system).

---

## 5. Failure Breakdown

### Current failure categories and their `SimLog` counters

| Failure Category | `FailureReason` enum | SimLog counter | Counter incremented by |
|---|---|---|---|
| **Deadline** | `FAILED_DUE_TO_LATENCY` | `tasksFailedLatency` | `incrementTasksFailedLatency(task)` |
| **Battery/Device Dead** | `FAILED_BECAUSE_DEVICE_DEAD` | `tasksFailedBeacauseDeviceDead` | `incrementFailedBeacauseDeviceDead(task)` |
| **Mobility** | `FAILED_DUE_TO_DEVICE_MOBILITY` | `tasksFailedMobility` | `incrementTasksFailedMobility(task)` |
| **OOM + No Destination** (merged) | `INSUFFICIENT_RESOURCES` or `NO_OFFLOADING_DESTINATIONS` | `tasksFailedRessourcesUnavailable` | `incrementTasksFailedLackOfRessources(task)` |

### Mutual exclusivity
**Yes, failure categories ARE mutually exclusive** in PureEdgeSim. The `taskFailed()` method returns `true` on the first matching condition and the calling code performs an early return. A task that fails at phase 1 (mobility) never reaches phase 2 (OOM) or phase 3 (deadline).

Therefore:
```
tasksFailed = tasksFailedLatency + tasksFailedBeacauseDeviceDead 
            + tasksFailedMobility + tasksFailedRessourcesUnavailable
```

This identity should hold exactly.

### Splitting the merged OOM / No-Destination counter
`tasksFailedRessourcesUnavailable` currently conflates two failure modes:

| Sub-category | `FailureReason` | What happens in the code |
|---|---|---|
| **OOM / Storage** | `INSUFFICIENT_RESOURCES` | `task.getOffloadingDestination().getAvailableStorage() < containerSize` OR RAM check, at phase 2 in `taskFailed()` |
| **No Destination (Network/Reachability)** | `NO_OFFLOADING_DESTINATIONS` | `task.getOffloadingDestination() == ComputingNode.NULL` after `orchestrate()`, at `sendFromOrchToDestination()`, NOT inside `taskFailed()` |

To split these, override `incrementTasksFailedLackOfRessources()` in a `ResearchSimLog` subclass and inspect `task.getFailureReason()`:

```java
@Override
public void incrementTasksFailedLackOfRessources(Task task) {
    if (task.getFailureReason() == Task.FailureReason.INSUFFICIENT_RESOURCES)
        this.tasksFailedOOM++;
    else  // NO_OFFLOADING_DESTINATIONS
        this.tasksFailedNoDestination++;
    super.incrementTasksFailedLackOfRessources(task);  // preserves existing counter
}
```

### Research failure categories with their sources

| Research Label | PureEdgeSim Source | Formula |
|---|---|---|
| **Network / Reachability** | `NO_OFFLOADING_DESTINATIONS` split | `tasksFailedNoDestination` (NEW counter) |
| **OOM** | `INSUFFICIENT_RESOURCES` | `tasksFailedOOM` (NEW counter) |
| **Deadline** | `FAILED_DUE_TO_LATENCY` | `tasksFailedLatency` (existing) |
| **Battery** | `FAILED_BECAUSE_DEVICE_DEAD` | `tasksFailedBeacauseDeviceDead` (existing) |
| **Mobility** | `FAILED_DUE_TO_DEVICE_MOBILITY` | `tasksFailedMobility` (existing) |

> **Note on "Network" failure**: PureEdgeSim does not model link-level network failures (packet drop, congestion collapse). The closest semantic match is "no reachable destination", which means the orchestrator could not find any computing node for this task. This is labeled "Network/Reachability" to reflect that it represents topological inaccessibility rather than a true link failure.

### Mathematical consistency
```
tasksFailed = tasksFailedLatency        (Deadline)
            + tasksFailedBeacauseDeviceDead  (Battery)
            + tasksFailedMobility             (Mobility)
            + tasksFailedOOM                  (OOM)      ← split from tasksFailedRessourcesUnavailable
            + tasksFailedNoDestination        (Network)  ← split from tasksFailedRessourcesUnavailable

// Verify: tasksFailedOOM + tasksFailedNoDestination == tasksFailedRessourcesUnavailable
```

Failure breakdown percentages:
```
pct(category) = category_count / tasksFailed × 100
// These sum to 100%

pct(Network)   + pct(OOM) + pct(Deadline) + pct(Battery) + pct(Mobility) = 100%
```

---

## 6. Limitations and Assumptions

### Assumption 1: "tasksSent" is the denominator for failure rate
Tasks that fail at phase 0 (device already dead when task was about to be sent) are NOT counted in `tasksSent` because `incrementTasksSent()` is called AFTER the phase-0 check. Phase-0 failures are also not tracked in any failure counter directly visible in these metrics — they fall into `tasksFailedBeacauseDeviceDead` but also increment `tasksFailed` which itself is only meaningful relative to `tasksSent`. 

Therefore: **Failure rate denominator = tasks that successfully entered the network (`tasksSent`)**.

### Assumption 2: "Network" failures = no offloading destination
PureEdgeSim has no real network failure mode (link drop, timeout). The label "Network/Reachability" maps to `NO_OFFLOADING_DESTINATIONS` — the orchestrator returned NULL. This is an approximation.

### Limitation 1: Cold start cannot be isolated without instrumentation
Container download time is accumulated in `task.getActualNetworkTime()` alongside all other network transfer times. There is no per-transfer-type breakdown on the task object.

**Required instrumentation**: Record simulation clock at `DOWNLOAD_CONTAINER` event start and at `containerDownloadFinished()` in a custom `NetworkModel` subclass or by overriding the method.

### Limitation 2: Total end-to-end latency is not in SimLog
The existing `totalExecutionTime` in `SimLog` is CPU time only (confusingly named "Total tasks execution delay" in CSV). End-to-end latency requires summing `task.getTotalDelay()` per task, which is not currently done.

**Required instrumentation**: In a `ResearchSimLog` subclass, add:
```java
this.totalEndToEndDelay += task.getTotalDelay();
// called inside getTasksExecutionInfos() override
```

### Limitation 3: Time-series metrics require per-task storage
Neither throughput-over-time nor queue-waiting-over-time is tracked currently. Both require storing per-task completion timestamps, which introduces O(N_tasks) memory overhead.

**Mitigation**: Use time-bucket aggregation instead of storing raw timestamps:
- Maintain a `Map<Integer, Integer> throughputBuckets` (bucket index → count).
- Maintain a `Map<Integer, DoubleAccumulator> waitingTimeBuckets`.
- At task completion, compute `bucketIndex = (int)(clock / windowSize)` and increment the bucket.
- This requires O(N_windows) memory, not O(N_tasks).

### Limitation 4: `getWatingTime()` returns 0 for tasks with immediate execution
When `availableCores > 0`, execution starts immediately at `submitTask()`. Both `arrivalTime` and `execStartTime` are set to the same value (`setArrivalTime()` sets both, then `startExecution()` calls `setExecutionStartTime()` which overrides `execStartTime` to the same clock value). Waiting time = 0 is correct in this case.

### Limitation 5: `tasksFailed` does NOT include `notGeneratedBecDeviceDead`
Tasks not generated because the device was already dead are counted in `notGeneratedBecDeviceDead` but NOT in `tasksFailed` or `tasksSent`. They are completely outside the failure rate calculation. This is the correct behavior (they never entered the system).

---

## 7. Reuse Summary — Avoiding Duplicate Instrumentation

| New metric | Reuses existing instrumentation | What is reused |
|---|---|---|
| Overall throughput | ✅ Yes | `tasksSent`, `tasksFailed`, `simulationDuration` |
| Avg latency: queue waiting | ✅ Yes | `totalWaitingTime`, `executedTasksCount` |
| Avg latency: CPU computation | ✅ Yes | `totalExecutionTime`, `executedTasksCount` |
| Failure rate | ✅ Yes | `tasksFailed`, `tasksSent` |
| Failure: deadline | ✅ Yes | `tasksFailedLatency` |
| Failure: battery | ✅ Yes | `tasksFailedBeacauseDeviceDead` |
| Failure: mobility | ✅ Yes | `tasksFailedMobility` |
| Avg latency: network total | ⚠️ Partial | `task.actualNetworkTime` exists per-task; needs aggregation |
| Avg total latency | ⚠️ Partial | `task.getTotalDelay()` exists per-task; needs aggregation |
| Failure: OOM | ❌ New counter | `task.getFailureReason()` available; new split counter needed |
| Failure: network (no destination) | ❌ New counter | `task.getFailureReason()` available; new split counter needed |
| Throughput over time | ❌ New | Needs per-task completion timestamp or bucket accumulator |
| Queue wait over time | ❌ New | Needs per-task wait time + bucket accumulator |
| Cold start time | ❌ New | Needs clock capture at DOWNLOAD_CONTAINER start/end |
