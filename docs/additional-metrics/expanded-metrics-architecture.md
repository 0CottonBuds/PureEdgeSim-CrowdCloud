# Expanded Metrics Architecture — Design Document
## Phase 3: Architecture for Extending PureEdgeSim Metrics

> **Principle**: Extend, never replace. All existing PureEdgeSim metrics continue to work exactly as before. New metrics are collected alongside them using the same event hooks.

---

## 1. Architecture Overview

```
┌──────────────────────────────────────────────────────────────────────┐
│                     PureEdgeSim Simulation Engine                    │
│                        (no changes required)                         │
└──────────────────────────────────────────────────────────────────────┘
          │ events                              │ events
          ▼                                     ▼
┌─────────────────────────┐       ┌─────────────────────────────────┐
│ DefaultSimulationManager│       │    ResearchNetworkModel          │
│  (no changes required)  │       │  extends DefaultNetworkModel     │
│                         │       │  ─ hooks DOWNLOAD_CONTAINER      │
│  calls simLog.*         │       │    start/finish timestamps       │
└─────────────┬───────────┘       └───────────────┬─────────────────┘
              │ calls                              │ calls
              ▼                                    ▼
┌──────────────────────────────────────────────────────────────────────┐
│                         ResearchSimLog                               │
│                       extends SimLog                                 │
│                                                                      │
│   ┌─────────────────────────┐  ┌───────────────────────────────┐    │
│   │   Existing SimLog state │  │   New Research state          │    │
│   │   (unchanged)           │  │   (additive)                  │    │
│   │                         │  │                               │    │
│   │  tasksSent              │  │  tasksFailedOOM               │    │
│   │  tasksFailed            │  │  tasksFailedNoDestination     │    │
│   │  tasksFailedLatency     │  │  totalNetworkTime             │    │
│   │  tasksFailedMobility    │  │  totalEndToEndDelay           │    │
│   │  tasksFailedDead        │  │  totalContainerDownloadTime   │    │
│   │  totalExecutionTime     │  │  containerTasksCount          │    │
│   │  totalWaitingTime       │  │  completionTimestamps[]       │    │
│   │  executedTasksCount     │  │  throughputBuckets{}          │    │
│   │  [... all others]       │  │  waitingTimeBuckets{}         │    │
│   └─────────────────────────┘  └───────────────────────────────┘    │
│                                                                      │
│   Overridden methods:                                                │
│     getTasksExecutionInfos(task) → calls super() + adds network,    │
│                                    e2e accumulation                  │
│     incrementTasksFailedLackOfRessources(task) → calls super() +    │
│                                    splits OOM vs no-destination      │
│     showIterationResults(tasks) → calls super() + writes            │
│                                    research output                   │
└──────────────────────────────────────────────────────────────────────┘
          │ writes                              │ writes
          ▼                                     ▼
┌──────────────────────┐            ┌───────────────────────────────┐
│  Existing CSV/TXT    │            │  Research Outputs             │
│  (unchanged format)  │            │  ─ research_summary.csv       │
│                      │            │  ─ research_timeseries.csv    │
└──────────────────────┘            └───────────────────────────────┘
```

**Design decisions**:
- `ResearchSimLog` is a **subclass** of `SimLog`. It is a drop-in replacement — it passes all existing `SimLog` constructor contracts and overrides only specific accumulator methods.
- `ResearchNetworkModel` is a **subclass** of `DefaultNetworkModel`. It adds cold-start timing hooks without altering any existing network behavior.
- `DefaultSimulationManager` and `TraceSimulationManager` require **zero changes**. They call `simLog.*` methods polymorphically — they call the overridden versions on `ResearchSimLog` automatically.
- No new event tags are introduced. All collection happens inside existing hook methods.
- The metric system is entirely scheduler-agnostic. The orchestrator generates scheduling decisions; `ResearchSimLog` passively observes results.

---

## 2. Data Flow

