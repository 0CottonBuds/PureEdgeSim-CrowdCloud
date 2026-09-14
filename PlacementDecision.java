I have this types that you might use for reference for this task.

decision.py:

"""
PlacementDecision dataclass and InvalidDecisionError exception.
"""

from __future__ import annotations
from dataclasses import dataclass, field
from typing import Optional, Any, Dict, TYPE_CHECKING

if TYPE_CHECKING:
    from pureedgesim.types.node import Node


@dataclass
class PlacementDecision:
    """
    Structured placement decision returned by select_node().
    """
    node: Optional[Node]
    confidence: Optional[float] = None
    metadata: Dict[str, Any] = field(default_factory=dict)


class InvalidDecisionError(Exception):
    """
    Raised when select_node() returns an invalid/unacceptable node.
    """
    def __init__(self, message: str, node=None, task=None):
        self.node = node
        self.task = task
        super().__init__(message)




episode.py:

"""
SimulationContext, EpisodeContext, and EpisodeSummary dataclasses.
"""

from __future__ import annotations
from dataclasses import dataclass, field
from typing import List, Any, Dict
from pureedgesim.types.node import Node


@dataclass(frozen=True)
class SimulationContext:
    """
    Metadata about the full simulation session across all episodes.
    """
    total_episodes: int
    look_ahead_window_size: int
    heartbeat_interval: float
    strict_validation: bool = True
    protocol_version: str = "1.0"
    metadata: Dict[str, Any] = field(default_factory=dict)


@dataclass(frozen=True)
class EpisodeContext:
    """
    Metadata about the current simulation scenario/episode.
    """
    episode_id: int
    algorithm_name: str
    architecture_name: str
    num_devices: int
    sim_duration: float
    num_candidate_nodes: int
    nodes: List[Node]
    metadata: Dict[str, Any] = field(default_factory=dict)

    @property
    def action_space_size(self) -> int:
        """Alias for num_candidate_nodes."""
        return self.num_candidate_nodes


@dataclass(frozen=True)
class EpisodeSummary:
    """
    Aggregate statistics for a completed episode.
    """
    episode_id: int
    total_tasks: int
    successful_tasks: int
    failed_tasks: int
    cached_tasks: int
    sim_duration: float
    metadata: Dict[str, Any] = field(default_factory=dict)

    @property
    def success_rate(self) -> float:
        """Fraction of tasks executed successfully."""
        return self.successful_tasks / max(1, self.total_tasks)

    @property
    def failure_rate(self) -> float:
        """Fraction of tasks that failed."""
        return self.failed_tasks / max(1, self.total_tasks)

    @property
    def cache_hit_rate(self) -> float:
        """Fraction of tasks served via cached placements."""
        return self.cached_tasks / max(1, self.total_tasks)




network.py

"""
NetworkState dataclass representing communication link utilization.
"""

from __future__ import annotations
from dataclasses import dataclass, field
from typing import Any, Dict


@dataclass(frozen=True)
class NetworkState:
    """
    Snapshot of network conditions at decision time.
    """
    wan_uplink_utilization: float   # WAN uplink load fraction (0.0 to 1.0)
    metadata: Dict[str, Any] = field(default_factory=dict)




node.py

"""
Node, NodeType, and Location dataclasses representing computing nodes and topology coordinates.
"""

from __future__ import annotations
from dataclasses import dataclass, field
from enum import Enum, auto
from typing import Any, Dict, TYPE_CHECKING

if TYPE_CHECKING:
    from pureedgesim.types.task import Task


class NodeType(Enum):
    """Classification of computing node hardware."""
    CLOUD       = auto()
    EDGE_SERVER = auto()   # Java: EDGE_DATACENTER
    EDGE_DEVICE = auto()   # Java: EDGE_DEVICE (mist node)


_JAVA_TYPE_MAP = {
    'CLOUD': NodeType.CLOUD,
    'EDGE_DATACENTER': NodeType.EDGE_SERVER,
    'EDGE_DEVICE': NodeType.EDGE_DEVICE,
}


