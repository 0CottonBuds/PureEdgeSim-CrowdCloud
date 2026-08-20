# Phase 6 — Master Implementation Roadmap: Trace-Driven Workload Generator

**Document Version:** 1.0  
**Date:** 2026-08-18  
**Scope:** Milestone-by-Milestone Implementation Plan, Progressive Scaling Roadmap, Data Operation Tracking, and Validation Hand-offs.

---

## 1. Executive Overview & Data Operations Matrix

This document defines the progressive implementation roadmap for building, validating, and scaling the Google Cluster Trace v3 (Borg 2019) workload generator for PureEdgeSim. The plan breaks development into 10 incremental milestones, ensuring each stage proves system correctness before scaling.

### Data Operation Classification Matrix across Milestones

| Milestone | Stage Description | Primary Data Operation Focus | Replay Scale Target |
| :--- | :--- | :--- | :--- |
| **M1** | BigQuery Dev Subset Extraction | **Reading Real Data** | 15 Minutes (900s) |
| **M2** | Raw Record Inspection & Audit | **Reading Real Data** | 15 Minutes (900s) |
| **M3** | Intermediate Representation Preprocessing | **Transforming Data & Deriving Info** | 15 Minutes (900s) |
| **M4** | PureEdgeSim Task Translation | **Deriving Info & Making Assumptions**| 15 Minutes (900s) |
| **M5** | Core Java Engine Stream Replay | **Replaying Trace (Java Engine)** | 15 Minutes (900s) |
| **M6** | End-to-End Python Bridge Replay | **Replaying Trace (Python Bridge)** | 15 Minutes (900s) |
| **M7** | Statistical Fidelity & Validation Audit | **Validation & Verification** | 15 Minutes (900s) |
| **M8** | Reproducibility & Pipeline Automation | **Pipeline Automation** | 15 Minutes (900s) |
| **M9** | Progressive Scaling (1h & 12h Windows) | **Scale Verification & Buffer Tuning** | 1 Hour & 12 Hours |
| **M10**| Full 24-Hour Production Replay Target | **Full Workload Experimentation** | 24 Hours ($200,000+$ tasks) |

---

## 2. Milestone-by-Milestone Implementation Roadmap

---

### Milestone 1: BigQuery Extraction of Tiny Development Subset (15-Min Window)

