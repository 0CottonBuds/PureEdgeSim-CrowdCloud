"""
NearestNodeOrchestrator example algorithm.
"""

from typing import Optional
from pureedgesim import Orchestrator
from pureedgesim.types import Task, SimulationState, Node


class NearestNodeOrchestrator(Orchestrator):
    """
    Heuristic orchestrator assigning task to the physically nearest candidate node.
    """

    def select_node(self, task: Task, state: SimulationState) -> Optional[Node]:
        candidates = state.candidates_for(task)
        return min(
            candidates,
            key=lambda n: n.current_location.distance_to(task.origin_location),
            default=None
        )