def node_type_from_java(java_str: str) -> NodeType:
    """Convert Java PureEdgeSim type string to NodeType enum."""
    return _JAVA_TYPE_MAP.get(java_str, NodeType.EDGE_DEVICE)


@dataclass(frozen=True)
class Location:
    """Physical 2D coordinates in meters."""
    x: float
    y: float

    def distance_to(self, other: Location) -> float:
        """Euclidean distance in meters to another location."""
        return ((self.x - other.x) ** 2 + (self.y - other.y) ** 2) ** 0.5


@dataclass
class Node:
    """
    A candidate computing node (Cloud, Edge Server, or Edge Device).

    Static fields (capacity, base location) are set once at episode start.
    Dynamic fields (available RAM, CPU utilization, current location) are refreshed
    every decision request.
    """
    # Static identity (constant per episode)
    index: int          # Position in candidate list; equals integer action value
    id: int             # Internal PureEdgeSim node ID
    type: NodeType
    total_mips: float
    mips_per_core: float
    num_cores: int
    total_ram_mb: float
    total_storage_mb: float
    is_peripheral: bool
    base_location: Location

    # Dynamic runtime state (refreshed on every decision request)
    available_ram_mb: float
    available_storage_mb: float
    cpu_utilization: float       # Instantaneous CPU utilization fraction (0.0 to 1.0)
    avg_cpu_utilization: float   # Time-averaged CPU utilization since sim start
    is_idle: bool
    is_alive: bool               # False if battery depleted (edge devices only)
    queued_tasks: int
    current_location: Location

    metadata: Dict[str, Any] = field(default_factory=dict)

    @property
    def is_cloud(self) -> bool:
        """True if node is a Cloud DataCenter."""
        return self.type == NodeType.CLOUD

    @property
    def is_edge_server(self) -> bool:
        """True if node is an Edge DataCenter/Server."""
        return self.type == NodeType.EDGE_SERVER

    @property
    def is_edge_device(self) -> bool:
        """True if node is an Edge Device (Mist)."""
        return self.type == NodeType.EDGE_DEVICE

    @property
    def available_mips(self) -> float:
        """Estimated currently available compute capacity in MIPS."""
        return self.total_mips * max(0.0, 1.0 - self.cpu_utilization)

    @property
    def ram_utilization(self) -> float:
        """Current RAM utilization fraction (0.0 to 1.0)."""
        if self.total_ram_mb <= 0:
            return 0.0
        return 1.0 - (self.available_ram_mb / self.total_ram_mb)

    def can_accept(self, task: Task) -> bool:
        """
        Quick check if this node is alive and has sufficient RAM and storage for task.
        """
        return (
            self.is_alive
            and self.available_ram_mb >= task.ram_required_mb
            and self.available_storage_mb >= task.container_size_mb
        )




state.py

"""
SimulationState dataclass representing full world snapshot delivered to select_node.
"""

from __future__ import annotations
from dataclasses import dataclass, field
from typing import List, Any, Dict

from pureedgesim.types.node import Node, NodeType
from pureedgesim.types.task import Task
from pureedgesim.types.network import NetworkState


