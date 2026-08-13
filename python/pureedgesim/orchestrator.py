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
