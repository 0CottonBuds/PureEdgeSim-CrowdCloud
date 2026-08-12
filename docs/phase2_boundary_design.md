# Phase 2 — Java/Python Orchestrator Boundary Design

> **Prerequisite reading:** `docs/phase1_architecture_analysis.md` — the Phase 1 investigation of PureEdgeSim's internal architecture.  
> **Status:** Design proposal — not yet implemented.

---

## 0. Governing Constraints (from Phase 1)

Before any design choices, these facts from the existing codebase must be respected:

| Constraint | Source | Implication |
|---|---|---|
| `findComputingNode` is **synchronous and blocks the DES thread** | `Orchestrator.java:50` | IPC must be a **blocking, request/response** call — no async fire-and-forget |
| Java **1.8** target | `pom.xml:295` | Rules out Java modules, records, newer gRPC stubs requiring Java 11+ features |
| Action is an **integer index** into `nodeList` | `Orchestrator.java:129-149` | The protocol only needs to carry a single `int` back from Python |
| `nodeList` **structure** is fixed at simulation init | `Orchestrator.initialize()` | Node **identity and capacity** (type, MIPS, cores, total RAM/storage) can be sent **once** at episode start. Node **runtime state** (available RAM, CPU utilisation, queue length, current location) changes every decision and must be re-sent every `DecisionRequest`. |
| Application-placement caching exists | `DefaultComputingNode:307-316` | Python is **not called for every task from the same device** — only after cache is invalidated by failure or mobility. Python must be aware of this. |
| One episode = one `SimulationThread` run | `SimulationThread.startSimulation()` | Episode boundaries are well-defined |
| `resultsReturned(Task)` fires after full round-trip | `DefaultSimulationManager:200` | This is the RL reward signal, but it arrives **asynchronously** relative to the decision |

---

## 1. What PureEdgeSim Sends to Python

> **Static vs Dynamic split.** Node *identity* (which nodes exist, their type, their hardware capacity) never changes within an episode and is sent once in `EpisodeInit`. Node *runtime state* (available RAM, CPU utilisation, task queue depth, current position for mobile devices) changes with every scheduling event and is re-sent in every `DecisionRequest`. These are fundamentally different things and must not be conflated.

### 1a. Episode Init (sent once per simulation run, before decisions begin)

Sent when the orchestrator is constructed and `nodeList` is populated. Carries only **static** node identity/capacity — things that never change within an episode. Python uses this to know the size and shape of the action space.

```
EpisodeInit {
    episode_id            : int      # which scenario run (for multi-run experiments)
    sim_duration_s        : float    # total simulated time in seconds
    algorithm_name        : str      # e.g. "ROUND_ROBIN", "DQN"
    architecture_name     : str      # e.g. "MIST_AND_EDGE", "ALL"
    look_ahead_window_size: int      # max pending tasks included per DecisionRequest (default 20)

    nodes: [NodeDescriptor] {        # fixed candidate node list (nodeList) -- STATIC
        node_index     : int         # index into nodeList -- this IS the action value
        node_id        : int         # internal PureEdgeSim node ID
        node_type      : str         # "CLOUD" | "EDGE_DATACENTER" | "EDGE_DEVICE"
        total_mips     : float       # total compute capacity (MIPS)
        mips_per_core  : float
        num_cores      : int
        total_ram_mb   : float       # installed RAM (capacity, not available)
        total_storage_mb: float      # installed storage (capacity, not available)
        is_peripheral  : bool        # edge datacenter reachable in 1 hop from devices?
        base_location_x: float       # fixed location (cloud/edge servers); start loc for mist
        base_location_y: float
    }
}
```

### 1b. Static vs Dynamic Node Properties

The following table clarifies which properties belong in `EpisodeInit` (sent once) and which belong in `DecisionRequest.node_states[]` (sent every decision):