@dataclass(frozen=True)
class SimulationState:
    """
    Immutable snapshot of the simulation world at the moment select_node() is called.
    """
    clock: float
    nodes: List[Node]
    pending_tasks: List[Task]
    tasks_in_flight: int
    network: NetworkState
    metadata: Dict[str, Any] = field(default_factory=dict)

    @property
    def alive_nodes(self) -> List[Node]:
        """All nodes that are not dead (battery alive)."""
        return [n for n in self.nodes if n.is_alive]

    @property
    def idle_nodes(self) -> List[Node]:
        """Nodes with no currently executing task."""
        return [n for n in self.nodes if n.is_idle and n.is_alive]

    @property
    def cloud_nodes(self) -> List[Node]:
        """Cloud datacenter nodes."""
        return [n for n in self.nodes if n.type == NodeType.CLOUD]

    @property
    def edge_servers(self) -> List[Node]:
        """Edge datacenter/server nodes."""
        return [n for n in self.nodes if n.type == NodeType.EDGE_SERVER]

    @property
    def edge_devices(self) -> List[Node]:
        """Edge device (mist) nodes."""
        return [n for n in self.nodes if n.type == NodeType.EDGE_DEVICE]

    def candidates_for(self, task: Task) -> List[Node]:
        """
        Returns nodes that can accept task (alive, sufficient RAM & storage).
        """
        return [
            n for n in self.nodes
            if n.is_alive
            and n.available_ram_mb >= task.ram_required_mb
            and n.available_storage_mb >= task.container_size_mb
        ]




task.py

"""
Task, TaskStatus, FailureReason, and TaskOutcome dataclasses.
"""

from __future__ import annotations
from dataclasses import dataclass, field
from enum import Enum, auto
from typing import Optional, Any, Dict, TYPE_CHECKING

if TYPE_CHECKING:
    from pureedgesim.types.node import Node, Location


class TaskStatus(Enum):
    """Task execution outcome status."""
    SUCCESS = auto()
    FAILED  = auto()


class FailureReason(Enum):
    """Reason for task execution failure."""
    LATENCY          = auto()   # Deadline exceeded
    DEAD_NODE        = auto()   # Node battery died
    NO_RESOURCE      = auto()   # Insufficient RAM or storage
    OUT_OF_RANGE     = auto()   # Device moved out of range
    NO_CANDIDATES    = auto()   # select_node returned None
    INVALID_DECISION = auto()   # Invalid node selection
    UNKNOWN          = auto()


_JAVA_FAILURE_MAP = {
    'FAILED_DUE_TO_LATENCY': FailureReason.LATENCY,
    'FAILED_BECAUSE_DEVICE_DEAD': FailureReason.DEAD_NODE,
    'FAILED_DUE_TO_DEVICE_MOBILITY': FailureReason.OUT_OF_RANGE,
    'NO_OFFLOADING_DESTINATIONS': FailureReason.NO_CANDIDATES,
    'INSUFFICIENT_RESOURCES': FailureReason.NO_RESOURCE,
    'INSUFFICIENT_POWER': FailureReason.DEAD_NODE,
    'NOT_GENERATED_BECAUSE_DEVICE_DEAD': FailureReason.DEAD_NODE,
}


@dataclass(frozen=True)
class Task:
    """
    A computation task requiring an offloading placement decision.

    All size fields are stored in Megabytes (MB).
    """
    id: int
    length_mi: float                # Compute demand in Million Instructions (MI)
    input_size_mb: float            # Request upload size in MB
    output_size_mb: float           # Result download size in MB
    container_size_mb: float        # RAM & Storage footprint in MB
    ram_required_mb: float          # Alias for container_size_mb
    deadline: float                 # Max latency in seconds
    app_id: int
    app_type: str
    origin_node_id: int
    origin_node_index: int
    origin_location: Location
    origin_cpu_utilization: float
    scheduled_arrival: float
    metadata: Dict[str, Any] = field(default_factory=dict)

    @property
    def is_compute_intensive(self) -> bool:
        """True if compute demand > 1000 MI."""
        return self.length_mi > 1000.0

    @property
    def is_data_intensive(self) -> bool:
        """True if upload payload > 5 MB."""
        return self.input_size_mb > 5.0

    @property
    def is_latency_sensitive(self) -> bool:
        """True if deadline < 1.0 second."""
        return self.deadline < 1.0


