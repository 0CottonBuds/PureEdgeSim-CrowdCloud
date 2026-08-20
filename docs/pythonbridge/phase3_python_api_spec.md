# Phase 3 — Python Orchestrator API Specification

> **Prerequisite reading:** `docs/phase2_boundary_design.md` — the Java/Python bridge design.  
> **Status:** API specification — not yet implemented.

---

## 0. Design Principles

These govern every API decision in this document:

1. **Researchers write algorithms, not plumbing.** Socket communication, JSON framing, integer indices, and Java internal types are invisible. A researcher's file imports from `pureedgesim` and does nothing else.
2. **Return domain objects, not raw integers.** `select_node()` returns a `Node` object. The bridge translates it to an index. Researchers never see index arithmetic.
3. **Fail loudly at the right level.** Returning a dead node or a node with insufficient resources is caught in Python with a descriptive error before Java ever sees it. During development this saves hours of debugging.
4. **RL is a first-class citizen, not an afterthought.** The base class works for any algorithm. The `RLOrchestrator` subclass adds RL-specific helpers without forcing them on rule-based users.
5. **NumPy at the boundary.** State objects expose `.to_array()` methods that produce clean, flat `numpy.ndarray` tensors. Researchers should never need to manually featurize.
6. **Extension without breakage.** Adding new fields, new lifecycle hooks, or new message types in later phases should not require changes to existing user code.

---

## 1. Package Structure

```
pureedgesim/                     # Python package root
│
├── __init__.py                  # Public exports: Orchestrator, RLOrchestrator, types
├── orchestrator.py              # Orchestrator ABC + RLOrchestrator
├── rewards.py                   # Built-in reward functions (composable)
├── features.py                  # Built-in featurization helpers (numpy)
│
├── types/                       # All public data types
│   ├── __init__.py
│   ├── node.py                  # Node, NodeType, Location
│   ├── task.py                  # Task, TaskOutcome, TaskStatus, FailureReason
│   ├── state.py                 # SimulationState, NetworkState
│   ├── episode.py               # EpisodeContext, EpisodeSummary, SimulationContext
│   └── decision.py              # PlacementDecision, InvalidDecisionError
│
├── _bridge/                     # Internal -- NOT part of public API
│   ├── __init__.py
│   ├── connection.py            # Unix socket I/O, length-prefixed framing
│   ├── protocol.py              # JSON message serialisation / deserialisation
│   ├── dispatcher.py            # Maps incoming message types to orchestrator methods
│   └── server.py                # Entry-point: connect, handshake, event loop
│
└── examples/                    # Reference implementations
    ├── round_robin.py
    ├── nearest_node.py
    ├── trade_off.py
    └── dqn_skeleton.py
```

**Convention:** Anything in `_bridge/` is internal infrastructure. Researchers never import from it. If a researcher needs to import something, it belongs in `types/` or the top-level package.

**Public surface** (what `from pureedgesim import *` exposes):

```python
# orchestrator.py
Orchestrator
RLOrchestrator

# types/node.py
Node
NodeType       # Enum: CLOUD, EDGE_SERVER, EDGE_DEVICE
Location

# types/task.py
Task
TaskOutcome
TaskStatus     # Enum: SUCCESS, FAILED
FailureReason  # Enum: LATENCY, DEAD_NODE, NO_RESOURCE, OUT_OF_RANGE, NO_CANDIDATES, INVALID_DECISION, UNKNOWN

# types/state.py
SimulationState
NetworkState

# types/episode.py
EpisodeContext
EpisodeSummary
SimulationContext

# types/decision.py
PlacementDecision
InvalidDecisionError

# rewards.py
latency_reward
success_reward
deadline_reward
CompositeReward
```

---

## 2. Base Orchestrator Interface

