"""
Shortest Job First-inspired Orchestrator.

Uses task computational length and currently available node
processing capacity to select the node with the shortest
estimated processing time.
"""

from __future__ import annotations

from typing import Optional

from pureedgesim import Orchestrator
from pureedgesim.types import Task, SimulationState, Node


class SJFOrchestrator(Orchestrator):
    """
    Shortest Job First-inspired task scheduling baseline.

    The current PureEdgeSim Python API calls select_node() for a
    specific task. Therefore, this implementation assigns the task
    to the candidate node with the shortest estimated computation
    time.

    Estimated computation time:

        task.length_mi / node.available_mips
    """

    def on_episode_begin(self, episode, nodes):
        self._episode = episode

    def select_node(
        self,
        task: Task,
        state: SimulationState,
    ) -> Optional[Node]:

        candidates = state.candidates_for(task)

        if not candidates:
            return None

        # Exclude nodes that cannot currently execute the task.
        viable_nodes = [
            node
            for node in candidates
            if node.is_alive and node.can_accept(task)
        ]

        if not viable_nodes:
            return None

        # Select node producing the shortest estimated computation time.
        # 
        return min(
            viable_nodes,
            key=lambda node: self._estimated_processing_time(task, node)
        )

    @staticmethod
    def _estimated_processing_time(
        task: Task,
        node: Node,
    ) -> float:
        """
        Estimate computation time for task on node.

        task.length_mi:
            Computational demand in Million Instructions.

        node.available_mips:
            Currently available processing capacity in MIPS.
        """

        available_mips = max(node.available_mips, 1e-9)

        return task.length_mi / available_mips

    def on_episode_end(self, summary):
        print(
            f"[SJFOrchestrator Ep {summary.episode_id}] "
            f"Success Rate: {summary.success_rate:.1%} | "
            f"Failure Rate: {summary.failure_rate:.1%} | "
            f"Tasks: {summary.total_tasks}"
        )