| Property | Where | Reason |
|---|---|---|
| `node_type`, `num_cores`, `mips_per_core`, `total_mips` | `EpisodeInit` | Hardware capacity — never changes |
| `total_ram_mb`, `total_storage_mb` | `EpisodeInit` | Hardware capacity — never changes |
| `is_peripheral`, `node_id`, `node_index` | `EpisodeInit` | Structural identity — never changes |
| `base_location_x/y` | `EpisodeInit` | Reference position — clouds/servers don't move |
| `available_ram_mb`, `available_storage_mb` | `DecisionRequest` | Fluctuates as tasks arrive and finish |
| `current_cpu_pct`, `avg_cpu_pct` | `DecisionRequest` | Changes every scheduling event |
| `queue_length` | `DecisionRequest` | Number of tasks queued at this node right now |
| `is_idle`, `is_dead` | `DecisionRequest` | Runtime condition |
| `current_location_x/y` | `DecisionRequest` | Mobile mist devices move; re-read each time |

### 1c. Decision Request (sent once per orchestrated task)

Sent synchronously inside `findComputingNode`. This is the full observation the Python algorithm receives per decision. It contains:
- The **current task** that needs a placement decision (must return an answer for this).
- A **live snapshot** of every candidate node's runtime state.
- A **look-ahead window** of upcoming tasks visible in the pending queue — up to `look_ahead_window_size` entries, sorted by scheduled arrival time. This allows scheduling-aware algorithms to see what is coming and make more informed decisions. The window reflects whatever is currently in the `FutureQueue<Task>`, so it naturally shrinks to zero at the end of an episode and works correctly with both the default pre-generated task list and future dynamic trace replay.

```
DecisionRequest {
    request_id            : int      # monotonically increasing; used to correlate TaskResult
    sim_clock_s           : float    # current DES simulation time

    # --- Current task (a decision MUST be returned for this) ---
    current_task: {
        task_id            : int
        task_length_mi     : float   # compute demand in Million Instructions
        task_file_size_bits: float   # uplink payload (request data)
        task_output_bits   : float   # downlink payload (result data)
        task_container_mb  : float   # storage + RAM needed at destination
        task_max_latency_s : float   # deadline
        task_app_id        : int
        task_app_type      : str
        src_device_index   : int     # source device's index in nodeList (-1 if not a candidate)
        src_device_id      : int
        src_location_x     : float   # device location at decision time
        src_location_y     : float
        src_avg_cpu_pct    : float
        src_current_cpu_pct: float
    }

    # --- Live node state snapshot (re-sent every DecisionRequest, parallel array) ---
    node_states: [NodeState] {       # one entry per node in EpisodeInit.nodes[], same order
        node_index           : int
        available_ram_mb     : float  # currently free RAM
        available_storage_mb : float  # currently free storage
        current_cpu_pct      : float  # instantaneous CPU utilisation %
        avg_cpu_pct          : float  # time-averaged CPU utilisation % since sim start
        is_idle              : bool   # true if no task currently executing
        is_dead              : bool   # true if battery depleted (edge devices only)
        queue_length         : int    # tasks waiting in the execution queue at this node
        current_location_x   : float  # current position (changes for mobile mist devices)
        current_location_y   : float
    }

    # --- Pending task look-ahead window ---
    # Up to look_ahead_window_size tasks from the FutureQueue, sorted by scheduled_time_s.
    # Empty list when no more tasks are queued (end of episode or streaming trace is caught up).
    # These tasks do NOT need a decision now -- they are context for the current decision.
    pending_tasks: [PendingTask] {
        task_id            : int
        scheduled_time_s   : float   # DES time when this task will be dispatched
        task_length_mi     : float
        task_file_size_bits: float
        task_output_bits   : float
        task_container_mb  : float
        task_max_latency_s : float
        task_app_id        : int
        task_app_type      : str
        src_device_id      : int
        src_device_index   : int
    }

    # --- Global state ---
    wan_uplink_utilization: float    # WAN uplink load fraction 0.0-1.0
    tasks_in_flight       : int      # tasks dispatched but not yet completed (network + exec)
}
```

### 1c. Task Result (sent after task completes -- the RL reward signal)

Sent from `resultsReturned(Task)`. This arrives **after** a network + execution delay, not immediately after the decision.

```
TaskResult {
    request_id         : int          # matches the DecisionRequest.request_id; -1 if cached
    task_id            : int
    chosen_node_index  : int          # what Python chose (or cached node index)
    status             : str          # "SUCCESS" | "FAILED"
    failure_reason     : str | null   # "LATENCY" | "DEVICE_DEAD" | "NO_RESOURCE" | ...
    total_delay_s      : float        # total end-to-end latency
    actual_cpu_time_s  : float
    actual_network_time_s: float
    waiting_time_s     : float        # time spent queued at destination
    exec_start_time_s  : float
    exec_finish_time_s : float
    sim_clock_s        : float        # clock when result returned
}
```

