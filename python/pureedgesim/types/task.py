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
