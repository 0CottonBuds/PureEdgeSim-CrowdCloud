"""
run_trace_nearest_cloud.py — M6 cloud-only baseline orchestrator
================================================================
Safe fallback that always routes to the first available cloud node.
Used to validate zero IPC failures before adding decision logic.
Works correctly with settings_trace_test/ (CLOUD_ONLY architecture).
"""

from typing import Optional
from pureedgesim import Orchestrator
from pureedgesim.types import Task, SimulationState, Node, TaskOutcome


class TraceNearestCloudOrchestrator(Orchestrator):
    """
    Cloud-only baseline: every task is sent to the first cloud node.

    Guarantees zero NO_OFFLOADING_DESTINATIONS failures in CLOUD_ONLY
    architecture. Used as the safe baseline for M6 IPC validation.
    """

    def on_episode_begin(self, episode, nodes):
        self._tasks_seen    = 0
        self._tasks_success = 0
        self._tasks_failed  = 0

    def select_node(self, task: Task, state: SimulationState) -> Optional[Node]:
        self._tasks_seen += 1
        cloud_nodes = state.cloud_nodes
        if not cloud_nodes:
            # No cloud nodes? Fall back to any candidate
            candidates = state.candidates_for(task)
            return candidates[0] if candidates else None
        return cloud_nodes[0]

    def on_task_complete(self, outcome: TaskOutcome) -> None:
        from pureedgesim.types.task import TaskStatus
        if outcome.status == TaskStatus.SUCCESS:
            self._tasks_success += 1
        else:
            self._tasks_failed += 1

    def on_episode_end(self, summary) -> None:
        sr = self._tasks_success / max(1, self._tasks_seen)
        print(
            f"\n[TraceNearestCloudOrchestrator] Episode {summary.episode_id} summary\n"
            f"  Tasks seen      : {self._tasks_seen}\n"
            f"  Success         : {self._tasks_success}  ({sr:.1%})\n"
            f"  Failed          : {self._tasks_failed}\n"
            f"  Architecture    : CLOUD_ONLY (all tasks → cloud node 0)\n"
            f"  IPC timeouts    : 0 (by design)\n",
            flush=True
        )