### 1d. Episode End (sent once when simulation terminates)

```
EpisodeEnd {
    episode_id         : int
    total_tasks        : int
    successful_tasks   : int
    failed_tasks       : int
    sim_duration_s     : float
}
```

---

## 2. What Python Sends Back to Java

### 2a. Decision Response (reply to DecisionRequest)

```
DecisionResponse {
    request_id         : int          # must match DecisionRequest.request_id
    node_index         : int          # chosen index into nodeList; -1 = no destination
}
```

That is the **entire protocol** from Python to Java during normal operation. The action space is a single integer.

### 2b. Ready Acknowledgement (reply to EpisodeInit)

```
ReadyAck {
    episode_id         : int
    status             : str          # "READY" | "ERROR"
    error_msg          : str | null
}
```

### 2c. Shutdown Acknowledgement (reply to EpisodeEnd, optional)

```
ShutdownAck {
    status : str   # "OK"
}
```

---

## 3. Python-Facing Abstraction

The Python side exposes a clean abstract base class. Researchers implement **only** the logic they care about. This is the key interface that must remain stable as algorithms change.

```python
# pureedgesim/orchestrator.py

from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import List, Optional


@dataclass(frozen=True)
class NodeDescriptor:
    """
    Static identity and hardware capacity of a candidate computing node.
    Sent ONCE per episode in EpisodeInit. Fields never change within an episode.
    """
    node_index: int
    node_id: int
    node_type: str           # "CLOUD" | "EDGE_DATACENTER" | "EDGE_DEVICE"
    total_mips: float        # total compute capacity
    mips_per_core: float
    num_cores: int
    total_ram_mb: float      # installed RAM (capacity)
    total_storage_mb: float  # installed storage (capacity)
    is_peripheral: bool
    base_location_x: float   # fixed reference position
    base_location_y: float


@dataclass
class NodeState:
    """
    Runtime state of a node, re-sent on EVERY DecisionRequest.
    Fields change as tasks arrive, execute, and finish.
    """
    node_index: int
    available_ram_mb: float      # currently free RAM
    available_storage_mb: float  # currently free storage
    current_cpu_pct: float       # instantaneous CPU utilisation %
    avg_cpu_pct: float           # time-averaged CPU utilisation % since sim start
    is_idle: bool
    is_dead: bool                # battery depleted (edge devices only)
    queue_length: int            # tasks waiting at this node right now
    current_location_x: float   # current position (moves for mobile mist devices)
    current_location_y: float


@dataclass(frozen=True)
class PendingTask:
    """
    A task visible in the upcoming queue at the time of a decision.
    These tasks do NOT need a decision right now -- they are context
    for the current scheduling decision.
    Present in TaskObservation.pending_tasks[] up to look_ahead_window_size entries.
    """
    task_id: int
    scheduled_time_s: float      # DES time when this task will be dispatched
    task_length_mi: float
    task_file_size_bits: float
    task_output_bits: float
    task_container_mb: float
    task_max_latency_s: float
    task_app_id: int
    task_app_type: str
    src_device_id: int
    src_device_index: int        # -1 if the source device is not a scheduling candidate


@dataclass
class TaskObservation:
    """
    The full observation presented to the orchestrator for one scheduling decision.
    Contains:
      - current_task: the task that must be placed right now
      - node_states:  live runtime state of every candidate node (re-sent every call)
      - pending_tasks: look-ahead window of upcoming tasks in the queue
    """
    request_id: int
    sim_clock_s: float
    # --- Current task ---
    task_id: int
    task_length_mi: float
    task_file_size_bits: float
    task_output_bits: float
    task_container_mb: float
    task_max_latency_s: float
    task_app_id: int
    task_app_type: str
    src_device_index: int
    src_device_id: int
    src_location_x: float
    src_location_y: float
    src_avg_cpu_pct: float
    src_current_cpu_pct: float
    # --- Live node state (updated every call) ---
    node_states: List[NodeState]
    # --- Look-ahead window ---
    pending_tasks: List[PendingTask]   # up to look_ahead_window_size upcoming tasks
    tasks_in_flight: int               # dispatched but not yet completed
    # --- Global ---
    wan_uplink_utilization: float


@dataclass
class TaskResult:
    """Outcome of a task -- the RL reward signal."""
    request_id: int
    task_id: int
    chosen_node_index: int
    status: str                     # "SUCCESS" | "FAILED"
    failure_reason: Optional[str]
    total_delay_s: float
    actual_cpu_time_s: float
    actual_network_time_s: float
    waiting_time_s: float
    exec_start_time_s: float
    exec_finish_time_s: float
    sim_clock_s: float


class PureEdgeOrchestrator(ABC):
    """
    Abstract base class for Python orchestration algorithms.

    Researchers subclass this and implement decide() and optionally
    on_episode_init(), on_task_result(), and on_episode_end().

    The bridge (server.py) calls these methods in the correct order.
    The researcher never interacts with sockets or serialization.
    """

    def on_episode_init(
        self,
        episode_id: int,
        sim_duration_s: float,
        algorithm_name: str,
        architecture_name: str,
        nodes: List[NodeDescriptor],
    ) -> None:
        """
        Called once at the start of each simulation run (episode).
        Use this to initialize models, reset episode state, etc.
        Default implementation does nothing.
        """
        pass

    @abstractmethod
    def decide(self, observation: TaskObservation) -> int:
        """
        Called synchronously for each task that needs an offloading decision.

        Returns:
            An integer index into the nodes list (from on_episode_init).
            Return -1 to signal that no suitable node was found
            (the task will be marked as failed).

        This method MUST return quickly. The Java DES thread is blocked
        waiting for this response. Any heavy computation (neural net
        inference, etc.) should use pre-computed values or be done
        asynchronously in a background thread with this method only
        reading the result.
        """
        ...

    def on_task_result(self, result: TaskResult) -> None:
        """
        Called when a task completes (success or failure).
        This is where RL algorithms compute rewards and update models.
        Note: this may arrive for task N while decide() is being called
        for task N+100, depending on simulation timing.
        Default implementation does nothing.
        """
        pass

    def on_episode_end(
        self,
        episode_id: int,
        total_tasks: int,
        successful_tasks: int,
        failed_tasks: int,
        sim_duration_s: float,
    ) -> None:
        """
        Called when a simulation run completes.
        Use this to save models, log episode metrics, etc.
        Default implementation does nothing.
        """
        pass
```

