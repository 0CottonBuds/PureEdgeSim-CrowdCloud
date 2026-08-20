# CHAPTER 3: METHODOLOGY

## 3.1 RESEARCH DESIGN
This study employs a **quantitative experimental research design** to develop, implement, and systematically evaluate an intelligent, reliability-aware task scheduling framework for distributed edge computing infrastructures. The core objective is to investigate whether incorporating dynamic node reliability telemetry and temporal availability profiles into a Deep Reinforcement Learning (RL) state space yields statistically significant improvements in operational scheduling efficiency under volatile, heterogeneous conditions.

This methodology represents a complete paradigm shift away from simplified, traditional academic scheduling models to ensure industrial relevance and high-fidelity validation:
1. **Elimination of Stochastic Workload Assumptions**: Traditional scheduling models represent task arrivals using synthetic Poisson processes or uniform queue injections. While mathematically convenient, such models fail to replicate the self-similar burstiness, high concurrency, and multi-tier priority clustering of real-world production networks. This research drives the simulation directly using empirical system event logs extracted from enterprise cloud deployments, preserving authentic workload dynamics.
2. **Transition to High-Fidelity Discrete-Event Simulation**: Instead of operating on a custom-built, discrete-time execution loop running at fixed 1-second ticks, this study implements its experimental scenarios inside **PureEdgeSim**. PureEdgeSim is an established, Java-based discrete-event simulation (DES) framework built on CloudSim. It models the physical and network characteristics of distributed infrastructures—including signal path loss, wide area network (WAN) uplink congestion bottlenecks, container memory limits, device mobility, and hardware battery depletion curves—with mathematical precision.
3. **Removal of Simplified Mathematical Tuples**: Rather than representing computational tasks and physical servers as abstract, static tuples designed from scratch, this methodology integrates tasks and nodes as dynamic, object-oriented entities natively managed by PureEdgeSim and updated in real-time via trace-driven telemetry.

The evaluation strategy compares a **Reliability-Aware DQN Scheduler** (utilizing conventional resource telemetry enriched with dynamic reliability metrics) directly against a **Standard DQN Scheduler** (conventional features only) and three traditional heuristic baselines under identical workload and topology configurations to isolate and quantify the performance gains.

---

## 3.2 RESEARCH ENVIRONMENT
The research environment establishes the technical and infrastructural context in which this study is conducted. It encompasses the datasets, computational resources, and software packages employed to develop, evaluate, and validate the machine learning model and system prototype:

1. **Hardware Configuration**: All data preprocessing, model training, and simulation runs are executed on a dedicated local development workstation equipped with an Intel Core i7-12700K CPU (12 cores, 20 threads, up to 5.0 GHz), 32 GB of DDR4 RAM (3200 MHz), and an NVIDIA GeForce RTX 3070 Ti GPU (8 GB GDDR6X VRAM) hosting the PyTorch CUDA acceleration environment.
2. **Software Stack and Runtimes**:
    * **Operating System**: Arch Linux (Kernel 6.x), providing a high-performance POSIX-compliant environment.
    * **Java Development Kit (JDK)**: OpenJDK 17 (compiled to a Java 1.8 target), managing the PureEdgeSim simulation engine execution.
    * **Python Environment**: Python 3.14.3 running within a isolated virtual environment (`.venv`).
    * **Core Libraries**: PyTorch (for neural network representation and training), NumPy (for high-speed array manipulations), Pandas and PyArrow (for Parquet data processing), and Gymnasium (for reinforcement learning environment abstractions).
3. **Inter-Process Communication (IPC)**: Bidirectional, sub-millisecond loopback communication between the Java simulation thread and the Python PyTorch server is managed via **Unix Domain Sockets** using the native POSIX socket API in Python and the `junixsocket` (v2.6.2) library in Java, bypassing the TCP/IP network stack entirely to eliminate network transmission overhead.

---

## 3.3 DATA GATHERING PROCEDURES

### 3.3.1 Data Collection Strategy
The empirical workload data driving this research is collected from the **Google Cluster Trace v3 (Borg 2019)** dataset, specifically utilizing the **"2019 set a"** trace window. This dataset records the complete, multi-day lifecycle event logs of a production Google compute cell under the control of the Borg cluster manager, starting on May 1, 2019, at 00:00 PDT.

Because Borg operates on massive, high-end cloud data centers while this research targets volatile consumer-owned volunteer edge networks, we implement a structured extraction and mapping strategy:

#### 1. Single-Pass SQL Lifecycle Aggregation
Raw Borg trace tables are extremely massive and record multiple disjoint event transitions (e.g., `SUBMIT`, `QUEUE`, `ENABLE`, `SCHEDULE`, `FAIL`, `EVICT`, `FINISH`, `KILL`) per task. Exporting raw event rows directly is computationally inefficient, generates substantial storage overhead, and requires heavy local join operations.

