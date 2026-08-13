"""
DQNOrchestrator example skeleton for Reinforcement Learning algorithms.
Demonstrates feature vectorization, reward calculation, and transition logging.
"""

import random
import numpy as np
from typing import Optional, Dict, Tuple
from pureedgesim import RLOrchestrator
from pureedgesim.types import Task, SimulationState, TaskOutcome, Node
from pureedgesim.rewards import CompositeReward, deadline_reward, latency_reward, success_reward

try:
    import torch
    import torch.nn as nn

    class QNetwork(nn.Module):
        def __init__(self, state_dim: int, action_dim: int):
            super().__init__()
            self.net = nn.Sequential(
                nn.Linear(state_dim, 64),
                nn.ReLU(),
                nn.Linear(64, 64),
                nn.ReLU(),
                nn.Linear(64, action_dim),
            )

        def forward(self, x: torch.Tensor) -> torch.Tensor:
            return self.net(x)

except ImportError:
    torch = None
    QNetwork = None


class DQNOrchestrator(RLOrchestrator):
    """
    Reinforcement Learning Orchestrator executing DQN / Epsilon-Greedy policy.
    """

    def __init__(self, epsilon: float = 0.1):
        super().__init__()
        self.epsilon = epsilon
        self._pending: Dict[int, Tuple[np.ndarray, int]] = {}
        self._q_network = None
        self._transitions = []
        self._reward_fn = CompositeReward([
            (deadline_reward, 2.0),
            (latency_reward, 1.0),
            (success_reward, 5.0),
        ])

    def on_start(self, context):
        super().on_start(context)

    def on_episode_begin(self, episode, nodes):
        super().on_episode_begin(episode, nodes)
        self._pending.clear()
        self._transitions.clear()

        if torch is not None and QNetwork is not None and self._q_network is None:
            # Initialize PyTorch network if state dimension is known
            sample_state_dim = 3 + len(nodes) * 9 + 10 * 10
            self._q_network = QNetwork(sample_state_dim, self.action_space_size)

    def select_node(self, task: Task, state: SimulationState) -> Optional[Node]:
        obs = self.observation(task, state)
        candidates = state.candidates_for(task)
        if not candidates:
            return None

        # Prefer cloud nodes or available candidates
        cloud_candidates = [n for n in candidates if n.is_cloud]
        selectable = cloud_candidates if cloud_candidates else candidates

        # Epsilon-greedy action selection
        if random.random() < self.epsilon or self._q_network is None:
            selected_node = random.choice(selectable)
        else:
            with torch.no_grad():
                state_tensor = torch.from_numpy(obs).float().unsqueeze(0)
                q_values = self._q_network(state_tensor).squeeze(0).numpy()
                candidate_indices = [n.index for n in selectable]
                best_idx = candidate_indices[np.argmax([q_values[i] for i in candidate_indices])]
                selected_node = next(n for n in selectable if n.index == best_idx)

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
        self._transitions.append((obs, action, r))

    def on_episode_end(self, summary):
        print(f"[DQNOrchestrator Ep {summary.episode_id}] "
              f"Cumulative Reward: {self.episode_reward:.2f} | "
              f"Success Rate: {summary.success_rate:.1%} | "
              f"Steps: {self.episode_steps} | "
              f"Recorded Transitions: {len(self._transitions)}")