**Example: A minimal round-robin implementation (3 lines of logic)**

```python
class RoundRobinOrchestrator(PureEdgeOrchestrator):
    def on_episode_init(self, episode_id, sim_duration_s, algorithm_name,
                        architecture_name, nodes):
        self._n = len(nodes)
        self._counter = 0

    def decide(self, obs: TaskObservation) -> int:
        idx = self._counter % self._n
        self._counter += 1
        return idx
```

**Example: A DQN skeleton (structure only)**

```python
class DQNOrchestrator(PureEdgeOrchestrator):
    def on_episode_init(self, episode_id, sim_duration_s, algorithm_name,
                        architecture_name, nodes):
        self.nodes = nodes
        self.model = load_or_build_model(len(nodes))
        self.replay_buffer = ReplayBuffer()
        self.last_obs: dict[int, TaskObservation] = {}  # request_id -> obs

    def decide(self, obs: TaskObservation) -> int:
        state = featurize(obs)
        action = self.model.epsilon_greedy(state)
        self.last_obs[obs.request_id] = obs
        return action

    def on_task_result(self, result: TaskResult) -> None:
        if result.request_id in self.last_obs:
            obs = self.last_obs.pop(result.request_id)
            reward = compute_reward(result)
            self.replay_buffer.push(obs, result.chosen_node_index, reward)
            self.model.train_step(self.replay_buffer.sample())

    def on_episode_end(self, episode_id, *args):
        self.model.save(f"checkpoint_ep{episode_id}.pt")
```

---

## 4. Lifecycle Design