```
Simulation lifecycle                    ResearchSimLog state changes
────────────────────────────────────────────────────────────────────────
[Task generator creates tasks]          → no metric change

[SEND_TO_ORCH fires]
  sendTaskToOrchestrator(task)
    simLog.incrementTasksSent()         → tasksSent++  (existing)

[taskFailed(task, phase 0)]
  → device dead before send
    incrementFailedBeacauseDeviceDead   → tasksFailedBeacauseDeviceDead++  (existing)

[SEND_TASK_FROM_ORCH_TO_DESTINATION]
  sendFromOrchToDestination(task)
    → orchestrate() returns NULL
      task.setFailureReason(NO_OFFLOADING_DESTINATIONS)
      simLog.incrementTasksFailedLackOfRessources(task)
                                        → tasksFailedNoDestination++  (NEW)
                                        → tasksFailedRessourcesUnavailable++  (existing via super)

[EXECUTE_TASK]
  taskFailed(task, phase 2)
    → OOM: task.setFailureReason(INSUFFICIENT_RESOURCES)
      simLog.incrementTasksFailedLackOfRessources(task)
                                        → tasksFailedOOM++  (NEW)
                                        → tasksFailedRessourcesUnavailable++  (existing via super)

[DOWNLOAD_CONTAINER event — ResearchNetworkModel only]
  containerDownloadStarted(task)        → task._containerStartTime = clock  (transient)
  containerDownloadFinished(transfer)   → delta = clock - task._containerStartTime
                                        → totalContainerDownloadTime += delta  (NEW)
                                        → containerTasksCount++  (NEW)

[EXECUTE_TASK → submitTask() → startExecution()]
  DefaultComputingNode
    task.setArrivalTime(clock)          → task.arrivalTime = clock  (existing)
    task.setExecutionStartTime(clock)   → task.execStartTime = clock  (existing)

[EXECUTION_FINISHED → executionFinished()]
  task.setExecutionFinishTime(clock)    → task.execFinishTime = clock  (existing)

[TRANSFER_RESULTS_TO_ORCH]
  sendResultsToOchestrator(task)
    finishedTasks.add(task)
    simLog.getTasksExecutionInfos(task)
      → super(): totalExecutionTime += task.getActualCpuTime()   (existing)
      → super(): totalWaitingTime   += task.getWatingTime()       (existing)
      → super(): executedTasksCount++                            (existing)
      → NEW:    totalNetworkTime    += task.getActualNetworkTime()
      → NEW:    totalEndToEndDelay  += task.getTotalDelay()

[RESULT_RETURN_FINISHED]
  taskFailed(task, phase 3)
    → deadline: simLog.incrementTasksFailedLatency(task)         (existing)
  OR task passes → tasksCount++

  → NEW (in ResearchSimLog): record completion in time-series buckets
      bucket = floor(clock / windowSize)
      if task.getStatus() == SUCCESS:
          throughputBuckets.merge(bucket, 1, Integer::sum)
      waitingTimeBuckets.merge(bucket, task.getWatingTime(), Double::sum)
      waitingCountBuckets.merge(bucket, 1, Integer::sum)

[PRINT_LOG event]
  simLog.showIterationResults(finishedTasks)
    → super(): prints all existing metrics, writes existing CSV/TXT  (unchanged)
    → NEW:    ResearchMetricsExporter.write(this)
                ├─ research_summary.csv
                └─ research_timeseries.csv
```

---

## 3. Required Changes to Existing Classes

### Changes: NONE to core simulation classes

The following classes require **zero modifications**:

| Class | Reason |
|---|---|
| `SimLog` | ResearchSimLog is a subclass; existing code unaffected |
| `DefaultSimulationManager` | Calls `simLog.*` polymorphically; uses ResearchSimLog automatically |
| `TraceSimulationManager` | Inherits from DefaultSimulationManager; same polymorphic behavior |
| `DefaultComputingNode` | `submitTask()`, `startExecution()`, `executionFinished()` unchanged |
| `Task` / `TaskAbstract` / `DefaultTask` | No new fields needed on task objects |
| `NetworkLink` | No changes needed |
| `TransferProgress` | No changes needed |
| `SimulationParameters` | No changes needed |

### Changes: `TraceSimulationThread` (or `SimulationThread`)

**One wiring change**: Where `SimLog` is instantiated, replace with `ResearchSimLog`. This is done in the simulation thread setup:

```
// Before:
SimLog simLog = new SimLog(startTime, isFirstIteration);

// After:
SimLog simLog = new ResearchSimLog(startTime, isFirstIteration, windowSizeSeconds);
```

