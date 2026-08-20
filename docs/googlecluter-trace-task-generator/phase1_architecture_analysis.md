# Phase 1 — Architecture Analysis: Trace-Driven Workload Generator

**Document Version:** 1.0  
**Date:** 2026-08-18  
**Scope:** PureEdgeSim Core Architecture, Task Lifecycle, Python Bridge Integration, and Trace Replay Integration Points.

---

## 1. System Overview & Current Architecture

PureEdgeSim is a discrete-event simulation framework designed to evaluate Cloud, Edge, and Mist computing environments. It uses an event-driven engine (`PureEdgeSim` / `SimEntity`) to simulate network communications, task execution, energy consumption, and device mobility.

### Key Components Relevant to Workload Generation

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
│  │  DefaultSimManager   │ <----------------─────────────────────────────────────┘       │
│  └──────────┬───────────┘                                                               │
└─────────────┼───────────────────────────────────────────────────────────────────────────┘
              │ findComputingNode(task)
              v
┌───────────────────────────┐      Unix Domain Socket (JSON)      ┌─────────────────────────┐
│    PythonOrchestrator     │ <─────────────────────────────────> │   Python Orchestrator   │
│  (Java Orchestrator)      │        DECISION_REQUEST             │ (PyTorch / RL / Policy) │
└───────────────────────────┘                                     └─────────────────────────┘
```

1. **`Simulation` / `SimulationAbstract`** ([Simulation.java](file:///home/cotton/Projects/ML/Thesis/PureEdgeSim/PureEdgeSim/com/mechalikh/pureedgesim/simulationmanager/Simulation.java)):
   - Configures simulation scenario parameters and registers customizable module classes (`TaskGenerator`, `Orchestrator`, `ComputingNode`, `MobilityModel`, `NetworkModel`, `SimulationManager`).
   - Enables users to register a custom task generator via `sim.setCustomTaskGenerator(MyCustomTaskGenerator.class)`.

2. **`SimulationThread`** ([SimulationThread.java](file:///home/cotton/Projects/ML/Thesis/PureEdgeSim/PureEdgeSim/com/mechalikh/pureedgesim/simulationmanager/SimulationThread.java)):
   - Executes each simulation scenario iteration.
   - During `loadModels()`, instantiates the configured `TaskGenerator` class via reflection and invokes `tasksGenerator.generate()`, obtaining a `FutureQueue<Task> taskList` which is passed to the `SimulationManager`.

3. **`TaskGenerator`** ([TaskGenerator.java](file:///home/cotton/Projects/ML/Thesis/PureEdgeSim/PureEdgeSim/com/mechalikh/pureedgesim/taskgenerator/TaskGenerator.java)):
   - Abstract base class for task generation.
   - Holds references to `FutureQueue<Task> taskList`, `List<ComputingNode> devicesList`, and `SimulationManager simulationManager`.
   - Exposes abstract method `public abstract FutureQueue<Task> generate()`.

4. **`DefaultTaskGenerator`** ([DefaultTaskGenerator.java](file:///home/cotton/Projects/ML/Thesis/PureEdgeSim/PureEdgeSim/com/mechalikh/pureedgesim/taskgenerator/DefaultTaskGenerator.java)):
   - Default implementation that parses application definitions from `applications.xml` (`SimulationParameters.applicationList`).
   - Generates tasks synthetically across simulation time minutes based on specified application request rates (`rate`), sizes, and device assignment percentages.

5. **`FutureQueue<Task>`** ([FutureQueue.java](file:///home/cotton/Projects/ML/Thesis/PureEdgeSim/PureEdgeSim/com/mechalikh/pureedgesim/simulationengine/FutureQueue.java)):
   - A priority queue storing `Task` instances ordered chronologically by arrival time (`task.getTime()`).

---

## 2. Existing Task Creation Mechanisms & Lifecycle

### Task Data Model (`Task` Interface)
In PureEdgeSim, a task is represented by the `Task` interface ([Task.java](file:///home/cotton/Projects/ML/Thesis/PureEdgeSim/PureEdgeSim/com/mechalikh/pureedgesim/taskgenerator/Task.java)), implemented by `DefaultTask` ([DefaultTask.java](file:///home/cotton/Projects/ML/Thesis/PureEdgeSim/PureEdgeSim/com/mechalikh/pureedgesim/taskgenerator/DefaultTask.java)).

A valid PureEdgeSim `Task` requires the following fields:

| Property | Type | Description |
| :--- | :--- | :--- |
| `id` | `int` | Unique integer identifier of the task. |
| `time` | `double` | Task arrival/generation timestamp in simulation seconds ($t \ge 0.0$). |
| `length` | `long` | Workload computational length in **Million Instructions (MI)**. |
| `fileSizeInBits` | `long` | Size of input data transmitted from generating device to execution target (in bits). |
| `outputSizeInBits` | `long` | Size of result data transmitted back from target to device (in bits). |
| `containerSizeInBits` | `long` | Container image size / memory footprint required on target node (in bits). |
| `maxLatency` | `double` | Maximum allowable latency (deadline) in seconds before task fails. |
| `edgeDevice` | `ComputingNode` | Reference to the generating source device (origin edge/mist node). |
| `applicationID` | `int` | ID of the application template associated with the task. |
| `registry` | `ComputingNode` | Reference to the Cloud registry node hosting container binaries. |

### How Tasks Are Created and Instantiated
In `DefaultTaskGenerator.java`:
```java
Task task = createTask(++id)
    .setType(appParams.getType())
    .setFileSizeInBits(requestSize)
    .setOutputSizeInBits(outputSize)
    .setContainerSizeInBits(containerSize)
    .setApplicationID(app)
    .setMaxLatency(maxLatency)
    .setLength(length)
    .setEdgeDevice(dev)
    .setRegistry(cloudRegistryNode);

