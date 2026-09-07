# Expanded Metrics — Implementation Roadmap
## Phase 4: Concrete Milestones for a Coding Agent

> **Rule**: Every milestone preserves all existing PureEdgeSim output files and metrics.
> The existing `Sequential_simulation.csv` and `.txt` must be byte-for-byte identical before and after each milestone.

---

## Repository Orientation (read before starting)

Key file locations:
```
PureEdgeSim/com/mechalikh/pureedgesim/
  simulationmanager/
    SimLog.java                    ← the existing metric accumulator
    DefaultSimulationManager.java  ← event dispatch, calls simLog.*
    SimulationThread.java          ← wires SimLog; instantiates all modules
    TraceSimulationThread.java     ← our custom thread; extends SimulationThread
    TraceSimulationManager.java    ← our custom manager; extends DefaultSimulationManager
  network/
    DefaultNetworkModel.java       ← has addContainer(), containerDownloadFinished()
    NetworkModel.java              ← abstract base; defines DOWNLOAD_CONTAINER constant
  taskgenerator/
    Task.java                      ← interface: getActualNetworkTime(), getTotalDelay(), etc.
    TaskAbstract.java              ← concrete timing fields: arrivalTime, execStartTime, etc.
    DefaultTask.java               ← concrete task implementation

src/test/java/com/mechalikh/pureedgesim/
  taskgenerator/StreamedTraceTaskGeneratorTest.java  ← existing test example
  python/...                                         ← Python bridge tests

PureEdgeSim/examples/
  ExampleTraceReplay.java          ← smoke test entry point (mvn exec:exec)

docs/additional-metrics/
  metrics-architecture.md          ← Phase 1 analysis
  metric-specifications.md         ← Phase 2 exact calculations
  expanded-metrics-architecture.md ← Phase 3 design
```

Maven build: `mvn compile` and `mvn test` from project root.
Run smoke test: `mvn exec:exec -Dexec.mainClass=examples.ExampleTraceReplay`

---

## Milestone Overview

| # | Name | Scope | Modifies existing? |
|---|---|---|---|
| M1 | Baseline snapshot | Test + document | Read-only |
| M2 | Lifecycle hook | SimLog + DefaultSimulationManager | 1 no-op method + 1 line |
| M3 | Throughput | ResearchSimLog (new) | Wiring only |
| M4 | Queue waiting time | ResearchSimLog | New accumulators |
| M5 | Latency | ResearchSimLog | New accumulators |
| M6 | Failure rate | ResearchSimLog | New computation |
| M7 | Failure categories | ResearchSimLog | Override + split counter |
| M8 | Time-series | ResearchSimLog + ResearchNetworkModel | New class + override |
| M9 | Regression | Test | Read-only |
| M10 | End-to-end validation | Validation script | Read-only |

---

## Milestone 1 — Preserve and Verify Existing Metrics

### Objective
Establish a baseline that proves existing PureEdgeSim metrics work correctly before any changes are made. All subsequent milestones verify against this baseline.