```python
# pureedgesim/orchestrator.py

from __future__ import annotations
from abc import ABC, abstractmethod
from typing import List, Optional

from pureedgesim.types import (
    Node, Task, TaskOutcome, SimulationState,
    PlacementDecision, EpisodeContext, EpisodeSummary, SimulationContext
)


class Orchestrator(ABC):
    """
    Base class for all PureEdgeSim orchestration algorithms.

    Subclass this and implement select_node(). Everything else is optional.

    The bridge (internal) calls lifecycle methods in the correct order and
    passes fully-constructed Python objects -- no raw data, no indices, no JSON.

    Minimal implementation:

        class MyOrchestrator(Orchestrator):
            def select_node(self, task: Task, state: SimulationState) -> Optional[Node]:
                return min(state.nodes, key=lambda n: n.cpu_utilization)
    """

    # ------------------------------------------------------------------
    # Lifecycle methods
    # ------------------------------------------------------------------

    def on_start(self, context: SimulationContext) -> None:
        """
        Called once when the Python process connects to Java and the
        handshake completes. Runs before the first episode begins.

        Use this to load pre-trained models, open log files, or allocate
        any global state that persists across multiple episodes.

        Args:
            context: Metadata about the full simulation run (all scenarios).
        """
        pass

    def on_episode_begin(self, episode: EpisodeContext, nodes: List[Node]) -> None:
        """
        Called at the start of each simulation run (episode/scenario).

        This is where episode-scoped state should be initialised.
        The nodes list is the fixed candidate set for this episode --
        its length is the action space size.

        Args:
            episode: Scenario metadata (architecture, device count, duration).
            nodes:   The complete list of candidate computing nodes for this
                     episode. The same list is available as state.nodes in
                     every subsequent select_node() call.
        """
        pass

    @abstractmethod
    def select_node(
        self,
        task: Task,
        state: SimulationState,
    ) -> Optional[Node]:
        """
        The primary decision method. Called for every task that needs a
        placement decision.

        Args:
            task:  The task that must be assigned right now. Returning None
                   or raising InvalidDecisionError fails the task gracefully.
            state: A snapshot of the full simulation state at this moment,
                   including live node states and the look-ahead task queue.

        Returns:
            A Node from state.nodes to assign the task to, or None to
            explicitly fail the task (equivalent to "no viable destination").

        PERFORMANCE NOTE:
            The Java DES thread is blocked waiting for this method to return.
            Keep this method fast. Neural network inference should use a
            pre-computed policy (updated asynchronously in on_task_complete)
            rather than running a forward pass here.
        """
        ...

    def on_task_complete(self, outcome: TaskOutcome) -> None:
        """
        Called when a task finishes (success or failure), after the
        full network + execution round-trip completes.

        This is the primary feedback/reward signal for RL algorithms.
        It may arrive for task N while select_node is being called for
        task N+100 -- outcomes are asynchronous relative to decisions.

        Outcomes for cached placements (where select_node was not called)
        are delivered here with outcome.was_cached = True, so they can
        be filtered if needed.

        Args:
            outcome: Complete task execution result, including latency
                     breakdown, failure reason, and the node it ran on.
        """
        pass

    def on_tick(self, state: SimulationState) -> None:
        """
        Called periodically at a fixed simulated-time interval (delta_t),
        regardless of task load. Enabled only if heartbeat_interval > 0
        is configured in SimulationContext.

        Use this for time-based state updates, value function refreshes,
        or any logic that should run even during idle periods.

        Note: In a DES, multiple ticks may fire back-to-back in real time
        if there is a gap in task arrivals. Keep this method lightweight.

        Args:
            state: Current world state snapshot (same structure as in
                   select_node, but no current task).
        """
        pass

    def on_episode_end(self, summary: EpisodeSummary) -> None:
        """
        Called when a simulation run finishes.

        Use this to save model checkpoints, flush logs, compute
        episode-level metrics, or reset episode-scoped state.

        Args:
            summary: Aggregate statistics for the finished episode.
        """
        pass

    def on_shutdown(self) -> None:
        """
        Called once when all episodes are complete and the simulation
        process is about to exit.

        Use this to close file handles, save final model state,
        or release any global resources.
        """
        pass
```

---

## 3. Lifecycle Ordering

```
Python process starts
    |
    v
on_start(context)                        # once per process
    |
    +--- [Episode 1] -------------------------------------------------+
    |   on_episode_begin(episode, nodes)                              |
    |       |                                                         |
    |       v                                                         |
    |   [for each non-cached task, in DES event order]               |
    |   select_node(task, state)  <-----------+                       |
    |       |                                 |                       |
    |       v  (async, after network+exec)    |                       |
    |   on_task_complete(outcome)             |                       |
    |       |                                 |                       |
    |       +-------------------------------  +                       |
    |                                                                 |
    |   [if heartbeat enabled, every delta_t simulated seconds]       |
    |   on_tick(state)                                                |
    |                                                                 |
    |   on_episode_end(summary)                                       |
    +------------------------------------------------------------------+
    |
    +--- [Episode 2] (next scenario) ---------------------------------+
    |   on_episode_begin(episode, nodes)                              |
    |   ...                                                           |
    +------------------------------------------------------------------+
    |
    v
on_shutdown()                            # once per process
```

Key ordering guarantees:
- `on_start` fires exactly once, before any episode.
- `on_episode_begin` always fires before the first `select_node` of that episode.
- `on_episode_end` always fires after the last `on_task_complete` of that episode.
- `on_tick` and `on_task_complete` may interleave with each other but never with `select_node` (the DES thread processes one event at a time).
- `on_shutdown` fires after the last `on_episode_end`.

---

## 4. SimulationState

The complete world snapshot delivered to `select_node()` and `on_tick()`.