To resolve this bottleneck, this study implements a **single-pass SQL lifecycle aggregation query** executed directly inside Google BigQuery. The query groups raw events by their unique compound key—Job ID (`collection_id`) and Task Index (`instance_index`)—to consolidate all disjoint transitions into a single, comprehensive record per task attempt:
* **Arrival Time ($t_{	ext{submit}}$)** is captured from the minimum timestamp where `type = 0` (SUBMIT).
* **Placement Start Time ($t_{	ext{schedule}}$)** is captured from the minimum timestamp where `type = 3` (SCHEDULE).
* **Termination Time ($t_{	ext{finish}}$)** is captured from the maximum timestamp where `type = 6` (FINISH) or other terminal states (FAIL=5, KILL=7, EVICT=4).
* **Active Execution Duration ($\Delta T_{	ext{exec}}$)** is calculated directly within the query as:
  $$\Delta T_{	ext{exec}} = rac{t_{	ext{finish}} - t_{	ext{schedule}}}{1,000,000} \quad (	ext{seconds})$$

This SQL-level aggregation reduces the raw dataset size by **$3	imes$ to $5	imes$**, outputting a highly dense local Parquet dataset (`google_v3_cell_a_24h.parquet`) comprising **18 essential fields**, including requested CPU rates, requested memory, user hashes, priority tiers, and scheduling classes.

#### 2. Workload Unit and Mapping Rules
Each consolidated task attempt represents a distinct **workload unit** inside the simulation. If a Borg task is evicted or fails and is subsequently restarted, each execution attempt is instantiated as a distinct PureEdgeSim task with a unique 1-based integer ID, as each run represents an independent offloading decision point.

Because Google obfuscates raw hardware capacities and normalizes floating-point metrics into $[0, 1]$, the local pipeline executes `preprocess_google_trace.py` to translate these abstract values back into physical units consumed by PureEdgeSim's Java models:

##### I. Relative Arrival Time ($t_{	ext{arrival}}$)
$$t_{	ext{arrival}} = rac{t_{	ext{SUBMIT}} - t_{	ext{window\_start}}}{1,000,000} \quad (	ext{seconds})$$
*Justification*: Converts microseconds to simulation-relative seconds, preserving the exact empirical inter-arrival intervals ($\Delta t$), diurnal workload peaks, and temporal burstiness.

##### II. Computational Workload Length ($	ext{Length}_{	ext{MI}}$)
$$	ext{Length}_{	ext{MI}} = 	ext{round}\left( 	ext{req\_cpus} 	imes 	ext{MIPS}_{	ext{base\_core}} 	imes \Delta T_{	ext{exec}} ight)$$
*Justification*: PureEdgeSim calculates execution duration on a host core as $T = rac{	ext{Length}_{	ext{MI}}}{	ext{Host MIPS}}$. By setting $	ext{Length}_{	ext{MI}}$ proportional to the normalized requested CPU rate ($	ext{req\_cpus}$), the base CPU speed ($	ext{MIPS}_{	ext{base\_core}}$, e.g., $2000 	ext{ MIPS}$), and the empirical duration ($\Delta T_{	ext{exec}}$), we guarantee that execution on an un-contended baseline host matches the real-world duration exactly.

##### III. Container Image & RAM Footprint ($	ext{containerSizeInBits}$)
$$	ext{containerSizeInBits} = 	ext{round}\left( 	ext{req\_memory} 	imes 	ext{MaxCellRAM}_{	ext{Bytes}} 	imes 8 ight)$$
*Justification*: Unscales the normalized RAM fraction back into physical bits to feed PureEdgeSim's memory allocation and cold-start container transfer physics.

#### 3. Domain-Sound Modeling of Missing Fields
Borg traces omit network transfer volumes, latency deadlines, and spatial client locations. To resolve this information gap, the pipeline applies four defensible, domain-established synthetic models:

##### I. Input Network Payload Size ($	ext{fileSizeInBits}$)
$$	ext{fileSizeInBits} = 	ext{round}\left( 	ext{containerSizeInBits} 	imes 0.25 	imes X ight), \quad X \sim 	ext{LogNormal}(\mu = 0, \sigma = 0.5)$$
*Justification*: Task input sizes (datasets + binaries) correlate with memory limits. A Log-Normal distribution captures the characteristic heavy-tailed volume of distributed data-processing networks.

