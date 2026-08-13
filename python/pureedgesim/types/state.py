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