task.setTime(time);
taskList.add(task);
```
Task creation currently occurs **statically upfront** during the simulation initialization phase (`SimulationThread.loadModels()`) before discrete-event engine startup.

---

## 3. Simulation Lifecycle & Task Entry Points

The lifecycle of a task moving through PureEdgeSim follows these distinct phases:

```
[Initialization]      SimulationThread.loadModels() calls TaskGenerator.generate()
                            │
                            ▼
[Queue Setup]         FutureQueue<Task> populated with sorted arrival times t_i
                            │
                            ▼
[Event Scheduling]    DefaultSimulationManager.onSimulationStart()
                      schedules SEND_TO_ORCH event at delay = (task.getTime() - clock)
                            │
                            ▼
[Task Arrival (t)]    Event SEND_TO_ORCH fires in discrete-event engine
                      DefaultSimulationManager.sendTaskToOrchestrator(task)
                            │
                            ▼
[Orchestration]       PythonOrchestrator.findComputingNode(layers, task)
                      Sends DECISION_REQUEST JSON to Python over IPC socket
                            │
                            ▼
[Offloading]          Python returns selected node index -> task routed to target node
                            │
                            ▼
[Execution & Return]  Target node executes task -> results returned to origin device
                      TASK_RESULT sent asynchronously to Python for RL reward calculation
