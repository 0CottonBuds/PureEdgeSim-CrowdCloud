# PureEdgeSim Python Orchestration Guide: Researcher & RL Developer Manual

**Document Version:** 1.0  
**Date:** 2026-08-13  
**Target Audience:** Researchers, ML Engineers, and RL Developers integrating PyTorch/Gymnasium algorithms into PureEdgeSim.

---

## 1. Overview & System Architecture

The PureEdgeSim Python Bridge allows custom task placement and orchestration algorithms (written in Python/PyTorch/RL frameworks) to execute directly inside the PureEdgeSim Java discrete-event simulator.

```
┌──────────────────────────────────────────────┐        Unix Domain Socket        ┌──────────────────────────────────────────────┐
│             PureEdgeSim (Java)               │  <---------------------------->  │           Python Orchestrator Engine         │
│  - Discrete-Event Engine                     │    4-Byte Length-Prefixed JSON   │  - PyTorch / RL Agent Policy                 │
│  - Datacenters & Network Model               │    (Median IPC Latency: 0.03ms)  │  - Vectorized State (NumPy float32)          │
│  - Task Offloading Dispatcher                │                                  │  - Asynchronous Reward Tracking              │
└──────────────────────────────────────────────┘                                  └──────────────────────────────────────────────┘
```

---

## 2. Quick Start Guide

### Prerequisites
- Java OpenJDK 17 & Maven (`mvn`)
- Python 3.14 (or Python 3.8+) with virtual environment at `python/.venv/`

### Step 1: Environment Setup
```bash
cd /home/cotton/Projects/ML/Thesis/PureEdgeSim

# Create Python virtual environment if not present
python3 -m venv python/.venv

# Activate and install dependencies
source python/.venv/bin/activate
pip install -r python/requirements.txt

# (Optional) Install PyTorch for RL neural networks
pip install torch
```

### Step 2: Running Built-in Example Algorithms

#### Run Round-Robin Orchestrator
```bash
PYTHONPATH=python mvn exec:exec \
    -Dexec.executable=java \
    -Dexec.args="-classpath %classpath examples.ExamplePythonBridgeE2E examples.run_round_robin.RoundRobinOrchestrator"
```

#### Run Nearest-Node Orchestrator
```bash
PYTHONPATH=python mvn exec:exec \
    -Dexec.executable=java \
    -Dexec.args="-classpath %classpath examples.ExamplePythonBridgeE2E examples.run_nearest_node.NearestNodeOrchestrator"
```

#### Run Deep Q-Network (DQN) Skeleton Orchestrator
```bash
PYTHONPATH=python mvn exec:exec \
    -Dexec.executable=java \
    -Dexec.args="-classpath %classpath examples.ExamplePythonBridgeE2E examples.run_dqn_skeleton.DQNOrchestrator"
```

---

## 3. Core Python APIs & Subclassing

All custom algorithms inherit from either `pureedgesim.Orchestrator` or `pureedgesim.RLOrchestrator`.