```python
# pureedgesim/types/state.py

from __future__ import annotations
from dataclasses import dataclass, field
from typing import List, Any, Dict
import numpy as np

from .node import Node, NodeType
from .task import Task
from .network import NetworkState


@dataclass(frozen=True)
class SimulationState:
    """
    A snapshot of the simulation world at the moment select_node() is called.

    This object is immutable -- it reflects the state at one instant in time.
    A new instance is created for every call to select_node().
    """

    # --- Time ---
    clock: float
    """Current simulation time in seconds."""

    # --- Nodes ---
    nodes: List[Node]
    """
    The full list of candidate computing nodes, in the same order as
    EpisodeContext.nodes. The index in this list equals the integer
    action value that is sent to Java -- but researchers just return Node
    objects; the bridge handles the translation.

    Dynamic fields (cpu_utilization, available_ram, etc.) are fresh
    snapshots re-sent by Java on every DecisionRequest.
    """

    # --- Pending queue look-ahead ---
    pending_tasks: List[Task]
    """
    Upcoming tasks visible in the simulation queue, sorted by scheduled
    arrival time. Does NOT include the current task being decided.
    Length is at most EpisodeContext.look_ahead_window_size.
    May be empty at the end of an episode or in streaming trace mode.
    """

    tasks_in_flight: int
    """Tasks that have been dispatched but have not yet returned a result."""

    # --- Network ---
    network: NetworkState
    """Current network load and topology information."""

    # --- Custom extensions ---
    metadata: Dict[str, Any] = field(default_factory=dict)
    """
    Extensibility hook. Future phases may add fields here without breaking
    existing orchestrators. Keys are namespaced: 'pureedgesim.<field>'.
    """

    # ------------------------------------------------------------------
    # Convenience accessors
    # ------------------------------------------------------------------

    @property
    def alive_nodes(self) -> List[Node]:
        """All nodes that are not dead (battery not depleted)."""
        return [n for n in self.nodes if n.is_alive]

    @property
    def idle_nodes(self) -> List[Node]:
        """Nodes with no currently executing task."""
        return [n for n in self.nodes if n.is_idle and n.is_alive]

    @property
    def cloud_nodes(self) -> List[Node]:
        return [n for n in self.nodes if n.type == NodeType.CLOUD]

    @property
    def edge_servers(self) -> List[Node]:
        return [n for n in self.nodes if n.type == NodeType.EDGE_SERVER]

    @property
    def edge_devices(self) -> List[Node]:
        return [n for n in self.nodes if n.type == NodeType.EDGE_DEVICE]

    def candidates_for(self, task: Task) -> List[Node]:
        """
        Returns nodes that can physically accept this task at this moment.
        Filters: alive, sufficient RAM, sufficient storage.

        This is a Python-side pre-filter for convenience. Java performs
        the authoritative range and resource check. Use this to avoid
        obviously invalid choices without waiting for Java feedback.
        """
        return [
            n for n in self.nodes
            if n.is_alive
            and n.available_ram_mb >= task.ram_required_mb
            and n.available_storage_mb >= task.container_size_mb
        ]

    def to_array(
        self,
        include_pending: bool = True,
        max_pending: int = 10,
    ) -> np.ndarray:
        """
        Flatten the state into a 1D numpy array suitable for neural network input.

        Layout:
            [clock, tasks_in_flight, wan_utilization,
             node_0_features..., node_1_features..., ...,
             pending_task_0_features..., ...]

        Use pureedgesim.features for normalisation and field selection.
        """
        from pureedgesim.features import state_to_array
        return state_to_array(self, include_pending, max_pending)
```

---

## 5. Task Representation

```python
# pureedgesim/types/task.py

from __future__ import annotations
from dataclasses import dataclass, field
from enum import Enum, auto
from typing import Optional, Any, Dict, TYPE_CHECKING
import numpy as np

if TYPE_CHECKING:
    from .node import Node, Location


class TaskStatus(Enum):
    SUCCESS = auto()
    FAILED  = auto()


class FailureReason(Enum):
    LATENCY          = auto()   # Deadline exceeded
    DEAD_NODE        = auto()   # Destination died before/during execution
    NO_RESOURCE      = auto()   # Insufficient RAM or storage at destination
    OUT_OF_RANGE     = auto()   # Mobility caused the node to move out of range
    NO_CANDIDATES    = auto()   # select_node() returned None
    INVALID_DECISION = auto()   # select_node() returned an invalid node (lenient mode)
    UNKNOWN          = auto()


@dataclass(frozen=True)
class Task:
    """
    A computation task that needs to be placed on a computing node.

    All size fields are in consistent, human-friendly units (MB, not bits).
    The bridge converts from Java's bit-based representation automatically.
    """

    id: int
    """Unique task identifier within this episode."""

    # --- Compute demand ---
    length_mi: float
    """Compute demand in Million Instructions."""

    # --- Data sizes ---
    input_size_mb: float
    """Size of the uplink payload (request data sent to the destination)."""

    output_size_mb: float
    """Size of the downlink payload (result data returned to origin)."""

    container_size_mb: float
    """RAM and storage footprint the task requires at the destination node."""

    ram_required_mb: float
    """Alias for container_size_mb. Used in resource feasibility checks."""

    # --- Deadline ---
    deadline: float
    """Maximum allowed end-to-end latency in seconds (dispatch to result return)."""

    # --- Application ---
    app_id: int
    app_type: str
    """Human-readable application category (from applications.xml)."""

    # --- Origin device ---
    origin_node_id: int
    """ID of the edge device that generated this task."""

    origin_node_index: int
    """
    Index of the origin device in state.nodes, or -1 if it is not a
    scheduling candidate in the current architecture.
    """

    origin_location: "Location"
    """Physical location of the origin device at decision time."""

    origin_cpu_utilization: float
    """CPU utilisation fraction of the origin device (0.0-1.0)."""

    # --- Scheduling time ---
    scheduled_arrival: float
    """
    Simulated time (seconds) at which this task will be/was dispatched.
    For the current task (in select_node), this is <= state.clock.
    For pending tasks (look-ahead window), this is > state.clock.
    """

    # --- Extensibility ---
    metadata: Dict[str, Any] = field(default_factory=dict)

    # ------------------------------------------------------------------
    # Convenience properties
    # ------------------------------------------------------------------

    @property
    def is_compute_intensive(self) -> bool:
        """True if compute demand > 1000 MI (heuristic threshold)."""
        return self.length_mi > 1000.0

    @property
    def is_data_intensive(self) -> bool:
        """True if uplink payload > 5 MB."""
        return self.input_size_mb > 5.0

    @property
    def is_latency_sensitive(self) -> bool:
        """True if deadline < 1 second."""
        return self.deadline < 1.0

    def to_array(self) -> np.ndarray:
        """
        Flat numpy feature vector for this task.
        Fields: [length_mi, input_size_mb, output_size_mb, container_size_mb,
                 deadline, app_id, origin_location_x, origin_location_y,
                 origin_cpu_utilization]
        """
        from pureedgesim.features import task_to_array
        return task_to_array(self)


@dataclass(frozen=True)
class TaskOutcome:
    """
    The complete result of a task execution, including timing breakdown.
    Delivered to on_task_complete() after the full network+execution round-trip.
    """

    task: Task
    """The original task."""

    assigned_node: "Node"
    """The node that was selected (even if execution subsequently failed)."""

    status: TaskStatus

    failure_reason: Optional[FailureReason]
    """None if status is SUCCESS."""

    # --- Timing (all in seconds) ---
    total_latency: float
    """Total end-to-end time from task dispatch to result return."""

    computation_time: float
    """Time spent executing on the destination CPU."""

    network_time: float
    """Total network transfer time (uplink + downlink)."""

    queue_wait_time: float
    """Time spent waiting in the destination node's task queue."""

    execution_start: float
    """Simulated clock time when execution began."""

    execution_end: float
    """Simulated clock time when execution finished."""

    result_returned_at: float
    """Simulated clock time when the result arrived at the orchestrator."""

    # --- Context ---
    was_cached: bool = False
    """
    True if this result is for a cached placement (select_node was NOT called
    for this task -- it reused a previous assignment). RL algorithms should
    typically skip cached outcomes when computing policy gradients.
    """

    request_id: int = -1
    """Correlation ID. -1 for cached placements."""

    # --- Extensibility ---
    metadata: Dict[str, Any] = field(default_factory=dict)

    # ------------------------------------------------------------------
    # Convenience properties
    # ------------------------------------------------------------------

    @property
    def met_deadline(self) -> bool:
        return self.status == TaskStatus.SUCCESS and self.total_latency <= self.task.deadline

    @property
    def deadline_excess(self) -> float:
        """How much the deadline was exceeded (0.0 if met)."""
        return max(0.0, self.total_latency - self.task.deadline)

    @property
    def deadline_slack(self) -> float:
        """How much time remained before deadline (negative if violated)."""
        return self.task.deadline - self.total_latency

    def compute_reward(
        self,
        success_weight: float = 1.0,
        latency_weight: float = 1.0,
        deadline_penalty: float = 2.0,
    ) -> float:
        """
        Built-in reward function with configurable weights.

        Base reward:     +success_weight if SUCCESS, 0 if FAILED
        Latency bonus:   +latency_weight * (1 - total_latency/deadline) if met
        Deadline penalty: -deadline_penalty * (excess/deadline) if violated

        For fully custom reward functions, use the pureedgesim.rewards module.
        """
        from pureedgesim.rewards import default_reward
        return default_reward(self, success_weight, latency_weight, deadline_penalty)
```