This change is purely mechanical — `ResearchSimLog` extends `SimLog`, so all existing usages of `simLog` continue to work.

### Changes: `SimulationThread.loadModels()` (or equivalent wiring code)

**One additional wiring change**: Where the network model is instantiated, use `ResearchNetworkModel` instead of `DefaultNetworkModel` (or whichever is currently configured):

```
// Before:
Constructor<?> networkConstructor = simulation.networkModel.getConstructor(SimulationManager.class);

// After: configure simulation.networkModel = ResearchNetworkModel.class before calling loadModels
```

This can also be done via the existing `Simulation.setCustomNetworkModel(Class)` mechanism without modifying `SimulationThread` at all.

---

## 4. New Classes / Components

### 4.1 `ResearchSimLog`
**Package**: `com.mechalikh.pureedgesim.simulationmanager`
**Extends**: `SimLog`

The central new class. A drop-in replacement for `SimLog` that:
1. Adds new accumulated state (counters, totals, time-series buckets).
2. Overrides three methods to hook into existing event call-sites.
3. Delegates entirely to `super()` for all existing behavior.
4. Writes research-specific output after the existing output.

### 4.2 `ResearchNetworkModel`
**Package**: `com.mechalikh.pureedgesim.network`
**Extends**: `DefaultNetworkModel`

Adds cold-start timing only. Overrides:
- `addContainer(Task)`: records `containerStartTime` before delegating to super.
- `containerDownloadFinished(TransferProgress)`: records elapsed time, notifies `ResearchSimLog`.

### 4.3 `ResearchMetricsExporter`
**Package**: `com.mechalikh.pureedgesim.simulationmanager`

A stateless utility class (no inheritance). Called by `ResearchSimLog.showIterationResults()`.
Responsible for:
- Formatting `research_summary.csv` rows.
- Formatting `research_timeseries.csv` rows.
- Handling the CSV header on first iteration.

Accepts `ResearchSimLog` instance and writes the files.

---

## 5. Data Structures Required

### 5.1 New scalar accumulators (in `ResearchSimLog`)

```java
// Failure breakdown (split from merged existing counter)
protected int tasksFailedOOM = 0;
protected int tasksFailedNoDestination = 0;

// Latency component accumulators (NEW aggregations; per-task values already exist)
protected double totalNetworkTime = 0.0;     // sum of task.getActualNetworkTime()
protected double totalEndToEndDelay = 0.0;   // sum of task.getTotalDelay()

// Cold start (only populated when registry is enabled)
protected double totalContainerDownloadTime = 0.0;
protected int containerTasksCount = 0;
```

### 5.2 Time-series data structures (in `ResearchSimLog`)

**Strategy**: Fixed-size time-window bucketing. Avoids O(N_tasks) memory; requires O(N_windows) memory which is small (typically 10–1440 windows for a 24h simulation at 1-minute resolution).

```java
// Window size in seconds (configurable, default = 60s)
protected final double timeWindowSeconds;

// Throughput time-series: bucket_index → count of successful completions in that window
protected final Map<Integer, Integer> throughputBuckets;

// Waiting time time-series: bucket_index → (sum, count) for averaging
protected final Map<Integer, Double> waitingTimeSumBuckets;
protected final Map<Integer, Integer> waitingTimeCountBuckets;

// Task completion time-series: bucket_index → completed (success+failure) count
protected final Map<Integer, Integer> completionCountBuckets;

// Failure time-series: bucket_index → failure count
protected final Map<Integer, Integer> failureCountBuckets;
```

**Bucket index calculation** (at `RESULT_RETURN_FINISHED`):
```java
int bucket = (int)(simulationManager.getSimulation().clock() / timeWindowSeconds);
```

### 5.3 Cold start transient state (in `ResearchNetworkModel`)

Container download start times must be associated with the task being downloaded. Since a task object is available at both `addContainer()` and `containerDownloadFinished()`, we can use a `Map<Task, Double>`:

```java
// ResearchNetworkModel: transient tracking of in-progress container downloads
private final Map<Task, Double> containerDownloadStartTimes = new HashMap<>();
```

