# PureEdgeSim Orchestration Architecture Analysis

## Overview

PureEdgeSim is a Java-based discrete-event simulation (DES) framework for evaluating Cloud, Edge (Fog), and Mist (device-level) computing environments. It is built around a clean plugin/strategy pattern — every major module (orchestrator, task generator, network model, mobility model, computing node) can be swapped out by providing a custom class.

---

## 1. How the Simulation Starts

**Entry point chain:**

```
main()
  └─ new Simulation().launchSimulation()                    [Simulation.java]
        └─ loadScenarios()                                  [builds Scenario list]
        └─ new SimulationThread(this, 0, 1).startSimulation()
              └─ new PureEdgeSim()                          [DES engine]
              └─ new DefaultSimulationManager(...)          [via reflection]
              └─ loadModels(simulationManager)              [wires all modules]
                    ├─ NetworkModel constructor             [via reflection]
                    ├─ new DataCentersManager(...)          [generates nodes]
                    ├─ TaskGenerator.generate()             [pre-generates all tasks]
                    └─ Orchestrator constructor             [via reflection]
              └─ simulationManager.startSimulation()
                    └─ simulation.start()                   [DES event loop begins]
```

Key files:
- [Simulation.java](file:///home/cotton/Projects/ML/Thesis/PureEdgeSim/PureEdgeSim/com/mechalikh/pureedgesim/simulationmanager/Simulation.java)
- [SimulationThread.java](file:///home/cotton/Projects/ML/Thesis/PureEdgeSim/PureEdgeSim/com/mechalikh/pureedgesim/simulationmanager/SimulationThread.java)
- [PureEdgeSim.java](file:///home/cotton/Projects/ML/Thesis/PureEdgeSim/PureEdgeSim/com/mechalikh/pureedgesim/simulationengine/PureEdgeSim.java) — the DES engine

---

## 2. How Tasks Are Created

All tasks are **pre-generated before simulation starts**, not created on-the-fly during the event loop.

**Class:** [DefaultTaskGenerator.java](file:///home/cotton/Projects/ML/Thesis/PureEdgeSim/PureEdgeSim/com/mechalikh/pureedgesim/taskgenerator/DefaultTaskGenerator.java)

**Process:**
1. `SimulationThread.loadModels()` calls `tasksGenerator.generate()`.
2. `DefaultTaskGenerator.generate()` iterates over all edge devices and all application types from `applications.xml`.
3. For each device × application, `generateTasksForDevice()` creates one task per simulated minute, with a random offset within that minute (0–15 s). This is done by calling `insert(time, app, dev)`.
4. `insert()` constructs `DefaultTask` objects with: task length (MI), file size (bits), output size (bits), container size (bits), max latency (s), application ID, and the originating edge device (`ComputingNode`).
5. All tasks are stored in a `FutureQueue<Task>` (sorted by simulation time) and handed to `SimulationManager.setTaskList()`.

**Key task properties observable at decision time (see [Task.java](file:///home/cotton/Projects/ML/Thesis/PureEdgeSim/PureEdgeSim/com/mechalikh/pureedgesim/taskgenerator/Task.java)):**

| Property | Getter | Meaning |
|---|---|---|
| `id` | `getId()` | Unique task ID |
| `length` | `getLength()` | Compute load (MI) |
| `fileSizeInBits` | `getFileSizeInBits()` | Uplink payload (bits) |
| `outputSizeInBits` | `getOutputSizeInBits()` | Downlink result (bits) |
| `containerSizeInMBytes` | `getContainerSizeInMBytes()` | Storage/RAM needed |
| `maxLatency` | `getMaxLatency()` | Deadline (seconds) |
| `applicationID` | `getApplicationID()` | App type index |
| `type` | `getType()` | App type string |
| `edgeDevice` | `getEdgeDevice()` | Originating device node |
| `orchestrator` | `getOrchestrator()` | Orchestrator node (if distributed) |
| `time` | `getTime()` | Scheduled generation time |

---

## 3. How Tasks Reach the Orchestration/Scheduling Stage

The DES engine drives everything through events. The `DefaultSimulationManager` is the central coordinator.

**Event flow in `DefaultSimulationManager.processEvent()`** ([DefaultSimulationManager.java](file:///home/cotton/Projects/ML/Thesis/PureEdgeSim/PureEdgeSim/com/mechalikh/pureedgesim/simulationmanager/DefaultSimulationManager.java)):

```
onSimulationStart()
  └─ schedule(SEND_TO_ORCH, task) for first batch
  └─ schedule(NEXT_BATCH) for rest

NEXT_BATCH event fires
  └─ schedule next batch of SEND_TO_ORCH events

SEND_TO_ORCH event fires
  └─ sendTaskToOrchestrator(task)
        └─ task.setOrchestrator(task.getEdgeDevice().getOrchestrator())
        └─ scheduleNow(networkModel, SEND_REQUEST_FROM_DEVICE_TO_ORCH, task)
              [Network model simulates uplink latency]
              └─ scheduleNow(simulationManager, SEND_TASK_FROM_ORCH_TO_DESTINATION, task)

SEND_TASK_FROM_ORCH_TO_DESTINATION event fires  ← ORCHESTRATION HAPPENS HERE
  └─ sendFromOrchToDestination(task)
        └─ edgeOrchestrator.orchestrate(task)    ← THE KEY CALL
        └─ scheduleNow(networkModel, SEND_REQUEST_FROM_ORCH_TO_DESTINATION, task)

EXECUTE_TASK event fires
  └─ task.getOffloadingDestination().submitTask(task)
        [DefaultComputingNode queues and executes the task]
        └─ schedule(this, task.length/mipsPerCore, EXECUTION_FINISHED, task)

EXECUTION_FINISHED event fires (in DefaultComputingNode)
  └─ scheduleNow(simulationManager, TRANSFER_RESULTS_TO_ORCH, task)

TRANSFER_RESULTS_TO_ORCH event fires
  └─ sendResultsToOchestrator(task)
        └─ scheduleNow(networkModel, SEND_RESULT_TO_ORCH, task)
              [Network simulates result downlink latency]
              └─ scheduleNow(simulationManager, RESULT_RETURN_FINISHED, task)

RESULT_RETURN_FINISHED event fires
  └─ edgeOrchestrator.resultsReturned(task)      ← FEEDBACK/REWARD HOOK
  └─ tasksCount++
```

> [!IMPORTANT]
> The orchestration call is **synchronous and blocking inside the DES event loop**. The entire simulation pauses at this point waiting for `orchestrate(task)` to return. This is the critical constraint for a Python interface.

---

## 4. Which Classes/Methods Make the Orchestration Decision

### The Extension Hierarchy

```
SimEntity (abstract)                                [SimEntity.java]
  └─ Orchestrator (abstract)                        [Orchestrator.java]
        └─ DefaultOrchestrator                      [DefaultOrchestrator.java]
              └─ Example8FuzzyLogicOrchestrator     [examples/]
              └─ YOUR_CUSTOM_ORCHESTRATOR
```

### Key Abstract Class: [Orchestrator.java](file:///home/cotton/Projects/ML/Thesis/PureEdgeSim/PureEdgeSim/com/mechalikh/pureedgesim/taskorchestrator/Orchestrator.java)

| Method | Signature | Role |
|---|---|---|
| `orchestrate` | `public void orchestrate(Task task)` | Entry point called by SimManager. Calls `assignTaskToComputingNode`. |
| `findComputingNode` | `protected abstract int findComputingNode(String[] layers, Task task)` | **THE method to implement.** Returns index into `nodeList`, or `-1` for failure. |
| `assignTaskToComputingNode` | `protected void assignTaskToComputingNode(Task task, String[] layers)` | Takes the index, gets the node, calls `task.setOffloadingDestination(node)`. |
| `resultsReturned` | `public abstract void resultsReturned(Task task)` | Called when task completes. Use for RL reward/feedback. |
| `offloadingIsPossible` | `protected boolean offloadingIsPossible(Task task, ComputingNode node, ...)` | Utility — checks architecture, range, liveness constraints. |
| `initialize` | `public void initialize()` | Called in constructor; sets `nodeList` and `architectureLayers` based on scenario arch. |

### The Decision: What `findComputingNode` Must Return

- Return an **index into `this.nodeList`** (the `List<ComputingNode>` set by `initialize()`).
- Return `-1` to indicate no suitable node → task fails with `NO_OFFLOADING_DESTINATIONS`.

---

## 5. How Available Devices/Nodes Are Represented

Nodes are accessed via `this.nodeList` inside an `Orchestrator` subclass, which is set during `initialize()` according to the `architectureName` (from simulation parameters).

### Node Lists (from [ComputingNodesGenerator.java](file:///home/cotton/Projects/ML/Thesis/PureEdgeSim/PureEdgeSim/com/mechalikh/pureedgesim/datacentersmanager/ComputingNodesGenerator.java))

| Architecture | `nodeList` used |
|---|---|
| `MIST_ONLY` | `getMistOnlyListSensorsExcluded()` — edge devices only |
| `EDGE_ONLY` | `getEdgeOnlyList()` — edge servers only |
| `CLOUD_ONLY` | `getCloudOnlyList()` — cloud VMs only |
| `EDGE_AND_CLOUD` | `getEdgeAndCloudList()` |
| `MIST_AND_CLOUD` | `getMistAndCloudListSensorsExcluded()` |
| `MIST_AND_EDGE` | `getMistAndEdgeListSensorsExcluded()` |
| `ALL` | `getAllNodesListSensorsExcluded()` |

### Key Properties of Each `ComputingNode` (see [ComputingNode.java](file:///home/cotton/Projects/ML/Thesis/PureEdgeSim/PureEdgeSim/com/mechalikh/pureedgesim/datacentersmanager/ComputingNode.java))

| Property | Getter | Meaning |
|---|---|---|
| `id` | `getId()` | Node ID |
| `type` | `getType()` | `CLOUD`, `EDGE_DATACENTER`, or `EDGE_DEVICE` |
| `totalMipsCapacity` | `getTotalMipsCapacity()` | Total MIPS |
| `mipsPerCore` | `getMipsPerCore()` | MIPS per core |
| `numberOfCPUCores` | `getNumberOfCPUCores()` | Core count |
| `availableStorage` | `getAvailableStorage()` | Free storage (MB) |
| `availableRam` | `getAvailableRam()` | Free RAM (MB) |
| `ramCapacity` | `getRamCapacity()` | Total RAM (MB) |
| `avgCpuUtilization` | `getAvgCpuUtilization()` | Avg CPU % since start |
| `currentCpuUtilization` | `getCurrentCpuUtilization()` | Instantaneous CPU % |
| `isIdle` | `isIdle()` | No task running |
| `isDead` | `isDead()` | Battery depleted (edge devices) |
| `isSensor` | `isSensor()` | No compute capability |
| `tasksQueue` | `getTasksQueue()` | Pending tasks |
| `mobilityModel` | `getMobilityModel()` | Current location, speed |
| `energyModel` | `getEnergyModel()` | Energy consumption |
| `isOrchestrator` | `isOrchestrator()` | Acts as orchestrator node |

---

## 6. How a Task Is Assigned to a Node

The full assignment sequence inside `Orchestrator.assignTaskToComputingNode()`:

```java
int nodeIndex = findComputingNode(architectureLayers, task);  // your algorithm runs here
if (nodeIndex != -1) {
    ComputingNode node = nodeList.get(nodeIndex);
    checkComputingNode(node);                      // throws if sensor selected
    task.setOffloadingDestination(node);           // assignment!
    task.getEdgeDevice().setApplicationPlacementLocation(node);  // caches for future tasks
}
```

If `nodeIndex == -1`, back in `DefaultSimulationManager.sendFromOrchToDestination()`:
```java
if (task.getOffloadingDestination() == ComputingNode.NULL) {
    task.setFailureReason(Task.FailureReason.NO_OFFLOADING_DESTINATIONS);
    simLog.incrementTasksFailedLackOfRessources(task);
    tasksCount++;
    return;
}
```

**Application placement caching:** Once a task is assigned, the edge device remembers the target node (`setApplicationPlacementLocation`). Future tasks from the same device skip orchestration entirely and go directly to that cached node — until a failure/mobility event clears it (`setApplicationPlaced(false)`).

---

## 7. What Happens After a Scheduling Decision

```
Orchestrator.orchestrate(task) returns
  ↓
DefaultSimulationManager.sendFromOrchToDestination() continues
  ↓
scheduleNow(networkModel, SEND_REQUEST_FROM_ORCH_TO_DESTINATION, task)
  ↓
[Network model: simulates WAN/LAN transfer time]
  ↓
scheduleNow(simulationManager, EXECUTE_TASK, task)
  ↓
task.getOffloadingDestination().submitTask(task)       [DefaultComputingNode]
  ├─ If core available: startExecution(task)
  │     └─ schedule(this, length/mipsPerCore, EXECUTION_FINISHED, task)
  └─ Else: add to tasksQueue (FIFO waiting)
  ↓
[Execution time elapses in simulation]
  ↓
executionFinished(event) in DefaultComputingNode
  └─ scheduleNow(simulationManager, TRANSFER_RESULTS_TO_ORCH, task)
  ↓
sendResultsToOchestrator(task)
  └─ scheduleNow(networkModel, SEND_RESULT_TO_ORCH, task)    [if offloaded]
  └─ scheduleNow(simulationManager, RESULT_RETURN_FINISHED, task)  [if local]
  ↓
edgeOrchestrator.resultsReturned(task)    ← RL reward/feedback point
  └─ (do whatever with task outcome here)
tasksCount++
```

Failure checks happen at 3 phases (0=pre-send, 1=at-orch, 2=at-dest, 3=result-return):
- Device/orchestrator died
- Insufficient storage/RAM at destination
- Destination out of range (mobility)
- Exceeded `maxLatency`

---

## 8. How Task Completion and Simulation Events Work

The DES engine ([PureEdgeSim.java](file:///home/cotton/Projects/ML/Thesis/PureEdgeSim/PureEdgeSim/com/mechalikh/pureedgesim/simulationengine/PureEdgeSim.java)) is a simple priority-queue-based loop:

```java
// PureEdgeSim.start()
entities.forEach(e -> e.onSimulationStart());    // all entities schedule first events
while (queue.notEmpty() && isRunning) {
    Event e = queue.first();
    time = e.getTime();
    e.getSimEntity().processEvent(e);            // entity handles event, may schedule more
}
entities.forEach(e -> e.onSimulationEnd());
```

**Event scheduling API** (from [SimEntity.java](file:///home/cotton/Projects/ML/Thesis/PureEdgeSim/PureEdgeSim/com/mechalikh/pureedgesim/simulationengine/SimEntity.java)):

| Method | When |
|---|---|
| `schedule(entity, delay, tag)` | Schedule event `delay` seconds from now |
| `schedule(entity, delay, tag, data)` | Same, with attached data object |
| `scheduleNow(entity, tag)` | Insert at front of queue (t=now) |
| `scheduleNow(entity, tag, data)` | Same, with data |

**Simulation termination:** `PRINT_LOG` event fires at `simulationDuration` seconds. It waits for 99%+ of tasks to finish (configurable), then calls `simulation.terminate()` → `isRunning = false`.

---

## 9. Key Extension Points / Interfaces

PureEdgeSim uses **reflection-based dependency injection** throughout. All custom classes are passed as `Class<?>` tokens to `Simulation`, then instantiated via `Constructor.newInstance()` with specific argument signatures.

### Primary Extension Points (via `Simulation` / `SimulationAbstract`)

| Setter | Default | Purpose |
|---|---|---|
| `setCustomEdgeOrchestrator(Class)` | `DefaultOrchestrator` | **The main target** — implement scheduling logic |
| `setCustomTaskGenerator(Class)` | `DefaultTaskGenerator` | Custom task workloads |
| `setCustomComputingNode(Class)` | `DefaultComputingNode` | Custom node behavior |
| `setCustomMobilityModel(Class)` | `DefaultMobilityModel` | Custom mobility |
| `setCustomNetworkModel(Class)` | `DefaultNetworkModel` | Custom network behavior |
| `setCustomSimulationManager(Class)` | `DefaultSimulationManager` | Custom event orchestration |
| `setCustomComputingNodesGenerator(Class)` | `DefaultComputingNodesGenerator` | Custom topology generation |
| `setCustomTopologyCreator(Class)` | `DefaultTopologyCreator` | Custom network topology |

### Required Constructor Signatures (must match exactly — used via reflection)

| Module | Required constructor |
|---|---|
| `Orchestrator` subclass | `(SimulationManager simulationManager)` |
| `TaskGenerator` subclass | `(SimulationManager simulationManager)` |
| `NetworkModel` subclass | `(SimulationManager simulationManager)` |
| `SimulationManager` subclass | `(SimLog, PureEdgeSim, int, int, Scenario)` |

### Methods to Implement in a Custom Orchestrator

```java
// Must extend Orchestrator
public class MyOrchestrator extends Orchestrator {

    public MyOrchestrator(SimulationManager simulationManager) {
        super(simulationManager);   // calls initialize() → sets nodeList
    }

    // REQUIRED: The decision function. Return index into nodeList, or -1.
    @Override
    protected int findComputingNode(String[] architectureLayers, Task task) {
        // Access task properties: task.getLength(), task.getEdgeDevice(), etc.
        // Access node properties: nodeList.get(i).getMipsPerCore(), etc.
        // Use offloadingIsPossible(task, node, architectureLayers) to filter
        return selectedIndex;  // or -1
    }

    // REQUIRED: Called when task completes. RL reward/feedback goes here.
    @Override
    public void resultsReturned(Task task) {
        // task.getStatus() == SUCCESS or FAILED
        // task.getTotalDelay(), task.getActualCpuTime(), etc.
    }

    // Optional: Handle custom DES events if needed
    @Override
    public void processEvent(Event e) { }
}
```

---

## 10. The Exact Point Where a Python Orchestrator Should Integrate

### The Single Intercept Point

**Location:** [Orchestrator.java L50–52](file:///home/cotton/Projects/ML/Thesis/PureEdgeSim/PureEdgeSim/com/mechalikh/pureedgesim/taskorchestrator/Orchestrator.java#L50-L52), specifically the abstract method `findComputingNode`.

```java
// Called once per task that needs a placement decision
protected abstract int findComputingNode(String[] architectureLayers, Task task);
```

This is where Python should provide the index of the selected node.

### The Feedback Point

**Location:** [Orchestrator.java L197](file:///home/cotton/Projects/ML/Thesis/PureEdgeSim/PureEdgeSim/com/mechalikh/pureedgesim/taskorchestrator/Orchestrator.java#L197), the abstract method `resultsReturned`.

```java
public abstract void resultsReturned(Task task);
```

This is where Python receives the outcome (latency, success/failure) to compute RL reward.

### State Available to Python at Decision Time

From `task` (one decision per call):
- `task.getId()`, `task.getLength()`, `task.getMaxLatency()`
- `task.getFileSizeInBits()`, `task.getOutputSizeInBits()`, `task.getContainerSizeInMBytes()`
- `task.getApplicationID()`, `task.getType()`
- `task.getEdgeDevice()` → full `ComputingNode` properties of requesting device
- `task.getOrchestrator()` → orchestrator node (if `enableOrchestrators = true`)

From `this.nodeList` (candidate nodes, index = action space):
- Node type (Cloud/Edge/Mist), MIPS, cores, RAM, storage
- Current CPU utilization, idle state
- Location (via `getMobilityModel().getCurrentLocation()`)
- Battery/death state, task queue length
- Network links

From `simulationManager`:
- `simulationManager.getSimulation().clock()` — current simulation time
- `simulationManager.getNetworkModel().getWanUpUtilization()` — WAN load
- `simulationManager.getFinishedTaskList()` — history of completed tasks
- Scenario info (algorithm name, architecture name, device count)

---

## Simplified Execution Flow Diagram

```
main()
  │
  ▼
Simulation.launchSimulation()
  │  loadScenarios()  →  [Scenario(devices, algID, archID), ...]
  │
  ▼
SimulationThread.startSimulation()  [per scenario]
  │
  ├─ new PureEdgeSim()              DES engine
  ├─ new DefaultSimulationManager() central coordinator
  ├─ NetworkModel()                 wire module
  ├─ DataCentersManager()           generate all nodes
  │     └─ ComputingNodesGenerator  parse XML → List<ComputingNode>
  ├─ TaskGenerator.generate()       pre-build FutureQueue<Task>
  └─ Orchestrator()                 YOUR CODE HERE
        └─ initialize()             set nodeList based on architecture
  │
  ▼
simulation.start()    [DES loop begins]
  │
  ├─ onSimulationStart()
  │     └─ schedule SEND_TO_ORCH events (batched)
  │
  ▼  [for each task, at its scheduled time]
  │
SEND_TO_ORCH
  └─ sendTaskToOrchestrator()
        └─ network: device → orchestrator
              │
              ▼
        SEND_TASK_FROM_ORCH_TO_DESTINATION
          └─ sendFromOrchToDestination()
                └─ ★ orchestrator.orchestrate(task)       [DECISION POINT]
                      └─ findComputingNode(layers, task)  [PYTHON ALGORITHM]
                            returns nodeIndex
                      └─ task.setOffloadingDestination(node)
                └─ network: orchestrator → destination
                      │
                      ▼
              EXECUTE_TASK
                └─ node.submitTask(task)
                      └─ startExecution(task)
                            └─ schedule EXECUTION_FINISHED
                                  │
                                  ▼
                      EXECUTION_FINISHED
                        └─ scheduleNow(TRANSFER_RESULTS_TO_ORCH)
                              │
                              ▼
                      TRANSFER_RESULTS_TO_ORCH
                        └─ network: destination → orchestrator → device
                              │
                              ▼
                      RESULT_RETURN_FINISHED
                        └─ ★ orchestrator.resultsReturned(task)  [FEEDBACK POINT]
                        └─ tasksCount++
  │
  ▼  [after simulationDuration]
PRINT_LOG → simulation.terminate()
```

---

## Summary for Python Interface Design

| Question | Answer |
|---|---|
| **Where does the decision happen?** | `Orchestrator.findComputingNode(String[], Task)` — synchronous, in the DES thread |
| **What is the action space?** | An integer index into `this.nodeList` (or -1 = failure) |
| **What is the observation space?** | Task properties + per-node properties (all available via Java getters at call time) |
| **Where does the reward come from?** | `Orchestrator.resultsReturned(Task)` — after network + execution delays |
| **How to inject a custom orchestrator?** | `sim.setCustomEdgeOrchestrator(MyOrchestrator.class)` — one-line registration |
| **Is the call synchronous?** | Yes. `findComputingNode` blocks the DES loop until it returns |
| **Can the orchestrator use DES events?** | Yes. `Orchestrator` extends `SimEntity`; it can `schedule()` its own events |
| **Is there an episode structure?** | Each simulation run (scenario) is one episode; `onSimulationStart/End` are natural hooks |
| **What is the note on application placement caching?** | After first assignment, subsequent tasks from same device bypass orchestration (go to cached node). The Python algorithm only gets called once per device per "session", until a failure clears the cache. |
