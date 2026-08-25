# M6 Python Bridge Replay — Run Log

**Run date:** 2026-08-21 22:14:45 PST
**Orchestrator:** `examples.run_trace_round_robin.TraceRoundRobinOrchestrator`
**Trace file:** `data/processed/pureedgesim_tasks_15min.json`
**Settings:** `settings_trace_test/` (CLOUD_ONLY, sim_time=1000s, batch_size=100)
**Wall time:** 27s

---

## IPC Socket Stability

| Metric | Value | Pass? |
|---|---|---|
| IPC handshake | success | ✅ |
| IPC timeouts | 0 | ✅ |
| Bridge crashes | 0 | ✅ |

---

## Task Delivery

| Metric | Value |
|---|---|
| Expected tasks | 6258 |
| Tasks dispatched to Python | 48 |
| Tasks completed (results returned) | 2051 |
| Tasks success | 25 |
| Tasks failed | 2026 |
| Success rate | 0.5208 |
| Deadline violations | 21 |
| Deadline violation rate | 0.8400 |

---

## Decision Throughput (IPC Latency)

| Metric | Value |
|---|---|
| Avg decision latency | 0.026 ms |
| P95 decision latency | 0.033 ms |
| Throughput | 3.2 tasks/s |

---

## Reward

| Metric | Value |
|---|---|
| Cumulative episode reward | -632135808.17 |

---

## Finish Status Breakdown

`{'KILL':`

---

## Raw Log

Full run log: `docs/logs/m6_run_20260821_221416.log`
