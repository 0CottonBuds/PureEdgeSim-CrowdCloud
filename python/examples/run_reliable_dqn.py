from __future__ import annotations

import math
import random
from typing import Dict, List, Optional, Sequence, Tuple

import numpy as np

from pureedgesim import RLOrchestrator
from pureedgesim.types import FailureReason, Node, SimulationState, Task, TaskOutcome

try:
    import torch
    import torch.nn as nn
except ImportError:  # pragma: no cover
    torch = None
    nn = None


def _safe(value: float, default: float = 0.0) -> float:
    """Return a finite float, defaulting when values are invalid."""
    if value is None or not math.isfinite(float(value)):
        return default
    return float(value)


class ReliabilityAwareQNetwork(nn.Module):
    """Small MLP that scores a candidate node using reliability-aware features."""

    def __init__(self, input_dim: int):
        super().__init__()
        self.net = nn.Sequential(
            nn.Linear(input_dim, 32),
            nn.ReLU(),
            nn.Linear(32, 16),
            nn.ReLU(),
            nn.Linear(16, 1),
        )

    def forward(self, x: torch.Tensor) -> torch.Tensor:
        return self.net(x)


class ReliableDQNOrchestrator(RLOrchestrator):

    def __init__(self, epsilon: float = 0.10, learning_rate: float = 0.001):
        super().__init__()
        self.epsilon = epsilon
        self.learning_rate = learning_rate
        self._pending: Dict[int, Tuple[np.ndarray, int]] = {}
        self._q_network: Optional[ReliabilityAwareQNetwork] = None
        self._feature_dim = 0
        self._training_history: List[Tuple[np.ndarray, int, float]] = []

    def _node_reliability(self, node: Node) -> float:
        """Compute a normalized reliability score for a node in [0, 1]."""
        metadata = getattr(node, "metadata", {}) or {}

        availability = _safe(metadata.get("availability_probability", metadata.get("availability", 0.7)), 0.7)
        success_ratio = _safe(metadata.get("success_ratio", metadata.get("historical_success", 0.7)), 0.7)
        battery = _safe(metadata.get("battery_level", 1.0 if node.is_alive else 0.0), 0.0)
        liveness = 1.0 if node.is_alive else 0.0
        risk = _safe(metadata.get("failure_risk", 1.0 - ((availability + success_ratio) / 2.0)), 0.0)

        score = 0.45 * availability
        score += 0.35 * success_ratio
        score += 0.10 * battery
        score += 0.10 * liveness
        score -= 0.25 * risk
        return float(np.clip(score, 0.0, 1.0))

    def _node_distance(self, task: Task, node: Node) -> float:
        try:
            return float(node.current_location.distance_to(task.origin_location))
        except Exception:
            return 0.0

    def _candidate_feature_vector(self, task: Task, node: Node) -> np.ndarray:
        """Build a reliability-aware per-node feature vector."""
        total_ram = max(node.total_ram_mb, 1.0)
        total_storage = max(node.total_storage_mb, 1.0)
        total_mips = max(node.total_mips, 1.0)
        distance = self._node_distance(task, node)
        reliability = self._node_reliability(node)

        feature = np.array(
            [
                _safe(task.length_mi / 1000.0, 0.0),
                _safe(task.input_size_mb / 100.0, 0.0),
                _safe(task.output_size_mb / 50.0, 0.0),
                _safe(task.container_size_mb / 200.0, 0.0),
                _safe(task.deadline / 60.0, 0.0),
                _safe(node.available_mips / total_mips, 0.0),
                _safe(node.available_ram_mb / total_ram, 0.0),
                _safe(node.available_storage_mb / total_storage, 0.0),
                _safe(node.cpu_utilization, 0.0),
                _safe(node.queued_tasks / 20.0, 0.0),
                reliability,
                1.0 if node.is_alive else 0.0,
                _safe(distance / 1000.0, 0.0),
                _safe((1.0 - node.cpu_utilization) * reliability, 0.0),
            ],
            dtype=np.float32,
        )
        return feature

    def _build_action_features(self, task: Task, candidates: Sequence[Node]) -> List[np.ndarray]:
        return [self._candidate_feature_vector(task, node) for node in candidates]

    def on_episode_begin(self, episode, nodes):
        super().on_episode_begin(episode, nodes)
        self._pending.clear()
        self._training_history.clear()

        if torch is not None and nn is not None:
            if self._q_network is None:
                self._feature_dim = 14
                self._q_network = ReliabilityAwareQNetwork(self._feature_dim)

    def select_node(self, task: Task, state: SimulationState) -> Optional[Node]:
        candidates = list(state.candidates_for(task))
        if not candidates:
            return None

        candidate_features = self._build_action_features(task, candidates)
        self._feature_dim = len(candidate_features[0])

        if self._q_network is None and torch is not None and nn is not None:
            self._q_network = ReliabilityAwareQNetwork(self._feature_dim)

        if torch is None or random.random() < self.epsilon or self._q_network is None:
            selected = random.choice(candidates)
        else:
            q_scores = []
            for feature in candidate_features:
                x = torch.tensor(feature, dtype=torch.float32).unsqueeze(0)
                with torch.no_grad():
                    q = self._q_network(x)
                q_scores.append(float(q.item()))
            best_index = int(np.argmax(q_scores))
            selected = candidates[best_index]

        self._pending[int(task.id)] = (np.vstack(candidate_features), selected.index)
        return selected

    def reward(self, outcome: TaskOutcome) -> float:
        task = outcome.task
        node = outcome.assigned_node
        reliability = self._node_reliability(node) if node is not None else 0.0

        reward = 0.0

        if outcome.status.name == "SUCCESS":
            reward += 5.0
        else:
            reward -= 4.0

        if node is not None and not node.is_alive:
            reward -= 6.0

        if outcome.failure_reason in {
            FailureReason.LATENCY,
            FailureReason.NO_RESOURCE,
            FailureReason.OUT_OF_RANGE,
            FailureReason.DEAD_NODE,
        }:
            reward -= 5.0

        latency_ratio = _safe(outcome.total_latency / max(task.deadline, 1e-6), 1.0)
        if latency_ratio <= 1.0:
            reward += (1.0 - latency_ratio) * 2.0
        else:
            reward -= (latency_ratio - 1.0) * 4.0

        reward += reliability * 3.0
        reward -= (1.0 - reliability) * 2.0

        if task.is_latency_sensitive and reliability < 0.5:
            reward -= 2.0

        return float(reward)

    def on_task_complete(self, outcome: TaskOutcome) -> None:
        super().on_task_complete(outcome)
        key = int(outcome.task.id)
        if key not in self._pending:
            return

        _, chosen_index = self._pending.pop(key)
        r = self.reward(outcome)
        self._training_history.append((np.zeros(1, dtype=np.float32), chosen_index, r))

