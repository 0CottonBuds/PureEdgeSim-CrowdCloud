# PureEdgeSim Python Orchestration Interface

This package provides a high-performance Python/PyTorch orchestration interface for the [PureEdgeSim](https://github.com/CharafMech/PureEdgeSim) discrete-event simulator.

## Documentation Guide
For complete architectural details, API reference, vectorization layouts, reward functions, and PyTorch / Deep Q-Learning (DQN) integration instructions, see:

👉 **[docs/python_orchestration_guide.md](file:///home/cotton/Projects/ML/Thesis/PureEdgeSim/docs/python_orchestration_guide.md)**

---

## Quick Command Reference

```bash
# 1. Run Round-Robin Orchestrator
PYTHONPATH=python mvn exec:exec \
    -Dexec.executable=java \
    -Dexec.args="-classpath %classpath examples.ExamplePythonBridgeE2E examples.run_round_robin.RoundRobinOrchestrator"

# 2. Run Nearest-Node Orchestrator
PYTHONPATH=python mvn exec:exec \
    -Dexec.executable=java \
    -Dexec.args="-classpath %classpath examples.ExamplePythonBridgeE2E examples.run_nearest_node.NearestNodeOrchestrator"

# 3. Run DQN Orchestrator Skeleton
PYTHONPATH=python mvn exec:exec \
    -Dexec.executable=java \
    -Dexec.args="-classpath %classpath examples.ExamplePythonBridgeE2E examples.run_dqn_skeleton.DQNOrchestrator"

# 4. Run Pytest Suite
PYTHONPATH=python python/.venv/bin/pytest python/tests/ -v

# 5. Run Micro-Benchmark
PYTHONPATH=python python/.venv/bin/python python/tests/bench_latency.py
```