- **Goal**: Extract a small, zero-cost, raw development subset of Google Cluster Trace v3 from BigQuery.
- **Data Operations**: **Reading Real Data**.
- **Required Files / Components**:
  - `sql/extract_lifecycle_events.sql`: Ultra-optimized single-pass SQL query ([phase4_extraction_replay_pipeline_design.md](file:///home/cotton/Projects/ML/Thesis/PureEdgeSim/docs/googlecluter-trace-task-generator/phase4_extraction_replay_pipeline_design.md)).
  - `scripts/bq_extract.sh`: Shell script wrapper using Google Cloud SDK (`bq query`).
- **Dependencies**: GCP BigQuery access to `google.com:google-cluster-data.clusterdata_2019_a`.
- **Expected Output**:
  - `data/raw/google_v3_cell_a_15min.parquet` (~500 - 1,000 task instances).
  - `data/metadata/extraction_manifest.json` recording query parameters and SHA-256 hash.
- **Test / Validation Criteria**:
  - Execute `bq query --dry_run` before submission; confirm scanned bytes $< 100 \text{ MB}$.
  - Verify Parquet file row count $> 0$ and file exists on disk.
- **Documentation**: Record query execution time, dataset ID, timestamp window bounds, and scanned byte volume in `docs/logs/m1_extraction.md`.

---

### Milestone 2: Raw Extracted Record Inspection & Telemetry Verification

- **Goal**: Audit extracted raw Parquet records to verify data integrity, non-null lifecycle timestamps, and resource request scaling.
- **Data Operations**: **Reading Real Data / Inspection**.
- **Required Files / Components**:
  - `python/scripts/inspect_raw_trace.py`: Inspection script using `pandas` / `pyarrow`.
- **Dependencies**: Completion of Milestone 1 (`google_v3_cell_a_15min.parquet`).
- **Expected Output**:
  - Summary report printed to console showing non-null counts, min/max values for `submit_time_us`, `schedule_time_us`, `finish_time_us`, `queue_time_us`, `exec_time_us`, `req_cpus`, `req_memory`, `priority`, and `scheduling_class`.
- **Test / Validation Criteria**:
  - Zero null values in `submit_time_us` or `req_cpus`.
  - Confirm `exec_time_us > 0` for all completed tasks (`finish_status = 'FINISH'`).
- **Documentation**: Document dataset statistics (P25, P50, P90, P99 quantile ranges of `exec_time_us` and `queue_time_us`) in `docs/logs/m2_inspection.md`.

---

### Milestone 3: Intermediate Trace Representation Preprocessing

- **Goal**: Process raw Parquet task records into an intermediate clean JSON Lines trace representation.
- **Data Operations**: **Transforming Data & Deriving Information**.
- **Required Files / Components**:
  - `python/scripts/preprocess_google_trace.py`: Python preprocessing pipeline.
- **Dependencies**: Completion of Milestone 2.
- **Expected Output**:
  - `data/processed/google_v3_cell_a_15min_intermediate.json` containing derived fields:
    - Relative arrival time $t_{\text{arrival}} = (t_{\text{submit}} - t_{\text{window\_start}}) / 10^6$ (seconds).
    - Ground-truth execution duration $\Delta T_{\text{exec}} = \text{exec\_time\_us} / 10^6$ (seconds).
    - Ground-truth queue delay $\Delta T_{\text{queue}} = \text{queue\_time\_us} / 10^6$ (seconds).
    - Task execution status string (`FINISH`, `FAIL`, `KILL`, `EVICT`).
- **Test / Validation Criteria**:
  - Verify $t_{\text{arrival}} \ge 0.0$ for all tasks.
  - Verify record count invariant: $N_{\text{intermediate}} == N_{\text{raw\_parquet}}$.
- **Documentation**: Document preprocessing formulas and transformation execution logs in `docs/logs/m3_intermediate.md`.

---

### Milestone 4: PureEdgeSim Task Specification Translation

- **Goal**: Translate intermediate trace records into fully-populated PureEdgeSim-compatible task specifications, applying domain modeling assumptions for missing fields.
- **Data Operations**: **Deriving Information & Making Assumptions**.
- **Required Files / Components**:
  - `python/scripts/translate_to_pureedgesim.py`: Translation module.
- **Dependencies**: Completion of Milestone 3.
- **Expected Output**:
  - `data/processed/pureedgesim_tasks_15min.json` containing complete PureEdgeSim task specs:
    - `id` (1-based integer), `time` ($t_{\text{arrival}}$ seconds), `length` (MI), `containerSizeInBits`, `fileSizeInBits`, `outputSizeInBits`, `maxLatency`, `edgeDevice` (assigned node ID), and `metadata` dictionary.
- **Test / Validation Criteria**:
  - Verify synthetic payload modeling assumptions: $\text{maxLatency}_i \ge \Delta T_{\text{exec}, i}$ for $100\%$ of tasks ([phase3_translation_mapping_design.md](file:///home/cotton/Projects/ML/Thesis/PureEdgeSim/docs/googlecluter-trace-task-generator/phase3_translation_mapping_design.md)).
  - Verify `containerSizeInBits > 0` and `length > 0`.
- **Documentation**: Document synthetic modeling formulas, Log-Normal payload distribution parameters, and deadline slack factors in `docs/logs/m4_translation.md`.

---

### Milestone 5: Core Java Engine Stream Replay (15-Min Subset)

- **Goal**: Implement `StreamedTraceTaskGenerator` in Java and replay the 15-minute workload through PureEdgeSim's discrete-event engine using default orchestrator.
- **Data Operations**: **Replaying the Trace (Java Core Engine)**.
- **Required Files / Components**:
  - `PureEdgeSim/com/mechalikh/pureedgesim/taskgenerator/StreamedTraceTaskGenerator.java`: Streaming task generator subclassing `TaskGenerator`.
  - Config settings in `PureEdgeSim/settings/simulation_parameters.properties`.
- **Dependencies**: Completion of Milestone 4.
- **Expected Output**:
  - Successful execution of PureEdgeSim simulation without memory leaks or crashes.
  - CSV output log in `PureEdgeSim/output/` showing generated and executed task metrics.
- **Test / Validation Criteria**:
  - Verify total generated tasks in simulation log equals total tasks in `pureedgesim_tasks_15min.json`.
  - Verify bounded JVM heap memory footprint during execution ($N_{\text{buffer}} = 1000$).
- **Documentation**: Record simulation run parameters, total execution duration, and task completion statistics in `docs/logs/m5_java_replay.md`.

---

### Milestone 6: End-to-End Simulation Run with Python RL Orchestrator

- **Goal**: Connect `StreamedTraceTaskGenerator` with `PythonOrchestrator` to execute an end-to-end trace replay using the Python IPC bridge.
- **Data Operations**: **Replaying the Trace (Python Bridge Integration)**.
- **Required Files / Components**:
  - `python/examples/run_round_robin.py` or `run_dqn_skeleton.py`.
  - `PythonOrchestrator.java` IPC socket bridge connection.
- **Dependencies**: Completion of Milestone 5 and Python Bridge environment (`python/.venv`).
- **Expected Output**:
  - Complete simulation run delivering `DECISION_REQUEST` payloads to Python and returning `TASK_RESULT` feedback callbacks for all trace tasks.
- **Test / Validation Criteria**:
  - Zero IPC socket timeouts or handshake failures.
  - Python RL orchestrator receives 100% of task outcomes (`on_task_complete`).
- **Documentation**: Record IPC latency statistics, socket communication stability, and step decision throughput in `docs/logs/m6_python_bridge_replay.md`.

---

### Milestone 7: Statistical Fidelity & Validation Audit

- **Goal**: Execute the Phase 5 formal validation suite to prove that the replayed workload matches Google Cluster ground-truth distributions.
- **Data Operations**: **Validation & Verification**.
- **Required Files / Components**:
  - `python/scripts/validate_workload_replay.py`: Validation suite script.
- **Dependencies**: Completion of Milestone 6 simulation outputs.
- **Expected Output**:
  - Comprehensive statistical validation report card covering Kolmogorov-Smirnov test, resource conservation, duration calibration, and priority Chi-Square fit ([phase5_validation_and_defense_plan.md](file:///home/cotton/Projects/ML/Thesis/PureEdgeSim/docs/googlecluter-trace-task-generator/phase5_validation_and_defense_plan.md)).
- **Test / Validation Criteria**:
  - Arrival IAT K-S Test: $D_{\text{KS}} < 0.01, p > 0.05$.
  - Compute & RAM Demand Integral Error: $\epsilon_{\text{cpu}} < 1.0\%, \epsilon_{\text{ram}} < 1.0\%$.
  - Baseline Host Execution MAPE: $\text{MAPE} < 0.1\%$.
  - Priority Chi-Square Test: $p > 0.05$.
- **Documentation**: Save the complete statistical report and CDF comparison plots to `docs/logs/m7_validation_report.md`.

---

### Milestone 8: Automated Pipeline CLI & Reproducibility Packaging

- **Goal**: Package the entire extraction, preprocessing, translation, and replay workflow into an automated, single-command CLI script.
- **Data Operations**: **Pipeline Automation**.
- **Required Files / Components**:
  - `scripts/run_trace_pipeline.sh`: Master CLI runner script.
  - Automated manifest generation (`data/metadata/extraction_manifest.json`).
- **Dependencies**: Completion of Milestone 7.
- **Expected Output**:
  - Single-command execution: `./scripts/run_trace_pipeline.sh --window 15min --orchestrator RoundRobin` executes the complete pipeline reproducibly.
- **Test / Validation Criteria**:
  - Re-executing pipeline produces identical SHA-256 dataset checksums and simulation metrics.
- **Documentation**: Document CLI usage instructions, flags, and reproducibility steps in `docs/googlecluter-trace-task-generator/user_guide.md`.

---

### Milestone 9: Progressive Scaling (1-Hour & 12-Hour Workload Windows)

- **Goal**: Scale the pipeline to extract, translate, and replay 1-hour ($\sim 10,000$ tasks) and 12-hour ($\sim 100,000$ tasks) workload windows.
- **Data Operations**: **Scale Verification & Buffer Tuning**.
- **Required Files / Components**:
  - Scaled dataset extracts: `google_v3_cell_a_1h.parquet` and `google_v3_cell_a_12h.parquet`.
  - `StreamedTraceTaskGenerator` buffer tuning ($N_{\text{buffer}} = 2000$, low watermark = 500).
- **Dependencies**: Completion of Milestone 8 CLI.
- **Expected Output**:
  - Successful 1-hour and 12-hour stream replay runs without memory degradation.
- **Test / Validation Criteria**:
  - Constant JVM heap memory utilization throughout 12-hour simulation run.
  - K-S arrival test $D_{\text{KS}} < 0.01$ maintained at scale.
- **Documentation**: Record heap memory profiles, GC pause durations, and streaming throughput in `docs/logs/m9_scaling_report.md`.

---

### Milestone 10: Full 24-Hour Production Workload Replay Target

- **Goal**: Execute a complete, reproducible 24-hour Google Cluster workload replay ($\sim 200,000+$ tasks) in PureEdgeSim, integrated with PyTorch Reinforcement Learning scheduling algorithms.
- **Data Operations**: **Full Workload Experimentation**.
- **Required Files / Components**:
  - Production dataset: `data/raw/google_v3_cell_a_24h.parquet` and `data/processed/pureedgesim_tasks_24h.json`.
  - Python RL Orchestrator (`DQNOrchestrator` / `PPOOrchestrator`).
- **Dependencies**: Completion of Milestone 9.
- **Expected Output**:
  - Full 24-hour simulation execution producing publication-ready baseline benchmarks (Round-Robin vs Nearest-Node vs PyTorch RL Agent) under real Google datacenter workload.
- **Test / Validation Criteria**:
  - 100% task pipeline losslessness ($N_{\text{BigQuery}} = N_{\text{Simulated}}$).
  - Validation suite passes all statistical tests ($D_{\text{KS}} < 0.01, \epsilon < 1.0\%, \text{MAPE} < 0.1\%$).
- **Documentation**: Publish final experimental results, reward learning curves, and benchmark summary in `docs/googlecluter-trace-task-generator/final_24h_replay_report.md`.
