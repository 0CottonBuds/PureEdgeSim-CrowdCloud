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