```
JAVA SIDE                           PYTHON SIDE
----------------------------------------------------------------------
[1. STARTUP]

Java main() launches Python process
as a subprocess, passing socket path
as a command-line argument.
Java waits for READY signal.         Python starts, connects to socket.
                                     Python instantiates the researcher's
                                     Orchestrator subclass.
                                     Python sends READY.
Java receives READY, proceeds.

----------------------------------------------------------------------
[2. INITIALIZATION -- once per simulation run]

Orchestrator.__init__() is called.
nodeList is populated by initialize().
Java sends EpisodeInit message.      Python receives EpisodeInit.
                                     Calls orchestrator.on_episode_init().
                                     Researcher sets up model state.
                                     Python sends ReadyAck.
Java receives ReadyAck.
DES simulation.start() begins.

----------------------------------------------------------------------
[3. DECISION LOOP -- repeated for every non-cached task]

DES fires SEND_TASK_FROM_ORCH_TO_DESTINATION.
findComputingNode() is called.
Java builds DecisionRequest,
sends it over socket.               Python receives DecisionRequest.
                                    Calls orchestrator.decide(obs).
                                    Returns node_index.
                                    Sends DecisionResponse.
Java receives response (blocking).
task.setOffloadingDestination().
findComputingNode() returns.
DES event loop continues.

----------------------------------------------------------------------
[4. TASK RESULT -- async, fires after network+exec delay]

DES fires RESULT_RETURN_FINISHED.
resultsReturned(task) is called.
Java builds TaskResult message,
sends it over socket.               Python receives TaskResult.
                                    Calls orchestrator.on_task_result().
                                    Researcher computes reward,
                                    updates model.
                                    (No response required)

----------------------------------------------------------------------
[5. EPISODE END]

DES terminates (PRINT_LOG event).
onSimulationEnd() fires.
Java sends EpisodeEnd.              Python receives EpisodeEnd.
                                    Calls orchestrator.on_episode_end().
                                    Researcher saves model/logs.
                                    Sends ShutdownAck.
Java receives ShutdownAck.

If more scenarios remain -> go to [2].
If all done -> send SHUTDOWN signal.  Python closes connection gracefully.
Java process exits.                  Python process exits.

----------------------------------------------------------------------
[RESET -- multi-scenario (parallel disabled)]

After EpisodeEnd, before next run,
Java re-instantiates the Orchestrator
(new object -- SimulationThread loop).
-> sends new EpisodeInit for next
   scenario (different device count,
   algorithm, or architecture).
Python on_episode_init() is called
again -- researcher resets state.
```

### Important lifecycle note: Task Result vs. Decision ordering

Because results return with a delay (network + CPU execution time), the following can happen:

```
t=0:   decide(task_1)  -> action_A
t=0:   decide(task_2)  -> action_B
t=1:   decide(task_3)  -> action_C
t=2:   on_task_result(task_1)     <- reward for action_A arrives here
t=3:   decide(task_4)  -> action_D
t=3:   on_task_result(task_2)
```

The `request_id` field in both `DecisionRequest` and `TaskResult` allows correlating actions to outcomes even when they are interleaved.

---

## 5. IPC Mechanism Comparison

### 5.1 Evaluation Criteria

| Criterion | Why it matters |
|---|---|
| **Blocking synchronous call** | `findComputingNode` cannot return until the response arrives |
| **Low latency** | Thousands of decisions per episode -- IPC overhead compounds into wall-clock simulation time |
| **Java 8 compatible** | Compile target is `1.8` |
| **No external service** | Should run with `java -jar` + `python orchestrator.py`, nothing else |
| **Structured data framing** | Must carry 10-50 fields per message |
| **Persistent connection** | Python process stays alive for the full simulation |

---

### 5.2 Option A -- gRPC (Protocol Buffers)

**How it works:** Java calls a gRPC stub (blocking unary RPC). Python implements the servicer. Both generated from a `.proto` file. Communicated over TCP loopback or Unix domain socket.

| Pro | Con |
|---|---|
| Strongly typed, auto-generated client/server code | gRPC Java library adds ~10 MB of Netty/protobuf deps |
| Efficient binary serialization | Java 8 compatible but build complexity is high (Maven protobuf plugin) |
| Industry standard for ML serving | `.proto` schema changes require recompile on both sides |
| Built-in streaming for result callbacks | Overkill for a single-process-per-machine workflow |

**Assessment:** Excellent for production ML serving. Adds significant build complexity for a research codebase where Java and Python are always co-located. Schema rigidity is a friction point during iterative algorithm development.