When `containerDownloadFinished()` fires:
```java
double startTime = containerDownloadStartTimes.remove(transfer.getTask());
double duration = getSimulation().clock() - startTime;
((ResearchSimLog) simulationManager.getSimulationLogger()).recordContainerDownload(duration);
```

---

## 6. Metric Calculation Lifecycle

### 6.1 Incremental vs. End-of-Simulation

| Metric | When calculated | How |
|---|---|---|
| Overall throughput | **End of simulation** | `(tasksSent - tasksFailed) / (simulationDuration / 60)` from accumulated counters |
| Avg total latency | **End of simulation** | `totalEndToEndDelay / executedTasksCount` |
| Avg network latency | **End of simulation** | `totalNetworkTime / executedTasksCount` |
| Avg queue waiting | **End of simulation** | `totalWaitingTime / executedTasksCount` (existing) |
| Avg CPU time | **End of simulation** | `totalExecutionTime / executedTasksCount` (existing) |
| Avg cold start time | **End of simulation** | `totalContainerDownloadTime / containerTasksCount` |
| Task failure rate | **End of simulation** | `tasksFailed * 100.0 / tasksSent` |
| Failure breakdown counts | **End of simulation** | Scalar counters accumulated throughout |
| Failure breakdown pct | **End of simulation** | `count / tasksFailed * 100` |
| Throughput over time | **Incremental** | `throughputBuckets[bucket]++` at each `RESULT_RETURN_FINISHED` |
| Queue wait over time | **Incremental** | `waitingTimeSumBuckets[bucket] += waitingTime` at each `RESULT_RETURN_FINISHED` |
| Task completion over time | **Incremental** | `completionCountBuckets[bucket]++` at each `RESULT_RETURN_FINISHED` |
| Failure rate over time | **Incremental** | `failureCountBuckets[bucket]++` at each failure event |

### 6.2 Override call order in `ResearchSimLog`

```
getTasksExecutionInfos(task):
  1. super.getTasksExecutionInfos(task)            ← existing: totalExecutionTime, totalWaitingTime, executedTasksCount
  2. totalNetworkTime    += task.getActualNetworkTime()    ← NEW
  3. totalEndToEndDelay  += task.getTotalDelay()           ← NEW

incrementTasksFailedLackOfRessources(task):
  1. inspect task.getFailureReason()
     → INSUFFICIENT_RESOURCES : tasksFailedOOM++          ← NEW
     → NO_OFFLOADING_DESTINATIONS : tasksFailedNoDestination++  ← NEW
  2. super.incrementTasksFailedLackOfRessources(task)      ← existing: tasksFailedRessourcesUnavailable++

recordCompletionInTimeSeries(task, clock):           ← NEW, called at RESULT_RETURN_FINISHED
  1. bucket = (int)(clock / timeWindowSeconds)
  2. completionCountBuckets.merge(bucket, 1, Integer::sum)
  3. if task.getStatus() == SUCCESS:
       throughputBuckets.merge(bucket, 1, Integer::sum)
  4. if task reached execution (executedTasksCount includes it):
       waitingTimeSumBuckets.merge(bucket, task.getWatingTime(), Double::sum)
       waitingTimeCountBuckets.merge(bucket, 1, Integer::sum)
  5. if task.getStatus() == FAILED:
       failureCountBuckets.merge(bucket, 1, Integer::sum)

showIterationResults(finishedTasks):
  1. super.showIterationResults(finishedTasks)             ← ALL existing output unchanged
  2. computeSummaryMetrics()                               ← NEW: compute derived scalars
  3. ResearchMetricsExporter.writeSummary(this)            ← NEW: research_summary.csv
  4. ResearchMetricsExporter.writeTimeSeries(this)         ← NEW: research_timeseries.csv
```

**Important**: `recordCompletionInTimeSeries()` must be triggered at `RESULT_RETURN_FINISHED`. This requires a hook that currently does not exist in `SimLog`. Two options:

- **Option A (preferred)**: Override `processEvent()` in `TraceSimulationManager` to also call `((ResearchSimLog) simLog).recordCompletionInTimeSeries(task, clock)` inside the `RESULT_RETURN_FINISHED` case.
- **Option B**: Add a `public void onTaskCompleted(Task task, double clock)` no-op method to `SimLog` that `ResearchSimLog` overrides; `DefaultSimulationManager` calls this at `RESULT_RETURN_FINISHED`.