##### II. Output Result Payload Size ($	ext{outputSizeInBits}$)
$$	ext{outputSizeInBits} = 	ext{round}\left( 	ext{fileSizeInBits} 	imes Y ight), \quad Y \sim 	ext{Uniform}(0.05, 0.20)$$
*Justification*: Task execution results (e.g., classification labels, summaries) returned to edge devices are structurally much smaller than the input code/dataset.

##### III. Latency Deadline ($	ext{maxLatency}$)
$$	ext{maxLatency} = \Delta T_{	ext{exec}} 	imes \left(1.0 + 	ext{SlackFactor}(S_c, P)ight)$$
$$	ext{SlackFactor}(S_c, P) = 0.5 \cdot (3 - S_c) + 0.5 \cdot \left(1.0 - rac{P}{450}ight)$$
*Justification*: Models Service Level Objectives (SLOs). A latency-sensitive production task ($S_c = 3, P = 450$) has $	ext{SlackFactor} = 0.0$, producing a strict deadline ($	ext{maxLatency} = 1.0 	imes \Delta T_{	ext{exec}}$). A non-production batch task ($S_c = 0, P = 0$) has $	ext{SlackFactor} = 2.0$, granting a relaxed deadline ($	ext{maxLatency} = 3.0 	imes \Delta T_{	ext{exec}}$).

##### IV. Origin Edge Device Assignment ($	ext{DeviceIndex}$)
$$	ext{DeviceIndex} = \left| 	ext{hashCode}(	ext{user}) ight| \pmod{N_{	ext{edge\_devices}}}$$
*Justification*: By hashing the obfuscated user string, tasks from the same engineer or service consistently originate from the same physical edge node, preserving spatial locality.

#### 4. Strict 4-Part Data Classification
To ensure maximum analytical and experimental rigor, all research parameters and variables are classified into a strict four-part taxonomy:
1. **Directly Observed Data**: Raw fields extracted directly from the Borg trace files (e.g., raw microsecond timestamps, normalized CPU/RAM request fractions, integer priority tiers, and categorical scheduling classes).
2. **Derived Data**: Exact mathematical properties calculated by combining raw observed fields (e.g., relative simulation arrival time, task ground-truth execution duration, and queue waiting time in seconds).
3. **Data Requiring Transformation**: Metrics requiring unit conversion or format adaptation to be compatible with PureEdgeSim models (e.g., converting microseconds to seconds, mapping rescaled RAM fractions to physical bits, and hashing compound 64-bit BigQuery string identifiers into 1-based sequential integers).
4. **Data Requiring Modeling Assumptions**: Omitted physical properties that must be modeled synthetically using mathematically sound, domain-justified probability distributions (e.g., log-normal input payloads, uniform return payloads, priority-derived slack deadlines, and user-modulo edge client node mappings).

---

### 3.3.2 Data Interpretation and Analysis Strategy

#### 1. Google Cluster Dataset Taxonomy
To ensure a rigorous understanding of the workload, the system deconstructs Google's organizational taxonomy across three core source tables:
* **`CollectionEvents`**: Logs job-level configurations. A collection represents a job (type 0) grouping related task instances running the same binary. Usernames and job names are obfuscated into cryptographic base64 strings to protect enterprise confidentiality while enabling equality matching.
* **`InstanceEvents`**: Documents individual task attempts. Tasks are identified by their parent job ID and a 0-based index. If a task fails or is evicted, Borg automatically resubmits it, recording subsequent attempts under the same compound key. This methodology maps each distinct execution attempt to a unique simulation task ID, as each run represents an independent offloading decision point.
* **`InstanceUsage`**: Records periodic (5-minute window) performance metrics sampled at 1 Hz, documenting actual CPU and RAM utilization. Resource requests represent the upper container limit; tasks exceeding memory limits are terminated by Borglet agents, appearing as memory-exhaustion failures in the trace.

#### 2. Operational Metrics Definitions
The performance of the scheduling frameworks is analyzed and compared using four core system-level metrics collected dynamically from the simulation logs:
* **Throughput (Tasks/Minute)**: Measures the total number of successfully completed tasks divided by the simulation execution duration in minutes. Higher throughput indicates superior scheduling speed and high utilization of worker nodes.
* **Average Latency (Seconds)**: Represents the total elapsed time from a task's arrival in the global queue to its final completion, capturing queuing delays, network transmission times, cold-start container initialization, and active computation:
  $$	ext{Latency} = T_{	ext{waiting}} + T_{	ext{network}} + T_{	ext{cold\_start}} + T_{	ext{computation}}$$