```

1. **Pre-Simulation Queueing**:
   - `TaskGenerator.generate()` returns a chronologically sorted `FutureQueue<Task>`.
2. **Discrete-Event Injection**:
   - In `onSimulationStart()`, `DefaultSimulationManager` inspects `taskList` and schedules discrete events with tag `SEND_TO_ORCH` for tasks in batches (up to `SimulationParameters.batchSize`).
   - Event trigger time is set to `task.getTime() - simulation.clock()`.
3. **Orchestrator Dispatch**:
   - At simulation clock $t = \text{task.getTime()}$, `DefaultSimulationManager.processEvent()` handles `SEND_TO_ORCH` and invokes `orchestrator.findComputingNode(architectureLayers, task)`.

---

## 4. Interaction with Python Orchestrator

When using the Python Bridge ([python_orchestration_guide.md](file:///home/cotton/Projects/ML/Thesis/PureEdgeSim/docs/pythonbridge/python_orchestration_guide.md)):

1. **Synchronous Placement Request**:
   - `PythonOrchestrator` receives the task in Java at time $t$.
   - Formats a JSON `DECISION_REQUEST` via `MessageBuilder` containing:
     - Task details: `id`, `length` (MI), `fileSizeInBytes`, `outputSizeInBytes`, `containerSizeInMB`, `maxLatency`, `edgeDevice` location/ID.
     - Dynamic snapshot of all computing nodes (CPU utilization, RAM fraction, active tasks, position).
   - Sends payload over Unix Domain Socket to Python (`pureedgesim._bridge.server`).

2. **Python Feature Vectorization**:
   - Python receives the request and converts `Task` into a 1D `float32` NumPy feature array via `task_to_array(task)` ([python/pureedgesim/features.py](file:///home/cotton/Projects/ML/Thesis/PureEdgeSim/python/pureedgesim/features.py)):
     - `[0]`: `length_mi`
     - `[1]`: `input_size_mb`
     - `[2]`: `output_size_mb`
     - `[3]`: `container_size_mb`
     - `[4]`: `deadline`
     - `[5]`: `app_id`
     - `[6]`: `origin_location_x`
     - `[7]`: `origin_location_y`
     - `[8]`: `origin_cpu_utilization`
   - Algorithm selects a candidate node index (or `None`).

3. **Asynchronous Feedback & RL Rewards**:
   - Once a task finishes or fails in Java, `PythonOrchestrator.resultsReturned(task)` sends a fire-and-forget `TASK_RESULT` message to Python.
   - Python invokes `on_task_complete(outcome)` with execution latency, network latency, energy, status, and failure reason, feeding RL reward computation.

---

## 5. Architectural Gap Analysis: What to Add & Integration Strategy

### What Already Exists
- Robust `TaskGenerator` plugin system (`sim.setCustomTaskGenerator(...)`).
- Complete `Task` data model with computational, network, and memory specifications.
- Discrete-event scheduling loop driven by `FutureQueue<Task>`.
- Fully functional Python IPC bridge, feature vectorizer, and reward tracking mechanism.

### What We Need to Add
1. **Trace Data Reader / Parser**:
   - Component to load and parse Google Cluster Trace v3 dataset records (from JSON/Protobuf/CSV files).
2. **Trace Replay Task Generator (`TraceTaskGenerator`)**:
   - Java implementation extending `TaskGenerator`.
   - Reads parsed trace records during `generate()`.
   - Maps trace record parameters (event timestamp, CPU GCU/NCU requests, memory requests, duration) into PureEdgeSim `Task` attributes (`time`, `length`, `fileSizeInBits`, `outputSizeInBits`, `containerSizeInBits`, `maxLatency`).
   - Assigns origin `ComputingNode`s (edge devices) to generated tasks.
3. **Configuration & Parameters Extension**:
   - Properties/configuration options for trace file path, time window offset, sampling rate, and resource scaling factors.

### Integration Point & Architectural Transparency

```
┌─────────────────────────────────────────────────────────────────────────┐
│                    Trace Replay Integration Point                       │
│                                                                         │
│  Google Cluster Trace v3 (JSON/CSV)                                     │
│                │                                                        │
│                v                                                        │
│   ┌─────────────────────────┐                                           │
│   │   TraceTaskGenerator    │ (Extends TaskGenerator)                   │
│   └────────────┬────────────┘                                           │
│                │ emits standard Task objects                            │
│                v                                                        │
│   ┌─────────────────────────┐                                           │
│   │    FutureQueue<Task>    │                                           │
│   └────────────┬────────────┘                                           │
└────────────────┼────────────────────────────────────────────────────────┘
                 │ (Unmodified PureEdgeSim Event Engine)
                 v
┌─────────────────────────────────────────────────────────────────────────┐
│  DefaultSimulationManager -> PythonOrchestrator -> Unix Domain Socket   │
│  (0 changes required to IPC Protocol, Python Bridge, or Vectorizers!)   │
└─────────────────────────────────────────────────────────────────────────┘
```

- **Clean Integration**: By subclassing `TaskGenerator` (`TraceTaskGenerator`) and populating `FutureQueue<Task>` with mapped `Task` objects, the trace replay engine plugs seamlessly into PureEdgeSim's existing workflow.
- **Zero Python Bridge Changes**: Because `TraceTaskGenerator` outputs standard Java `Task` instances, `DefaultSimulationManager`, `PythonOrchestrator`, `MessageBuilder`, and Python's `task_to_array()` feature vectorizer will work **without requiring any modifications to the IPC protocol or Python codebase**.