---

## 6. Node / Device Representation

```python
# pureedgesim/types/node.py

from __future__ import annotations
from dataclasses import dataclass, field
from enum import Enum, auto
from typing import Any, Dict, TYPE_CHECKING
import numpy as np

if TYPE_CHECKING:
    from .task import Task


class NodeType(Enum):
    CLOUD       = auto()
    EDGE_SERVER = auto()   # Edge datacenter or server
    EDGE_DEVICE = auto()   # Mist node (end-user device)


@dataclass(frozen=True)
class Location:
    x: float
    y: float

    def distance_to(self, other: "Location") -> float:
        return ((self.x - other.x) ** 2 + (self.y - other.y) ** 2) ** 0.5


@dataclass
class Node:
    """
    A computing node that is a candidate scheduling target.

    Static fields (identity and capacity) never change within an episode.
    Dynamic fields (runtime state) are refreshed on every select_node() call.

    The researcher works exclusively with Node objects. The bridge handles
    translation to/from Java's integer node index internally.
    """

    # ------------------------------------------------------------------
    # Static identity (set once at episode start, never changes)
    # ------------------------------------------------------------------

    index: int
    """
    Position in the candidate node list. This equals the integer action value
    sent to Java. Researchers do not use this directly -- they return the Node
    object and the bridge reads this field.
    """

    id: int
    """Internal PureEdgeSim node identifier."""

    type: NodeType

    # --- Hardware capacity ---
    total_mips: float
    """Total compute capacity in Million Instructions per Second."""

    mips_per_core: float

    num_cores: int

    total_ram_mb: float
    """Installed RAM in megabytes. NOT the currently available amount."""

    total_storage_mb: float
    """Installed storage in megabytes. NOT the currently available amount."""

    is_peripheral: bool
    """
    True for edge servers directly reachable from end devices in one hop.
    Always True for edge devices. Always False for cloud nodes.
    """

    base_location: Location
    """
    Fixed reference position. For cloud and edge servers this never changes.
    For mist devices this is the starting position; use current_location for
    the live position during simulation.
    """

    # ------------------------------------------------------------------
    # Dynamic runtime state (refreshed on every select_node() call)
    # ------------------------------------------------------------------

    available_ram_mb: float
    """Currently free RAM in megabytes. Changes as tasks arrive and finish."""

    available_storage_mb: float
    """Currently free storage in megabytes."""

    cpu_utilization: float
    """Instantaneous CPU utilisation fraction (0.0 = idle, 1.0 = fully loaded)."""

    avg_cpu_utilization: float
    """Time-averaged CPU utilisation since simulation start."""

    is_idle: bool
    """True if no task is currently executing on this node."""

    is_alive: bool
    """
    False if the node's battery is depleted (edge devices only).
    Dead nodes must not be returned from select_node() -- the bridge
    will raise InvalidDecisionError in strict mode.
    """

    queued_tasks: int
    """Number of tasks waiting in this node's execution queue right now."""

    current_location: Location
    """
    Current physical position. Changes each timestep for mobile mist devices.
    Equals base_location for cloud nodes and fixed edge servers.
    """

    # --- Extensibility ---
    metadata: Dict[str, Any] = field(default_factory=dict)

    # ------------------------------------------------------------------
    # Convenience properties
    # ------------------------------------------------------------------

    @property
    def is_cloud(self) -> bool:
        return self.type == NodeType.CLOUD

    @property
    def is_edge_server(self) -> bool:
        return self.type == NodeType.EDGE_SERVER

    @property
    def is_edge_device(self) -> bool:
        return self.type == NodeType.EDGE_DEVICE

    @property
    def available_mips(self) -> float:
        """Estimated currently free compute capacity in MIPS."""
        return self.total_mips * max(0.0, 1.0 - self.cpu_utilization)

    @property
    def ram_utilization(self) -> float:
        """Current RAM utilisation fraction (0.0-1.0)."""
        return 1.0 - (self.available_ram_mb / self.total_ram_mb)

    def can_accept(self, task: "Task") -> bool:
        """
        Quick feasibility check: is this node alive and does it have enough
        RAM and storage for the task right now?

        This is a necessary but not sufficient condition -- Java also checks
        network range. Use state.candidates_for(task) to bulk-filter.
        """
        return (
            self.is_alive
            and self.available_ram_mb >= task.ram_required_mb
            and self.available_storage_mb >= task.container_size_mb
        )

    def to_array(self) -> np.ndarray:
        """
        Flat numpy feature vector for this node's dynamic state.
        Fields: [cpu_utilization, avg_cpu_utilization, available_ram_fraction,
                 available_storage_fraction, is_idle, is_alive, queued_tasks,
                 current_location_x, current_location_y]
        """
        from pureedgesim.features import node_to_array
        return node_to_array(self)

    def static_array(self) -> np.ndarray:
        """
        Flat numpy feature vector for this node's static properties.
        Fields: [total_mips, mips_per_core, num_cores, total_ram_mb,
                 total_storage_mb, is_cloud, is_edge_server, is_edge_device,
                 is_peripheral, base_location_x, base_location_y]
        Useful for graph embeddings or one-time node encoding.
        """
        from pureedgesim.features import node_static_to_array
        return node_static_to_array(self)
```