### Standard Base Class: `Orchestrator`
[python/pureedgesim/orchestrator.py](file:///home/cotton/Projects/ML/Thesis/PureEdgeSim/python/pureedgesim/orchestrator.py)

```python
from pureedgesim import Orchestrator
from pureedgesim.types import Task, SimulationState, Node
from typing import Optional

class MyCustomOrchestrator(Orchestrator):

    def on_episode_begin(self, episode, nodes):
        """Called at the start of each simulation run/episode."""
        pass

    def select_node(self, task: Task, state: SimulationState) -> Optional[Node]:
        """
        Synchronous decision method. Must return a target Node or None.
        """
        candidates = state.candidates_for(task)
        if not candidates:
            return None
        return candidates[0]

    def on_task_complete(self, outcome):
        """Asynchronous callback when a task completes or fails in Java."""
        pass

    def on_episode_end(self, summary):
        """Called at simulation conclusion with episode summary statistics."""
        pass
```

---

### Reinforcement Learning Base Class: `RLOrchestrator`

`RLOrchestrator` extends `Orchestrator` with automated vectorization, reward tracking, and episode metrics.

```python
from pureedgesim import RLOrchestrator
from pureedgesim.types import Task, SimulationState, TaskOutcome, Node
from pureedgesim.rewards import CompositeReward, deadline_reward, latency_reward, success_reward
import numpy as np

class MyRLOrchestrator(RLOrchestrator):

    def __init__(self):
        super().__init__()
        # Define composite reward function
        self._reward_fn = CompositeReward([
            (deadline_reward, 2.0),
            (latency_reward, 1.0),
            (success_reward, 5.0),
        ])
        self._pending = {}

    def select_node(self, task: Task, state: SimulationState) -> Optional[Node]:
        # 1. Extract vectorized observation (NumPy float32 array)
        obs: np.ndarray = self.observation(task, state)

        # 2. Get valid candidate nodes
        candidates = state.candidates_for(task)
        if not candidates:
            return None

        # 3. Select action (e.g. via Neural Network or Epsilon-Greedy)
        selected_node = candidates[0]

        # 4. Store pending observation for asynchronous reward correlation
        req_id = task.metadata.get("request_id", -1)
        self._pending[req_id] = (obs, selected_node.index)

        return selected_node

    def reward(self, outcome: TaskOutcome) -> float:
        """Compute scalar step reward from task outcome."""
        return self._reward_fn(outcome)

    def on_task_complete(self, outcome: TaskOutcome):
        super().on_task_complete(outcome)
        req_id = outcome.request_id
        if req_id in self._pending:
            obs, action = self._pending.pop(req_id)
            r = self.reward(outcome)
            # Log transition (obs, action, r) into Replay Buffer
```

---

## 4. Feature Vectorization (`features.py`)

Vectorization routines convert complex simulation objects into flat 1D `float32` NumPy arrays suitable for PyTorch neural network inputs.

### 1. Task Vectorization: `task_to_array(task)`
Returns a 1D array of shape **`(9,)`**:
- `[0]`: `length_mi` — Computational length in Million Instructions (MI)
- `[1]`: `input_size_mb` — Input file size in Megabytes
- `[2]`: `output_size_mb` — Output result size in Megabytes
- `[3]`: `container_size_mb` — Container image size / RAM requirement in MB
- `[4]`: `deadline` — Max allowable latency in seconds
- `[5]`: `app_id` — Application identifier
- `[6]`: `origin_location_x` — Origin device X position
- `[7]`: `origin_location_y` — Origin device Y position
- `[8]`: `origin_cpu_utilization` — Instantaneous CPU utilization of origin device

### 2. Node Dynamic State Vectorization: `node_to_array(node)`
Returns a 1D array of shape **`(9,)`**:
- `[0]`: `cpu_utilization` — Instantaneous CPU utilization ($0.0 - 1.0$)
- `[1]`: `avg_cpu_utilization` — Time-averaged CPU utilization ($0.0 - 1.0$)
- `[2]`: `available_ram_fraction` — Available RAM ratio ($\frac{\text{RAM}_{\text{avail}}}{\text{RAM}_{\text{total}}}$)
- `[3]`: `available_storage_fraction` — Available storage ratio ($\frac{\text{Storage}_{\text{avail}}}{\text{Storage}_{\text{total}}}$)
- `[4]`: `is_idle` — $1.0$ if node has 0 active tasks, $0.0$ otherwise
- `[5]`: `is_alive` — $1.0$ if node battery is alive, $0.0$ if depleted
- `[6]`: `queued_tasks` — Number of tasks queued on this node
- `[7]`: `current_location_x` — Current X location
- `[8]`: `current_location_y` — Current Y location

### 3. Node Static Properties: `node_static_to_array(node)`
Returns a 1D array of shape **`(11,)`**:
- `[0]`: `total_mips` | `[1]`: `mips_per_core` | `[2]`: `num_cores` | `[3]`: `total_ram_mb` | `[4]`: `total_storage_mb`
- `[5]`: `is_cloud` | `[6]`: `is_edge_server` | `[7]`: `is_edge_device` | `[8]`: `is_peripheral`
- `[9]`: `base_location_x` | `[10]`: `base_location_y`

### 4. Full World State Vectorization: `state_to_array(state, max_pending=10)`
Flattens `SimulationState` into a 1D float32 NumPy array of shape:
$$\text{Shape} = \left(3 + N_{\text{nodes}} \times 9 + \text{max\_pending} \times 10\right)$$

- **Global Snapshot (3):** `[clock, tasks_in_flight, wan_uplink_utilization]`
- **Nodes Array ($N_{\text{nodes}} \times 9$):** Concatenation of `node_to_array(n)` for all nodes
- **Pending Tasks ($\text{max\_pending} \times 10$):** Zero-padded array of upcoming queued tasks

---

## 5. Modular Reward System (`rewards.py`)

[python/pureedgesim/rewards.py](file:///home/cotton/Projects/ML/Thesis/PureEdgeSim/python/pureedgesim/rewards.py) provides pre-built reward functions and composite reward weighting:

| Reward Function | Formula / Behavior | Use Case |
|---|---|---|
| `latency_reward(outcome)` | $-\frac{\text{total\_latency}}{\text{deadline}}$ | Penalizes execution delay normalized by deadline. |
| `deadline_reward(outcome)` | $+1.0$ if $\text{latency} \le \text{deadline}$, else $-1.0$ | Binary reward for meeting latency SLA. |
| `success_reward(outcome)` | $+1.0$ if `SUCCESS`, else $-1.0$ | Reward for successful task completion. |
| `energy_reward(outcome)` | Negative normalized energy consumption | Penalizes energy usage. |
| `CompositeReward` | $\sum w_i \cdot R_i(\text{outcome})$ | Weighted linear combination of multiple rewards. |

### Example Composite Reward Setup:
```python
from pureedgesim.rewards import CompositeReward, deadline_reward, latency_reward, success_reward

reward_function = CompositeReward([
    (deadline_reward, 2.0),
    (latency_reward, 1.0),
    (success_reward, 5.0),
])

# Compute reward from TaskOutcome
step_reward = reward_function(outcome)
```

---

## 6. Research Walkthrough: Implementing Deep Q-Learning (DQN)

To research DQN in PureEdgeSim, follow the pattern in [run_dqn_skeleton.py](file:///home/cotton/Projects/ML/Thesis/PureEdgeSim/python/examples/run_dqn_skeleton.py):

### Asynchronous Reward Handling Pattern

Because PureEdgeSim is a discrete-event simulator, tasks execute asynchronously:
1. `select_node()` is called when a task arrives at time $t_1$.
2. The task offloads, queued, and computes.
3. `on_task_complete()` is called when execution finishes at time $t_2 > t_1$.

To handle this in PyTorch/RL:
- In `select_node()`: Record `obs = self.observation(task, state)` and store in `_pending[task.metadata['request_id']] = (obs, action)`.
- In `on_task_complete()`: Retrieve `(obs, action)` using `outcome.request_id`, calculate `r = self.reward(outcome)`, and push `(obs, action, r, next_obs, done)` into your PyTorch Replay Buffer.

```python
import torch
import torch.nn as nn
import random
from pureedgesim import RLOrchestrator

class PyTorchDQNOrchestrator(RLOrchestrator):

    def __init__(self):
        super().__init__()
        self.epsilon = 0.1
        self._pending = {}
        self.replay_buffer = []
        self.model = None

    def on_episode_begin(self, episode, nodes):
        super().on_episode_begin(episode, nodes)
        if self.model is None:
            state_dim = 3 + len(nodes) * 9 + 10 * 10
            self.model = nn.Sequential(
                nn.Linear(state_dim, 128),
                nn.ReLU(),
                nn.Linear(128, self.action_space_size)
            )

    def select_node(self, task, state):
        obs = self.observation(task, state)
        candidates = state.candidates_for(task)
        if not candidates:
            return None

        # Epsilon-greedy Q-value selection
        if random.random() < self.epsilon:
            node = random.choice(candidates)
        else:
            with torch.no_grad():
                q_vals = self.model(torch.tensor(obs).float()).numpy()
                best_idx = max([n.index for n in candidates], key=lambda i: q_vals[i])
                node = next(n for n in candidates if n.index == best_idx)

        self._pending[task.metadata['request_id']] = (obs, node.index)
        return node

    def on_task_complete(self, outcome):
        super().on_task_complete(outcome)
        req_id = outcome.request_id
        if req_id in self._pending:
            obs, action = self._pending.pop(req_id)
            reward = self.reward(outcome)
            self.replay_buffer.append((obs, action, reward))
```

---

## 7. Error Handling & Validation Policies

The Python bridge features an automated decision validator:
1. **Candidate Validation:** Returned nodes must be present in the active episode node set.
2. **Resource Constraints:** Returned nodes must have `available_ram_mb >= task.ram_required_mb` and `available_storage_mb >= task.container_size_mb`.
3. **Node Health:** Nodes with depleted batteries (`is_alive == False`) are rejected.
4. **NaN Safeguard:** Decisions with `NaN` float values are automatically rejected.
5. **Strict vs Lenient Mode (`--strict`):**
   - In `strict=True` (default), invalid decisions raise `InvalidDecisionError` to cleanly stop execution and alert the researcher.
   - In `strict=False`, invalid decisions trigger a warning and fall back to `node_index = -1` (local execution/default policy).

---

## 8. Directory & File Reference

- **[PureEdgeSim/com/mechalikh/pureedgesim/python/](file:///home/cotton/Projects/ML/Thesis/PureEdgeSim/PureEdgeSim/com/mechalikh/pureedgesim/python/)** — Java Bridge (socket I/O, `PythonOrchestrator.java`, JSON parser/builder).
- **[python/pureedgesim/orchestrator.py](file:///home/cotton/Projects/ML/Thesis/PureEdgeSim/python/pureedgesim/orchestrator.py)** — `Orchestrator` & `RLOrchestrator` base classes.
- **[python/pureedgesim/features.py](file:///home/cotton/Projects/ML/Thesis/PureEdgeSim/python/pureedgesim/features.py)** — State & Task vectorization methods.
- **[python/pureedgesim/rewards.py](file:///home/cotton/Projects/ML/Thesis/PureEdgeSim/python/pureedgesim/rewards.py)** — Modular reward components.
- **[python/pureedgesim/types/](file:///home/cotton/Projects/ML/Thesis/PureEdgeSim/python/pureedgesim/types/)** — Dataclasses (`Node`, `Task`, `SimulationState`, `TaskOutcome`).
- **[python/examples/](file:///home/cotton/Projects/ML/Thesis/PureEdgeSim/python/examples/)** — Reference implementations (`run_round_robin.py`, `run_nearest_node.py`, `run_dqn_skeleton.py`).
- **[docs/performance_results.md](file:///home/cotton/Projects/ML/Thesis/PureEdgeSim/docs/performance_results.md)** — Benchmarking reports.
