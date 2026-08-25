"""
run_trace_round_robin.py — M6 priority-aware trace orchestrator
================================================================
Google Cluster Trace v3 aware orchestrator that:

  1. Reads trace metadata from task.metadata (scheduling_class, priority,
     finish_status) serialised by StreamedTraceTaskGenerator as app_type
     string "SC{sc}_P{pri}_{status}".

  2. Applies a priority-aware routing policy:
       - Priority >= 440 (production batch) → always cloud node
       - Priority 100-439 → round-robin across cloud nodes
       - Priority < 100 (best-effort) → last cloud node (lowest priority)

  3. Records per-episode IPC telemetry:
       - decision_count, result_count (= on_task_complete calls)
       - success_rate, deadline_violation_rate
       - cumulative_reward

  4. Prints a machine-readable summary at episode end for run_m6_replay.sh.

Design notes:
  - Works with settings_trace_test/ (CLOUD_ONLY architecture).
    All nodes in state.cloud_nodes — no edge routing attempted.
  - task.metadata dict carries request_id; task.app_type carries SC/P/status.
  - Priority tiers mirror Google Cluster v3 priority bands:
      0-99   best-effort  (free tier, easily preempted)
      100-439 batch       (normal production)
      440+   production   (SLO-bound, highest priority)
"""

import time
import re
from collections import defaultdict
from typing import Optional

from pureedgesim import RLOrchestrator
from pureedgesim.rewards import CompositeReward, deadline_reward, latency_reward, success_reward
from pureedgesim.types import Task, SimulationState, Node, TaskOutcome
from pureedgesim.types.task import TaskStatus


# ── Priority band thresholds (Google Cluster v3) ─────────────────────────────
PRIORITY_HIGH   = 440   # production / SLO-bound
PRIORITY_MEDIUM = 100   # batch / normal
# < 100           = best-effort / free tier


def _parse_metadata(task: Task) -> dict:
    """
    Extract scheduling_class, priority, finish_status from task.app_type.
    Format: "SC{scheduling_class}_P{priority}_{finish_status}"
    Example: "SC1_P200_KILL"

    Falls back to defaults if app_type is not in the expected format.
    """
    result = {"scheduling_class": 0, "priority": 0, "finish_status": "UNKNOWN"}
    app_type = getattr(task, "app_type", "") or ""
    m = re.fullmatch(r"SC(\d+)_P(\d+)_(\w+)", app_type)
    if m:
        result["scheduling_class"] = int(m.group(1))
        result["priority"]         = int(m.group(2))
        result["finish_status"]    = m.group(3)
    return result


class TraceRoundRobinOrchestrator(RLOrchestrator):
    """
    Priority-aware round-robin orchestrator for Google Cluster Trace replay.

    Routes tasks based on Google Cluster v3 priority bands, always staying
    within the cloud node pool (required for CLOUD_ONLY architecture).
    Collects IPC telemetry and episode statistics for M6 logging.
    """

    def on_episode_begin(self, episode, nodes) -> None:
        super().on_episode_begin(episode, nodes)

        # Round-robin counters per priority band
        self._counters: dict[str, int] = defaultdict(int)

        # IPC telemetry
        self._decision_count    = 0
        self._result_count      = 0
        self._timeout_count     = 0   # incremented if select_node returns None unexpectedly
        self._ipc_latency_ms    = []  # wall-clock time per select_node call
        self._start_wall        = time.monotonic()

        # Outcome tracking
        self._success_count     = 0
        self._failed_count      = 0
        self._deadline_violated = 0
        self._priority_stats: dict[str, int] = defaultdict(int)

        # Reward
        self._reward_fn = CompositeReward([
            (deadline_reward, 2.0),
            (latency_reward,  1.0),
            (success_reward,  5.0),
        ])

        self._episode_id = episode.episode_id if episode else 0

    def select_node(self, task: Task, state: SimulationState) -> Optional[Node]:
        t0 = time.monotonic()
        self._decision_count += 1

        meta     = _parse_metadata(task)
        priority = meta["priority"]
        self._priority_stats[meta["finish_status"]] = (
            self._priority_stats.get(meta["finish_status"], 0) + 1
        )

        cloud_nodes = state.cloud_nodes
        if not cloud_nodes:
            # Shouldn't happen in CLOUD_ONLY, but be defensive
            self._timeout_count += 1
            return None

        # ── Priority routing ──────────────────────────────────────────────────
        if priority >= PRIORITY_HIGH:
            # Production tasks: always cloud node 0 (fastest, lowest latency)
            node = cloud_nodes[0]
        elif priority >= PRIORITY_MEDIUM:
            # Batch tasks: round-robin across all cloud nodes
            band_key = "batch"
            idx = self._counters[band_key] % len(cloud_nodes)
            self._counters[band_key] += 1
            node = cloud_nodes[idx]
        else:
            # Best-effort: last cloud node (least contention from production)
            node = cloud_nodes[-1]

        elapsed_ms = (time.monotonic() - t0) * 1000.0
        self._ipc_latency_ms.append(elapsed_ms)

        return node

    def reward(self, outcome: TaskOutcome) -> float:
        return self._reward_fn(outcome)

    def on_task_complete(self, outcome: TaskOutcome) -> None:
        super().on_task_complete(outcome)
        self._result_count += 1

        if outcome.status == TaskStatus.SUCCESS:
            self._success_count += 1
            if not outcome.met_deadline:
                self._deadline_violated += 1
        else:
            self._failed_count += 1

    def on_episode_end(self, summary) -> None:
        wall_s  = time.monotonic() - self._start_wall
        n       = max(1, self._decision_count)
        sr      = self._success_count / max(1, self._decision_count)
        dvr     = self._deadline_violated / max(1, self._success_count)
        avg_lat = sum(self._ipc_latency_ms) / len(self._ipc_latency_ms) if self._ipc_latency_ms else 0.0
        p95_lat = sorted(self._ipc_latency_ms)[int(0.95 * len(self._ipc_latency_ms))] if self._ipc_latency_ms else 0.0
        throughput = self._decision_count / max(1.0, wall_s)

        # Machine-readable summary (parsed by run_m6_replay.sh)
        print(
            f"\n[M6_EPISODE_SUMMARY episode_id={self._episode_id}]\n"
            f"  tasks_dispatched={self._decision_count}\n"
            f"  tasks_completed={self._result_count}\n"
            f"  tasks_success={self._success_count}\n"
            f"  tasks_failed={self._failed_count}\n"
            f"  deadline_violations={self._deadline_violated}\n"
            f"  success_rate={sr:.4f}\n"
            f"  deadline_violation_rate={dvr:.4f}\n"
            f"  ipc_timeouts={self._timeout_count}\n"
            f"  avg_decision_latency_ms={avg_lat:.3f}\n"
            f"  p95_decision_latency_ms={p95_lat:.3f}\n"
            f"  throughput_tasks_per_sec={throughput:.1f}\n"
            f"  cumulative_reward={self.episode_reward:.2f}\n"
            f"  wall_time_s={wall_s:.1f}\n"
            f"  finish_status_breakdown={dict(self._priority_stats)}\n"
            f"[M6_EPISODE_SUMMARY_END]",
            flush=True
        )