* **Queue Waiting Time (Seconds)**: Tracks the duration a task remains buffered in the global queue before the orchestrator assigns it to an execution target. Lower waiting times indicate high system responsiveness and efficient load balancing.
* **Task Failure Rate (%)**: Computed as the percentage of task execution failures relative to the total submitted tasks:
  $$	ext{Failure Rate} = rac{F_{	ext{network}} + F_{	ext{OOM}} + F_{	ext{deadline}} + F_{	ext{battery}}}{N_{	ext{total}}} 	imes 100$$
  This metric serves as the primary gauge of scheduler reliability, capturing drops caused by network disconnections, destination node battery exhaustion, memory exhaustion (Out of Memory), or deadline expirations.

#### 3. Ablation and Baseline Comparison Strategy
To isolate and quantify the impact of incorporating dynamic reliability metrics, the experimental design structures a direct ablation-based comparison between two distinct scheduling frameworks executed under identical workload and topology constraints:
* **Standard DQN Scheduler**: Ingests conventional scheduling features only, including task computational limits, localized task queue lengths, and standard node resource capacities (available CPU, RAM, and storage). All reliability-aware historical features are omitted from the state representation.
* **Reliability-Aware DQN Scheduler**: Extends the standard state space by embedding dynamic reliability-oriented node features, specifically the dynamic temporal availability probability $A_i(h)$, node liveness trackers, and historical execution success ratios.

To verify the learning stability of the PyTorch neural networks, we plot and analyze the cumulative step reward over training episodes, checking for steady upward progression and eventual stabilization. To mathematically prove that the performance improvements of our model are statistically significant, both schedulers are evaluated under identical random seed controls (Seed = 42) across multiple simulation scenarios representing varying edge volatility (battery depletion rates) and network congestion (WAN uplink saturation). Performance is benchmarked against traditional baseline heuristics—including First-Come, First-Serve (FCFS), Round Robin (RR), and Shortest Job First (SJF)—and evaluated using non-parametric hypothesis testing (such as the Wilcoxon signed-rank test over independent simulation intervals).

---

### 3.3.3 Ethical Considerations
As empirical research driven by enterprise cloud datasets and automated machine learning, this study addresses three core ethical areas: data licensing, confidentiality preservation, and sustainable computing.

#### 1. Dataset Licensing and Usage Policy
This research utilizes the Google Cluster Trace v3 (Borg 2019) dataset, which is publicly distributed under the Creative Commons Attribution 4.0 International (CC-BY 4.0) license. The study strictly complies with all licensing requirements:
* Full academic attribution is provided to the dataset creators, John Wilkes, and the Google Systems Research Group.
* The foundational Borg cluster architecture papers are appropriately cited.
* No redistributions, proprietary re-licensing, or commercial exploitations of the raw trace files are attempted.

#### 2. Confidentiality and Anonymization Preservation
To safeguard proprietary secrets and prevent privacy breaches, Google applied rigorous obfuscation techniques to the Borg traces, including cryptographically hashing user identity strings and collection names, and linearly rescaling physical resource values. This study maintains strict academic integrity regarding anonymization:
* No attempts are made to de-anonymize or decrypt obfuscated user base64 hashes or job template names.
* No reverse-engineering is performed on Google's proprietary server hardware profiles or microarchitecture chipsets (represented by platform IDs).
* The study treats the trace strictly as a normalized behavioral workload pattern to evaluate task placement scheduling, respecting all boundaries of corporate and data confidentiality.

#### 3. Responsible AI and Sustainable "Green" Computing
Deep reinforcement learning training processes are computationally intensive, consuming non-trivial electrical power. To mitigate the environmental footprint of this study:
* **Simulation Efficiency**: By utilizing the lightweight, highly optimized PureEdgeSim discrete-event simulator combined with our constant-memory prefetch buffer (`StreamedTraceTaskGenerator`), we minimize the CPU cycle overhead and memory footprint of our experiments, enabling a complete 24-hour trace replay with minimal carbon emissions.
* **Sustainable Orchestration Policies**: The core objective of our scheduling algorithm is to minimize task execution failures and eliminate redundant re-computations caused by node disconnections or battery starvation. By successfully routing tasks to reliable consumer nodes, the proposed system reduces energy waste across mobile edge devices, actively supporting the ethical principles of sustainable, energy-efficient "Green Computing."

---

## 3.4 SYSTEM ARCHITECTURE / PROPOSED MODEL

This section details the proposed system's architectural design and bidirectional data flow. Figure 3.1 illustrates the end-to-end Input-Process-Output (IPO) conceptual flow of the trace-driven, socket-bridged simulation experiment, while Figure 3.2 details the physical architectural block diagram showing how components interact.