@dataclass(frozen=True)
class TaskOutcome:
    """
    Complete result of a task execution, delivered to on_task_complete().
    """
    task: Task
    assigned_node: Optional[Node]
    status: TaskStatus
    failure_reason: Optional[FailureReason]
    total_latency: float
    computation_time: float
    network_time: float
    queue_wait_time: float
    execution_start: float
    execution_end: float
    result_returned_at: float
    was_cached: bool = False
    request_id: int = -1
    metadata: Dict[str, Any] = field(default_factory=dict)

    @property
    def met_deadline(self) -> bool:
        """True if task succeeded and total latency <= deadline."""
        return self.status == TaskStatus.SUCCESS and self.total_latency <= self.task.deadline

    @property
    def deadline_excess(self) -> float:
        """Seconds by which deadline was exceeded (0.0 if met)."""
        return max(0.0, self.total_latency - self.task.deadline)

    @property
    def deadline_slack(self) -> float:
        """Remaining seconds before deadline (negative if violated)."""
        return self.task.deadline - self.total_latency




feature.py

"""
Feature vectorization utilities for converting Task, Node, and SimulationState to NumPy arrays.
"""

from __future__ import annotations
import numpy as np

from pureedgesim.types.node import Node, NodeType
from pureedgesim.types.task import Task
from pureedgesim.types.state import SimulationState


def task_to_array(task: Task) -> np.ndarray:
    """
    Vectorize a Task object into a 1D float32 NumPy array.

    Fields (9):
        [length_mi, input_size_mb, output_size_mb, container_size_mb,
         deadline, app_id, origin_location_x, origin_location_y,
         origin_cpu_utilization]
    """
    if task is None:
        return np.zeros(9, dtype=np.float32)

    return np.array([
        float(task.length_mi),
        float(task.input_size_mb),
        float(task.output_size_mb),
        float(task.container_size_mb),
        float(task.deadline),
        float(task.app_id),
        float(task.origin_location.x) if task.origin_location else 0.0,
        float(task.origin_location.y) if task.origin_location else 0.0,
        float(task.origin_cpu_utilization),
    ], dtype=np.float32)


def node_to_array(node: Node) -> np.ndarray:
    """
    Vectorize a Node's dynamic state into a 1D float32 NumPy array.

    Fields (9):
        [cpu_utilization, avg_cpu_utilization, available_ram_fraction,
         available_storage_fraction, is_idle, is_alive, queued_tasks,
         current_location_x, current_location_y]
    """
    if node is None:
        return np.zeros(9, dtype=np.float32)

    ram_frac = node.available_ram_mb / max(1.0, node.total_ram_mb)
    storage_frac = node.available_storage_mb / max(1.0, node.total_storage_mb)

    return np.array([
        float(node.cpu_utilization),
        float(node.avg_cpu_utilization),
        float(ram_frac),
        float(storage_frac),
        1.0 if node.is_idle else 0.0,
        1.0 if node.is_alive else 0.0,
        float(node.queued_tasks),
        float(node.current_location.x) if node.current_location else 0.0,
        float(node.current_location.y) if node.current_location else 0.0,
    ], dtype=np.float32)


def node_static_to_array(node: Node) -> np.ndarray:
    """
    Vectorize a Node's static properties into a 1D float32 NumPy array.

    Fields (11):
        [total_mips, mips_per_core, num_cores, total_ram_mb,
         total_storage_mb, is_cloud, is_edge_server, is_edge_device,
         is_peripheral, base_location_x, base_location_y]
    """
    if node is None:
        return np.zeros(11, dtype=np.float32)

    return np.array([
        float(node.total_mips),
        float(node.mips_per_core),
        float(node.num_cores),
        float(node.total_ram_mb),
        float(node.total_storage_mb),
        1.0 if node.type == NodeType.CLOUD else 0.0,
        1.0 if node.type == NodeType.EDGE_SERVER else 0.0,
        1.0 if node.type == NodeType.EDGE_DEVICE else 0.0,
        1.0 if node.is_peripheral else 0.0,
        float(node.base_location.x) if node.base_location else 0.0,
        float(node.base_location.y) if node.base_location else 0.0,
    ], dtype=np.float32)


