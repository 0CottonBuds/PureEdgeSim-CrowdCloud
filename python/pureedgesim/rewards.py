"""
Built-in reward functions and CompositeReward helper for RL orchestrators.
"""

from __future__ import annotations
from typing import Callable, List, Tuple
from pureedgesim.types.task import TaskOutcome, TaskStatus

RewardFn = Callable[[TaskOutcome], float]


def latency_reward(outcome: TaskOutcome) -> float:
    """Reward proportional to latency headroom below deadline."""
    if outcome.status == TaskStatus.FAILED:
        return -1.0
    return outcome.deadline_slack / max(1e-6, outcome.task.deadline)


def success_reward(outcome: TaskOutcome) -> float:
    """Binary reward: +1 for success, -1 for failure."""
    return 1.0 if outcome.status == TaskStatus.SUCCESS else -1.0


def deadline_reward(outcome: TaskOutcome) -> float:
    """Binary reward: +1 if deadline met, -1 if violated or failed."""
    return 1.0 if outcome.met_deadline else -1.0


def energy_reward(outcome: TaskOutcome) -> float:
    """Energy-aware reward component."""
    joules = outcome.metadata.get("energy_joules")
    return -float(joules) if joules is not None else 0.0


def default_reward(
    outcome: TaskOutcome,
    success_weight: float = 1.0,
    latency_weight: float = 1.0,
    deadline_penalty: float = 2.0,
) -> float:
    """
    Default weighted reward combination.
    """
    if outcome.status == TaskStatus.FAILED:
        base = -success_weight
    else:
        base = success_weight

    if outcome.met_deadline:
        latency_bonus = latency_weight * (1.0 - outcome.total_latency / max(1e-6, outcome.task.deadline))
    else:
        latency_bonus = -deadline_penalty * outcome.deadline_excess / max(1e-6, outcome.task.deadline)

    return base + latency_bonus


class CompositeReward:
    """
    Weighted combination of multiple reward functions.
    """

    def __init__(self, components: List[Tuple[RewardFn, float]]):
        self._components = components
        self._total_weight = sum(w for _, w in components) if components else 1.0

    def __call__(self, outcome: TaskOutcome) -> float:
        if not self._components:
            return 0.0
        return sum(fn(outcome) * w for fn, w in self._components) / self._total_weight