```
+-----------------------------------------------------------------------------------+
|                                 3.4.1 INPUTS                                      |
|  * Google Borg Traces    * PureEdgeSim Settings    * Candidate Node Telemetry     |
+-----------------------------------------------------------------------------------+
                                         |
                                         v
+-----------------------------------------------------------------------------------+
|                                 3.4.2 PROCESS                                     |
|  [SQL Aggregation] -> [Trace Translation] -> [Streamed Ingestion] -> [DES Loop]   |
|                                         |                                         |
|                                         v                                         |
|                       +-----------------------------------+                       |
|                       |       Python DQN Orchestrator     |                       |
|                       |   * Features     * Policy Network |                       |
|                       |   * Rewards      * Replay Buffer  |                       |
|                       +-----------------------------------+                       |
+-----------------------------------------------------------------------------------+
                                         |
                                         v
+-----------------------------------------------------------------------------------+
|                                 3.4.3 OUTPUTS                                     |
|  * Throughput    * Avg Latency    * Queue Waiting Time    * Task Failure Rate     |
+-----------------------------------------------------------------------------------+
Figure 3.1: Mathematical Input-Process-Output (IPO) Conceptual Framework
```

```
+------------------------------------------------------------------------------------+
|                                  JAVA SIMULATOR                                    |
|                                                                                    |
|  +--------------------+      NEXT_BATCH      +-------------------------------+     |
|  | pureedgesim.json   | -------------------> |  StreamedTraceTaskGenerator   |     |
|  +--------------------+                      +-------------------------------+     |
|                                                              |                     |
|                                                              v (Prefetched Queue)  |
|                                              +-------------------------------+     |
|                                              |      Simulated Event Loop     |     |
|                                              +-------------------------------+     |
|                                                              |                     |
|                                                              | SEND_TO_ORCH (t)    |
|                                                              v                     |
|                                              +-------------------------------+     |
|                                              |      PythonOrchestrator       |     |
|                                              +-------------------------------+     |
|                                                              |                     |
|                                                              | AF_UNIX Client      |
|                                                              v                     |
|                                              +-------------------------------+     |
|                                              |          JavaBridge           |     |
|                                              +-------------------------------+     |
+--------------------------------------------------------------|---------------------+
                                                               |
                                      Bidirectional IPC         | Length-Prefixed JSON
                                      Loopback Sockets          | [Length: 4-Bytes]
                                                               | [Payload: UTF-8]
                                                               v
+------------------------------------------------------------------------------------+
|                                  PYTHON SERVER                                     |
|                                                                                    |
|  +--------------------+                      +-------------------------------+     |
|  |     connection     | <==================> |          dispatcher           |     |
|  |  (AF_UNIX Server)  |                      +-------------------------------+     |
|  +--------------------+                                      |                     |
|                                                              v (JSON Parse)        |
|                                              +-------------------------------+     |
|                                              |      RLOrchestrator ABC       |     |
|                                              +-------------------------------+     |
|                                                              |                     |
|                                                              v (State Array Tensor)|
|                                              +-------------------------------+     |
|                                              |     DQN Scheduler Module      |     |
|                                              |    [PyTorch Neural Network]   |     |
|                                              +-------------------------------+     |
+------------------------------------------------------------------------------------+
Figure 3.2: Bidirectional IPC System Architecture Block Diagram
```

### 3.4.4 Architectural Data Flow Description
The system's execution loop is driven strictly by PureEdgeSim's discrete-event scheduler thread, interacting synchronously with the PyTorch neural network via local Unix domain sockets:
1. **Workload Ingestion**: The custom `StreamedTraceTaskGenerator` reads trace task events from disk and maintains a bounded look-ahead prefetch queue of size $N_{	ext{buffer}} = 1000$.
2. **Orchestration Interception**: When the simulation clock matches a task's arrival time, the simulator fires a `SEND_TO_ORCH` event, blocking its thread and handing the task to `PythonOrchestrator.findComputingNode()`.
3. **IPC State Transmission**: The `JavaBridge` serializes a static-dynamic split representation of the simulator's world state into JSON, prefixes it with a 4-byte big-endian length header, and writes it to the socket.
4. **Action Selection**: The Python connection server accepts the frame, parses the JSON dictionary into typed dataclasses, executes PyTorch forward passes to predict candidate node Q-values, and returns the chosen integer node index.
5. **Simulated Execution**: The Java thread unblocks, routes the task to the chosen physical edge node, and calculates resource consumption and delays.
6. **Asynchronous Reward Feedback**: Upon task completion or drop, `resultsReturned(Task)` fires asynchronously. It transmits a `TASK_RESULT` JSON packet containing execution telemetry to the Python server. This allows the scheduler to compute composite rewards and insert transitions into its PyTorch experience replay buffer.

---

## 3.5 EXPERIMENTAL PROCEDURES