def state_to_array(
    state: SimulationState,
    include_pending: bool = True,
    max_pending: int = 10,
) -> np.ndarray:
    """
    Flatten SimulationState into a 1D float32 NumPy array.

    Layout:
        Global: [clock, tasks_in_flight, wan_utilization] (3)
        Nodes: len(nodes) * 9
        Pending tasks: max_pending * 10 (zero-padded)

    Total shape: (3 + len(nodes)*9 + max_pending*10,)
    """
    if state is None:
        global_arr = np.zeros(3, dtype=np.float32)
        return global_arr

    global_arr = np.array([
        float(state.clock),
        float(state.tasks_in_flight),
        float(state.network.wan_uplink_utilization) if state.network else 0.0,
    ], dtype=np.float32)

    nodes_arr_list = [node_to_array(n) for n in state.nodes]
    nodes_arr = np.concatenate(nodes_arr_list) if nodes_arr_list else np.array([], dtype=np.float32)

    pending_list = []
    if include_pending and max_pending > 0:
        tasks = state.pending_tasks[:max_pending]
        for t in tasks:
            pt_vec = np.array([
                float(t.scheduled_arrival),
                float(t.length_mi),
                float(t.input_size_mb),
                float(t.output_size_mb),
                float(t.container_size_mb),
                float(t.deadline),
                float(t.app_id),
                float(t.origin_node_index),
                0.0,
                0.0,
            ], dtype=np.float32)
            pending_list.append(pt_vec)

        # Pad with zeros if fewer than max_pending
        while len(pending_list) < max_pending:
            pending_list.append(np.zeros(10, dtype=np.float32))

        pending_arr = np.concatenate(pending_list)
    else:
        pending_arr = np.array([], dtype=np.float32)

    return np.concatenate([global_arr, nodes_arr, pending_arr]).astype(np.float32)




orchestrator.py

"""
Base Orchestrator and RLOrchestrator abstract base classes for PureEdgeSim Python SDK.
"""

from __future__ import annotations
from abc import ABC, abstractmethod
from typing import List, Optional, Tuple
import numpy as np

from pureedgesim.types.node import Node
from pureedgesim.types.task import Task, TaskOutcome
from pureedgesim.types.state import SimulationState
from pureedgesim.types.episode import EpisodeContext, EpisodeSummary, SimulationContext


class Orchestrator(ABC):
    """
    Abstract base class for all PureEdgeSim Python orchestration algorithms.

    Subclass this and implement select_node(). All other lifecycle methods are optional.
    """

    def on_start(self, context: SimulationContext) -> None:
        """
        Called once when Python connects to Java, before the first episode begins.
        """
        pass

    def on_episode_begin(self, episode: EpisodeContext, nodes: List[Node]) -> None:
        """
        Called at the beginning of each simulation episode/scenario.
        """
        pass

    @abstractmethod
    def select_node(
        self,
        task: Task,
        state: SimulationState,
    ) -> Optional[Node]:
        """
        Primary scheduling decision method. Called for every task needing placement.

        Args:
            task: Task requiring placement.
            state: Current world state snapshot.

        Returns:
            A Node from state.nodes to assign task to, or None to fail task.
        """
        ...

    def on_task_complete(self, outcome: TaskOutcome) -> None:
        """
        Called asynchronously when a task finishes execution or fails.
        """
        pass

    def on_tick(self, state: SimulationState) -> None:
        """
        Called periodically at fixed simulated-time intervals if heartbeat is enabled.
        """
        pass

    def on_episode_end(self, summary: EpisodeSummary) -> None:
        """
        Called when a simulation episode finishes.
        """
        pass

    def on_shutdown(self) -> None:
        """
        Called once before the Python process exits.
        """
        pass


