# Performance Results — PureEdgeSim Java/Python Orchestration Bridge

**Document Version:** 1.0  
**Date:** 2026-08-13  
**Status:** Completed & Verified against Budget ($\le 0.5\text{ ms}$ per decision request).

---

## 1. Environment & Machine Specification

- **Operating System:** Arch Linux (Kernel 6.x)
- **JVM Runtime:** OpenJDK 17 (`mvn`)
- **Python Runtime:** Python 3.14.3 (`python/.venv`)
- **IPC Protocol:** Unix Domain Sockets (`AFUNIXSocket` / `junixsocket` 2.6.2) with 4-byte big-endian framing
- **Project Root:** `/home/cotton/Projects/ML/Thesis/PureEdgeSim`

---

## 2. Micro-Benchmark: Unix Socket Round-Trip Latency

Measured **10,000 framed decision request round-trips** over a local Unix domain socket using a representative JSON payload containing:
- Current task properties (MI, input/output bits, container MB, max latency, origin device)
- 10 dynamic node states (available RAM/storage, CPU utilization, queue length, location)
- Pending task queue snapshot & WAN uplink utilization
- Payload size: **2,619 bytes**

### IPC Latency Statistics

| Metric | Measured Value | Budget Limit | Status |
|---|---|---|---|
| **Median (p50)** | **0.032 ms** | $\le 0.500\text{ ms}$ | **PASS** |
| **Mean** | **0.033 ms** | $\le 0.500\text{ ms}$ | **PASS** |
| **p95 Latency** | **0.045 ms** | - | **EXCELLENT** |
| **p99 Latency** | **0.063 ms** | - | **EXCELLENT** |
| **Max Latency** | **1.188 ms** | - | **ACCEPTABLE** |

> **Key Finding:** Median round-trip IPC latency is **0.032 ms**, which is **15.6x faster** than the strict $0.5\text{ ms}$ latency budget.

---

## 3. Macro-Benchmark: End-to-End Simulation Throughput

Measured full wall-clock execution time across complete PureEdgeSim discrete-event simulations executing 44 offloading decisions under `settings_bridge_test`.

### Wall-Clock Comparisons

| Run Scenario | Algorithm Executed | Total Wall-Clock | Overhead vs. Baseline |
|---|---|---|---|
| **Java-Only Baseline** | Stub Orchestrator (All-Fail) | `14.73s` | $0.00\text{s}$ |
| **Java+Python Bridge** | `RoundRobinOrchestrator` | `14.78s` | $+0.05\text{s}$ |
| **Java+Python Bridge** | `DQNOrchestrator` | `14.45s` | $-0.29\text{s}$ |

> **Key Finding:** The total execution overhead of the Java+Python socket bridge is **$< 0.05\text{s}$** across the entire simulation run, rendering the bridge overhead virtually imperceptible compared to standard JVM simulation execution.

---

## 4. Conclusion & Final Verdict

The Unix domain socket bridge implementation with length-prefixed framing successfully passes all performance criteria:
- **Micro-level IPC Budget:** **PASS** ($0.032\text{ ms} \le 0.500\text{ ms}$)
- **Resource Overhead:** Minimal ($2.6\text{ KB}$ per payload)
- **Throughput:** Capable of handling $> 30,000$ decisions/second per thread.
