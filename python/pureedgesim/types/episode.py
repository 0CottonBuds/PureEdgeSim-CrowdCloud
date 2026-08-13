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