Option B is cleaner (avoids putting metric logic in `TraceSimulationManager`) but requires adding one line to `DefaultSimulationManager`. Since this is a minimal, non-breaking additive change, **Option B is preferred**.

---

## 7. Final Output / Reporting Structure

### 7.1 Existing outputs (unchanged)

```
PureEdgeSim/output/<startTime>/Sequential_simulation.csv   ← unchanged
PureEdgeSim/output/<startTime>/Sequential_simulation.txt   ← unchanged
PureEdgeSim/output/<startTime>/Final results/              ← unchanged PNG charts
```

### 7.2 New research outputs

```
PureEdgeSim/output/<startTime>/research_summary.csv
PureEdgeSim/output/<startTime>/research_timeseries.csv
```

#### `research_summary.csv` — one row per simulation iteration

```
Orchestration architecture,
Orchestration algorithm,
Edge devices count,
Throughput (tasks/min),
Average latency (s),
Average latency: network (s),
Average latency: queue waiting (s),
Average latency: computation (s),
Average latency: cold start (s),
Task failure rate (%),
Failures: Deadline,
Failures: Battery,
Failures: OOM,
Failures: Network,
Failures: Mobility,
Failures: Deadline (%),
Failures: Battery (%),
Failures: OOM (%),
Failures: Network (%),
Failures: Mobility (%)
```

This CSV accumulates across simulation iterations (same append behavior as the existing CSV).

#### `research_timeseries.csv` — one row per time window per simulation iteration

```
Orchestration architecture,
Orchestration algorithm,
Edge devices count,
Window index,
Window start (s),
Window end (s),
Throughput in window (tasks/min),
Completions in window (total),
Failures in window,
Avg queue waiting in window (s)
```

This gives the full temporal picture needed for time-series charts.

---

## 8. Time-Series Data Strategy

### Window size
Configurable. Default: **60 seconds**. Can be set via `ResearchSimLog` constructor or a research-specific properties entry.

Choosing the window size:
- Too small: noisy, high variance per window.
- Too large: loses temporal resolution.
- 60 seconds is appropriate for simulations of 300–3600 seconds.
- For very long simulations (e.g., trace-based 24h), use 300 seconds (5-minute windows).

### Handling windows with no events
Windows between events are populated with 0 when writing the CSV. Do NOT skip sparse windows — the consumer (Python plotting) expects a contiguous index.

```
for bucket in range(0, maxBucket + 1):
    throughput = throughputBuckets.getOrDefault(bucket, 0)
    completions = completionCountBuckets.getOrDefault(bucket, 0)
    failures = failureCountBuckets.getOrDefault(bucket, 0)
    avgWait = waitingTimeSumBuckets.getOrDefault(bucket, 0.0)
             / max(1, waitingTimeCountBuckets.getOrDefault(bucket, 1))
    write row...
```

### Memory estimate
For a 24-hour simulation at 60s windows: 1440 buckets × 4 Maps × ~32 bytes/entry ≈ 180 KB. Negligible.

### Scheduler comparison
Each scheduler run produces its own `research_timeseries.csv`. Downstream Python analysis overlays the time series from multiple runs. No changes needed to the metric system for this — it is purely an analysis concern.

---

## 9. Failure Categorization Strategy

### Category definitions

| Category | PureEdgeSim source | Semantics |
|---|---|---|
| **Deadline** | `FailureReason.FAILED_DUE_TO_LATENCY` → `tasksFailedLatency` | Task's total delay exceeded `maxLatency` at `RESULT_RETURN_FINISHED` |
| **Battery** | `FailureReason.FAILED_BECAUSE_DEVICE_DEAD` → `tasksFailedBeacauseDeviceDead` | Source device, orchestrator, or destination ran out of battery |
| **OOM** | `FailureReason.INSUFFICIENT_RESOURCES` → `tasksFailedOOM` (NEW, split from merged) | Destination had insufficient RAM or storage |
| **Network** | `FailureReason.NO_OFFLOADING_DESTINATIONS` → `tasksFailedNoDestination` (NEW, split from merged) | No compute node reachable/selected by orchestrator |
| **Mobility** | `FailureReason.FAILED_DUE_TO_DEVICE_MOBILITY` → `tasksFailedMobility` | Device moved out of range of orchestrator/destination |