### 3.5.1 Machine Learning Model Development

#### 1. Semi-Markov Decision Process (SMDP) Timestep Formulation
In highly dynamic and volatile edge networks, task arrivals are highly bursty and non-uniform. Modeling this system under a standard discrete-time Markov Decision Process (MDP)—where the scheduler is actively queried at fixed, uniform 1-second intervals—forces the agent to evaluate the environment even when task queues are completely empty. This generates an extremely sparse action-gradient that severely destabilizes neural network convergence.

To resolve this limitation, this study formulates the scheduling problem as an **event-driven Semi-Markov Decision Process (SMDP)**:
* **Decision Epochs ($k = 1, 2, \dots$)**: The reinforcement learning scheduler is queried to select an action *only* at discrete, event-driven decision points. These points are triggered synchronously inside PureEdgeSim whenever an edge client submits a task that requires an offloading decision.
* **Continuous Temporal Gaps ($	au$)**: The physical simulated time elapsed between decision epochs, defined as:
  $$	au_k = t_{k} - t_{k-1}$$
  is a continuous, variable time duration governed by the empirical task arrival intervals of the preprocessed Google Borg workload.
* **State Transition Dynamics**: The transition probability $P(s_{k+1}, 	au_k \mid s_k, a_k)$ is jointly determined by the deterministic execution times calculated inside PureEdgeSim, stochastic edge network latency variations, and the dynamic battery depletion or mobility disconnections of simulated consumer nodes.

#### 2. Deep Q-Network State Space Representation
At each decision epoch, the DQN agent receives a highly descriptive observation of the simulation environment. This observation is vectorized into a single, flat 1D NumPy float32 array via the PyTorch-compatible `state_to_array()` module. The shape of the state vector is defined as:
$$	ext{State Vector Shape} = \left(3 + N_{	ext{nodes}} 	imes 9 + 	ext{max\_pending} 	imes 10ight)$$
which comprises three distinct sub-components:
* **Global Snapshot (3 features)**: `[clock_time, tasks_in_flight, wan_uplink_utilization_fraction]`.
* **Dynamic Node Array ($N_{	ext{nodes}} 	imes 9$ features)**: For each candidate target node $i$, the dynamic feature vector is defined as:
  $$\mathbf{x}_{	ext{node}, i} = egin{bmatrix} U_{	ext{cpu}, i} & ar{U}_{	ext{cpu}, i} & M_{	ext{avail\_frac}, i} & S_{	ext{avail\_frac}, i} & I_{	ext{idle}, i} & L_{	ext{alive}, i} & Q_{	ext{len}, i} & 	ext{pos}_{x, i} & 	ext{pos}_{y, i} \end{bmatrix}^T$$
  where $L_{	ext{alive}, i} \in \{0.0, 1.0\}$ tracks whether the node's battery is alive, and $Q_{	ext{len}, i}$ is the node's active execution queue length.
* **Task Look-ahead Window ($	ext{max\_pending} 	imes 10$ features)**: Extracted details of the next $	ext{max\_pending} = 10$ upcoming tasks in the queue (sorted by arrival time) to allow look-ahead scheduling decisions.

#### 3. Action Space and Feasibility Constraints
To maintain a tractable action space as the simulated network scales ($>100$ nodes), the action space is formulated as a single-assignment discrete choice:
$$\mathcal{A}_k = \{0, 1, 2, \dots, N_{	ext{nodes}} - 1\} \cup \{	ext{idle}\}$$
where selecting action $a \in [0, N_{	ext{nodes}}-1]$ represents assigning the task to node $a$. Selecting the "idle" action (mapped to integer `-1` over the IPC socket) defers placement or falls back to local execution.

A selected action is validated in Python against three physical constraints before socket transmission:
$$	ext{Action Validity} = egin{cases} 	ext{Valid} & 	ext{if } L_{	ext{alive}, a} = 1.0 	ext{ and } M_{	ext{avail}, a} \ge M_{	ext{task}} 	ext{ and } S_{	ext{avail}, a} \ge S_{	ext{task}} \ 	ext{Invalid} & 	ext{otherwise} \end{cases}$$
If the constraint fails in strict validation mode, the system raises an `InvalidDecisionError` immediately to alert PyTorch developers. In lenient mode, the task is failed with a resource exhaustion penalty.

