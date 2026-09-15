"""
RandomOrchestrator — random task scheduling policy.
Used as a sanity-check baseline to verify DQN training pipeline correctness.
"""

import random
from typing import Optional
from pureedgesim import Orchestrator
from pureedgesim.types import Task, SimulationState, Node


class RandomOrchestrator(Orchestrator):
    """
    Random task orchestrator that selects a candidate node uniformly at random.
    Serves as a lower-bound sanity check — a properly training DQN agent
    should clearly outperform this policy after sufficient training episodes.
    """

    def on_episode_begin(self, episode, nodes):
        pass

    def select_node(self, task: Task, state: SimulationState) -> Optional[Node]:
        candidates = state.cloud_nodes or state.candidates_for(task)
        # candidates = state.candidates_for(task)
        if not candidates:
            return None
        return random.choice(candidates)
    

    def on_episode_end(self, summary):
        print(f"[RandomOrchestrator Ep {summary.episode_id}] "
              f"Success Rate: {summary.success_rate:.1%} | ")