---

### 5.3 Option B -- Py4J

**How it works:** Py4J embeds a gateway server in Java. Python gets a proxy object that calls Java methods directly using a private TCP protocol.

| Pro | Con |
|---|---|
| Python can call Java objects directly (no serialization layer) | The Java DES thread calls back into Python -- inverts natural control flow |
| No manual serialization | Thread safety: DES thread -> Py4J -> Python thread is hard to reason about |
| | Py4J latency ~1ms per call -- too slow for thousands of decisions/episode |

**Assessment:** Wrong inversion of control. PureEdgeSim's DES thread drives the loop; Python must respond to it, not drive it.

---

### 5.4 Option C -- REST/HTTP

**How it works:** Python runs a Flask/FastAPI HTTP server. Java sends HTTP POST requests for each decision.

| Pro | Con |
|---|---|
| Human-readable, easy to debug | HTTP overhead (5-20ms per request) is catastrophic for thousands of calls |
| No binary protocol to learn | Connection setup overhead even with keep-alive |
| | Not designed for sub-millisecond IPC |

**Assessment:** Definitively ruled out by latency. At 10ms per round-trip and 50,000 decisions = 500 seconds of added wall-clock time. Unacceptable.

---

### 5.5 Option D -- Raw TCP Sockets with JSON framing

**How it works:** Java opens a TCP socket to `localhost:PORT`. Python listens. Messages are length-prefixed JSON strings. Both sides use blocking `read()`/`write()` calls.

| Pro | Con |
|---|---|
| Zero external dependencies on Java side (`java.net.Socket`) | TCP has ~0.05ms loopback overhead (vs ~0.001ms Unix socket) |
| Human-readable JSON, trivially debuggable | Must implement a simple framing protocol (length prefix) |
| Works on any OS including Windows | |

---

### 5.6 Option E -- Unix Domain Socket with JSON framing (RECOMMENDED)

**How it works:** Same as Option D but using a Unix domain socket file (e.g. `/tmp/pureedgesim_orch.sock`). Java uses `junixsocket` library (MIT license, 150KB JAR) for Java 8 compatibility.

| Pro | Con |
|---|---|
| **~10x lower latency than TCP loopback** (no TCP stack, kernel-to-kernel) | Requires `junixsocket` library in Maven for Java 8 |
| Zero network stack overhead | Linux/macOS only (Windows has Unix sockets since Win10 1803) |
| No port conflicts in shared lab environments | |
| Length-prefixed JSON framing: simple to implement, easy to debug | |
| Python uses stdlib `socket` module | |
| Persistent connection handles episode lifecycle naturally | |
| Swapping JSON for MessagePack is a one-file change | |

**Latency benchmark (approximate, loopback, Linux):**

| Mechanism | Per-round-trip latency |
|---|---|
| HTTP/REST | 5-20 ms |
| gRPC over TCP | 0.3-1 ms |
| TCP loopback + JSON | 0.1-0.3 ms |
| **Unix socket + JSON** | **0.01-0.05 ms** |
| Py4J | ~1 ms |

For 50,000 decisions at 0.03ms each = **1.5 seconds** of added wall-clock time. Acceptable for research.

---

### 5.7 Mechanism Summary Table

| | gRPC | Py4J | REST | TCP+JSON | **Unix+JSON** |
|---|---|---|---|---|---|
| Latency | High | Medium | Very Low | High | **Highest** |
| Java 8 compat | Moderate | Good | Good | Good | **Good** (junixsocket) |
| Build simplicity | Low | Medium | High | Very High | **High** |
| Debug ease | Low | Medium | Very High | Very High | **Very High** |
| Schema flexibility | Low | Very High | Very High | Very High | **Very High** |

(Latency column: "Highest" = best, as in lowest round-trip time.)

**Recommendation: Unix domain socket with length-prefixed JSON framing.**
Fallback: Pure TCP loopback with identical framing (one constant change).

---

## 6. Recommended Architecture

### 6.1 Component Diagram