---

## 7. Network State

```python
# pureedgesim/types/network.py

from __future__ import annotations
from dataclasses import dataclass, field
from typing import Any, Dict
import numpy as np


@dataclass(frozen=True)
class NetworkState:
    """
    Current network conditions at the time of a scheduling decision.

    Phase 2 exposes WAN utilisation only. Future phases will add per-link
    bandwidth, estimated transfer latencies, and topology information via
    the metadata dict before promoting them to first-class fields.
    """

    wan_uplink_utilization: float
    """
    WAN uplink load fraction (0.0 = idle, 1.0 = saturated).
    High values indicate that cloud-offloaded tasks will experience
    additional transfer latency.
    """

    metadata: Dict[str, Any] = field(default_factory=dict)
    """
    Future network fields will appear here first:
        'wan_downlink_utilization' : float
        'lan_utilization'          : float
        'estimated_cloud_rtt_s'    : float
    """

    def to_array(self) -> np.ndarray:
        """Flat feature vector: [wan_uplink_utilization]"""
        return np.array([self.wan_uplink_utilization], dtype=np.float32)
```

---

## 8. Episode and Simulation Context

```python
# pureedgesim/types/episode.py

from __future__ import annotations
from dataclasses import dataclass, field
from typing import Any, Dict


@dataclass(frozen=True)
class SimulationContext:
    """
    Metadata about the full simulation run. Available in on_start().
    Covers all episodes that will be executed in this session.
    """

    total_episodes: int
    """Total number of scenarios that will be simulated in this session."""

    look_ahead_window_size: int
    """Max number of pending tasks included in each select_node() call."""

    heartbeat_interval: float
    """
    Simulated-time interval between on_tick() calls, in seconds.
    0.0 means heartbeat is disabled (on_tick() will never fire).
    """

    strict_validation: bool = True
    """
    If True, InvalidDecisionError stops the simulation on invalid decisions.
    If False (lenient), the task is failed and the simulation continues.
    """

    protocol_version: str = "1.0"
    """Bridge protocol version. Used for backward compatibility checks."""

    metadata: Dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class EpisodeContext:
    """
    Metadata about the current simulation run (episode/scenario).
    Available in on_episode_begin().
    """

    episode_id: int
    """Zero-based episode index."""

    algorithm_name: str
    """The algorithm name configured in the simulation scenario."""

    architecture_name: str
    """
    Architecture name: 'MIST_ONLY', 'EDGE_ONLY', 'CLOUD_ONLY',
    'MIST_AND_EDGE', 'MIST_AND_CLOUD', 'EDGE_AND_CLOUD', or 'ALL'.
    Determines which node types appear in the candidate list.
    """

    num_devices: int
    """Total number of edge devices in this scenario."""

    sim_duration: float
    """Total simulated time for this episode in seconds."""

    num_candidate_nodes: int
    """Length of the nodes list -- equals the action space size."""

    metadata: Dict[str, Any] = field(default_factory=dict)

    @property
    def action_space_size(self) -> int:
        """Convenience alias for num_candidate_nodes."""
        return self.num_candidate_nodes


@dataclass(frozen=True)
class EpisodeSummary:
    """
    Aggregate statistics for a completed episode. Available in on_episode_end().
    """

    episode_id: int
    total_tasks: int
    successful_tasks: int
    failed_tasks: int
    cached_tasks: int
    """Tasks that reused a cached placement (select_node was not called)."""

    sim_duration: float
    metadata: Dict[str, Any] = field(default_factory=dict)

    @property
    def success_rate(self) -> float:
        return self.successful_tasks / max(1, self.total_tasks)

    @property
    def failure_rate(self) -> float:
        return self.failed_tasks / max(1, self.total_tasks)

    @property
    def cache_hit_rate(self) -> float:
        return self.cached_tasks / max(1, self.total_tasks)
```