class RLOrchestrator(Orchestrator):
    """
    Subclass of Orchestrator tailored for Reinforcement Learning algorithms.

    Provides observation space vectorization, reward function hooks, and episode reward tracking.
    """

    def __init__(self) -> None:
        super().__init__()
        self._action_space_size: int = 0
        self._episode_reward: float = 0.0
        self._episode_steps: int = 0

    @property
    def action_space_size(self) -> int:
        """Number of candidate computing nodes in current episode."""
        return self._action_space_size

    @property
    def episode_reward(self) -> float:
        """Cumulative reward accumulated in current episode."""
        return self._episode_reward

    @property
    def episode_steps(self) -> int:
        """Number of completed task outcomes received in current episode."""
        return self._episode_steps

    def on_episode_begin(self, episode: EpisodeContext, nodes: List[Node]) -> None:
        """Initialize RL episode tracking state."""
        super().on_episode_begin(episode, nodes)
        if episode is not None:
            self._action_space_size = episode.num_candidate_nodes
        else:
            self._action_space_size = len(nodes)
        self._episode_reward = 0.0
        self._episode_steps = 0

    def observation(
        self,
        task: Task,
        state: SimulationState,
        include_pending: bool = True,
        max_pending: int = 10,
    ) -> np.ndarray:
        """
        Construct a flat 1D NumPy float32 feature vector representing (task, state).
        """
        from pureedgesim.features import state_to_array, task_to_array
        t_arr = task_to_array(task)
        s_arr = state_to_array(state, include_pending=include_pending, max_pending=max_pending)
        return np.concatenate([t_arr, s_arr]).astype(np.float32)

    def reward(self, outcome: TaskOutcome) -> float:
        """
        Compute reward for a completed task outcome. Override to customize reward logic.
        """
        from pureedgesim.rewards import default_reward
        return default_reward(outcome)

    def on_task_complete(self, outcome: TaskOutcome) -> None:
        """Accumulate episode reward and step count."""
        super().on_task_complete(outcome)
        r = self.reward(outcome)
        self._episode_reward += r
        self._episode_steps += 1




reward.py

"""
Built-in reward functions and CompositeReward helper for RL orchestrators.
"""

from __future__ import annotations
from typing import Callable, List, Tuple
from pureedgesim.types.task import TaskOutcome, TaskStatus

RewardFn = Callable[[TaskOutcome], float]


def latency_reward(outcome: TaskOutcome) -> float:
    """Reward proportional to latency headroom below deadline."""
    if outcome.status == TaskStatus.FAILED:
        return -1.0
    return outcome.deadline_slack / max(1e-6, outcome.task.deadline)


def success_reward(outcome: TaskOutcome) -> float:
    """Binary reward: +1 for success, -1 for failure."""
    return 1.0 if outcome.status == TaskStatus.SUCCESS else -1.0


def deadline_reward(outcome: TaskOutcome) -> float:
    """Binary reward: +1 if deadline met, -1 if violated or failed."""
    return 1.0 if outcome.met_deadline else -1.0


def energy_reward(outcome: TaskOutcome) -> float:
    """Energy-aware reward component."""
    joules = outcome.metadata.get("energy_joules")
    return -float(joules) if joules is not None else 0.0


def default_reward(
    outcome: TaskOutcome,
    success_weight: float = 1.0,
    latency_weight: float = 1.0,
    deadline_penalty: float = 2.0,
) -> float:
    """
    Default weighted reward combination.
    """
    if outcome.status == TaskStatus.FAILED:
        base = -success_weight
    else:
        base = success_weight

    if outcome.met_deadline:
        latency_bonus = latency_weight * (1.0 - outcome.total_latency / max(1e-6, outcome.task.deadline))
    else:
        latency_bonus = -deadline_penalty * outcome.deadline_excess / max(1e-6, outcome.task.deadline)

    return base + latency_bonus


class CompositeReward:
    """
    Weighted combination of multiple reward functions.
    """

    def __init__(self, components: List[Tuple[RewardFn, float]]):
        self._components = components
        self._total_weight = sum(w for _, w in components) if components else 1.0

    def __call__(self, outcome: TaskOutcome) -> float:
        if not self._components:
            return 0.0
        return sum(fn(outcome) * w for fn, w in self._components) / self._total_weight




