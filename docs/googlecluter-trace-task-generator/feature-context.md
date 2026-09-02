# Google Cluster Trace Replay — Project Context

## Why We Are Building This

We are extending PureEdgeSim to support a research experiment involving reinforcement-learning-based scheduling in a volunteer edge/cloud environment.

The goal of the research is to investigate whether **consumer devices participating in a volunteer network can potentially serve as an alternative or complementary computing resource to traditional centralized cloud data centers**.

To evaluate this meaningfully, we need a realistic workload.

We do NOT want to generate random or synthetic tasks.

We want to **replay a real-world cloud workload** derived from the Google Cluster 2019 / Borg traces.

The intended experimental flow is:

```text
Google Cluster / Borg Trace
          ↓
Trace Extraction
          ↓
Trace Processing / Interpretation
          ↓
Workload Translation
          ↓
PureEdgeSim
          ↓
Volunteer/Edge Computing Environment
          ↓
Reinforcement Learning Scheduler
          ↓
Scheduling Decisions
          ↓
Simulation Results
```

The Google trace represents real cloud workload behavior.

PureEdgeSim represents the simulated computing environment in which we want to investigate what happens when that workload is executed across consumer/edge devices.

The reinforcement-learning scheduler will then learn from the interaction between:

* the incoming workload
* available computing resources
* network conditions
* task execution
* task outcomes
* resource availability/reliability

The eventual goal is to determine whether decentralized/volunteer computing resources can handle realistic cloud workloads effectively enough to be considered an alternative or complementary architecture to centralized cloud infrastructure.

---

# Critical Requirement: This Must Be Trace Replay

The most important requirement of this project is:

**This is a trace replay system, NOT a random task generator.**

The system must preserve meaningful characteristics of the original Google Cluster workload.

We therefore need to understand and preserve, where possible:

* task/job arrival patterns
* workload intensity over time
* resource requirements
* execution characteristics
* task durations
* temporal relationships
* workload bursts
* idle periods
* task lifecycle information

We must not simply:

```text
Read Google data
        ↓
Generate random PureEdgeSim tasks
```

That would defeat the purpose of using the trace.

Instead:

```text
Google Trace
     ↓
Understand what each record means
     ↓
Extract relevant workload information
     ↓
Translate it into PureEdgeSim's workload model
     ↓
Replay it according to the original timeline
```

The resulting PureEdgeSim workload should be defensible as a **trace-derived/replayed workload**, even where transformations or modeling are necessary.

---

# The Fundamental Problem

Google Cluster and PureEdgeSim were not designed around the same workload model.

Therefore, there is NOT necessarily a one-to-one mapping between the Google Cluster trace and a PureEdgeSim task.

For example, the Google trace may contain concepts, fields, events, and resource measurements that do not have direct equivalents in PureEdgeSim.

Conversely, PureEdgeSim may require information that the Google trace does not directly provide.

This means we may need to:

1. Extract information from Google Cluster.
2. Interpret what that information represents.
3. Determine which information can be mapped directly.
4. Determine which information needs transformation.
5. Determine which information needs to be derived.
6. Determine where modeling assumptions are unavoidable.
7. Document those assumptions.
8. Validate that the resulting workload still represents the original trace characteristics.

This translation layer is a core research/engineering component of the project.

Do not hide these transformations.

They must be explicitly documented because they will eventually need to be explained in the thesis.

---

# Google Cluster Data Access

Another problem is that we currently do not have a finalized mechanism for extracting the exact subset of Google Cluster data required by the simulation.

The eventual workload target is approximately:

**one full day of Google Cluster workload.**

However, we do NOT want to start by processing an entire day.

The implementation should progressively scale:

```text
Tiny dataset
    ↓
Small trace window
    ↓
Larger trace window
    ↓
Several hours
    ↓
Full 24-hour workload
```

The first objective is simply to prove that:

```text
Google data
    ↓
Query
    ↓
Process
    ↓
Translate
    ↓
PureEdgeSim
    ↓
Simulation
```

actually works.

---

# BigQuery Is Part of the Problem

The Google Cluster dataset needs to be queried to obtain the actual workload data.

Therefore this project must also determine:

* Which Google Cluster tables contain the required information.
* Which fields are required.
* Which records need to be joined.
* Which filters should be applied.
* How timestamps should be filtered.
* How much data the query processes.
* How to avoid unnecessarily expensive queries.
* How to extract a small development subset first.
* How the query can eventually retrieve a full day's workload.
* How the extracted data should be stored for reproducible experiments.