#### 4. The Composite Reward Function
Because tasks execute asynchronously, reward calculation is deferred to task termination. The composite step reward computed in Python's `rewards.py` upon task termination is defined as:
$$\mathcal{R}_{	ext{step}} = w_{	ext{success}} \cdot \mathcal{R}_{	ext{success}} + w_{	ext{latency}} \cdot \mathcal{R}_{	ext{latency}} + w_{	ext{deadline}} \cdot \mathcal{R}_{	ext{deadline}}$$
Where:
* $\mathcal{R}_{	ext{success}}$ is $+1.0$ on successful completion, and $-1.0$ on failure (battery starvation, range disconnections, or OOM).
* $\mathcal{R}_{	ext{latency}} = -rac{T_{	ext{total}}}{	ext{maxLatency}}$ penalizes execution latency.
* $\mathcal{R}_{	ext{deadline}}$ is $+1.0$ if $T_{	ext{total}} \le 	ext{maxLatency}$, and $-1.0$ on a deadline miss.
* **Priority-Scaled Penalty**: If a task fails, we scale the penalty based on Borg's raw task priority metadata ($P \in [0, 450]$):
  $$	ext{Penalty}_{	ext{final}} = \mathcal{R}_{	ext{step}} 	imes \left(1.0 + 9.0 \cdot \mathbb{I}(P \ge 120)ight)$$
  This scales the failure penalty by $10	imes$ for production-tier tasks ($P \ge 120$), forcing PyTorch gradients to prioritize production workloads over non-critical batch jobs.

#### 5. DQN Neural Network Architecture and Training Loop
The policy network is a fully connected feedforward neural network designed in PyTorch:
* **Input Layer**: Dimension matches the state vector shape.
* **Hidden Layers**: Two fully connected layers of size $256$ and $128$, utilizing Rectified Linear Unit (ReLU) activations.
* **Output Layer**: Dimension matches $|\mathcal{A}_k|$, predicting state-action Q-values.
* **Experience Replay**: Transitions $(s_k, a_k, r_k, s_{k+1}, d_k)$ are stored in a replay buffer of capacity $10,000$. Training samples are drawn in batches of $64$ to update weights using the Adam optimizer with a learning rate of $lpha = 0.001$ and a discount factor of $\gamma = 0.99$. Epsilon-greedy exploration decays from $\epsilon_{	ext{start}} = 1.0$ to $\epsilon_{	ext{end}} = 0.01$ over simulated episodes.

---

### 3.5.2 System Prototype Development

#### 1. Software Stack and Directory Structure
The prototype is engineered as a clean, additive system integrated with PureEdgeSim. The file inventory is structured as:
```
/workspace/
├── com/mechalikh/pureedgesim/python/    <- Java Bridge Package
│   ├── PythonOrchestrator.java         <- Core Orchestrator interface
│   ├── JavaBridge.java                 <- AF_UNIX socket connector
│   ├── MessageBuilder.java             <- Hand-rolled JSON serializer
│   └── MessageParser.java              <- Lightweight JSON regex parser
├── python/pureedgesim/                  <- Python Package Root
│   ├── _bridge/
│   │   ├── connection.py               <- Socket server binder
│   │   ├── protocol.py                 <- Message parser & types factories
│   │   └── dispatcher.py               <- Core event-loop router
│   ├── types/                          <- Typed data containers
│   │   ├── node.py, task.py, state.py  <- Object model classes
│   │   └── episode.py, decision.py     <- Context & decision classes
│   ├── orchestrator.py                 <- Orchestrator abstract base classes
│   ├── rewards.py                      <- Step and composite reward functions
│   └── features.py                     <- NumPy vectorization algorithms
```

#### 2. Core Java Modules (Bridge Client)
* **`StreamedTraceTaskGenerator`**: To prevent JVM `OutOfMemory` errors during 24-hour trace replays ($200,000+$ tasks), this module implements a streaming sliding-window prefetch buffer of size $N_{	ext{buffer}} = 1000$ and a low-watermark of $N_{	ext{low}} = 200$. Memory is bounded to:
  $$	ext{JVM Memory Footprint} = \mathcal{O}(N_{	ext{buffer}} + N_{	ext{active\_in\_flight}})$$
* **`JavaBridge`**: Manages the socket interface using the `junixsocket` library. It implements a big-endian 4-byte length-prefixed framing protocol over Unix domain sockets to transmit UTF-8 JSON payloads safely.
* **`PythonOrchestrator`**: Extends PureEdgeSim's `Orchestrator` base class. On instantiation, it captures the current system PID, derives a unique socket path, launches the Python server as a sub-process via `ProcessBuilder`, executes a handshake to initialize the episode state, and intercepts task orchestration.

#### 3. Core Python Modules (Bridge Server)
* **`connection.py`**: Binds the Unix socket path and executes a blocking loop to accept the Java client.
* **`dispatcher.py`**: Routes incoming JSON packets. It decodes DECISION_REQUEST frames, instantiates dynamic state classes, queries the user's `select_node()` algorithm, validates constraints, and writes back indices. On TASK_RESULT frames, it forwards telemetry asynchronously to update PyTorch buffers.