### Mutual exclusivity guarantee
PureEdgeSim's phased `taskFailed()` method ensures a task can only be classified into one failure category. The phases fire in order and return early on the first match:
- Phase 0: Battery (device dead before transmission)
- Phase 1: Battery or Mobility
- Phase 2: Battery, OOM, or Mobility
- Phase 3: Deadline

Therefore: `Σ(all category counts) = tasksFailed` exactly.

### Mathematical consistency
```
failureRate = tasksFailed / tasksSent × 100

tasksFailed = tasksFailedDeadline
            + tasksFailedBattery
            + tasksFailedOOM
            + tasksFailedNetwork
            + tasksFailedMobility

pct(Deadline) + pct(Battery) + pct(OOM) + pct(Network) + pct(Mobility) = 100%
```

### Verification assertion (for testing)
```java
assert tasksFailedOOM + tasksFailedNoDestination == tasksFailedRessourcesUnavailable :
    "OOM + NoDestination must equal the merged counter";

assert tasksFailedLatency + tasksFailedBeacauseDeviceDead + tasksFailedMobility
     + tasksFailedOOM + tasksFailedNoDestination == tasksFailed :
    "All failure categories must sum to tasksFailed";
```

---

## 10. Validation Strategy

### 10.1 Invariant checks (run at end of `showIterationResults`)

These checks run in `ResearchSimLog.computeSummaryMetrics()` and throw/warn if violated:

| Invariant | Formula | Expected |
|---|---|---|
| Failure category sum | `tasksFailedLatency + tasksFailedBeacauseDeviceDead + tasksFailedMobility + tasksFailedOOM + tasksFailedNoDestination` | `== tasksFailed` |
| OOM + NoDestination | `tasksFailedOOM + tasksFailedNoDestination` | `== tasksFailedRessourcesUnavailable` |
| Tasks accounted for | `(tasksSent - tasksFailed) + tasksFailed` | `== tasksSent` |
| End-to-end delay non-negative | `totalEndToEndDelay` | `>= 0` |
| Network time bounded | `totalNetworkTime` | `<= totalEndToEndDelay` (network is a subset of total) |

### 10.2 Cross-validation against existing metrics

The existing CSV contains enough raw data to derive several new metrics independently via Python. Compare:

| New metric | Should equal (from existing CSV) |
|---|---|
| `failureRate` | `(tasksSent - tasksSucceeded) / tasksSent × 100` |
| `avgQueueWaiting` | `"Average waiting time (s)"` column (already in existing CSV) |
| `avgCpuTime` | `"Average execution delay (s)"` column (already in existing CSV) |
| `tasksFailedLatency` | `"Tasks failed (delay)"` column |
| `tasksFailedBattery` | `"Tasks failed (device dead)"` column |
| `tasksFailedMobility` | `"Tasks failed (mobility)"` column |
| `tasksFailedOOM + tasksFailedNoDestination` | `"Task not executed (No resources available...)"` column |

Run a post-simulation Python script that reads both CSVs and asserts equivalence within floating-point tolerance.

### 10.3 Regression test approach

For unit testing `ResearchSimLog`:
1. Create a minimal simulation stub (or use the existing PureEdgeSim test examples).
2. Run one simulation iteration with `ResearchSimLog` wired in.
3. Verify:
   - Existing CSV is identical to what `SimLog` produces.
   - Research CSV contains correctly computed values.
   - Time-series CSV has N_windows rows, all bucket indices contiguous.
   - All invariants from 10.1 pass.

### 10.4 Scheduler-independence verification
Run the same trace with different orchestration algorithms (e.g., ROUND_ROBIN vs TRADE_OFF). The metric system should produce the same CSV schema regardless of which algorithm is used. Only the values should differ.

---

## 11. Proposed Class Design (non-code notation)