```
+------------------------------------------------------------------+
|  Java Process (PureEdgeSim)                                      |
|                                                                  |
|  +------------------+    +-----------------------------------+   |
|  | Simulation.java  |    | PythonOrchestrator.java           |   |
|  | (entry point)    +--->+ extends Orchestrator              |   |
|  |                  |    |                                   |   |
|  | sim.setCustom    |    | findComputingNode():              |   |
|  | EdgeOrchestrator |    |   build DecisionRequest           |   |
|  | (PythonOrch...)  |    |   bridge.sendRequest(req)  ---+  |   |
|  +------------------+    |   wait for response        <--+  |   |
|                          |   return nodeIndex              |   |
|                          |                                   |   |
|                          | resultsReturned():                |   |
|                          |   build TaskResult                |   |
|                          |   bridge.sendResult(res) ---+     |   |
|                          |   (no wait)                 |     |   |
|                          +-----------------------------------+   |
|                                         |                        |
|                          +--------------v--------------------+   |
|                          | JavaBridge.java                   |   |
|                          | (socket I/O + framing)            |   |
|                          |                                   |   |
|                          | send(msg): write 4-byte len       |   |
|                          |            write JSON bytes       |   |
|                          | recv(): read 4-byte len           |   |
|                          |         read N bytes              |   |
|                          |         parse JSON                |   |
|                          +----------------+------------------+   |
+-------------------------------------------|-----------------------+
                                            |
                                 Unix domain socket
                                 /tmp/pureedgesim_{pid}.sock
+-------------------------------------------|-----------------------+
|  Python Process                           |                       |
|                          +----------------v------------------+   |
|                          | bridge.py                         |   |
|                          | (socket I/O + framing)            |   |
|                          +----------------+------------------+   |
|                                           |                       |
|                          +----------------v------------------+   |
|                          | server.py (message router)        |   |
|                          |                                   |   |
|                          | loop:                             |   |
|                          |   msg = bridge.recv()             |   |
|                          |   if EPISODE_INIT:                |   |
|                          |     orchestrator.on_episode_init()|   |
|                          |     bridge.send(ReadyAck)         |   |
|                          |   if DECISION_REQUEST:            |   |
|                          |     idx = orchestrator.decide()   |   |
|                          |     bridge.send(DecisionResponse) |   |
|                          |   if TASK_RESULT:                 |   |
|                          |     orchestrator.on_task_result() |   |
|                          |     # no response sent            |   |
|                          |   if EPISODE_END:                 |   |
|                          |     orchestrator.on_episode_end() |   |
|                          |     bridge.send(ShutdownAck)      |   |
|                          +----------------+------------------+   |
|                                           |                       |
|                          +----------------v------------------+   |
|                          | Researcher's Orchestrator         |   |
|                          | (subclass of PureEdgeOrchestrator)|   |
|                          |                                   |   |
|                          | class DQNOrchestrator(            |   |
|                          |   PureEdgeOrchestrator):          |   |
|                          |     def decide(obs): ...          |   |
|                          |     def on_task_result(r): ...    |   |
|                          +-----------------------------------+   |
+------------------------------------------------------------------+
```

### 6.2 Message Framing Protocol

Simple, robust, dependency-free:

```
+------------------+----------------------------------+
|  4 bytes         |  N bytes (UTF-8 JSON payload)   |
|  big-endian int  |                                 |
|  = N             |  { "type": "DECISION_REQUEST",  |
|                  |    "request_id": 42, ... }      |
+------------------+----------------------------------+
```

Every message has a `"type"` field. The reader always reads 4 bytes to get the length, then exactly N bytes for the body.

Message types:

```
Java -> Python:          Python -> Java:
  "EPISODE_INIT"           "READY"
  "DECISION_REQUEST"       "READY_ACK"
  "TASK_RESULT"            "DECISION_RESPONSE"
  "EPISODE_END"            "SHUTDOWN_ACK"
  "SHUTDOWN"
```

### 6.3 Startup Sequence

Java launches Python as a subprocess:

```java
// In PythonOrchestrator constructor
String socketPath = "/tmp/pureedgesim_orch_" + ProcessHandle.current().pid() + ".sock";
ProcessBuilder pb = new ProcessBuilder(
    "python3", "-m", "pureedgesim_bridge",
    "--socket", socketPath,
    "--orchestrator", "my_package.DQNOrchestrator"
);
Process pythonProcess = pb.start();
// Wait for READY signal on socket before proceeding
```

