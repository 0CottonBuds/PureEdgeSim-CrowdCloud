"""
DQNOrchestrator example skeleton for Reinforcement Learning algorithms.
"""

import random
from typing import Optional
from pureedgesim import RLOrchestrator
from pureedgesim.types import Task, SimulationState, TaskOutcome, Node
from pureedgesim.rewards import CompositeReward, deadline_reward, latency_reward


class DQNOrchestrator(RLOrchestrator):
    """
    Skeleton RL Orchestrator demonstrating state vectorization and asynchronous reward tracking.
    """

    def on_start(self, context):
        self._pending = {}
        self._reward_fn = CompositeReward([
            (deadline_reward, 2.0),
            (latency_reward, 1.0),
        ])

    def on_episode_begin(self, episode, nodes):
        super().on_episode_begin(episode, nodes)
        self._pending.clear()

    def select_node(self, task: Task, state: SimulationState) -> Optional[Node]:
        obs = self.observation(task, state)
        candidates = state.candidates_for(task)
        if not candidates:
            return None

        # Random action choice as fallback
        selected_node = random.choice(candidates)
        req_id = task.metadata.get("request_id", -1)
        self._pending[req_id] = (obs, selected_node.index)
        return selected_node

    def reward(self, outcome: TaskOutcome) -> float:
        return self._reward_fn(outcome)

    def on_task_complete(self, outcome: TaskOutcome) -> None:
        super().on_task_complete(outcome)
        if outcome.was_cached:
            return

        key = outcome.request_id
        if key not in self._pending:
            return

        obs, action = self._pending.pop(key)
        r = self.reward(outcome)

    def on_episode_end(self, summary):
        print(f"[Ep {summary.episode_id}] "
              f"reward={self.episode_reward:.2f}  "
              f"success={summary.success_rate:.1%}  "
              f"steps={self.episode_steps}")