---

## 9. Action / Decision Model

`select_node()` returns a `Node` object (or `None`). The bridge reads the node's `.index` field and sends that integer to Java. Researchers never deal with indices.

```python
# pureedgesim/types/decision.py

from __future__ import annotations
from dataclasses import dataclass, field
from typing import Optional, Any, Dict, TYPE_CHECKING

if TYPE_CHECKING:
    from .node import Node


@dataclass
class PlacementDecision:
    """
    The result of a scheduling decision.

    In the simplest case, return the Node directly from select_node().
    Use PlacementDecision explicitly when you want to attach metadata
    to the decision (e.g., confidence scores, for logging).

    Example:
        return PlacementDecision(node=my_node, confidence=0.91)

    Future action types (migration requests, container pre-warming) will
    be expressed as additional fields on this class.
    """

    node: Optional["Node"]
    """
    The selected node. None means 'fail this task -- no viable destination'.
    """

    confidence: Optional[float] = None
    """
    Optional scalar (0.0-1.0) indicating algorithm confidence in this choice.
    Not used by the bridge; available for logging and analysis.
    """

    metadata: Dict[str, Any] = field(default_factory=dict)
    """
    Extensibility hook. Future action types will use this.
    Example future keys: 'pre_warm_container', 'migration_target', 'priority'.
    """


class InvalidDecisionError(Exception):
    """
    Raised by the bridge when select_node() returns an unacceptable node.

    In strict mode (default during development), this error propagates and
    stops the simulation with a helpful message. In lenient mode, the task
    is failed with FailureReason.INVALID_DECISION and the simulation continues.

    Common causes:
        - Returning a node that is not in state.nodes (stale reference)
        - Returning a node with is_alive == False
        - Returning a node with insufficient RAM or storage for the task
    """

    def __init__(self, message: str, node=None, task=None):
        self.node = node
        self.task = task
        super().__init__(message)
```

**`select_node()` accepts three return forms — all handled by the bridge:**

```python
# Form 1: a Node directly (most common)
return some_node

# Form 2: None (explicit failure -- task marked as NO_CANDIDATES)
return None

# Form 3: a PlacementDecision (for metadata/logging)
return PlacementDecision(node=some_node, confidence=0.85)
```

---

## 10. Error Handling for Invalid Decisions

**What the bridge validates before sending to Java (Python-side):**

| Check | Error if fails |
|---|---|
| Return value is `Node`, `PlacementDecision`, or `None` | `TypeError` |
| Node is from `state.nodes` of this episode (not a stale ref) | `InvalidDecisionError` |
| `node.is_alive == True` | `InvalidDecisionError` |
| `node.available_ram_mb >= task.ram_required_mb` | `InvalidDecisionError` |
| `node.available_storage_mb >= task.container_size_mb` | `InvalidDecisionError` |

**Validation modes** (controlled by `SimulationContext.strict_validation`):

| Mode | Behaviour |
|---|---|
| `strict=True` (default) | `InvalidDecisionError` is raised immediately. The simulation stops. The traceback points to the researcher's code. |
| `strict=False` | `InvalidDecisionError` is caught, a warning is logged, the task is failed with `FailureReason.INVALID_DECISION`, and the simulation continues. |

**Rationale:** Strict mode is on by default because silent wrong decisions during development produce misleading metrics. Researchers can set `strict=False` for production runs where robustness matters more than immediate failure detection.

---

## 11. RL Extension: RLOrchestrator

```python
# pureedgesim/orchestrator.py  (continued)

import numpy as np
from pureedgesim.types import (
    Node, Task, TaskOutcome, TaskStatus, SimulationState, EpisodeContext
)


class RLOrchestrator(Orchestrator):
    """
    Extended base class for Reinforcement Learning orchestration algorithms.

    Adds:
        - observation(): encodes (task, state) as a flat numpy vector
        - action_space_size: number of schedulable nodes
        - reward(): scalar reward from a task outcome (override for shaped rewards)
        - episode_reward and episode_steps tracking

    Typical RL loop per task:

        obs    = self.observation(task, state)    # encode state as vector
        action = self.policy(obs)                 # your model's forward pass -> int
        node   = state.nodes[action]              # translate to Node
        return node                               # returned from select_node()

        # later, asynchronously, in on_task_complete():
        r = self.reward(outcome)                  # scalar reward signal
        self.replay_buffer.add(obs, action, r)    # store (s, a, r) transition
    """

    def on_episode_begin(
        self,
        episode: EpisodeContext,
        nodes: list,
    ) -> None:
        self._action_space_size = episode.num_candidate_nodes
        self._episode_reward = 0.0
        self._episode_steps = 0

    @property
    def action_space_size(self) -> int:
        """Number of scheduling targets (== len(state.nodes) this episode)."""
        return self._action_space_size

    def observation(self, task: Task, state: SimulationState) -> np.ndarray:
        """
        Encode (task, state) as a flat numpy float32 feature vector.

        Default implementation concatenates (in order):
            1. task.to_array()                     task properties
            2. state.network.to_array()            network state
            3. node.to_array() for each node       per-node dynamic state

        The observation shape is therefore:
            len(task.to_array())
            + len(state.network.to_array())
            + len(state.nodes) * len(node.to_array())

        Override this to add custom features, normalisation, look-ahead
        task encoding, or graph-structured embeddings.

        Returns:
            1D numpy array of dtype float32.
        """
        parts = [task.to_array(), state.network.to_array()]
        parts += [node.to_array() for node in state.nodes]
        return np.concatenate(parts).astype(np.float32)

    def reward(self, outcome: TaskOutcome) -> float:
        """
        Scalar reward signal for one task outcome.

        Default: +1 if deadline met, proportional penalty if violated or failed.
        Override for shaped rewards (e.g., energy-aware, fairness-weighted).

        Sign convention: higher is better.

        Args:
            outcome: The completed task's result.
        Returns:
            A scalar float.
        """
        if outcome.met_deadline:
            return 1.0
        elif outcome.status == TaskStatus.SUCCESS:
            # Succeeded but missed deadline
            return -outcome.deadline_excess / max(1e-6, outcome.task.deadline)
        else:
            return -1.0

    def on_task_complete(self, outcome: TaskOutcome) -> None:
        """
        Default RLOrchestrator implementation: accumulate reward for non-cached tasks.
        If you override this, call super().on_task_complete(outcome) to preserve tracking.
        """
        if not outcome.was_cached:
            self._episode_reward += self.reward(outcome)
            self._episode_steps += 1

    @property
    def episode_reward(self) -> float:
        """Total accumulated reward for the current episode."""
        return self._episode_reward

    @property
    def episode_steps(self) -> int:
        """Number of non-cached scheduling decisions made this episode."""
        return self._episode_steps
```