> [!WARNING]
> When `parallelism_enabled = true`, PureEdgeSim runs multiple `SimulationThread` instances simultaneously. Each thread creates its own `Orchestrator` instance. Therefore each thread must use a **separate socket path and a separate Python process**. The socket path should include both the PID and the simulation ID from the thread.

### 6.4 Handling the Application-Placement Cache

**Problem:** PureEdgeSim caches the first placement decision per device. Subsequent tasks from the same device bypass `findComputingNode` entirely until a failure or mobility event clears the cache. Python therefore does **not receive a decision request** for cached tasks -- but it **does receive task results** for them.

**Implication for RL:** The reward stream contains outcomes for decisions Python didn't make in this call. The `request_id` correlation handles this: results without a matching pending `request_id` should be treated as outcomes of cached decisions.

**Resolution:** Send `request_id = -1` in `TaskResult` when the result is from a cached placement, so Python can cleanly distinguish these outcomes and handle them appropriately (e.g., count toward episode statistics but skip for policy gradient updates).

---

## 7. Extensibility Considerations

### 7.1 Adding new observation fields
Add fields to `DecisionRequest` JSON. Existing algorithms that don't reference them are unaffected. No protocol version bump needed for additive changes.

### 7.2 Swapping serialization format
`bridge.py` and `JavaBridge.java` are the only files that touch serialization. Replace JSON with MessagePack by changing two files, without touching the orchestrator interface.

### 7.3 Supporting multiple Python algorithms in one run
The `EpisodeInit.algorithm_name` field lets one Python process branch behavior per scenario. Alternatively, each `SimulationThread` spawns its own Python process with a different `--orchestrator` flag.

### 7.4 Supporting non-RL algorithms
Supervised learning, heuristics, and rule-based algorithms only need `decide()`. `on_task_result()` and `on_episode_end()` default to no-ops. The interface does not force RL semantics.

### 7.5 Moving to gRPC later
If the project scales to distributed training across machines, migrating from Unix socket + JSON to gRPC requires changing only `JavaBridge.java` and `bridge.py`. The `PureEdgeOrchestrator` API and `PythonOrchestrator.java` remain identical.

---

## 8. Decision Rationale Summary

| Decision | Rationale |
|---|---|
| **Unix socket + JSON** | Lowest latency without external deps beyond junixsocket; human-readable for debugging; zero build-time code generation; swappable |
| **Length-prefixed framing** | Robust against partial reads; simpler than HTTP or websockets; no delimiter scanning needed |
| **JSON (not Protobuf/MsgPack)** | Zero code generation; trivially inspectable with any text editor; fast enough at this scale; upgradable later |
| **Python as server, Java as client** | Python process starts first and binds the socket; Java connects once ready. Python's server.py event loop owns the session |
| **Separate EpisodeInit message** | Node descriptors are static per episode; sending them once prevents redundant per-task overhead of ~50 fields |
| **`request_id` correlation** | Results are delayed (async) relative to decisions; `request_id` is the only reliable way to map action->outcome for RL |
| **Abstract base class** | Researchers implement only what they need; socket/framing/routing is invisible; the interface is stable as the protocol evolves |
| **Java spawns Python** | Ensures Python lifetime is tied to Java; avoids needing a separate process manager; Java can detect Python crashes via process exit code |
| **Separate socket per parallel thread** | PureEdgeSim's parallel mode runs multiple threads simultaneously; they cannot share a socket without complex synchronization |

---

## 9. Files to Create (Implementation Roadmap)

```
PureEdgeSim/
+-- PureEdgeSim/com/mechalikh/pureedgesim/python/
|   +-- JavaBridge.java           # Socket I/O, framing, JSON marshalling
|   +-- PythonOrchestrator.java   # Extends Orchestrator, calls JavaBridge
|
+-- python/pureedgesim/
    +-- __init__.py
    +-- orchestrator.py           # PureEdgeOrchestrator ABC + dataclasses
    +-- bridge.py                 # Python socket I/O + framing
    +-- server.py                 # Message router (event loop)
    +-- examples/
        +-- round_robin.py
        +-- trade_off.py
        +-- dqn_skeleton.py
```

No changes are needed to any existing PureEdgeSim Java files. The entire bridge is additive.
