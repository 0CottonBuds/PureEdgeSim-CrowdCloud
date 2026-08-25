# User & Operational Guide: Google Cluster Trace Workload Replay Engine

**Document Version:** 1.0  
**Date:** 2026-08-21  
**Target Audience:** Researchers, Thesis Reviewers, and System Engineers.

---

## 1. Quick Start Guide

The entire Google Cluster Trace v3 workload extraction, translation, PureEdgeSim streaming replay, and statistical validation suite can be executed with a single CLI command:

```bash
# Run complete end-to-end pipeline with priority-aware RoundRobin orchestrator
bash scripts/run_trace_pipeline.sh
```

### Dry Run (Configuration Check)
To verify Python virtual environment, dataset paths, and flag settings without launching a full simulation:

```bash
bash scripts/run_trace_pipeline.sh --dry-run
```

---

## 2. Command Line Interface (CLI) Reference

Master Runner: `bash scripts/run_trace_pipeline.sh [OPTIONS]`

| Flag | Argument Type | Default Value | Description |
|---|---|---|---|
| `--window` | String | `15min` | Selects trace dataset duration window label (e.g. `15min`). |
| `--orchestrator` | String / Alias | `RoundRobin` | Python orchestrator algorithm. Accepts aliases (`RoundRobin`, `NearestCloud`, `DQN`) or dotted Python class paths. |
| `--seed` | Integer | `42` | Random seed for reproducible synthetic attribute generation (`fileSizeInBits`, `outputSizeInBits`, `maxLatency`). |
| `--skip-prep` | Flag (no value) | `false` | Skips Stage 1 (Preprocessing) and Stage 2 (Translation) if processed JSON files already exist. |
| `--dry-run` | Flag (no value) | `false` | Validates dependencies, environment, and CLI flags, then exits 0. |
| `-h`, `--help` | Flag (no value) | N/A | Displays CLI usage instructions and options. |

### CLI Usage Examples

#### 1. Replay using Cloud-Only Nearest Node Orchestrator
```bash
bash scripts/run_trace_pipeline.sh --orchestrator NearestCloud
```

#### 2. Replay with Custom Seed and Skipped Preprocessing
```bash
bash scripts/run_trace_pipeline.sh --skip-prep --seed 123 --orchestrator RoundRobin
```

#### 3. Replay using Custom PyTorch RL Orchestrator Class
```bash
bash scripts/run_trace_pipeline.sh --orchestrator my_module.MyRLOrchestrator
```

---

## 3. Four-Stage Pipeline Architecture

```
┌──────────────────────────────────────────────────────────────────────────────────┐
│                             Pipeline Stage Flow                                  │
├───────────────────┬───────────────────┬───────────────────┬──────────────────────┤
│ Stage 1: Preproc. │ Stage 2: Transl.  │ Stage 3: Replay   │ Stage 4: Validation  │
│ Parquet → JSONL   │ JSONL → Task Stream│ Java + Python IPC │ 6-Test Audit Suite   │
└───────────────────┴───────────────────┴───────────────────┴──────────────────────┘
```

1. **Stage 1: Preprocessing (`preprocess_google_trace.py`)**
   Reads raw BigQuery extract `data/raw/google_v3_cell_a_15min.parquet`, calculates $t_{\text{arrival}}$, $\Delta T_{\text{exec}}$, and $\Delta T_{\text{queue}}$, and outputs `data/processed/google_v3_cell_a_15min_intermediate.jsonl`.

2. **Stage 2: Translation (`translate_to_pureedgesim.py`)**
   Translates normalized attributes to PureEdgeSim units ($Length_{\text{MI}}$, $containerSizeInBits$, $maxLatency$, $edgeDevice$). Generates `data/processed/pureedgesim_tasks_15min.json` and records metadata manifest.

3. **Stage 3: Streaming Simulation Replay (`ExampleTraceE2E.java`)**
   Invokes `StreamedTraceTaskGenerator` ($N_{\text{buffer}} = 1000$) wired into `TraceSimulationManager` and `PythonOrchestrator` over Unix Domain Sockets.

4. **Stage 4: Statistical Fidelity Audit (`validate_workload_replay.py`)**
   Executes 6 mathematical verification tests (K-S IAT test, resource integrals, MAPE, Chi-Square priority fit, synthetic plausibility, and 100% losslessness check) and outputs `docs/logs/m7_validation_report.md`.

---

## 4. Dataset Checksums & Reproducibility Guarantees

To ensure 100% deterministic reproducibility across research environments, the pipeline enforces fixed seeds ($S = 42$) and records SHA-256 dataset hashes.

### Reference Dataset Hashes (15-Minute Cell A Trace)

- **Raw Parquet Extract (`google_v3_cell_a_15min.parquet`):**
  `378,964 bytes` | SHA-256: `d497082f9f174dba9634ad494aabdd5d319f71c00dc79ef7ea33fcc790aa4207`
- **Translated PureEdgeSim Tasks (`pureedgesim_tasks_15min.json`):**
  `3,193,078 bytes` | `6,258 tasks`

### Verifying Dataset Integrity
```bash
sha256sum data/processed/pureedgesim_tasks_15min.json
```

---

## 5. Troubleshooting & FAQs

### Q1: The real-time visual charts do not pop up.
**Solution:** Ensure `display_real_time_charts=true` in `PureEdgeSim/settings_trace_test/simulation_parameters.properties`.

### Q2: OutOfMemoryError during simulation.
**Solution:** Use `mvn exec:exec` (as executed by `run_trace_pipeline.sh`) which spawns a dedicated JVM with `-Xmx2g`.

### Q3: Python IPC Socket Timeout / Handshake Failure.
**Solution:** Verify `python/.venv` has all requirements installed and socket file path `/tmp/pureedgesim_orch_*.sock` is writable.