### Relevant existing files
- [`SimulationThread.java`](file:///home/cotton/Projects/ML/Thesis/PureEdgeSim/PureEdgeSim/com/mechalikh/pureedgesim/simulationmanager/SimulationThread.java) — line 113: `simLog = new SimLog(startTime, isFirstIteration)`
- [`SimLog.java`](file:///home/cotton/Projects/ML/Thesis/PureEdgeSim/PureEdgeSim/com/mechalikh/pureedgesim/simulationmanager/SimLog.java) — full class
- [`ExampleTraceReplay.java`](file:///home/cotton/Projects/ML/Thesis/PureEdgeSim/PureEdgeSim/examples/ExampleTraceReplay.java) — smoke test entry point

### Files to modify
None.

### New files
`src/test/java/com/mechalikh/pureedgesim/metrics/BaselineMetricsTest.java`

### Dependencies
None.

### Implementation work

1. Run `ExampleTraceReplay` and capture the output CSV:
   ```bash
   mvn exec:exec -Dexec.mainClass=examples.ExampleTraceReplay
   cp PureEdgeSim/output/<latest>/Sequential_simulation.csv docs/additional-metrics/baseline_output.csv
   ```

2. Write `BaselineMetricsTest.java`. This test:
   - Runs a complete simulation using the existing `SimLog` (not `ResearchSimLog`).
   - After simulation, reads the produced CSV.
   - Asserts that the following columns exist and contain non-zero values:
     - `"Tasks successfully executed"`
     - `"Number of generated tasks"`
     - `"Average waiting time (s)"`
     - `"Average execution delay (s)"`
     - `"Tasks failed (delay)"`
     - `"Tasks failed (device dead)"`
     - `"Tasks failed (mobility)"`
     - `"Task not executed (No resources available or long waiting time)"`
     - `"Network usage (s)"`

3. Document the baseline CSV column order and row count in `docs/additional-metrics/baseline_output.csv`.

### Tests
`BaselineMetricsTest.java`:
```
testExistingCsvColumnsPresent()        — assert all expected column headers exist
testTaskCountsNonNegative()            — all task counters >= 0
testSucceededPlusFailedLteSubmitted()  — success + failures <= tasksSent
```

### Expected output
- `baseline_output.csv` committed to docs.
- `BaselineMetricsTest` passes with `mvn test`.

### Acceptance criteria
- `mvn test -Dtest=BaselineMetricsTest` exits 0.
- `baseline_output.csv` exists and has at least 1 data row.
- No changes made to any existing `.java` file.

---

## Milestone 2 — Instrument Task Lifecycle Hook

### Objective
Add the minimal hook to `SimLog` and `DefaultSimulationManager` that allows `ResearchSimLog` (created in M3) to observe task completion events at `RESULT_RETURN_FINISHED`. This is the only modification to existing PureEdgeSim classes in the entire roadmap.

### Relevant existing files
- [`SimLog.java`](file:///home/cotton/Projects/ML/Thesis/PureEdgeSim/PureEdgeSim/com/mechalikh/pureedgesim/simulationmanager/SimLog.java)
- [`DefaultSimulationManager.java`](file:///home/cotton/Projects/ML/Thesis/PureEdgeSim/PureEdgeSim/com/mechalikh/pureedgesim/simulationmanager/DefaultSimulationManager.java) — lines 195–202 (`RESULT_RETURN_FINISHED` case)

### Files to modify
1. **`SimLog.java`**: Add one no-op public method at the end of the class (before the closing `}`):
   ```java
   /**
    * Called when a task's result has been delivered back to the edge device
    * (i.e., at the RESULT_RETURN_FINISHED event). The task status is already
    * finalized at this point (SUCCESS or FAILED).
    *
    * This is a no-op in the base SimLog. Subclasses (e.g., ResearchSimLog)
    * override this to collect time-series data without modifying event dispatch.
    *
    * @param task  the completed task
    * @param clock the current simulation time (seconds)
    */
   public void onTaskCompleted(Task task, double clock) {
       // no-op: base SimLog does not use this hook
   }
   ```

2. **`DefaultSimulationManager.java`**: In the `RESULT_RETURN_FINISHED` case (around line 200), add ONE line after `tasksCount++`:
   ```java
   case RESULT_RETURN_FINISHED:
       if (taskFailed(task, 3))
           return;
       edgeOrchestrator.resultsReturned(task);
       tasksCount++;
       simLog.onTaskCompleted(task, simulation.clock());  // ← ADD THIS LINE
       break;
   ```

### New files
None in this milestone.

### Dependencies
M1 must be complete (baseline established).

### Implementation work

1. Add `onTaskCompleted(Task task, double clock)` no-op to `SimLog.java`.
2. Add `simLog.onTaskCompleted(task, simulation.clock())` call in `DefaultSimulationManager.java`.
3. Re-run `BaselineMetricsTest` to confirm existing output is unchanged.

### Tests
Re-run `BaselineMetricsTest` without any changes. If it still passes, the hook is non-breaking.

Also write `LifecycleHookTest.java`:
```
testOnTaskCompletedCalledForSuccessfulTask()
  — Create a SimLog subclass that counts onTaskCompleted calls.
  — Run a simulation.
  — Assert: call count == number of tasks that reached RESULT_RETURN_FINISHED
            (i.e., simLog.getTasksSent() - tasksFailedBeforeResultReturn)

testOnTaskCompletedCalledForFailedTask()
  — Assert that tasks failing at phase 3 (FAILED_DUE_TO_LATENCY) also trigger
    the hook (because taskFailed(task,3) is called BEFORE the early return, and
    the hook fires after the success path — so only SUCCESS tasks get the hook).

  IMPORTANT: Check the actual code path. At RESULT_RETURN_FINISHED:
    if (taskFailed(task, 3)) return;     ← early return for phase-3 failures
    tasksCount++;
    simLog.onTaskCompleted(task, clock); ← only reached for SUCCESS tasks
  So the hook fires ONLY for successful tasks. Document this explicitly.
```

### Expected output
- All existing tests still pass.
- `LifecycleHookTest` passes.
- Existing CSV output byte-for-byte identical to baseline.

### Acceptance criteria
- `mvn test` exits 0 (all tests pass including M1 baseline).
- `git diff PureEdgeSim/com/mechalikh/pureedgesim/simulationmanager/SimLog.java` shows exactly one new method added.
- `git diff PureEdgeSim/com/mechalikh/pureedgesim/simulationmanager/DefaultSimulationManager.java` shows exactly one line added.

---

## Milestone 3 — Add Throughput

### Objective
Implement `ResearchSimLog` as a drop-in subclass of `SimLog` and wire it into `TraceSimulationThread`. Compute and report overall throughput (tasks/minute) in a new `research_summary.csv`.

### Relevant existing files
- [`TraceSimulationThread.java`](file:///home/cotton/Projects/ML/Thesis/PureEdgeSim/PureEdgeSim/com/mechalikh/pureedgesim/simulationmanager/TraceSimulationThread.java) — line 113 in parent `SimulationThread.java`: `simLog = new SimLog(startTime, isFirstIteration)`
- [`SimLog.java`](file:///home/cotton/Projects/ML/Thesis/PureEdgeSim/PureEdgeSim/com/mechalikh/pureedgesim/simulationmanager/SimLog.java) — `showIterationResults()`, `tasksSent`, `tasksFailed`
- [`SimulationParameters.java`](file:///home/cotton/Projects/ML/Thesis/PureEdgeSim/PureEdgeSim/com/mechalikh/pureedgesim/scenariomanager/SimulationParameters.java) — `simulationDuration`

### Files to modify
- **`TraceSimulationThread.java`**: Override `startSimulation()` to instantiate `ResearchSimLog` instead of `SimLog`.

  In `SimulationThread.startSimulation()`, line 113:
  ```java
  simLog = new SimLog(startTime, isFirstIteration);
  ```
  Override in `TraceSimulationThread`:
  ```java
  @Override
  public void startSimulation() {
      // Change SimLog instantiation to ResearchSimLog; rest of method unchanged.
      // Option: call super.startSimulation() and use a factory method, OR
      // copy the startSimulation() body and replace the SimLog line.
      // Cleanest approach: override a protected factory method in SimulationThread.
  }
  ```

  **Preferred approach**: Add a `protected SimLog createSimLog(String startTime, boolean isFirst)` factory method to `SimulationThread`:
  ```java
  // In SimulationThread.java — add one protected factory method:
  protected SimLog createSimLog(String startTime, boolean isFirst) {
      return new SimLog(startTime, isFirst);
  }
  // And change line 113 from: simLog = new SimLog(startTime, isFirstIteration);
  //                     to:   simLog = createSimLog(startTime, isFirstIteration);
  ```

  Then `TraceSimulationThread` overrides it:
  ```java
  @Override
  protected SimLog createSimLog(String startTime, boolean isFirst) {
      return new ResearchSimLog(startTime, isFirst, ResearchSimLog.DEFAULT_WINDOW_SECONDS);
  }
  ```

  This is a 2-line change to `SimulationThread` and 1 override in `TraceSimulationThread`.

### New files
1. **`ResearchSimLog.java`** — package `com.mechalikh.pureedgesim.simulationmanager`
2. **`ResearchMetricsExporter.java`** — package `com.mechalikh.pureedgesim.simulationmanager`

### `ResearchSimLog.java` — M3 contents (throughput only)

```java
public class ResearchSimLog extends SimLog {

    public static final double DEFAULT_WINDOW_SECONDS = 60.0;

    private final double timeWindowSeconds;

    // Computed at end of simulation:
    private double throughputPerMinute = 0.0;

    public ResearchSimLog(String startTime, boolean isFirstIteration, double timeWindowSeconds) {
        super(startTime, isFirstIteration);
        this.timeWindowSeconds = timeWindowSeconds;
    }

    @Override
    public void showIterationResults(List<Task> finishedTasks) {
        super.showIterationResults(finishedTasks);  // ALL existing output — unchanged
        computeSummaryMetrics();
        ResearchMetricsExporter.writeSummaryRow(this);
    }

    private void computeSummaryMetrics() {
        double durationSeconds = SimulationParameters.simulationDuration;
        double durationMinutes = (durationSeconds > 0) ? durationSeconds / 60.0 : 1.0;
        int succeeded = getTasksSent() - getTasksFailed();
        this.throughputPerMinute = succeeded / durationMinutes;
    }

    // Getters for ResearchMetricsExporter
    public double getThroughputPerMinute() { return throughputPerMinute; }
    public double getTimeWindowSeconds()    { return timeWindowSeconds; }
    // Expose protected SimLog fields via package-friendly getters as needed
}
```

### `ResearchMetricsExporter.java` — M3 contents

```java
public class ResearchMetricsExporter {

    private static final String SUMMARY_HEADER =
        "Orchestration architecture,Orchestration algorithm,Edge devices count," +
        "Throughput (tasks/min)";

    public static void writeSummaryRow(ResearchSimLog log) {
        String fileName = log.getFileName("_research_summary.csv");
        boolean writeHeader = !new File(fileName).exists();
        try (PrintWriter pw = new PrintWriter(new FileWriter(fileName, true))) {
            if (writeHeader) pw.println(SUMMARY_HEADER);
            pw.printf("%s,%s,%d,%.4f%n",
                log.getOrchArchitecture(),
                log.getOrchAlgorithm(),
                log.getDevicesCount(),
                log.getThroughputPerMinute());
        } catch (IOException e) {
            SimLog.println("ResearchMetricsExporter - Failed to write summary: " + e.getMessage());
        }
    }
}
```

### Dependencies
M2 must be complete.

### Implementation work

1. Add `createSimLog()` factory method to `SimulationThread`.
2. Override `createSimLog()` in `TraceSimulationThread` to return `ResearchSimLog`.
3. Implement `ResearchSimLog` with throughput only.
4. Implement `ResearchMetricsExporter` with summary CSV writer.
5. Confirm `getFileName()`, `getOrchArchitecture()`, `getOrchAlgorithm()`, `getDevicesCount()` are accessible (they exist as protected/public methods on `SimLog` or via `initialize()`).

### Tests

`ThroughputMetricTest.java`:
```
testThroughputNonNegative()
  — Run simulation → assert researchSimLog.getThroughputPerMinute() >= 0

testThroughputFormula()
  — Assert: throughput = (tasksSent - tasksFailed) / (simulationDuration / 60)
  — Verify using known counts from a controlled run

testResearchSummaryCsvCreated()
  — Assert file exists at expected path
  — Assert it has a header row + 1 data row

testResearchCsvDoesNotModifyExistingCsv()
  — Read existing Sequential_simulation.csv before and after
  — Assert byte-identical
```

### Expected output
```
PureEdgeSim/output/<startTime>/Sequential_simulation.csv    ← unchanged
PureEdgeSim/output/<startTime>/_research_summary.csv        ← NEW
```

`_research_summary.csv` example:
```
Orchestration architecture,Orchestration algorithm,Edge devices count,Throughput (tasks/min)
CLOUD_ONLY,ROUND_ROBIN,10,142.8
```

### Acceptance criteria
- `mvn test -Dtest=ThroughputMetricTest` exits 0.
- `_research_summary.csv` created alongside existing CSV.
- Existing `Sequential_simulation.csv` content unchanged.
- Throughput value is non-negative and reasonable (not 0 unless all tasks failed).

---

## Milestone 4 — Add Queue Waiting Time

### Objective
Add average queue waiting time to the research summary output. This metric already exists in `SimLog` — the milestone is purely about surfacing it in `ResearchSimLog`'s output.

### Relevant existing files
- `SimLog.java` — fields `totalWaitingTime`, `executedTasksCount`; method `getTasksExecutionInfos(task)` accumulates both

### Files to modify
- **`ResearchSimLog.java`**: Add computed field and expose via getter.
- **`ResearchMetricsExporter.java`**: Add column to summary CSV.

### Implementation work

1. Add to `ResearchSimLog.computeSummaryMetrics()`:
   ```java
   this.avgQueueWaitingTime = (getExecutedTasksCount() > 0)
       ? getTotalWaitingTime() / getExecutedTasksCount()
       : 0.0;
   ```

2. Note: `getTotalWaitingTime()` and `getExecutedTasksCount()` must be accessible. Check whether they are already `public` or `protected` in `SimLog`. If `protected`, they are accessible from `ResearchSimLog` directly. If package-private, expose them.

3. Update `ResearchMetricsExporter.SUMMARY_HEADER` to include `"Average queue waiting time (s)"`.

4. Update `ResearchMetricsExporter.writeSummaryRow()` to append the new column value.

### Tests

`QueueWaitingTimeTest.java`:
```
testAvgQueueWaitingNonNegative()

testAvgQueueWaitingMatchesExistingCsv()
  — Read "Average waiting time (s)" from Sequential_simulation.csv
  — Assert: abs(researchSimLog.avgQueueWaitingTime - existingValue) < 1e-6
  — This verifies ResearchSimLog is reading from the same source as SimLog
  — and NOT double-counting or computing differently

testQueueWaitingZeroWhenNoTasksExecuted()
  — Defensive: if executedTasksCount == 0, avgQueueWaitingTime == 0 (no NaN, no exception)
```

### Expected output
Updated `_research_summary.csv`:
```
Orchestration architecture,...,Throughput (tasks/min),Average queue waiting time (s)
CLOUD_ONLY,...,142.8,0.0031
```

### Acceptance criteria
- `avgQueueWaitingTime` in research CSV matches `"Average waiting time (s)"` in existing CSV to 6 decimal places.
- No new information computed — purely surfacing existing data.

---

## Milestone 5 — Add Latency

### Objective
Add latency metrics to the research summary:
1. Average total end-to-end latency (NEW — requires new accumulation).
2. Average network latency (NEW — requires new accumulation).
3. Average queue waiting (already done in M4).
4. Average CPU computation time (already in SimLog as `totalExecutionTime`).
5. Average cold-start time (only if registry enabled — requires `ResearchNetworkModel`).

### Relevant existing files
- `SimLog.java` — `getTasksExecutionInfos(task)` is the hook point; `totalExecutionTime` already accumulated
- `DefaultNetworkModel.java` — `addContainer()` L57, `containerDownloadFinished()` L231
- `TaskAbstract.java` — `getActualNetworkTime()`, `getTotalDelay()`

### Files to modify
1. **`ResearchSimLog.java`**: Override `getTasksExecutionInfos(task)` to accumulate two new totals.
2. **`ResearchMetricsExporter.java`**: Add 3 new columns.
3. **`TraceSimulationThread.java`**: Configure `ResearchNetworkModel` if cold-start is needed.

### New files
**`ResearchNetworkModel.java`** — package `com.mechalikh.pureedgesim.network`

### `ResearchSimLog.java` additions for M5

```java
// New fields
private double totalNetworkTime   = 0.0;
private double totalEndToEndDelay = 0.0;

// Computed at end:
private double avgNetworkLatency  = 0.0;
private double avgCpuTime         = 0.0;
private double avgTotalLatency    = 0.0;

@Override
public void getTasksExecutionInfos(Task task) {
    super.getTasksExecutionInfos(task);                      // existing accumulation unchanged
    totalNetworkTime   += task.getActualNetworkTime();       // NEW
    totalEndToEndDelay += task.getTotalDelay();              // NEW
}

// In computeSummaryMetrics():
int n = getExecutedTasksCount();
this.avgNetworkLatency = (n > 0) ? totalNetworkTime   / n : 0.0;
this.avgCpuTime        = (n > 0) ? getTotalExecutionTime() / n : 0.0;  // reuses existing
this.avgTotalLatency   = (n > 0) ? totalEndToEndDelay / n : 0.0;
```

### `ResearchNetworkModel.java` — cold-start timing

```java
public class ResearchNetworkModel extends DefaultNetworkModel {

    // Tracks in-progress container downloads: task → start clock
    private final Map<Task, Double> containerStartTimes = new HashMap<>();

    public ResearchNetworkModel(SimulationManager simulationManager) {
        super(simulationManager);
    }

    @Override
    protected void addContainer(Task task) {
        // Record start time BEFORE delegating to super (which schedules the transfer)
        containerStartTimes.put(task, getSimulation().clock());
        super.addContainer(task);
    }

    @Override
    protected void containerDownloadFinished(TransferProgress transfer) {
        Task task = transfer.getTask();
        Double startTime = containerStartTimes.remove(task);
        if (startTime != null) {
            double duration = getSimulation().clock() - startTime;
            SimLog logger = simulationManager.getSimulationLogger();
            if (logger instanceof ResearchSimLog) {
                ((ResearchSimLog) logger).recordContainerDownload(duration);
            }
        }
        super.containerDownloadFinished(transfer);  // existing: schedules EXECUTE_TASK
    }
}
```

Add to `ResearchSimLog`:
```java
// Cold-start fields
private double totalContainerDownloadTime = 0.0;
private int    containerTasksCount        = 0;
private double avgColdStartTime           = 0.0;

public void recordContainerDownload(double duration) {
    totalContainerDownloadTime += duration;
    containerTasksCount++;
}

// In computeSummaryMetrics():
this.avgColdStartTime = (containerTasksCount > 0)
    ? totalContainerDownloadTime / containerTasksCount : 0.0;
```

Wire `ResearchNetworkModel` in `TraceSimulationThread.createSimLog()` region OR by setting:
```java
sim.setCustomNetworkModel(ResearchNetworkModel.class);
```
in the entry point. This uses the existing `setCustomNetworkModel` mechanism — no wiring code changes needed.

### Tests

`LatencyMetricTest.java`:
```
testAvgCpuTimeMatchesExistingCsv()
  — Assert researchSimLog.avgCpuTime ≈ "Average execution delay (s)" in existing CSV
  — (within floating-point tolerance)

testAvgTotalLatencyGeComponentSum()
  — Assert: avgTotalLatency >= avgNetworkLatency + avgQueueWaiting + avgCpuTime - 1e-9
  — (total >= sum of measured components; equality holds if all phases measured)

testAvgTotalLatencyNotZero()
  — Sanity: total latency > 0 if any tasks were executed

testNetworkLatencyNotNegative()

testColdStartZeroWhenRegistryDisabled()
  — When SimulationParameters.enableRegistry == false:
  — Assert avgColdStartTime == 0.0 AND containerTasksCount == 0

testLatencyComponentSumApproxTotalLatency()
  — avgQueueWaiting + avgNetworkLatency + avgCpuTime ≈ avgTotalLatency
  — Allow tolerance (cold start is included in networkLatency when registry enabled)
```

### Expected output
Updated `_research_summary.csv` (additional columns):
```
...,Average total latency (s),Average latency: network (s),Average latency: queue waiting (s),Average latency: computation (s),Average latency: cold start (s)
```

### Acceptance criteria
- `avgCpuTime` agrees with `"Average execution delay (s)"` to 6 decimal places.
- `avgQueueWaiting` agrees with `"Average waiting time (s)"` to 6 decimal places.
- `avgTotalLatency >= avgNetworkLatency` (network is a component of total).
- Cold-start = 0 when registry disabled.
- All latency values >= 0.

---

## Milestone 6 — Add Failure Rate

### Objective
Compute and report task failure rate as an explicit percentage in the research summary. Identify the exact denominator (`tasksSent`).

### Relevant existing files
- `SimLog.java` — `tasksSent`, `tasksFailed` (both `protected int`)
- `DefaultSimulationManager.java` — confirms `tasksFailed` is NOT incremented for phase-0 failures before `incrementTasksSent()` (i.e., `tasksSent` counts only tasks that passed phase 0)

**Confirmed code path** (from `DefaultSimulationManager.sendTaskToOrchestrator()`):
```java
if (taskFailed(task, 0))      // phase 0: device dead → incrementFailedBecauseDeviceDead, return
    return;
simLog.incrementTasksSent();  // only reached if passed phase 0
```
Therefore: `tasksSent` = tasks that entered the network system.
`tasksFailed` = tasks failing at phases 1, 2, or 3 (post-submission failures only? No — actually `tasksFailed` is accumulated for phase 0 too via `incrementTasksFailed()` called from `incrementFailedBecauseDeviceDead()`. But `tasksSent` is NOT incremented for phase 0.

**Important clarification needed**: Check whether `tasksFailed` includes phase-0 failures.
- If yes: `tasksFailed` > `tasksSent` is possible → failure rate > 100% → BUG.
- The correct denominator must be verified by running a simulation and checking: `tasksSent + notGeneratedBecDeviceDead + phase0failures = generatedTasksCount`.

**Recommended denominator**: `tasksSent` (phase-0 failures excluded from both numerator and denominator), yielding the failure rate among tasks that actually entered the network.

### Files to modify
- **`ResearchSimLog.java`**: Add `failureRatePct` field and computation.
- **`ResearchMetricsExporter.java`**: Add column.

### Implementation work

1. Verify via a debug log or test that `tasksFailed` does/does not include phase-0 failures. If it does, the failure rate calculation must use `tasksFailed - tasksFailedBeacauseDeviceDead_phase0`. The safest approach is to count ONLY tasks failing at phases 1–3 as `tasksFailed_submitted`.

   Since PureEdgeSim does not separately track phase-0 vs. phase-1+ device-dead failures, the implementation must use:
   ```java
   // Conservative: use tasksSent as denominator, tasksFailed as numerator
   // This is correct if tasksFailed ONLY includes tasks that were sent (phases 1-3)
   // If tasksFailed also includes phase-0, use:
   //   denominator = generatedTasksCount; numerator = tasksFailed + notGeneratedBecDeviceDead
   // but this changes the metric semantics.
   ```

   **Decision**: Use `tasksSent` as denominator, `tasksFailed` as numerator.
   The test will verify that `tasksFailed <= tasksSent` (assert in test). If this fails, revisit phase-0 counting.

2. In `computeSummaryMetrics()`:
   ```java
   int sent = getTasksSent();
   this.failureRatePct = (sent > 0)
       ? (getTasksFailed() * 100.0 / sent)
       : 0.0;
   ```

### Tests

`FailureRateTest.java`:
```
testFailureRateRange()
  — Assert: 0.0 <= failureRatePct <= 100.0

testFailureRateDenominatorIsTasksSent()
  — Assert: tasksFailed <= tasksSent (i.e., denominator is correct)
  — If this fails: tasksFailed includes phase-0 failures; must revisit denominator

testFailureRateZeroWhenNoFailures()
  — Configure simulation where all tasks should succeed (generous latency, no mobility)
  — Assert failureRatePct ≈ 0

testFailureRateConsistentWithExistingCsv()
  — From existing CSV: succeeded = "Tasks successfully executed", generated = "Number of generated tasks"
  — Cross-check: failureRatePct ≈ (1 - succeeded / tasksSent) * 100
  — (note: submitted != generated if some not generated due to dead devices)
```

### Expected output
Updated `_research_summary.csv` (additional column):
```
...,Task failure rate (%)
...,12.34
```

### Acceptance criteria
- `failureRatePct` between 0 and 100.
- `tasksFailed <= tasksSent` verified by test.
- Value cross-checks with existing CSV counts.

---

## Milestone 7 — Add Failure Categories

### Objective
Split the merged `tasksFailedRessourcesUnavailable` counter into `tasksFailedOOM` and `tasksFailedNoDestination`. Report all five failure categories (Deadline, Battery, OOM, Network, Mobility) with counts and percentages.

### Relevant existing files
- `SimLog.java` — `incrementTasksFailedLackOfRessources(Task task)`
- `Task.java` / `TaskAbstract.java` — `getFailureReason()` returns `Task.FailureReason` enum
- `DefaultSimulationManager.java` — calls `incrementTasksFailedLackOfRessources` in two places:
  1. When orchestrator returns NULL (NO_OFFLOADING_DESTINATIONS) — in `sendFromOrchToDestination()`
  2. When OOM/storage check fails (INSUFFICIENT_RESOURCES) — in `taskFailed(task, 2)`

### Files to modify
**`ResearchSimLog.java`**: Override `incrementTasksFailedLackOfRessources(Task task)`.

### Implementation work

1. Add new fields:
   ```java
   private int tasksFailedOOM           = 0;
   private int tasksFailedNoDestination = 0;
   ```

2. Override the method:
   ```java
   @Override
   public void incrementTasksFailedLackOfRessources(Task task) {
       // Split before calling super (super increments the merged counter)
       Task.FailureReason reason = task.getFailureReason();
       if (reason == Task.FailureReason.INSUFFICIENT_RESOURCES) {
           tasksFailedOOM++;
       } else {
           // NO_OFFLOADING_DESTINATIONS
           tasksFailedNoDestination++;
       }
       super.incrementTasksFailedLackOfRessources(task);  // existing counter unchanged
   }
   ```

3. In `computeSummaryMetrics()`, add invariant validation:
   ```java
   // Invariant 1: split must equal merged
   assert tasksFailedOOM + tasksFailedNoDestination == getTasksFailedRessourcesUnavailable()
       : "Split failure counts don't sum to merged counter";

   // Invariant 2: all categories sum to total
   int sumCategories = getTasksFailedLatency()
       + getTasksFailedBeacauseDeviceDead()
       + getTasksFailedMobility()
       + tasksFailedOOM
       + tasksFailedNoDestination;
   assert sumCategories == getTasksFailed()
       : "Category sum " + sumCategories + " != tasksFailed " + getTasksFailed();
   ```

4. Compute percentages:
   ```java
   int failed = getTasksFailed();
   // Avoid division by zero
   double inv = (failed > 0) ? 100.0 / failed : 0.0;
   deadlinePct    = getTasksFailedLatency()             * inv;
   batteryPct     = getTasksFailedBeacauseDeviceDead()  * inv;
   mobilityPct    = getTasksFailedMobility()            * inv;
   oomPct         = tasksFailedOOM                      * inv;
   networkPct     = tasksFailedNoDestination            * inv;
   ```

5. Update `ResearchMetricsExporter` with 10 new columns (5 counts + 5 percentages).

### Tests

`FailureCategoryTest.java`:
```
testOomPlusNoDestinationEqualsMergedCounter()
  — Assert: tasksFailedOOM + tasksFailedNoDestination == tasksFailedRessourcesUnavailable

testAllCategoriesSumToTasksFailed()
  — Assert: deadline + battery + mobility + oom + network == tasksFailed

testCategoryPercentagesSumTo100()
  — Assert: deadlinePct + batteryPct + mobilityPct + oomPct + networkPct ≈ 100.0
  — (within 0.01% floating point tolerance)
  — Special case: if tasksFailed == 0, all percentages == 0 (not 100)

testCategoryCountsNonNegative()
  — All counts >= 0

testExistingDeadlineCountMatchesCsv()
  — Assert tasksFailedLatency == "Tasks failed (delay)" from existing CSV

testExistingBatteryCountMatchesCsv()
  — Assert tasksFailedBeacauseDeviceDead == "Tasks failed (device dead)" from existing CSV

testExistingMobilityCountMatchesCsv()
  — Assert tasksFailedMobility == "Tasks failed (mobility)" from existing CSV

testOomPlusNetworkMatchesMergedCsvColumn()
  — Assert tasksFailedOOM + tasksFailedNoDestination ==
           "Task not executed (No resources available or long waiting time)" from existing CSV
```

### Expected output
Updated `_research_summary.csv` additions:
```
Failures: Deadline,Failures: Battery,Failures: OOM,Failures: Network,Failures: Mobility,Failures: Deadline (%),Failures: Battery (%),Failures: OOM (%),Failures: Network (%),Failures: Mobility (%)
```

### Acceptance criteria
- Both invariant assertions pass (not just tests — the `assert` statements in `computeSummaryMetrics` must not throw).
- All new columns present in research CSV.
- Deadline/Battery/Mobility counts exactly match existing CSV columns.
- OOM + Network counts sum to the existing merged column.

---

## Milestone 8 — Add Time-Series Metrics

### Objective
Produce `research_timeseries.csv` with one row per time window per simulation iteration, enabling temporal visualization of throughput, queue wait, task completion, and failure behavior.

### Relevant existing files
- `SimLog.java` — `onTaskCompleted(Task, double)` no-op hook (added in M2)
- `DefaultSimulationManager.java` — calls `simLog.onTaskCompleted(task, clock)` at `RESULT_RETURN_FINISHED` (added in M2)

### Files to modify
- **`ResearchSimLog.java`**: Override `onTaskCompleted()` to update time-series buckets. Also add failure bucket updates inside the overridden failure methods.
- **`ResearchMetricsExporter.java`**: Add `writeTimeSeriesRows(ResearchSimLog log)`.

### Implementation work

#### 1. Time-series data structures in `ResearchSimLog`

```java
// All initialized in constructor
private final TreeMap<Integer, Integer> throughputBuckets     = new TreeMap<>();
private final TreeMap<Integer, Integer> completionBuckets     = new TreeMap<>();
private final TreeMap<Integer, Integer> failureBuckets        = new TreeMap<>();
private final TreeMap<Integer, Double>  waitSumBuckets        = new TreeMap<>();
private final TreeMap<Integer, Integer> waitCountBuckets      = new TreeMap<>();
```

Use `TreeMap` (not `HashMap`) so iteration order is sorted by bucket index at write time.

#### 2. Override `onTaskCompleted()` in `ResearchSimLog`

```java
@Override
public void onTaskCompleted(Task task, double clock) {
    // Called only for tasks that PASSED phase-3 check (SUCCESS only)
    int bucket = (int)(clock / timeWindowSeconds);
    // This task is a success (taskFailed returned false before this call)
    throughputBuckets.merge(bucket, 1, Integer::sum);
    completionBuckets.merge(bucket, 1, Integer::sum);
    // Queue waiting time for this task
    double wait = task.getWatingTime();
    waitSumBuckets.merge(bucket,   wait, Double::sum);
    waitCountBuckets.merge(bucket, 1,    Integer::sum);
}
```

#### 3. Track failure time-series in failure methods

Override `incrementTasksFailed(Task task)` (the base method called by ALL failure sub-methods):
```java
@Override
public void incrementTasksFailed(Task task) {
    super.incrementTasksFailed(task);
    // Also record in failure time-series using the task's completion clock
    // Problem: we don't have the clock here. Use simulationManager.getSimulation().clock()
    double clock = simulationManager.getSimulation().clock();
    int bucket = (int)(clock / timeWindowSeconds);
    failureBuckets.merge(bucket, 1, Integer::sum);
    completionBuckets.merge(bucket, 1, Integer::sum);  // failures also count as completions
}
```

Note: `simulationManager` is already available in `SimLog` (set via `initialize()`).

#### 4. `ResearchMetricsExporter.writeTimeSeriesRows()`

```java
public static void writeTimeSeriesRows(ResearchSimLog log) {
    String fileName = log.getFileName("_research_timeseries.csv");
    boolean writeHeader = !new File(fileName).exists();
    // Determine max bucket
    int maxBucket = /* max key across all bucket maps */ ...;
    try (PrintWriter pw = new PrintWriter(new FileWriter(fileName, true))) {
        if (writeHeader) pw.println(TIME_SERIES_HEADER);
        for (int b = 0; b <= maxBucket; b++) {
            int successes = log.getThroughputBuckets().getOrDefault(b, 0);
            int completions = log.getCompletionBuckets().getOrDefault(b, 0);
            int failures   = log.getFailureBuckets().getOrDefault(b, 0);
            double waitSum  = log.getWaitSumBuckets().getOrDefault(b, 0.0);
            int    waitCnt  = log.getWaitCountBuckets().getOrDefault(b, 0);
            double avgWait  = (waitCnt > 0) ? waitSum / waitCnt : 0.0;
            double winStart = b * log.getTimeWindowSeconds();
            double winEnd   = winStart + log.getTimeWindowSeconds();
            double tputPerMin = successes / (log.getTimeWindowSeconds() / 60.0);
            pw.printf("%s,%s,%d,%d,%.1f,%.1f,%.4f,%d,%d,%.6f%n",
                log.getOrchArchitecture(), log.getOrchAlgorithm(), log.getDevicesCount(),
                b, winStart, winEnd, tputPerMin, completions, failures, avgWait);
        }
    } catch (IOException e) {
        SimLog.println("ResearchMetricsExporter - Failed to write time series: " + e.getMessage());
    }
}
```

Time-series CSV header:
```
Orchestration architecture,Orchestration algorithm,Edge devices count,
Window index,Window start (s),Window end (s),
Throughput in window (tasks/min),Completions in window,Failures in window,
Average queue waiting in window (s)
```

### Tests

`TimeSeriesMetricTest.java`:
```
testTimeSeriesCsvCreated()

testWindowIndicesContiguous()
  — Read research_timeseries.csv
  — Assert window indices go from 0 to maxBucket with no gaps (zeros filled for empty windows)

testSuccessPlusFailureEqualsCompletions()
  — For each row: throughput * (windowSeconds/60) + failures == completions
  — (within rounding tolerance of 1 task)

testThroughputBucketsSumEqualsOverallSucceeded()
  — Sum of all successes across buckets == tasksSent - tasksFailed

testFailureBucketsSumEqualsTasksFailed()
  — Sum of all failures across buckets == tasksFailed

testNoNanOrInfinityInTimeSeries()
  — Parse all numeric cells; assert all are finite

testTimeSeriesWindowSizeRespected()
  — winEnd - winStart == timeWindowSeconds for all rows
```

### Expected output
```
PureEdgeSim/output/<startTime>/_research_timeseries.csv    ← NEW
```

Example rows:
```
Window index,Window start (s),Window end (s),Throughput (tasks/min),Completions,Failures,Avg queue waiting (s)
0,0.0,60.0,150.3,251,15,0.00120
1,60.0,120.0,142.1,237,18,0.00134
```

### Acceptance criteria
- `research_timeseries.csv` present with correct column count.
- Sum of throughput buckets × window size ≈ total successful tasks.
- Sum of failure buckets == `tasksFailed`.
- No window index gaps (zeros filled).

---

## Milestone 9 — Preserve Existing Metrics (Regression)

### Objective
Formally verify that all changes from M1–M8 have not altered the existing `Sequential_simulation.csv` or `.txt` output in any way.

### Files to modify
None.

### New files
`src/test/java/com/mechalikh/pureedgesim/metrics/RegressionTest.java`

### Implementation work

1. Run `ExampleTraceReplay` with `ResearchSimLog` wired in.
2. Collect `Sequential_simulation.csv` output.
3. Compare against `baseline_output.csv` (committed in M1).

The comparison must account for:
- Same column headers (exact string match).
- Same column count.
- Same numeric values within floating-point tolerance (1e-9).
- The `_research_summary.csv` and `_research_timeseries.csv` are ADDITIONAL files, not replacements.

### Tests

`RegressionTest.java`:
```
testExistingCsvColumnsUnchanged()
  — Parse headers from latest run vs baseline_output.csv
  — Assert identical column names in identical order

testExistingCsvValuesUnchanged()
  — For each numeric cell: assert abs(newValue - baselineValue) < 1e-9

testExistingTxtLogUnchanged()
  — Assert Sequential_simulation.txt contains the same summary statistics
  — (Check key lines: "Tasks successfully executed:", "Tasks failed (delay):", etc.)

testNoExistingFilesDeleted()
  — Assert Sequential_simulation.csv and Sequential_simulation.txt both exist

testResearchFilesAreAdditional()
  — Assert _research_summary.csv and _research_timeseries.csv ALSO exist
  — (confirming they are added alongside, not replacing, existing files)
```

### Expected output
All regression tests pass.

### Acceptance criteria
- `mvn test -Dtest=RegressionTest` exits 0.
- Zero numeric differences in any existing CSV cell.

---

## Milestone 10 — End-to-End Validation

### Objective
Run a representative simulation and verify that all metrics are internally consistent. This is a correctness validation, not a code change milestone.

### New files
- `src/test/java/com/mechalikh/pureedgesim/metrics/EndToEndValidationTest.java`
- `docs/additional-metrics/validation-report.md` (manual: document results of validation run)

### Validation assertions

#### Task accounting identity
```
tasksSent = succeeded + tasksFailed_at_phases_1_2_3
generatedTasksCount = tasksSent + notGeneratedBecDeviceDead + tasksFailed_at_phase_0
```
Since we cannot separate phase-0 battery from phases 1-2 battery in the current counters, validate:
```
tasksSent + notGeneratedBecDeviceDead <= generatedTasksCount
```

#### Failure category consistency
```
assert deadline + battery + mobility + oom + network == tasksFailed
assert oom + network == tasksFailedRessourcesUnavailable
assert abs(sum_of_pcts - 100.0) < 0.01 OR tasksFailed == 0
```

#### Latency component bounds
```
assert avgNetworkLatency >= 0
assert avgQueueWaiting   >= 0
assert avgCpuTime        >= 0
assert avgColdStart      >= 0
assert avgTotalLatency   >= (avgQueueWaiting + avgCpuTime) - 1e-6
// (total includes network; total >= queue + cpu; network may include cold-start)
```

#### Time-series consistency
```
sum_of_throughput_buckets == tasksSent - tasksFailed
sum_of_failure_buckets    == tasksFailed
sum_of_completion_buckets == tasksSent
// (since onTaskCompleted fires for success, and incrementTasksFailed fires for failure,
//  and tasksSent = success + failures-phases-1-2-3)
```

#### Throughput sanity
```
assert throughputPerMinute >= 0
if (tasksSent - tasksFailed > 0):
    assert throughputPerMinute > 0
```

### Tests in `EndToEndValidationTest.java`

```
testTaskAccountingIdentity()
testFailureCategoryConsistency()      (moved from M7 invariants into formal test)
testLatencyComponentBounds()
testTimeSeriesConsistency()
testThroughputSanity()
testResearchAndExistingCsvConsistency()
  — For columns present in both files (latency, waiting time), values must agree
testNoNanOrInfinityInSummary()
testNoNanOrInfinityInTimeSeries()
```

### Documentation
Write `docs/additional-metrics/validation-report.md` documenting:
- Which assertions were checked.
- Actual values from the validation run.
- Any known limitations (e.g., cold-start = 0 in CLOUD_ONLY config).

### Acceptance criteria
- All 8 validation assertions pass.
- `validation-report.md` documents one complete simulation run.
- `mvn test -Dtest=EndToEndValidationTest` exits 0.

---

## Summary of All Changes

### Files modified in existing codebase (minimal)

| File | Change | Milestone |
|---|---|---|
| `SimLog.java` | +1 no-op public method `onTaskCompleted()` | M2 |
| `DefaultSimulationManager.java` | +1 call `simLog.onTaskCompleted()` at RESULT_RETURN_FINISHED | M2 |
| `SimulationThread.java` | +1 protected factory method `createSimLog()` | M3 |
| `TraceSimulationThread.java` | Override `createSimLog()` to return ResearchSimLog | M3 |

### New files created

| File | Package | Milestone |
|---|---|---|
| `ResearchSimLog.java` | `simulationmanager` | M3 (grows through M8) |
| `ResearchMetricsExporter.java` | `simulationmanager` | M3 (grows through M8) |
| `ResearchNetworkModel.java` | `network` | M5 |
| `BaselineMetricsTest.java` | `test` | M1 |
| `LifecycleHookTest.java` | `test` | M2 |
| `ThroughputMetricTest.java` | `test` | M3 |
| `QueueWaitingTimeTest.java` | `test` | M4 |
| `LatencyMetricTest.java` | `test` | M5 |
| `FailureRateTest.java` | `test` | M6 |
| `FailureCategoryTest.java` | `test` | M7 |
| `TimeSeriesMetricTest.java` | `test` | M8 |
| `RegressionTest.java` | `test` | M9 |
| `EndToEndValidationTest.java` | `test` | M10 |

### New output files produced

| File | Milestone |
|---|---|
| `docs/additional-metrics/baseline_output.csv` | M1 |
| `output/<run>/_research_summary.csv` | M3 (grows through M7) |
| `output/<run>/_research_timeseries.csv` | M8 |
| `docs/additional-metrics/validation-report.md` | M10 |

---

## Inter-Milestone Dependencies

```
M1 ──► M2 ──► M3 ──► M4 ──► M5 ──► M6 ──► M7 ──► M8 ──► M9 ──► M10
              │
              └── ResearchSimLog grows incrementally M3→M8
                  ResearchMetricsExporter grows incrementally M3→M8
                  ResearchNetworkModel created fresh at M5
```

No milestone can be skipped. Each milestone's acceptance criteria must be verified before starting the next.

