"""
RoundRobinOrchestrator example algorithm.
"""

from typing import Optional
from pureedgesim import Orchestrator
from pureedgesim.types import Task, SimulationState, Node


class RoundRobinOrchestrator(Orchestrator):
    """
    Simple Round-Robin task orchestrator algorithm cycling through viable candidate nodes.
    """

    def on_episode_begin(self, episode, nodes):
        self._counter = 0

    def select_node(self, task: Task, state: SimulationState) -> Optional[Node]:
        candidates = state.candidates_for(task)
        if not candidates:
            return None
        node = candidates[self._counter % len(candidates)]
        self._counter += 1
        return node