---

## 12. Built-in Reward Functions

```python
# pureedgesim/rewards.py

from __future__ import annotations
from typing import Callable, List, Tuple
from pureedgesim.types import TaskOutcome, TaskStatus


RewardFn = Callable[[TaskOutcome], float]


def latency_reward(outcome: TaskOutcome) -> float:
    """Reward proportional to latency headroom below the deadline."""
    if outcome.status == TaskStatus.FAILED:
        return -1.0
    return outcome.deadline_slack / max(1e-6, outcome.task.deadline)


def success_reward(outcome: TaskOutcome) -> float:
    """Binary: +1 for any success, -1 for any failure."""
    return 1.0 if outcome.status == TaskStatus.SUCCESS else -1.0


def deadline_reward(outcome: TaskOutcome) -> float:
    """Binary: +1 if deadline met, -1 if violated or failed."""
    return 1.0 if outcome.met_deadline else -1.0


def energy_reward(outcome: TaskOutcome) -> float:
    """
    Placeholder for energy-aware reward.
    Returns 0.0 until Phase N adds energy reporting to the protocol.
    Will use outcome.metadata['energy_joules'] when available.
    """
    joules = outcome.metadata.get("energy_joules")
    return -joules if joules is not None else 0.0


def default_reward(
    outcome: TaskOutcome,
    success_weight: float = 1.0,
    latency_weight: float = 1.0,
    deadline_penalty: float = 2.0,
) -> float:
    """
    Weighted combination used by TaskOutcome.compute_reward().
    """
    if outcome.status == TaskStatus.FAILED:
        base = -success_weight
    else:
        base = success_weight

    if outcome.met_deadline:
        latency_bonus = latency_weight * (1.0 - outcome.total_latency / outcome.task.deadline)
    else:
        latency_bonus = -deadline_penalty * outcome.deadline_excess / max(1e-6, outcome.task.deadline)

    return base + latency_bonus


class CompositeReward:
    """
    Weighted combination of multiple reward functions.

    Example:
        reward_fn = CompositeReward([
            (deadline_reward,  2.0),
            (latency_reward,   1.0),
            (energy_reward,    0.5),
        ])

        class MyOrchestrator(RLOrchestrator):
            def __init__(self):
                self._reward_fn = CompositeReward([...])

            def reward(self, outcome):
                return self._reward_fn(outcome)
    """

    def __init__(self, components: List[Tuple[RewardFn, float]]):
        self._components = components
        self._total_weight = sum(w for _, w in components)

    def __call__(self, outcome: TaskOutcome) -> float:
        return sum(
            fn(outcome) * w for fn, w in self._components
        ) / self._total_weight
```

---

## 13. Extensibility Strategy

Future phases may need new observation fields, new action types, or new lifecycle hooks. The API handles this without breaking existing user code through four mechanisms:

### 13.1 Metadata dicts (safe landing zone for new fields)

Every data object carries `metadata: Dict[str, Any]`. New fields from the bridge appear here before being promoted to first-class attributes in a later API version:

```python
# Energy data appears in metadata in Phase N, then becomes first-class in Phase N+1
energy = outcome.metadata.get("energy_joules")   # None on old bridge versions
```

### 13.2 Default no-op lifecycle methods

All lifecycle methods except `select_node()` have default no-op implementations. New hooks can be added to the base class with a default no-op. Existing orchestrators that don't override them are unaffected:

```python
def on_migration_complete(self, event: MigrationEvent) -> None:
    pass  # added in a future phase -- existing code sees no change
```

### 13.3 PlacementDecision metadata (future action types)

Future actions (container pre-warming, workload migration) are expressed through `PlacementDecision.metadata` before getting their own fields:

```python
return PlacementDecision(
    node=my_node,
    metadata={"pre_warm": True, "target_for_migration": other_node.id}
)
```

### 13.4 Protocol version field

`SimulationContext.protocol_version` allows the bridge to handle backward compatibility. Newer Java bridges talking to older Python packages send only the fields the package knows about. Unknown future fields arrive in `metadata` dicts rather than crashing.

