# Phase 1.5 — Architecture Design: Streamed Task Generation Strategy

**Document Version:** 1.0  
**Date:** 2026-08-18  
**Scope:** Streamed Workload Replay, Sliding-Window Buffering, Memory-Efficient Task Ingestion, and Integration with PureEdgeSim.

---

## 1. Problem Statement: Upfront Heap Memory Explosion

In standard PureEdgeSim ([phase1_architecture_analysis.md](file:///home/cotton/Projects/ML/Thesis/PureEdgeSim/docs/googlecluter-trace-task-generator/phase1_architecture_analysis.md)), task generation is pre-allocated:

1. During initialization (`SimulationThread.loadModels()`), `TaskGenerator.generate()` reads workload configurations and instantiates **all** simulation `Task` Java objects into `FutureQueue<Task> taskList`.
2. For small synthetic runs (e.g., 500 tasks), upfront creation is negligible.
3. However, real-world Google Cluster Trace v3 datasets span hundreds of thousands to millions of task events ($10^5 \sim 10^6+$ tasks per day).
4. Pre-allocating millions of `Task` objects upfront consumes several gigabytes of JVM heap, triggering heavy Garbage Collection (GC) latency spikes or fatal `java.lang.OutOfMemoryError` crashes.

---

## 2. Proposed Solution: Streamed / Sliding-Window Task Generation

Instead of pre-allocating the full workload upfront, we introduce a **Streamed Task Generator Strategy** (`StreamedTraceTaskGenerator`). Tasks are read from disk line-by-line or in chunked blocks, instantiated on-demand, and fed into a bounded sliding window buffer.

```
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│                             Streamed Task Replay Architecture                               │
│                                                                                             │
│   Google Cluster Trace File (GZ / JSON / CSV)                                               │
│                         │                                                                   │
│                         ▼ (Line-by-line Stream / Buffer Window)                             │
│       ┌───────────────────────────────────┐                                                 │
│       │    StreamedTraceTaskGenerator     │                                                 │
│       └─────────────────┬─────────────────┘                                                 │
│                         │ fills sliding window buffer (e.g., N_buffer = 1000 tasks)         │
│                         v                                                                   │
│       ┌───────────────────────────────────┐                                                 │
│       │      FutureQueue<Task> (Buffer)   │  <-- Constant Memory Footprint O(N_buffer)      │
│       └─────────────────┬─────────────────┘                                                 │
└─────────────────────────┼───────────────────────────────────────────────────────────────────┘
                          │ (Refills on NEXT_BATCH trigger when buffer < low-watermark)
                          v
┌─────────────────────────────────────────────────────────────────────────────────────────────┐
│  DefaultSimulationManager -> PythonOrchestrator -> Unix Domain Socket                       │
│  (Transparent stream: Python Bridge and Event Engine see standard Task stream)             │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
```

---

## 3. Detailed Mechanism & Lifecycle

### 1. Initial Buffer Prefetch (`generate()`)
- When `StreamedTraceTaskGenerator.generate()` is called during startup:
  - Opens a buffered file reader / streaming iterator over the trace file.
  - Reads only an initial window of tasks (e.g., $N_{\text{buffer}} = 1000$ tasks) into `taskList`.
  - Maintains a cursor/offset pointing to the current position in the trace file.
  - Returns the pre-filled `FutureQueue<Task>` to `SimulationManager`.

### 2. On-Demand Stream Replenishment (`fetchNextBatch`)
- `DefaultSimulationManager` continuously processes tasks using `NEXT_BATCH` event triggers.
- As tasks are dequeued from `taskList` and scheduled into the discrete event engine, `taskList.size()` decreases.
- When `taskList.size()` drops below a configurable **Low-Watermark** (e.g., $N_{\text{low}} = 200$), `StreamedTraceTaskGenerator` reads the next chunk of trace lines from disk and instantiates new `Task` objects until `taskList.size() == N_{\text{buffer}}`.

### 3. Garbage Collection & Eviction
- Once a task finishes execution or fails and its results are reported to the logger/orchestrator, references to the `Task` object are cleared.
- JVM Garbage Collection reclaims the memory automatically, enforcing a strict upper bound on heap usage:
$$\text{Memory Footprint} = \mathcal{O}(N_{\text{buffer}} + N_{\text{active\_in\_flight}})$$
- Heap utilization remains constant whether the simulation runs for 10 minutes, 1 hour, or 24 hours ($10^6+$ tasks).

---

## 4. Integration & Protocol Transparency

### Integration with PureEdgeSim Event Engine
- PureEdgeSim's `DefaultSimulationManager` already uses batching (`NEXT_BATCH` event tag, batch size = 100).
- We hook the stream replenishment into the `NEXT_BATCH` event processing pipeline or provide a lightweight `StreamedSimulationManager` / callback in `TraceTaskGenerator`.

### Zero Impact on Python Orchestrator & IPC Bridge
- The streaming mechanism is purely an internal task ingestion detail within PureEdgeSim.
- The `PythonOrchestrator`, Unix Domain Socket IPC messages (`DECISION_REQUEST`), and Python feature vectorizer (`task_to_array()`) continue to operate on standard `Task` instances in exact chronological order.
- No changes are required in the Python Bridge or Python RL orchestrators.