---

### 3.5.3 Testing, Refinement, and Validation

#### 1. Micro-Benchmarks: IPC Latency
To measure socket transmission overhead, we executed a micro-benchmark replaying $10,000$ framed decision requests containing a 2,619-byte payload (representing a complex network snapshot of 10 dynamic nodes, queue records, and WAN utilization) over the Unix domain socket.
* **Target Budget**: Median round-trip latency $\le 0.5	ext{ ms}$.
* **Measured Performance**:
  $$	ext{p50 (Median) Latency} = \mathbf{0.032	ext{ ms}}$$
  $$	ext{p95 Latency} = \mathbf{0.045	ext{ ms}}$$
  $$	ext{p99 Latency} = \mathbf{0.063	ext{ ms}}$$
The bridge operates **$15.6	imes$ faster** than the strict $0.5	ext{ ms}$ budget, adding negligible overhead.

#### 2. Macro-Benchmarks: Wall-Clock Simulation Execution
We compared the wall-clock execution time of a complete simulation run executing 44 scheduling decisions under three distinct scenarios:
* **Java-Only Baseline (All-Fail Stub)**: $14.73	ext{s}$ total execution.
* **Java+Python Bridge (Round-Robin Heuristic)**: $14.78	ext{s}$ total execution ($+0.05	ext{s}$ overhead).
* **Java+Python Bridge (DQN PyTorch Scheduler)**: $14.45	ext{s}$ total execution (actually $-0.28	ext{s}$ faster due to optimized thread waiting and reduced JVM garbage collection spikes).
The results confirm that the socket bridge overhead is imperceptible under real-world simulation runs.

#### 3. The 6-Stage Statistical Validation Suite
To mathematically prove to the thesis committee that our replayed trace-driven workload matches Google's raw cluster distributions, the system executes six formal validation protocols:
* **Test 1: Arrival Process Temporal Fidelity**: We extract inter-arrival times (IAT) $\Delta t_i = t_{i+1} - t_i$ of tasks inside PureEdgeSim and perform a non-parametric **Two-Sample Kolmogorov-Smirnov (K-S) Test** against the raw BigQuery trace IAT:
  $$D_{	ext{KS}} = \sup_{\Delta t} \left| F_{	ext{trace}}(\Delta t) - F_{	ext{sim}}(\Delta t) ight|$$
  *Success Criteria*: $D_{	ext{KS}} < 0.01$ and $p	ext{-value} > 0.05$ (proving temporal burstiness and scheduling spikes are preserved without distortion).
* **Test 2: Resource Demand Conservation**: We calculate the integrals of total requested CPU work (NCU-seconds) and RAM footprint (GB-seconds) to verify conservation:
  $$\epsilon_{	ext{cpu}} = rac{\left| W_{	ext{sim\_cpu}} - W_{	ext{trace\_cpu}} ight|}{W_{	ext{trace\_cpu}}} < 1.0\%$$
  $$\epsilon_{	ext{ram}} = rac{\left| M_{	ext{sim\_ram}} - M_{	ext{trace\_ram}} ight|}{M_{	ext{trace\_ram}}} < 1.0\%$$
* **Test 3: Baseline Host Duration Calibration**: We run a contention-free calibration test assigning all tasks to un-contended baseline cores.
  *Success Criteria*: Mean Absolute Percentage Error (MAPE) $\le 0.1\%$ between simulated execution times and trace ground-truth durations.
* **Test 4: Priority Categorical Preservation**: We compare priority and scheduling class frequency distributions between raw trace inputs and PureEdgeSim using a **Chi-Square ($\chi^2$) Goodness-of-Fit Test**.
  *Success Criteria*: $p	ext{-value} > 0.05$, ensuring identical decision trade-off profiles for the RL agent.
* **Test 5: Synthetic Plausibility Audit**: We verify that modeled fields adhere to physical limits (e.g., $0\%$ tasks have impossible deadlines where $	ext{maxLatency} < \Delta T_{	ext{exec}}$, and output result sizes are strictly smaller than input dataset sizes).
* **Test 6: Pipeline Record Losslessness**: Enforces record-count conservation across the entire pipeline:
  $$N_{	ext{BigQuery}} = N_{	ext{Parquet}} = N_{	ext{Python\_Preprocessed}} = N_{	ext{StreamedTaskGen}} = N_{	ext{Simulated}}$$
  *Success Criteria*: Zero record discrepancies ($N_{	ext{BigQuery}} - N_{	ext{Simulated}} = 0$).

---