---

## 14. Example Implementations

### Round-robin (rule-based, 6 lines of logic)

```python
from pureedgesim import Orchestrator
from pureedgesim.types import Task, SimulationState, Node
from typing import Optional

class RoundRobinOrchestrator(Orchestrator):

    def on_episode_begin(self, episode, nodes):
        self._counter = 0

    def select_node(self, task: Task, state: SimulationState) -> Optional[Node]:
        alive = state.candidates_for(task)
        if not alive:
            return None
        node = alive[self._counter % len(alive)]
        self._counter += 1
        return node
```

### Nearest-node (heuristic, 1 line of logic)

```python
class NearestNodeOrchestrator(Orchestrator):

    def select_node(self, task: Task, state: SimulationState) -> Optional[Node]:
        candidates = state.candidates_for(task)
        return min(candidates,
                   key=lambda n: n.current_location.distance_to(task.origin_location),
                   default=None)
```

### Least-loaded (heuristic, 1 line of logic)

```python
class LeastLoadedOrchestrator(Orchestrator):

    def select_node(self, task: Task, state: SimulationState) -> Optional[Node]:
        candidates = state.candidates_for(task)
        return min(candidates, key=lambda n: n.cpu_utilization, default=None)
```

### DQN (RL skeleton — structure only, no ML framework assumed)

```python
from pureedgesim import RLOrchestrator
from pureedgesim.types import Task, SimulationState, TaskOutcome, Node
from pureedgesim.rewards import CompositeReward, deadline_reward, latency_reward
from typing import Optional

class DQNOrchestrator(RLOrchestrator):

    def on_start(self, context):
        self.model = build_dqn_model()     # your PyTorch / TF / JAX model
        self.replay_buffer = ReplayBuffer(capacity=10_000)
        self._pending = {}                 # request_id -> (observation, action)
        self._reward_fn = CompositeReward([
            (deadline_reward, 2.0),
            (latency_reward,  1.0),
        ])

    def on_episode_begin(self, episode, nodes):
        super().on_episode_begin(episode, nodes)
        self.model.set_action_size(episode.num_candidate_nodes)
        self._pending.clear()

    def select_node(self, task: Task, state: SimulationState) -> Optional[Node]:
        obs = self.observation(task, state)      # numpy vector
        action_idx = self.model.epsilon_greedy(obs)

        # Store (obs, action) keyed by request_id for when the outcome arrives
        # request_id is injected into the task's metadata by the bridge
        req_id = task.metadata.get("request_id", -1)
        self._pending[req_id] = (obs, action_idx)

        # Validate and fall back if model picked an infeasible node
        candidates = state.candidates_for(task)
        if not candidates:
            return None
        if action_idx < len(state.nodes) and state.nodes[action_idx].can_accept(task):
            return state.nodes[action_idx]
        # Fallback: least-loaded valid node
        return min(candidates, key=lambda n: n.cpu_utilization)

    def reward(self, outcome: TaskOutcome) -> float:
        return self._reward_fn(outcome)

    def on_task_complete(self, outcome: TaskOutcome) -> None:
        super().on_task_complete(outcome)     # accumulates episode_reward
        if outcome.was_cached:
            return

        key = outcome.request_id
        if key not in self._pending:
            return

        obs, action = self._pending.pop(key)
        r = self.reward(outcome)
        self.replay_buffer.push(obs, action, r)

        if len(self.replay_buffer) >= 256:
            batch = self.replay_buffer.sample(64)
            self.model.train_step(batch)

    def on_episode_end(self, summary):
        print(f"[Ep {summary.episode_id}] "
              f"reward={self.episode_reward:.2f}  "
              f"success={summary.success_rate:.1%}  "
              f"steps={self.episode_steps}")
        self.model.save(f"checkpoint_ep{summary.episode_id}.pt")
        self.model.decay_epsilon()

    def on_shutdown(self):
        self.model.save("final_model.pt")
```

---

## 15. How to Run

Researchers start the Python process; Java connects to it:

```bash
python -m pureedgesim.run \
    --orchestrator my_package.DQNOrchestrator \
    --socket /tmp/pureedgesim_orch_12345.sock \
    --strict \
    --heartbeat 1.0
```

Or call `run()` programmatically:

```python
# my_experiment.py
from pureedgesim import run
from my_package import DQNOrchestrator

run(
    DQNOrchestrator(),
    socket_path="/tmp/pureedgesim_orch.sock",
    strict=True,
    heartbeat_interval=0.0,   # disable ticks
)
```

---

## 16. API Surface Summary

| What researcher needs | API | What the bridge hides |
|---|---|---|
| Place a task | `select_node(task, state) -> Node` | Integer index arithmetic, Java IPC |
| World state | `state.nodes`, `state.network`, `state.pending_tasks` | JSON parsing, field renaming, unit conversion |
| Task properties | `task.deadline`, `task.length_mi`, `task.input_size_mb` | Bit-to-MB conversion, Java internal IDs |
| Filter valid nodes | `state.candidates_for(task)` | Resource availability checks |
| Fail a task explicitly | `return None` | Sends `node_index: -1` to Java |
| RL reward | `outcome.compute_reward()` or `self.reward(outcome)` | Nothing -- purely Python-side |
| NumPy observation | `self.observation(task, state)` | Manual feature concatenation |
| Episode lifecycle | `on_start / on_episode_begin / on_episode_end / on_shutdown` | Socket handshake, JSON protocol, process management |
| Invalid decision detection | `InvalidDecisionError` with message | Silent wrong behaviour in Java |