```
SimLog
│
└── ResearchSimLog
        Fields added:
          + tasksFailedOOM : int
          + tasksFailedNoDestination : int
          + totalNetworkTime : double
          + totalEndToEndDelay : double
          + totalContainerDownloadTime : double
          + containerTasksCount : int
          + timeWindowSeconds : double
          + throughputBuckets : Map<Integer, Integer>
          + completionCountBuckets : Map<Integer, Integer>
          + failureCountBuckets : Map<Integer, Integer>
          + waitingTimeSumBuckets : Map<Integer, Double>
          + waitingTimeCountBuckets : Map<Integer, Integer>

        Methods overridden:
          + getTasksExecutionInfos(task) → super() + network/e2e accumulation
          + incrementTasksFailedLackOfRessources(task) → split + super()
          + showIterationResults(tasks) → super() + research export

        Methods added:
          + onTaskCompleted(task, clock) → time-series bucket update
          + recordContainerDownload(duration) → cold-start accumulation
          + computeSummaryMetrics() → compute derived scalars + run invariant checks
          + getResearchSummaryRow() : String → CSV row formatter

NetworkModel (abstract)
│
└── DefaultNetworkModel
        │
        └── ResearchNetworkModel
                Fields added:
                  + containerDownloadStartTimes : Map<Task, Double>

                Methods overridden:
                  + addContainer(task) → record start time + super()
                  + containerDownloadFinished(transfer) → compute duration +
                      ((ResearchSimLog)simLog).recordContainerDownload(duration) + super()

SimLog (minimal additive change — Option B)
  onTaskCompleted(task, clock) : void   ← add as no-op public method
                                          ResearchSimLog overrides it

DefaultSimulationManager (minimal additive change — Option B)
  In RESULT_RETURN_FINISHED case:
    simLog.onTaskCompleted(task, simulation.clock())  ← add one line

(all other existing classes: zero changes)

New utility class:
ResearchMetricsExporter
  writeSummaryRow(simLog : ResearchSimLog) : void
  writeTimeSeriesRows(simLog : ResearchSimLog) : void
```

---

## 12. Metric Availability Matrix

| Metric | Scheduler-independent | Post-hoc calculable | Requires incremental collection |
|---|---|---|---|
| Throughput (overall) | ✅ | ✅ from existing CSV | No |
| Avg total latency | ✅ | ❌ (not in existing CSV) | No — accumulate at task completion |
| Avg network latency | ✅ | ❌ | No — accumulate at task completion |
| Avg queue waiting | ✅ | ✅ from existing CSV | No |
| Avg CPU time | ✅ | ✅ from existing CSV | No |
| Avg cold start | ✅ | ❌ | Yes — needs network model hook |
| Task failure rate | ✅ | ✅ from existing CSV | No |
| Failure: Deadline | ✅ | ✅ from existing CSV | No |
| Failure: Battery | ✅ | ✅ from existing CSV | No |
| Failure: Mobility | ✅ | ✅ from existing CSV | No |
| Failure: OOM | ✅ | ❌ (merged in existing) | No — accumulate at failure event |
| Failure: Network | ✅ | ❌ (merged in existing) | No — accumulate at failure event |
| Throughput over time | ✅ | ❌ | Yes — bucket at completion |
| Queue wait over time | ✅ | ❌ | Yes — bucket at completion |
| Task completion over time | ✅ | ❌ | Yes — bucket at completion |
| Failure rate over time | ✅ | ❌ | Yes — bucket at failure |

---

## 13. Summary of Required Changes

| Component | Change type | Description |
|---|---|---|
| `SimLog` | **Additive** | Add `onTaskCompleted(task, clock)` as no-op public method |
| `DefaultSimulationManager` | **Additive** | Call `simLog.onTaskCompleted(task, clock)` in `RESULT_RETURN_FINISHED` case |
| `SimulationThread` / `TraceSimulationThread` | **Wiring** | Instantiate `ResearchSimLog` instead of `SimLog`; configure `ResearchNetworkModel` |
| `ResearchSimLog` | **New class** | Extends `SimLog`; adds all research metric collection |
| `ResearchNetworkModel` | **New class** | Extends `DefaultNetworkModel`; adds cold-start timing |
| `ResearchMetricsExporter` | **New class** | Writes research CSV files |

**Total existing class modifications**: 3 (two are single-line additions; one is a wiring change in the thread setup).

**New classes**: 3.

**Zero impact on existing output format.**