The final design should include the actual query strategy.

If BigQuery is appropriate, the implementation should produce a reproducible BigQuery extraction/query process rather than relying on manually downloaded data.

Query efficiency matters because the Google Cluster dataset is large and querying unnecessary data can become expensive.

---

# Trace Replay vs Data Transformation

We need to distinguish between:

### Trace information

Information directly obtained from Google Cluster.

### Derived information

Information calculated from trace data.

Example:

```text
Google timestamps
       ↓
derive task duration
```

### Transformed information

Information converted into a representation required by PureEdgeSim.

Example:

```text
Google resource measurement
       ↓
PureEdgeSim task resource requirement
```

### Modeled information

Information that does not exist directly in the trace and therefore requires a defensible modeling assumption.

Example:

```text
Google workload characteristic
       ↓
PureEdgeSim-compatible representation
       ↓
model missing parameter
```

These categories must remain separate in the documentation.

We must never accidentally present a modeled value as if it came directly from Google Cluster.

---

# Reproducibility Is Important

The eventual system should allow us to answer:

> "Exactly how did you turn the Google Cluster trace into the PureEdgeSim workload used in this experiment?"

Someone reading the thesis should be able to understand:

```text
Dataset
    ↓
Query
    ↓
Selected trace window
    ↓
Filtering
    ↓
Preprocessing
    ↓
Transformation
    ↓
Modeling assumptions
    ↓
PureEdgeSim task representation
    ↓
Replay mechanism
    ↓
Simulation
```

The process should therefore be deterministic and configurable where possible.

---

# Documentation Is a Deliverable

Documentation is NOT an afterthought.

Throughout this project, progressively create documentation describing:

* What we discovered.
* Why decisions were made.
* What alternatives were considered.
* What mappings were chosen.
* What transformations were performed.
* What assumptions were made.
* What data was discarded and why.
* How BigQuery queries were designed.
* How trace windows were selected.
* How the workload was validated.
* How the trace was replayed.
* How the resulting workload relates to the original Google trace.

These documents will eventually be used to transfer the technical details into the thesis methodology.

Do not wait until implementation is finished to document the reasoning.

---

# Existing Project Context

Before making any architectural decision, inspect:

`@docs/pythonbridge`

The Python bridge work establishes the broader architecture for allowing Python-based orchestration/ML logic to interact with PureEdgeSim.

The trace replay system must fit into that architecture.

The eventual system should conceptually become:

```text
                   Google Cluster Trace
                           │
                           ▼
                    Trace Extraction
                           │
                           ▼
                   Trace Processing
                           │
                           ▼
                  Workload Translation
                           │
                           ▼
                     PureEdgeSim
                           │
                ┌──────────┴──────────┐
                │                     │
                ▼                     ▼
          Simulated Nodes       Network/State
                │                     │
                └──────────┬──────────┘
                           ▼
                   Python Orchestrator
                           │
                           ▼
                     RL Scheduler
                           │
                           ▼
                    Scheduling Action
                           │
                           ▼
                     PureEdgeSim
                           │
                           ▼
                       Metrics
```

The exact architecture should be determined by inspecting the repository rather than assuming this diagram is technically correct.

---

# Research Principle

The primary principle guiding every design decision is:

> **Preserve the characteristics of the real workload while translating it into a form that PureEdgeSim can execute.**

If a perfect one-to-one mapping is impossible, do not pretend otherwise.

Instead:

1. Identify the mismatch.
2. Explain why it exists.
3. Determine the least-distorting transformation.
4. Document the assumption.
5. Validate its effect where possible.

---

# What We Are NOT Doing

Do not turn this into:

* A random task generator.
* A synthetic workload generator.
* A replacement for PureEdgeSim's simulation model.
* A custom cloud simulator.
* A system that invents workload characteristics without justification.

PureEdgeSim remains the simulation environment.

Google Cluster provides the workload trace.

Our work is the **trace extraction, interpretation, translation, replay, and validation layer** required to make the real workload usable within PureEdgeSim.

---

# Final Objective

The final system should allow us to take a defined period of Google Cluster workload, ideally one full day, and reproducibly replay that workload inside PureEdgeSim.

The first milestone is NOT one full day.

The first milestone is:

> **Successfully replay a small, verified subset of Google Cluster workload inside PureEdgeSim.**

Once that works, we scale the same pipeline without changing the fundamental methodology.

The final result should provide a defensible bridge between:

**real Google cloud workload → simulated volunteer edge environment → reinforcement-learning scheduling experiment.**