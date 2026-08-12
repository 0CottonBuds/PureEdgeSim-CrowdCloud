# Phase 4 — Implementation Plan: Java/Python Orchestration Bridge

> **Prerequisites read:** `docs/phase1_architecture_analysis.md`, `docs/phase2_boundary_design.md`, `docs/phase3_python_api_spec.md`
> **Status:** Implementation plan — awaiting execution.

---

## Overview

This document breaks down the full implementation into **10 sequential sub-phases**, each independently testable. Every sub-phase ends with a clear definition of done so an AI coding agent can implement one phase at a time without making major architectural decisions.

The bridge is **100% additive** — no existing PureEdgeSim Java files are modified except `pom.xml`.

---

## Dependency Graph

```
4.1 Java Stub
  └─ 4.2 Java Socket I/O
        └─ 4.3 Java Message Serialisation
              └─ 4.4 Python Bridge (_bridge/)
                    └─ 4.5 Python Types (types/)
                          └─ 4.6 Python Orchestrator ABC
                                └─ 4.7 End-to-End Integration
                                      ├─ 4.8 Error Handling
                                      └─ 4.9 RL Support
                                            └─ 4.10 Performance Benchmarking
```

---

## Final Directory Layout

```
PureEdgeSim/                              ← existing root
├── pom.xml                               ← MODIFIED (add junixsocket dep)
├── PureEdgeSim/com/mechalikh/pureedgesim/
│   └── python/                           ← NEW package
│       ├── JavaBridge.java
│       ├── PythonOrchestrator.java
│       ├── MessageBuilder.java
│       ├── MessageParser.java
│       ├── BridgeTimeoutException.java
│       └── BridgeCrashException.java
│
└── python/                               ← NEW directory
    ├── pureedgesim/
    │   ├── __init__.py
    │   ├── orchestrator.py
    │   ├── rewards.py
    │   ├── features.py
    │   ├── types/
    │   │   ├── __init__.py
    │   │   ├── node.py
    │   │   ├── task.py
    │   │   ├── state.py
    │   │   ├── episode.py
    │   │   ├── decision.py
    │   │   └── network.py
    │   └── _bridge/
    │       ├── __init__.py
    │       ├── connection.py
    │       ├── protocol.py
    │       ├── dispatcher.py
    │       └── server.py
    ├── examples/
    │   ├── run_round_robin.py
    │   ├── run_nearest_node.py
    │   └── run_dqn_skeleton.py
    ├── tests/
    │   ├── test_connection.py
    │   ├── test_protocol.py
    │   ├── test_types.py
    │   ├── test_orchestrator.py
    │   ├── test_error_handling.py
    │   ├── test_features.py
    │   ├── test_integration.py
    │   ├── bench_latency.py
    │   └── bench_e2e.py
    ├── requirements.txt
    └── pyproject.toml
```

---

## Cross-Cutting Implementation Rules

These rules apply to every sub-phase:

1. **No existing Java file may be modified** except `pom.xml` and the `examples/` directory.
2. **Java 8 compatibility is mandatory.** Do not use `var`, records, text blocks, `ProcessHandle`, or any Java 9+ API. Use `ManagementFactory.getRuntimeMXBean().getName().split("@")[0]` to get the JVM PID.
3. **No external Python dependencies** beyond stdlib and `numpy`. `numpy` is imported only inside `features.py` and `types/network.py`.
4. **Thread safety:** `PythonOrchestrator` and `JavaBridge` are called from the DES thread only. The Python `Dispatcher` is single-threaded. No synchronisation needed.
5. **No silent failures.** Every caught `IOException` in Java must be logged via `simLog.deepLog()` before returning `-1`. Every caught `Exception` in the Python dispatcher must be logged to stderr.
6. **Additive field policy.** When reading JSON, always use `msg.get("field", default)` in Python and null-safe reads in Java. New fields must not break old code.
7. **Test naming.** Java tests in `src/test/java/com/mechalikh/pureedgesim/python/`. Python tests in `python/tests/`.

---

## Sub-Phase 4.1 — Java Stub: PythonOrchestrator + pom.xml

### What It Accomplishes
Creates the two new Java files in a new `python/` package and adds the `junixsocket` Maven dependency. `findComputingNode` returns `-1` (fail all tasks) as a placeholder. This confirms the new class compiles and plugs into PureEdgeSim's reflection-based injection.

### Files to Create or Modify

#### [MODIFY] pom.xml
Add inside `<dependencies>`:
```xml
<dependency>
    <groupId>com.kohlschutter.junixsocket</groupId>
    <artifactId>junixsocket-core</artifactId>
    <version>2.6.2</version>
</dependency>
```
`junixsocket-core` bundles `junixsocket-common` and the native libraries. Supports Java 8. Provides `AFUNIXSocket` / `AFUNIXServerSocket` with the same API as `java.net.Socket`.

#### [NEW] PureEdgeSim/com/mechalikh/pureedgesim/python/JavaBridge.java
Empty stub. Package `com.mechalikh.pureedgesim.python`. No fields or methods yet. Add a `// TODO: implement socket I/O` comment.

#### [NEW] PureEdgeSim/com/mechalikh/pureedgesim/python/PythonOrchestrator.java
- Package: `com.mechalikh.pureedgesim.python`
- Extends: `com.mechalikh.pureedgesim.taskorchestrator.Orchestrator`
- Constructor: `public PythonOrchestrator(SimulationManager simulationManager)` — calls `super(simulationManager)`.
- `findComputingNode(String[], Task)`: returns `-1`. Add `// TODO: send DECISION_REQUEST, recv DECISION_RESPONSE`.
- `resultsReturned(Task task)`: empty. Add `// TODO: send TASK_RESULT`.

#### [NEW] PureEdgeSim/examples/ExamplePythonBridge.java
```java
public class ExamplePythonBridge {
    public static void main(String[] args) throws Exception {
        Simulation sim = new Simulation();
        sim.setCustomEdgeOrchestrator(PythonOrchestrator.class);
        sim.launchSimulation();
    }
}
```

### Dependencies
None — this is the foundation.

### How to Test
```bash
mvn compile -q
mvn exec:java -Dexec.mainClass=examples.ExamplePythonBridge
```
The simulation must complete with 100% task failure (`NO_OFFLOADING_DESTINATIONS`) and no exception.

### Definition of Done
- `mvn compile` succeeds.
- `ExamplePythonBridge` runs to completion with all tasks failing.
- No existing test suite failures.

---

## Sub-Phase 4.2 — Java Socket I/O: JavaBridge

### What It Accomplishes
Implements the Unix domain socket layer in `JavaBridge.java`. Java can write a 4-byte length-prefixed JSON frame to a socket and read one back. Tested in isolation with a Java echo server, no PureEdgeSim logic involved.

### Files to Create or Modify

#### [MODIFY] PureEdgeSim/com/mechalikh/pureedgesim/python/JavaBridge.java

Implement this public API (all methods throw `IOException`):
```java
public class JavaBridge {

    // Connect to an existing Unix socket file.
    // Retries every 100ms up to timeoutMs.
    public JavaBridge(String socketPath, int timeoutMs) throws IOException;

    // Write one framed message: 4-byte big-endian int length + UTF-8 JSON bytes.
    public void send(String jsonPayload) throws IOException;

    // Read one framed message: read 4-byte length, then exactly N bytes.
    // Returns the UTF-8 decoded JSON string.
    public String recv() throws IOException;

    // Close the socket. Safe to call multiple times.
    public void close();
}
```

**Internal implementation details:**
- Use `com.kohlschutter.junixsocket.AFUNIXSocket` and `AFUNIXSocketAddress`.
- Wrap streams in `DataOutputStream` / `DataInputStream`.
- `send()`: calls `out.writeInt(bytes.length)` then `out.write(bytes)` then `out.flush()`.
- `recv()`: calls `in.readInt()` to get length N, then `in.readFully(buf, 0, N)`.
- Constructor retry loop: `AFUNIXSocket.connectTo(address)` every 100ms up to `timeoutMs`. Throw `BridgeTimeoutException` if exhausted.
- After connecting: `socket.setSoTimeout(30_000)` (30-second read timeout).

#### [NEW] PureEdgeSim/com/mechalikh/pureedgesim/python/BridgeTimeoutException.java
```java
public class BridgeTimeoutException extends RuntimeException {
    public BridgeTimeoutException(String message) { super(message); }
}
```

### Dependencies
Phase 4.1 (junixsocket on classpath).

### How to Test

Create `src/test/java/com/mechalikh/pureedgesim/python/JavaBridgeTest.java`:
```java
@Test
public void testEchoRoundTrip() throws Exception {
    // Use a temp file path for the socket
    Path sockFile = Files.createTempFile("pes_test", ".sock");
    sockFile.toFile().delete(); // AFUNIXServerSocket needs the path, not the file
    String sockPath = sockFile.toAbsolutePath().toString();

    // Start echo server in background thread
    Thread server = new Thread(() -> {
        try (AFUNIXServerSocket srv = AFUNIXServerSocket.newInstance()) {
            srv.bind(new AFUNIXSocketAddress(new File(sockPath)));
            try (AFUNIXSocket conn = srv.accept()) {
                DataInputStream in = new DataInputStream(conn.getInputStream());
                DataOutputStream out = new DataOutputStream(conn.getOutputStream());
                int len = in.readInt();
                byte[] buf = new byte[len];
                in.readFully(buf);
                out.writeInt(len);
                out.write(buf);
                out.flush();
            }
        } catch (Exception e) { e.printStackTrace(); }
    });
    server.setDaemon(true);
    server.start();
    Thread.sleep(100);

    JavaBridge bridge = new JavaBridge(sockPath, 2000);
    bridge.send("{\"type\":\"PING\"}");
    assertEquals("{\"type\":\"PING\"}", bridge.recv());
    bridge.close();
}
```

Run: `mvn test -Dtest=JavaBridgeTest`

### Definition of Done
- `JavaBridgeTest` passes.
- `mvn compile` still passes.
- `ExamplePythonBridge` still runs (all tasks failing).

---

## Sub-Phase 4.3 — Java Message Serialisation

### What It Accomplishes
Implements the full JSON message protocol on the Java side. `MessageBuilder` constructs all outgoing messages from live Java objects. `MessageParser` extracts values from the 3 incoming message types. `PythonOrchestrator` is wired to send/receive real JSON — it still returns whatever integer Python sends back.

### Files to Create or Modify

#### [NEW] PureEdgeSim/com/mechalikh/pureedgesim/python/MessageBuilder.java

Static utility class. Build JSON strings using `StringBuilder` — no external JSON library.

```java
public class MessageBuilder {

    // EPISODE_INIT: sent once after the orchestrator is constructed.
    public static String buildEpisodeInit(
        int episodeId,
        SimulationManager sm,
        List<ComputingNode> nodeList
    );

    // DECISION_REQUEST: sent synchronously inside findComputingNode().
    // requestId: monotonically increasing counter.
    // lookAheadWindowSize: max entries from the future task queue to include.
    public static String buildDecisionRequest(
        int requestId,
        Task task,
        SimulationManager sm,
        List<ComputingNode> nodeList,
        int lookAheadWindowSize
    );

    // TASK_RESULT: sent from resultsReturned(). requestId = -1 for cached placements.
    public static String buildTaskResult(
        int requestId,
        Task task,
        int chosenNodeIndex
    );

    // EPISODE_END: sent from onSimulationEnd().
    public static String buildEpisodeEnd(int episodeId, SimulationManager sm);
}
```

**Critical field mappings for `buildDecisionRequest`:**

Node dynamic state (re-read on every call from live ComputingNode objects):
- `node.getAvailableRam()` → `available_ram_mb`
- `node.getAvailableStorage()` → `available_storage_mb`
- `node.getCurrentCpuUtilization()` → `current_cpu_pct`
- `node.getAvgCpuUtilization()` → `avg_cpu_pct`
- `node.isIdle()` → `is_idle`
- `node.isDead()` → `is_dead`
- `node.getTasksQueue().size()` → `queue_length`
- `node.getMobilityModel().getCurrentLocation().getXPos()` → `current_location_x`
- `node.getMobilityModel().getCurrentLocation().getYPos()` → `current_location_y`

Look-ahead window: access via `sm.getTaskList()` which returns a `FutureQueue<Task>`. Convert with `new ArrayList<>(sm.getTaskList())`, take first `min(size, lookAheadWindowSize)` elements.

Unit conversions:
- `task.getFileSizeInBits()` → JSON field `task_file_size_bits` (no conversion — Python converts bits→MB)
- `task.getOutputSizeInBits()` → JSON field `task_output_bits` (no conversion)
- `task.getContainerSizeInMBytes()` → JSON field `task_container_mb` (already MB)
- `node.getType()` → JSON field `node_type`: map `CLOUD`→`"CLOUD"`, `EDGE_DATACENTER`→`"EDGE_DATACENTER"`, `EDGE_DEVICE`→`"EDGE_DEVICE"`

WAN utilisation: `sm.getNetworkModel().getWanUpUtilization()` → `wan_uplink_utilization`

Static node fields for `buildEpisodeInit`:
- `node.getId()` → `node_id`
- `nodeList.indexOf(node)` → `node_index`
- `node.getType().toString()` → `node_type`
- `node.getTotalMipsCapacity()` → `total_mips`
- `node.getMipsPerCore()` → `mips_per_core`
- `node.getNumberOfCPUCores()` → `num_cores`
- `node.getRamCapacity()` → `total_ram_mb`
- `node.getAvailableStorage() + (used storage)` → use `node.getTotalStorage()` if available, else `node.getAvailableStorage()` as approximation and document
- `node.getMobilityModel().getCurrentLocation().getXPos()` → `base_location_x`
- `node.getMobilityModel().getCurrentLocation().getYPos()` → `base_location_y`
- `node.getType() == EDGE_DATACENTER` → `is_peripheral: true` (edge servers directly reachable by devices)

#### [NEW] PureEdgeSim/com/mechalikh/pureedgesim/python/MessageParser.java

Hand-rolled parser using regex. Incoming messages have at most 4 fields, so this is safe:

```java
public class MessageParser {

    // Extract value of "type" field. Returns null if absent.
    public static String getType(String json);

    // Extract integer value of "node_index" field. Returns -1 if absent.
    public static int getNodeIndex(String json);

    // Extract string value of "status" field.
    public static String getStatus(String json);

    // Extract integer value of "episode_id" field. Returns -1 if absent.
    public static int getEpisodeId(String json);
}
```

Use `Pattern.compile("\"node_index\"\\s*:\\s*(-?\\d+)")` for integer extraction and `Pattern.compile("\"type\"\\s*:\\s*\"([^\"]+)\"")` for strings.

#### [MODIFY] PureEdgeSim/com/mechalikh/pureedgesim/python/PythonOrchestrator.java

Add fields:
```java
private JavaBridge bridge;
private int requestIdCounter = 0;
private static final int LOOK_AHEAD_WINDOW_SIZE = 20;
// Maps task.getId() -> requestId for result correlation
private final Map<Integer, Integer> taskIdToRequestId = new HashMap<>();
// Maps task.getId() -> chosenNodeIndex for TaskResult building
private final Map<Integer, Integer> taskIdToNodeIndex = new HashMap<>();
// Static field for the Python orchestrator class name (configurable)
private static volatile String pythonOrchestratorClass =
    "examples.run_round_robin.RoundRobinOrchestrator";
// Socket path tracking for parallelism guard
private static final Set<String> activeSockets =
    Collections.synchronizedSet(new HashSet<>());
```

Add static method:
```java
public static void setOrchestratorClass(String dotted) {
    pythonOrchestratorClass = dotted;
}
```

**Constructor (modify existing stub):**
After `super(simulationManager)` (which calls `initialize()` and populates `nodeList`):
1. Compute `pid` using `ManagementFactory.getRuntimeMXBean().getName().split("@")[0]`
2. Compute `simId` using a static `AtomicInteger` counter incremented per instance
3. `socketPath = "/tmp/pureedgesim_orch_" + pid + "_" + simId + ".sock"`
4. Assert `activeSockets.add(socketPath)` is true (parallelism guard)
5. Launch Python: `new ProcessBuilder("python3", "-m", "pureedgesim._bridge.server", "--socket", socketPath, "--orchestrator", pythonOrchestratorClass).inheritIO().start()`
6. Construct `bridge = new JavaBridge(socketPath, 10_000)`
7. Read `READY` message: `bridge.recv()`, verify type is `"READY"`
8. Send `EpisodeInit`: `bridge.send(MessageBuilder.buildEpisodeInit(simId, simulationManager, nodeList))`
9. Read `READY_ACK`: verify status is `"READY"`

> **Note on `onSimulationStart()` vs constructor:** `SimEntity.onSimulationStart()` fires only after `simulation.start()` begins. But the `Orchestrator` constructor is called during `loadModels()`, which is before `simulation.start()`. This means the constructor is the correct place to launch Python and do the handshake, because `nodeList` is already populated by `initialize()` at that point.

**`findComputingNode(String[], Task)` (replace stub):**
```java
int reqId = requestIdCounter++;
taskIdToRequestId.put(task.getId(), reqId);
try {
    bridge.send(MessageBuilder.buildDecisionRequest(reqId, task,
        simulationManager, nodeList, LOOK_AHEAD_WINDOW_SIZE));
    String resp = bridge.recv();
    int idx = MessageParser.getNodeIndex(resp);
    taskIdToNodeIndex.put(task.getId(), idx);
    return idx;
} catch (BridgeCrashException e) {
    simLog.deepLog("Python bridge crashed: " + e.getMessage());
    return -1;
} catch (SocketTimeoutException e) {
    simLog.deepLog("Python bridge timed out for task " + task.getId());
    return -1;
} catch (IOException e) {
    simLog.deepLog("Bridge I/O error: " + e.getMessage());
    return -1;
}
```

**`resultsReturned(Task task)` (replace stub):**
```java
int reqId = taskIdToRequestId.getOrDefault(task.getId(), -1);
int nodeIdx = taskIdToNodeIndex.getOrDefault(task.getId(), -1);
taskIdToRequestId.remove(task.getId());
taskIdToNodeIndex.remove(task.getId());
try {
    bridge.send(MessageBuilder.buildTaskResult(reqId, task, nodeIdx));
    // Fire and forget — no response expected
} catch (IOException e) {
    simLog.deepLog("Failed to send TaskResult: " + e.getMessage());
}
```

**`onSimulationEnd()` (override from SimEntity):**
```java
try {
    bridge.send(MessageBuilder.buildEpisodeEnd(simId, simulationManager));
    bridge.recv(); // wait for SHUTDOWN_ACK
} catch (IOException e) {
    simLog.deepLog("Error during episode end: " + e.getMessage());
} finally {
    bridge.close();
    activeSockets.remove(socketPath);
}
```

### Dependencies
Phase 4.2 (`JavaBridge` passes unit test).

### How to Test

**MessageBuilderTest.java** (use Mockito — already in pom.xml):
```java
// Verify EPISODE_INIT structure
String json = MessageBuilder.buildEpisodeInit(0, mockSm, mockNodeList);
assertTrue(json.contains("\"type\":\"EPISODE_INIT\""));
assertTrue(json.contains("\"episode_id\":0"));
assertTrue(json.contains("\"nodes\":[{"));

// Verify DECISION_REQUEST structure
String dreq = MessageBuilder.buildDecisionRequest(5, mockTask, mockSm, mockNodeList, 3);
assertTrue(dreq.contains("\"request_id\":5"));
assertTrue(dreq.contains("\"current_task\":{"));
assertTrue(dreq.contains("\"node_states\":["));
assertTrue(dreq.contains("\"pending_tasks\":["));
```

**MessageParserTest.java:**
```java
String resp = "{\"type\":\"DECISION_RESPONSE\",\"request_id\":5,\"node_index\":2}";
assertEquals("DECISION_RESPONSE", MessageParser.getType(resp));
assertEquals(2, MessageParser.getNodeIndex(resp));

String ack = "{\"type\":\"READY_ACK\",\"episode_id\":0,\"status\":\"READY\"}";
assertEquals("READY", MessageParser.getStatus(ack));
assertEquals(0, MessageParser.getEpisodeId(ack));
```

Run: `mvn test -Dtest=MessageBuilderTest,MessageParserTest`

### Definition of Done
- Both unit tests pass.
- `mvn compile` succeeds.
- `PythonOrchestrator` has all fields and method bodies stubbed to send/receive correct message types.

---

## Sub-Phase 4.4 — Python Bridge (`_bridge/` package)

### What It Accomplishes
Implements the Python side of the socket I/O layer: the mirror image of `JavaBridge.java`. Python can connect to a Unix socket, read length-prefixed JSON frames, and write responses. The `Dispatcher` event loop routes messages to orchestrator lifecycle methods (stubs at this stage).

### Files to Create or Modify

#### [NEW] python/requirements.txt
```
numpy>=1.21
pytest>=7.0
```

#### [NEW] python/pureedgesim/_bridge/__init__.py
Empty.

#### [NEW] python/pureedgesim/_bridge/connection.py
```python
import socket
import struct
import time


class Connection:
    """
    Unix domain socket I/O with 4-byte big-endian length-prefix framing.
    Connects to an existing socket file (created by Java).
    """

    def __init__(self, socket_path: str, connect_timeout: float = 10.0) -> None:
        """
        Connect to the Unix socket at socket_path.
        Retries every 100ms until connect_timeout seconds elapse.
        Raises ConnectionError if the socket never becomes available.
        """
        deadline = time.monotonic() + connect_timeout
        last_err = None
        while time.monotonic() < deadline:
            try:
                self._sock = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
                self._sock.connect(socket_path)
                return
            except (FileNotFoundError, ConnectionRefusedError) as e:
                last_err = e
                self._sock.close()
                time.sleep(0.1)
        raise ConnectionError(
            f"Could not connect to {socket_path} within {connect_timeout}s: {last_err}"
        )

    def send(self, payload: str) -> None:
        """Encode payload as UTF-8, prefix with 4-byte big-endian length, write."""
        data = payload.encode('utf-8')
        header = struct.pack('>I', len(data))
        self._sock.sendall(header + data)

    def recv(self) -> str:
        """Read 4-byte length, then exactly N bytes. Decode as UTF-8."""
        header = self._recv_exactly(4)
        n = struct.unpack('>I', header)[0]
        body = self._recv_exactly(n)
        return body.decode('utf-8')

    def _recv_exactly(self, n: int) -> bytes:
        buf = bytearray()
        while len(buf) < n:
            chunk = self._sock.recv(n - len(buf))
            if not chunk:
                raise EOFError("Connection closed by remote end")
            buf.extend(chunk)
        return bytes(buf)

    def close(self) -> None:
        """Close the socket. Safe to call multiple times."""
        try:
            self._sock.close()
        except Exception:
            pass
```

#### [NEW] python/pureedgesim/_bridge/protocol.py
```python
import json
from typing import Any, Dict


def decode(raw: str) -> Dict[str, Any]:
    """Parse a JSON string into a dict. Raises ValueError on malformed JSON."""
    return json.loads(raw)


def encode(msg: Dict[str, Any]) -> str:
    """Serialise a dict to a compact JSON string."""
    return json.dumps(msg, separators=(',', ':'))
```

The factory functions `build_nodes_from_episode_init`, `build_state_from_decision_request`, and `build_outcome_from_task_result` are added in Phase 4.5.

#### [NEW] python/pureedgesim/_bridge/dispatcher.py
```python
from pureedgesim._bridge.connection import Connection
from pureedgesim._bridge.protocol import decode, encode


class Dispatcher:
    """
    Message router. Reads messages in a loop and calls orchestrator
    lifecycle methods. Sends responses where required.

    Routing table:
        EPISODE_INIT     -> on_episode_begin()  -> send READY_ACK
        DECISION_REQUEST -> select_node()       -> send DECISION_RESPONSE
        TASK_RESULT      -> on_task_complete()  -> (no response)
        EPISODE_END      -> on_episode_end()    -> send SHUTDOWN_ACK
        SHUTDOWN         -> on_shutdown()       -> break
        HEARTBEAT        -> on_tick()           -> (no response)
    """

    def __init__(self, conn: Connection, orchestrator,
                 strict: bool = True, heartbeat_interval: float = 0.0):
        self._conn = conn
        self._orch = orchestrator
        self._strict = strict
        self._heartbeat_interval = heartbeat_interval
        self._nodes = []      # List[Node] — set by EPISODE_INIT
        self._context = None  # SimulationContext — set by first EPISODE_INIT
        self._task_cache = {} # task_id -> Task for outcome correlation

    def run(self) -> None:
        """Block, reading and dispatching messages until SHUTDOWN."""
        while True:
            raw = self._conn.recv()
            msg = decode(raw)
            msg_type = msg.get('type')
            if msg_type == 'EPISODE_INIT':
                self._handle_episode_init(msg)
            elif msg_type == 'DECISION_REQUEST':
                self._handle_decision_request(msg)
            elif msg_type == 'TASK_RESULT':
                self._handle_task_result(msg)
            elif msg_type == 'EPISODE_END':
                self._handle_episode_end(msg)
            elif msg_type == 'SHUTDOWN':
                self._orch.on_shutdown()
                break
            elif msg_type == 'HEARTBEAT':
                # Phase 4.9: call self._orch.on_tick(state)
                pass
            else:
                import sys
                print(f"[bridge] Unknown message type: {msg_type}", file=sys.stderr)

    def _handle_episode_init(self, msg: dict) -> None:
        # Phase 4.5: self._nodes = protocol.build_nodes_from_episode_init(msg)
        # Phase 4.5: self._context = protocol.build_context(msg)
        # Phase 4.5: self._orch.on_episode_begin(episode_ctx, self._nodes)
        # For now: stub — send READY_ACK immediately
        self._conn.send(encode({
            'type': 'READY_ACK',
            'episode_id': msg.get('episode_id', 0),
            'status': 'READY',
        }))

    def _handle_decision_request(self, msg: dict) -> None:
        # Phase 4.5: task, state = protocol.build_state_from_decision_request(msg, self._nodes)
        # Phase 4.5: result = self._orch.select_node(task, state)
        # Phase 4.8: validate result
        # For now: stub — always respond with node_index=0
        self._conn.send(encode({
            'type': 'DECISION_RESPONSE',
            'request_id': msg.get('request_id', 0),
            'node_index': 0,
        }))

    def _handle_task_result(self, msg: dict) -> None:
        # Phase 4.5: outcome = protocol.build_outcome_from_task_result(msg, self._task_cache)
        # Phase 4.5: self._orch.on_task_complete(outcome)
        pass  # No response required

    def _handle_episode_end(self, msg: dict) -> None:
        # Phase 4.5: summary = protocol.build_summary(msg)
        # Phase 4.5: self._orch.on_episode_end(summary)
        self._conn.send(encode({'type': 'SHUTDOWN_ACK', 'status': 'OK'}))
```

#### [NEW] python/pureedgesim/_bridge/server.py
```python
"""
Entry point for the Python bridge process.

Usage:
    python -m pureedgesim._bridge.server \
        --socket /tmp/pureedgesim_orch_12345.sock \
        --orchestrator my_package.MyOrchestrator
"""
import argparse
import importlib
import sys

from pureedgesim._bridge.connection import Connection
from pureedgesim._bridge.dispatcher import Dispatcher


def main() -> None:
    parser = argparse.ArgumentParser(description='PureEdgeSim Python Bridge')
    parser.add_argument('--socket', required=True,
                        help='Unix socket path to connect to')
    parser.add_argument('--orchestrator', required=True,
                        help='Dotted module.ClassName of the Orchestrator subclass')
    parser.add_argument('--strict', action='store_true', default=True,
                        help='Raise on invalid decisions (default: True)')
    parser.add_argument('--heartbeat', type=float, default=0.0,
                        help='Heartbeat interval in simulated seconds (0 = disabled)')
    args = parser.parse_args()

    # Dynamically import the orchestrator class
    try:
        module_name, class_name = args.orchestrator.rsplit('.', 1)
        module = importlib.import_module(module_name)
        orchestrator_cls = getattr(module, class_name)
    except (ValueError, ImportError, AttributeError) as e:
        print(f"[bridge] Failed to import orchestrator '{args.orchestrator}': {e}",
              file=sys.stderr)
        sys.exit(1)

    orchestrator = orchestrator_cls()

    try:
        conn = Connection(args.socket)
    except ConnectionError as e:
        print(f"[bridge] {e}", file=sys.stderr)
        sys.exit(1)

    dispatcher = Dispatcher(conn, orchestrator,
                            strict=args.strict,
                            heartbeat_interval=args.heartbeat)
    try:
        dispatcher.run()
    except EOFError:
        print("[bridge] Java closed the connection.", file=sys.stderr)
    except Exception as e:
        print(f"[bridge] Fatal error: {e}", file=sys.stderr)
        sys.exit(1)


if __name__ == '__main__':
    main()
```

### Dependencies
Phase 4.3 (defines the exact JSON message formats).

### How to Test

**python/tests/test_connection.py:**
```python
import threading
import socket
import struct
import time
import pytest
from pureedgesim._bridge.connection import Connection


def test_echo_round_trip(tmp_path):
    sock_path = str(tmp_path / "test.sock")

    def echo_server():
        srv = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        srv.bind(sock_path)
        srv.listen(1)
        conn, _ = srv.accept()
        # Read one framed message and echo it back
        header = conn.recv(4)
        n = struct.unpack('>I', header)[0]
        body = b''
        while len(body) < n:
            body += conn.recv(n - len(body))
        conn.sendall(header + body)
        conn.close()
        srv.close()

    t = threading.Thread(target=echo_server, daemon=True)
    t.start()
    time.sleep(0.05)

    c = Connection(sock_path)
    c.send('{"type":"PING"}')
    assert c.recv() == '{"type":"PING"}'
    c.close()


def test_connection_timeout(tmp_path):
    sock_path = str(tmp_path / "nonexistent.sock")
    with pytest.raises(ConnectionError):
        Connection(sock_path, connect_timeout=0.2)
```

**python/tests/test_protocol.py:**
```python
from pureedgesim._bridge.protocol import encode, decode


def test_encode_decode_round_trip():
    original = {"type": "DECISION_RESPONSE", "request_id": 3, "node_index": 1}
    assert decode(encode(original)) == original


def test_decode_raises_on_invalid_json():
    import pytest
    with pytest.raises(Exception):
        decode("not valid json{")
```

Run: `pytest python/tests/test_connection.py python/tests/test_protocol.py -v`

Also verify: `python -m pureedgesim._bridge.server --help` prints usage without error.

### Definition of Done
- Both test files pass.
- `python -m pureedgesim._bridge.server --help` exits cleanly.
- The dispatcher handles `EPISODE_INIT` → `DECISION_REQUEST` × N → `EPISODE_END` without crashing (even with stub handlers).

---

## Sub-Phase 4.5 — Python Types (`types/` package)

### What It Accomplishes
Implements all public data types as pure data containers. Updates `protocol.py` with factory functions that convert raw JSON dicts to typed objects. Updates the `Dispatcher` to pass typed objects to the orchestrator.

### Files to Create or Modify

#### [NEW] python/pureedgesim/types/__init__.py
Re-export `Node`, `NodeType`, `Location`, `Task`, `TaskOutcome`, `TaskStatus`, `FailureReason`, `SimulationState`, `NetworkState`, `EpisodeContext`, `EpisodeSummary`, `SimulationContext`, `PlacementDecision`, `InvalidDecisionError`.

#### [NEW] python/pureedgesim/types/node.py

```python
from __future__ import annotations
from dataclasses import dataclass, field
from enum import Enum, auto
from typing import Any, Dict, TYPE_CHECKING
import numpy as np

if TYPE_CHECKING:
    from pureedgesim.types.task import Task


class NodeType(Enum):
    CLOUD       = auto()
    EDGE_SERVER = auto()   # Java: EDGE_DATACENTER
    EDGE_DEVICE = auto()   # Java: EDGE_DEVICE (mist)


# Mapping from Java type strings to NodeType
_JAVA_TYPE_MAP = {
    'CLOUD': NodeType.CLOUD,
    'EDGE_DATACENTER': NodeType.EDGE_SERVER,
    'EDGE_DEVICE': NodeType.EDGE_DEVICE,
}


def node_type_from_java(java_str: str) -> NodeType:
    return _JAVA_TYPE_MAP.get(java_str, NodeType.EDGE_DEVICE)


@dataclass(frozen=True)
class Location:
    x: float
    y: float

    def distance_to(self, other: Location) -> float:
        return ((self.x - other.x) ** 2 + (self.y - other.y) ** 2) ** 0.5


@dataclass
class Node:
    # --- Static identity (never changes within an episode) ---
    index: int          # position in nodeList; equals the integer action value
    id: int             # internal PureEdgeSim node ID
    type: NodeType
    total_mips: float
    mips_per_core: float
    num_cores: int
    total_ram_mb: float
    total_storage_mb: float
    is_peripheral: bool
    base_location: Location

    # --- Dynamic runtime state (refreshed every select_node() call) ---
    available_ram_mb: float
    available_storage_mb: float
    cpu_utilization: float       # instantaneous, 0.0–1.0
    avg_cpu_utilization: float   # time-averaged since sim start
    is_idle: bool
    is_alive: bool               # False if battery depleted
    queued_tasks: int
    current_location: Location

    # --- Extensibility ---
    metadata: Dict[str, Any] = field(default_factory=dict)

    @property
    def is_cloud(self) -> bool:
        return self.type == NodeType.CLOUD

    @property
    def is_edge_server(self) -> bool:
        return self.type == NodeType.EDGE_SERVER

    @property
    def is_edge_device(self) -> bool:
        return self.type == NodeType.EDGE_DEVICE

    @property
    def available_mips(self) -> float:
        return self.total_mips * max(0.0, 1.0 - self.cpu_utilization)

    @property
    def ram_utilization(self) -> float:
        return 1.0 - (self.available_ram_mb / max(1e-6, self.total_ram_mb))

    def can_accept(self, task: Task) -> bool:
        return (
            self.is_alive
            and self.available_ram_mb >= task.ram_required_mb
            and self.available_storage_mb >= task.container_size_mb
        )

    def to_array(self) -> np.ndarray:
        from pureedgesim.features import node_to_array
        return node_to_array(self)

    def static_array(self) -> np.ndarray:
        from pureedgesim.features import node_static_to_array
        return node_static_to_array(self)
```

#### [NEW] python/pureedgesim/types/task.py

Implement `TaskStatus`, `FailureReason`, `Task`, `TaskOutcome` exactly as in Phase 3 §5.

**FailureReason mapping from Java strings:**
```python
_JAVA_FAILURE_MAP = {
    'FAILED_DUE_TO_LATENCY': FailureReason.LATENCY,
    'FAILED_BECAUSE_DEVICE_DEAD': FailureReason.DEAD_NODE,
    'FAILED_DUE_TO_DEVICE_MOBILITY': FailureReason.OUT_OF_RANGE,
    'NO_OFFLOADING_DESTINATIONS': FailureReason.NO_CANDIDATES,
    'INSUFFICIENT_RESOURCES': FailureReason.NO_RESOURCE,
    'INSUFFICIENT_POWER': FailureReason.DEAD_NODE,
    'NOT_GENERATED_BECAUSE_DEVICE_DEAD': FailureReason.DEAD_NODE,
}
```

**Unit conversion:** `input_size_mb` and `output_size_mb` are converted in `protocol.py` from bits using `bits / 8 / 1_000_000`. The `Task` dataclass fields hold MB values.

#### [NEW] python/pureedgesim/types/state.py
Implement `SimulationState` as in Phase 3 §4. Properties: `alive_nodes`, `idle_nodes`, `cloud_nodes`, `edge_servers`, `edge_devices`, `candidates_for(task)`, `to_array()`.

**Important:** `SimulationState.nodes` is a **new list of new `Node` instances** created for each `select_node()` call. The master node list from `EpisodeInit` stores only static fields; dynamic fields are overlaid from `msg['node_states']` on every decision. Never mutate the master list.

#### [NEW] python/pureedgesim/types/network.py
Implement `NetworkState` as in Phase 3 §7.

#### [NEW] python/pureedgesim/types/episode.py
Implement `SimulationContext`, `EpisodeContext`, `EpisodeSummary` as in Phase 3 §8.

#### [NEW] python/pureedgesim/types/decision.py
Implement `PlacementDecision` and `InvalidDecisionError` as in Phase 3 §9.

#### [MODIFY] python/pureedgesim/_bridge/protocol.py

Add factory functions:

```python
from typing import List, Tuple, Dict
from pureedgesim.types.node import Node, NodeType, Location, node_type_from_java
from pureedgesim.types.task import Task, TaskOutcome, TaskStatus, FailureReason, _JAVA_FAILURE_MAP
from pureedgesim.types.state import SimulationState
from pureedgesim.types.network import NetworkState
from pureedgesim.types.episode import EpisodeContext, EpisodeSummary, SimulationContext


def build_nodes_from_episode_init(msg: dict) -> List[Node]:
    """Convert EPISODE_INIT.nodes[] to List[Node] with only static fields."""
    nodes = []
    for n in msg['nodes']:
        node = Node(
            index=n['node_index'],
            id=n['node_id'],
            type=node_type_from_java(n['node_type']),
            total_mips=n['total_mips'],
            mips_per_core=n['mips_per_core'],
            num_cores=n['num_cores'],
            total_ram_mb=n['total_ram_mb'],
            total_storage_mb=n['total_storage_mb'],
            is_peripheral=n['is_peripheral'],
            base_location=Location(n['base_location_x'], n['base_location_y']),
            # Dynamic fields: set to zero — will be populated per-decision
            available_ram_mb=0.0,
            available_storage_mb=0.0,
            cpu_utilization=0.0,
            avg_cpu_utilization=0.0,
            is_idle=True,
            is_alive=True,
            queued_tasks=0,
            current_location=Location(n['base_location_x'], n['base_location_y']),
        )
        nodes.append(node)
    return nodes


def build_state_from_decision_request(
    msg: dict,
    static_nodes: List[Node],
) -> Tuple[Task, SimulationState]:
    """
    Convert DECISION_REQUEST to (Task, SimulationState).
    Creates NEW Node instances each call by merging static fields
    from static_nodes with dynamic fields from msg['node_states'].
    """
    # Build current task
    ct = msg['current_task']
    task = Task(
        id=ct['task_id'],
        length_mi=ct['task_length_mi'],
        input_size_mb=ct['task_file_size_bits'] / 8 / 1_000_000,
        output_size_mb=ct['task_output_bits'] / 8 / 1_000_000,
        container_size_mb=ct['task_container_mb'],
        ram_required_mb=ct['task_container_mb'],
        deadline=ct['task_max_latency_s'],
        app_id=ct['task_app_id'],
        app_type=ct['task_app_type'],
        origin_node_id=ct['src_device_id'],
        origin_node_index=ct['src_device_index'],
        origin_location=Location(ct['src_location_x'], ct['src_location_y']),
        origin_cpu_utilization=ct['src_current_cpu_pct'],
        scheduled_arrival=msg['sim_clock_s'],
        metadata={'request_id': msg['request_id']},
    )

    # Build fresh node list by merging static + dynamic
    live_nodes = []
    for static_node, ns in zip(static_nodes, msg['node_states']):
        live_node = Node(
            # Static fields copied from master
            index=static_node.index,
            id=static_node.id,
            type=static_node.type,
            total_mips=static_node.total_mips,
            mips_per_core=static_node.mips_per_core,
            num_cores=static_node.num_cores,
            total_ram_mb=static_node.total_ram_mb,
            total_storage_mb=static_node.total_storage_mb,
            is_peripheral=static_node.is_peripheral,
            base_location=static_node.base_location,
            # Dynamic fields from this decision request
            available_ram_mb=ns['available_ram_mb'],
            available_storage_mb=ns['available_storage_mb'],
            cpu_utilization=ns['current_cpu_pct'],
            avg_cpu_utilization=ns['avg_cpu_pct'],
            is_idle=ns['is_idle'],
            is_alive=not ns['is_dead'],
            queued_tasks=ns['queue_length'],
            current_location=Location(ns['current_location_x'], ns['current_location_y']),
        )
        live_nodes.append(live_node)

    # Build pending tasks list
    pending = []
    for pt in msg.get('pending_tasks', []):
        pending.append(Task(
            id=pt['task_id'],
            length_mi=pt['task_length_mi'],
            input_size_mb=pt['task_file_size_bits'] / 8 / 1_000_000,
            output_size_mb=pt['task_output_bits'] / 8 / 1_000_000,
            container_size_mb=pt['task_container_mb'],
            ram_required_mb=pt['task_container_mb'],
            deadline=pt['task_max_latency_s'],
            app_id=pt['task_app_id'],
            app_type=pt['task_app_type'],
            origin_node_id=pt['src_device_id'],
            origin_node_index=pt['src_device_index'],
            origin_location=Location(0.0, 0.0),  # not provided for pending tasks
            origin_cpu_utilization=0.0,
            scheduled_arrival=pt['scheduled_time_s'],
        ))

    state = SimulationState(
        clock=msg['sim_clock_s'],
        nodes=live_nodes,
        pending_tasks=pending,
        tasks_in_flight=msg.get('tasks_in_flight', 0),
        network=NetworkState(
            wan_uplink_utilization=msg.get('wan_uplink_utilization', 0.0)
        ),
    )
    return task, state


def build_outcome_from_task_result(
    msg: dict,
    task_cache: Dict[int, Task],
) -> TaskOutcome:
    """Convert TASK_RESULT to TaskOutcome."""
    from pureedgesim.types.task import TaskStatus, TaskOutcome
    task = task_cache.get(msg['task_id'])
    status = TaskStatus.SUCCESS if msg['status'] == 'SUCCESS' else TaskStatus.FAILED
    failure_java = msg.get('failure_reason')
    failure = _JAVA_FAILURE_MAP.get(failure_java) if failure_java else None
    # Find the assigned node — caller must provide the live nodes for lookup
    # For now, store node index; Phase 4.6 will wire this properly
    return TaskOutcome(
        task=task,
        assigned_node=None,  # resolved by dispatcher using node index
        status=status,
        failure_reason=failure,
        total_latency=msg.get('total_delay_s', 0.0),
        computation_time=msg.get('actual_cpu_time_s', 0.0),
        network_time=msg.get('actual_network_time_s', 0.0),
        queue_wait_time=msg.get('waiting_time_s', 0.0),
        execution_start=msg.get('exec_start_time_s', 0.0),
        execution_end=msg.get('exec_finish_time_s', 0.0),
        result_returned_at=msg.get('sim_clock_s', 0.0),
        was_cached=(msg.get('request_id', 0) == -1),
        request_id=msg.get('request_id', -1),
    )
```

#### [MODIFY] python/pureedgesim/_bridge/dispatcher.py

Replace stub handler bodies with real implementations using the factory functions above. Update `_handle_episode_init` to call `on_episode_begin`. Update `_handle_decision_request` to call `select_node` and pass typed objects. Update `_handle_task_result` to call `on_task_complete`.

### Dependencies
Phase 4.4 (dispatcher exists and routes messages).

### How to Test

**python/tests/test_types.py:**
```python
from pureedgesim.types.node import Node, NodeType, Location
from pureedgesim.types.task import Task, TaskStatus, TaskOutcome, FailureReason
from pureedgesim.types.state import SimulationState
from pureedgesim.types.network import NetworkState
from pureedgesim._bridge.protocol import (
    build_nodes_from_episode_init,
    build_state_from_decision_request,
)

SAMPLE_EPISODE_INIT = {
    'type': 'EPISODE_INIT',
    'episode_id': 0,
    'nodes': [
        {'node_index': 0, 'node_id': 1, 'node_type': 'CLOUD',
         'total_mips': 10000.0, 'mips_per_core': 5000.0, 'num_cores': 2,
         'total_ram_mb': 8192.0, 'total_storage_mb': 102400.0,
         'is_peripheral': False, 'base_location_x': 0.0, 'base_location_y': 0.0},
        {'node_index': 1, 'node_id': 2, 'node_type': 'EDGE_DATACENTER',
         'total_mips': 2000.0, 'mips_per_core': 1000.0, 'num_cores': 2,
         'total_ram_mb': 2048.0, 'total_storage_mb': 20480.0,
         'is_peripheral': True, 'base_location_x': 100.0, 'base_location_y': 200.0},
    ],
}

SAMPLE_DECISION_REQUEST = {
    'type': 'DECISION_REQUEST',
    'request_id': 5,
    'sim_clock_s': 10.0,
    'current_task': {
        'task_id': 42, 'task_length_mi': 500.0, 'task_file_size_bits': 8_000_000,
        'task_output_bits': 1_000_000, 'task_container_mb': 50.0,
        'task_max_latency_s': 2.0, 'task_app_id': 0, 'task_app_type': 'AUGMENTED_REALITY',
        'src_device_index': -1, 'src_device_id': 10,
        'src_location_x': 50.0, 'src_location_y': 100.0,
        'src_avg_cpu_pct': 0.3, 'src_current_cpu_pct': 0.5,
    },
    'node_states': [
        {'node_index': 0, 'available_ram_mb': 6000.0, 'available_storage_mb': 80000.0,
         'current_cpu_pct': 0.2, 'avg_cpu_pct': 0.3, 'is_idle': False, 'is_dead': False,
         'queue_length': 2, 'current_location_x': 0.0, 'current_location_y': 0.0},
        {'node_index': 1, 'available_ram_mb': 1500.0, 'available_storage_mb': 15000.0,
         'current_cpu_pct': 0.6, 'avg_cpu_pct': 0.5, 'is_idle': False, 'is_dead': False,
         'queue_length': 5, 'current_location_x': 100.0, 'current_location_y': 200.0},
    ],
    'pending_tasks': [],
    'wan_uplink_utilization': 0.1,
    'tasks_in_flight': 3,
}

def test_build_nodes():
    nodes = build_nodes_from_episode_init(SAMPLE_EPISODE_INIT)
    assert len(nodes) == 2
    assert nodes[0].type == NodeType.CLOUD
    assert nodes[1].type == NodeType.EDGE_SERVER
    assert nodes[1].base_location.x == 100.0

def test_build_state():
    static_nodes = build_nodes_from_episode_init(SAMPLE_EPISODE_INIT)
    task, state = build_state_from_decision_request(SAMPLE_DECISION_REQUEST, static_nodes)
    assert task.id == 42
    assert abs(task.input_size_mb - 1.0) < 1e-6  # 8_000_000 bits / 8 / 1e6
    assert len(state.nodes) == 2
    assert state.nodes[0].available_ram_mb == 6000.0
    assert state.clock == 10.0

def test_candidates_for():
    static_nodes = build_nodes_from_episode_init(SAMPLE_EPISODE_INIT)
    task, state = build_state_from_decision_request(SAMPLE_DECISION_REQUEST, static_nodes)
    candidates = state.candidates_for(task)
    assert all(n.is_alive for n in candidates)
    assert all(n.available_ram_mb >= task.ram_required_mb for n in candidates)

def test_node_properties():
    nodes = build_nodes_from_episode_init(SAMPLE_EPISODE_INIT)
    assert nodes[0].is_cloud
    assert not nodes[0].is_edge_server
    assert nodes[1].is_peripheral
```

Run: `pytest python/tests/test_types.py -v`

### Definition of Done
- All `test_types.py` tests pass.
- `from pureedgesim.types import Node, Task, TaskOutcome, SimulationState` imports cleanly.
- `protocol.build_state_from_decision_request` produces live nodes that are distinct objects from the static master list.

---

## Sub-Phase 4.6 — Python Orchestrator ABC

### What It Accomplishes
Implements the public `Orchestrator` and `RLOrchestrator` base classes, `rewards.py`, `features.py` (dynamic field implementations), and three reference example orchestrators. After this phase a researcher can subclass `Orchestrator` and run the dispatcher.

### Files to Create or Modify

#### [NEW] python/pureedgesim/orchestrator.py
Implement `Orchestrator` and `RLOrchestrator` exactly as in Phase 3 §2 and §11. No changes from the specification.

#### [NEW] python/pureedgesim/rewards.py
Implement `latency_reward`, `success_reward`, `deadline_reward`, `energy_reward`, `default_reward`, and `CompositeReward` exactly as in Phase 3 §12.

#### [NEW] python/pureedgesim/features.py
Implement `task_to_array`, `node_to_array`, `node_static_to_array`, `state_to_array`.

`state_to_array` layout and shape:
- Global: `[clock, tasks_in_flight, wan_utilization]` → 3 elements
- Per node: `node_to_array(n)` → 9 elements each → `len(nodes) * 9`
- Per pending task (up to `max_pending`): `[scheduled_time_s, length_mi, input_size_mb, output_size_mb, container_size_mb, deadline, app_id, src_device_index, 0.0, 0.0]` → 10 elements each → `max_pending * 10` (zero-padded if fewer tasks)

Total shape: `(3 + len(nodes)*9 + max_pending*10,)`

#### [NEW] python/pureedgesim/__init__.py
```python
from pureedgesim.orchestrator import Orchestrator, RLOrchestrator
from pureedgesim.types.node import Node, NodeType, Location
from pureedgesim.types.task import Task, TaskOutcome, TaskStatus, FailureReason
from pureedgesim.types.state import SimulationState
from pureedgesim.types.network import NetworkState
from pureedgesim.types.episode import EpisodeContext, EpisodeSummary, SimulationContext
from pureedgesim.types.decision import PlacementDecision, InvalidDecisionError
from pureedgesim.rewards import latency_reward, success_reward, deadline_reward, CompositeReward

__all__ = [
    'Orchestrator', 'RLOrchestrator',
    'Node', 'NodeType', 'Location',
    'Task', 'TaskOutcome', 'TaskStatus', 'FailureReason',
    'SimulationState', 'NetworkState',
    'EpisodeContext', 'EpisodeSummary', 'SimulationContext',
    'PlacementDecision', 'InvalidDecisionError',
    'latency_reward', 'success_reward', 'deadline_reward', 'CompositeReward',
]
```

#### [NEW] python/examples/run_round_robin.py
`RoundRobinOrchestrator`: implements `on_episode_begin` (reset counter) and `select_node` (round-robin over `state.candidates_for(task)`).

#### [NEW] python/examples/run_nearest_node.py
`NearestNodeOrchestrator`: `select_node` returns `min(candidates, key=lambda n: n.current_location.distance_to(task.origin_location), default=None)`.

#### [NEW] python/examples/run_dqn_skeleton.py
`DQNOrchestrator(RLOrchestrator)`: `select_node` uses `random.choice(candidates)` as the random policy fallback. Stores `(obs, action)` in `self._pending` keyed by `task.metadata['request_id']`. `on_task_complete` calls `self.reward(outcome)` and prints it. `on_episode_end` prints summary stats.

### Dependencies
Phase 4.5 (all types must exist).

### How to Test

**python/tests/test_orchestrator.py:**
```python
from pureedgesim import Orchestrator, RLOrchestrator
from pureedgesim.types.node import Node, NodeType, Location
import pytest


def make_node(index, alive=True, ram=1000.0):
    return Node(
        index=index, id=index, type=NodeType.EDGE_SERVER,
        total_mips=1000.0, mips_per_core=500.0, num_cores=2,
        total_ram_mb=2048.0, total_storage_mb=10240.0,
        is_peripheral=True, base_location=Location(0, 0),
        available_ram_mb=ram, available_storage_mb=5000.0,
        cpu_utilization=0.3, avg_cpu_utilization=0.3,
        is_idle=False, is_alive=alive,
        queued_tasks=1, current_location=Location(0, 0),
    )


class ConcreteOrch(Orchestrator):
    def select_node(self, task, state):
        return state.nodes[0] if state.nodes else None


def test_orchestrator_lifecycle():
    o = ConcreteOrch()
    o.on_start(context=None)
    o.on_episode_begin(episode=None, nodes=[])
    o.on_episode_end(summary=None)
    o.on_shutdown()


def test_rl_orchestrator_reward_tracking():
    class ConcreteRL(RLOrchestrator):
        def select_node(self, task, state):
            return state.nodes[0]

    from unittest.mock import MagicMock
    rl = ConcreteRL()
    mock_episode = MagicMock()
    mock_episode.num_candidate_nodes = 3
    rl.on_episode_begin(mock_episode, [make_node(i) for i in range(3)])
    assert rl.action_space_size == 3
    assert rl.episode_reward == 0.0
    assert rl.episode_steps == 0


def test_round_robin_cycles():
    from examples.run_round_robin import RoundRobinOrchestrator
    from unittest.mock import MagicMock
    o = RoundRobinOrchestrator()
    o.on_episode_begin(MagicMock(), [make_node(i) for i in range(3)])

    mock_task = MagicMock()
    mock_task.ram_required_mb = 100.0
    mock_task.container_size_mb = 100.0

    from pureedgesim.types.state import SimulationState
    from pureedgesim.types.network import NetworkState
    state = SimulationState(
        clock=0.0,
        nodes=[make_node(i) for i in range(3)],
        pending_tasks=[],
        tasks_in_flight=0,
        network=NetworkState(wan_uplink_utilization=0.0),
    )

    results = [o.select_node(mock_task, state) for _ in range(6)]
    indices = [n.index for n in results]
    # Should cycle: 0, 1, 2, 0, 1, 2
    assert indices == [0, 1, 2, 0, 1, 2]
```

Run: `pytest python/tests/test_orchestrator.py -v`

### Definition of Done
- `test_orchestrator.py` passes.
- `from pureedgesim import Orchestrator, RLOrchestrator` imports cleanly.
- All three example files import without error.
- `python -m pureedgesim._bridge.server --orchestrator examples.run_round_robin.RoundRobinOrchestrator --socket /nonexistent` exits with a `ConnectionError` (not an import error).

---

## Sub-Phase 4.7 — End-to-End Integration Test

### What It Accomplishes
A complete PureEdgeSim simulation runs with `RoundRobinOrchestrator` as the Python-side algorithm. Tasks succeed (not 100% fail). Java and Python communicate over a real Unix socket for the first time.

### Files to Create or Modify

#### [MODIFY] PureEdgeSim/com/mechalikh/pureedgesim/python/PythonOrchestrator.java

Make the orchestrator class configurable (add before the constructor):
```java
private static volatile String pythonOrchestratorClass =
    "examples.run_round_robin.RoundRobinOrchestrator";

public static void setOrchestratorClass(String dotted) {
    pythonOrchestratorClass = dotted;
}
```

Investigate `SimulationManager` for `getTaskList()`. Check `DefaultSimulationManager.java` for a `taskList` field or `getTaskList()` method. If none exists, add a package-private getter to `DefaultSimulationManager` or access via reflection as a last resort. Document this in a code comment.

Add `total_episodes` to `buildEpisodeInit`: use `simulationManager.getSimulation().getSimulationList().size()` if that method exists, otherwise `1`.

#### [NEW] PureEdgeSim/examples/ExamplePythonBridgeE2E.java
```java
package examples;

import com.mechalikh.pureedgesim.python.PythonOrchestrator;
import com.mechalikh.pureedgesim.simulationmanager.Simulation;

public class ExamplePythonBridgeE2E {
    public static void main(String[] args) throws Exception {
        PythonOrchestrator.setOrchestratorClass(
            "examples.run_round_robin.RoundRobinOrchestrator"
        );
        Simulation sim = new Simulation();
        sim.setCustomEdgeOrchestrator(PythonOrchestrator.class);
        sim.launchSimulation();
    }
}
```

#### [NEW] python/tests/test_integration.py

```python
"""
Integration test: launches the Java simulation and checks that it
completes successfully with RoundRobinOrchestrator.

Requires: Maven on PATH, Python bridge package installed/on PYTHONPATH.
"""
import subprocess
import os
import pytest

PROJECT_ROOT = os.environ.get(
    'PUREEDGESIM_ROOT',
    '/home/cotton/Projects/ML/Thesis/PureEdgeSim'
)


@pytest.mark.integration
def test_e2e_round_robin():
    """Full simulation run with Python RoundRobinOrchestrator."""
    result = subprocess.run(
        ['mvn', '-q', 'exec:java',
         '-Dexec.mainClass=examples.ExamplePythonBridgeE2E',
         f'-Dexec.classpathScope=compile'],
        cwd=PROJECT_ROOT,
        capture_output=True,
        text=True,
        timeout=180,
        env={**os.environ, 'PYTHONPATH': os.path.join(PROJECT_ROOT, 'python')},
    )
    assert result.returncode == 0, (
        f"Simulation failed.\nSTDOUT:\n{result.stdout[-3000:]}\n"
        f"STDERR:\n{result.stderr[-3000:]}"
    )
    # Verify output CSV was created
    output_dir = os.path.join(PROJECT_ROOT, 'PureEdgeSim', 'output')
    csv_files = [f for f in os.listdir(output_dir) if f.endswith('.csv')]
    assert len(csv_files) > 0, "No output CSV generated"
```

### Dependencies
Phases 4.1 through 4.6 all completed.

### How to Test

**Manual (primary):**
```bash
# Terminal 1 (Python side must be ready before Java connects):
cd /home/cotton/Projects/ML/Thesis/PureEdgeSim
PYTHONPATH=python python -m pureedgesim._bridge.server \
    --socket /tmp/pes_test.sock \
    --orchestrator examples.run_round_robin.RoundRobinOrchestrator

# Terminal 2:
mvn exec:java -Dexec.mainClass=examples.ExamplePythonBridgeE2E
```

Wait — actually the Java side launches the Python process itself via `ProcessBuilder`. Just run:
```bash
PYTHONPATH=python mvn exec:java -Dexec.mainClass=examples.ExamplePythonBridgeE2E
```

**Expected outcome:**
- Simulation completes with task success rate > 0%.
- Both processes exit cleanly (exit code 0).
- No `IOException` or `ConnectionError`.

**Automated:**
```bash
pytest python/tests/test_integration.py -v -m integration
```

### Definition of Done
- Full simulation completes with `RoundRobinOrchestrator`.
- Task success rate > 0%.
- Both processes exit with code 0.
- Output CSV is generated in `PureEdgeSim/output/`.

---

## Sub-Phase 4.8 — Error Handling

### What It Accomplishes
Makes the bridge robust against five failure modes: invalid decisions, Python crash, socket timeout, protocol version mismatch, and parallelism conflicts.

### Files to Create or Modify

#### [MODIFY] python/pureedgesim/_bridge/dispatcher.py

Add validation in `_handle_decision_request` after calling `select_node`:

```python
def _validate_decision(self, result, task, state):
    from pureedgesim.types.node import Node
    from pureedgesim.types.decision import PlacementDecision, InvalidDecisionError

    if result is None:
        return -1

    if isinstance(result, PlacementDecision):
        node = result.node
        if node is None:
            return -1
    elif isinstance(result, Node):
        node = result
    else:
        raise TypeError(
            f"select_node() returned {type(result).__name__}, "
            f"expected Node, PlacementDecision, or None"
        )

    # Check node identity: must be in this episode's node list by index
    valid_indices = {n.index for n in state.nodes}
    if node.index not in valid_indices:
        raise InvalidDecisionError(
            "Node is not in the candidate list for this episode. "
            "Did you return a stale reference from a previous episode?",
            node=node, task=task,
        )

    if not node.is_alive:
        raise InvalidDecisionError(
            f"Selected node (index={node.index}) is dead (battery depleted). "
            "Check node.is_alive before returning.",
            node=node, task=task,
        )

    if node.available_ram_mb < task.ram_required_mb:
        raise InvalidDecisionError(
            f"Node (index={node.index}) has insufficient RAM: "
            f"needs {task.ram_required_mb:.1f} MB, "
            f"has {node.available_ram_mb:.1f} MB.",
            node=node, task=task,
        )

    if node.available_storage_mb < task.container_size_mb:
        raise InvalidDecisionError(
            f"Node (index={node.index}) has insufficient storage: "
            f"needs {task.container_size_mb:.1f} MB, "
            f"has {node.available_storage_mb:.1f} MB.",
            node=node, task=task,
        )

    return node.index
```

Wrap in `_handle_decision_request`:
```python
try:
    result = self._orch.select_node(task, state)
    idx = self._validate_decision(result, task, state)
except InvalidDecisionError as e:
    if self._strict:
        raise  # propagates → kills process → Java detects crash
    else:
        import warnings
        warnings.warn(f"Invalid decision (lenient mode): {e}")
        idx = -1
```

#### [NEW] PureEdgeSim/com/mechalikh/pureedgesim/python/BridgeCrashException.java
```java
package com.mechalikh.pureedgesim.python;

public class BridgeCrashException extends RuntimeException {
    public BridgeCrashException(String message) { super(message); }
    public BridgeCrashException(String message, Throwable cause) {
        super(message, cause);
    }
}
```

#### [MODIFY] PureEdgeSim/com/mechalikh/pureedgesim/python/JavaBridge.java

In `recv()`, catch `EOFException` and re-throw:
```java
} catch (EOFException e) {
    throw new BridgeCrashException(
        "Python orchestrator process died unexpectedly. " +
        "Check Python stderr for the traceback.", e
    );
}
```

#### [MODIFY] python/pureedgesim/_bridge/dispatcher.py

In `_handle_episode_init`, add protocol version check:
```python
PROTOCOL_VERSION = "1.0"
received_version = msg.get('protocol_version', '1.0')
if received_version != PROTOCOL_VERSION:
    import warnings
    warnings.warn(
        f"Protocol version mismatch: bridge expects {PROTOCOL_VERSION}, "
        f"Java sent {received_version}. Some fields may be missing."
    )
```

### Dependencies
Phase 4.7 (integration test passing before hardening).

### How to Test

**python/tests/test_error_handling.py:**
```python
import pytest
from unittest.mock import MagicMock, patch
from pureedgesim._bridge.dispatcher import Dispatcher
from pureedgesim.types.decision import InvalidDecisionError
from pureedgesim.types.node import Node, NodeType, Location


def make_node(index, alive=True, ram=1000.0, storage=5000.0):
    return Node(
        index=index, id=index, type=NodeType.EDGE_SERVER,
        total_mips=1000.0, mips_per_core=500.0, num_cores=2,
        total_ram_mb=2048.0, total_storage_mb=10240.0,
        is_peripheral=True, base_location=Location(0, 0),
        available_ram_mb=ram, available_storage_mb=storage,
        cpu_utilization=0.3, avg_cpu_utilization=0.3,
        is_idle=False, is_alive=alive,
        queued_tasks=1, current_location=Location(0, 0),
    )


def make_dispatcher(orch, strict=True):
    conn = MagicMock()
    return Dispatcher(conn, orch, strict=strict, heartbeat_interval=0.0)


def make_task(ram=50.0, storage=100.0):
    t = MagicMock()
    t.ram_required_mb = ram
    t.container_size_mb = storage
    return t


def make_state(nodes):
    from pureedgesim.types.state import SimulationState
    from pureedgesim.types.network import NetworkState
    return SimulationState(
        clock=0.0, nodes=nodes, pending_tasks=[],
        tasks_in_flight=0,
        network=NetworkState(wan_uplink_utilization=0.0),
    )


def test_none_return_is_not_an_error():
    class NullOrch:
        def select_node(self, t, s): return None

    d = make_dispatcher(NullOrch())
    task = make_task()
    state = make_state([make_node(0)])
    idx = d._validate_decision(None, task, state)
    assert idx == -1


def test_stale_node_raises_strict():
    class StaleOrch:
        def select_node(self, t, s): return make_node(999)

    d = make_dispatcher(StaleOrch(), strict=True)
    task = make_task()
    state = make_state([make_node(0)])
    with pytest.raises(InvalidDecisionError, match="not in the candidate list"):
        d._validate_decision(make_node(999), task, state)


def test_dead_node_raises_strict():
    dead = make_node(0, alive=False)
    d = make_dispatcher(MagicMock())
    with pytest.raises(InvalidDecisionError, match="dead"):
        d._validate_decision(dead, make_task(), make_state([dead]))


def test_insufficient_ram_raises():
    stingy = make_node(0, ram=10.0)
    d = make_dispatcher(MagicMock())
    with pytest.raises(InvalidDecisionError, match="RAM"):
        d._validate_decision(stingy, make_task(ram=500.0), make_state([stingy]))


def test_lenient_mode_sends_minus_one():
    class BadOrch:
        def select_node(self, t, s): return make_node(999)

    conn = MagicMock()
    d = Dispatcher(conn, BadOrch(), strict=False, heartbeat_interval=0.0)
    d._nodes = [make_node(0)]

    import warnings
    with warnings.catch_warnings(record=True) as w:
        warnings.simplefilter("always")
        # Simulate the decision flow directly
        task = make_task()
        state = make_state([make_node(0)])
        try:
            idx = d._validate_decision(make_node(999), task, state)
        except InvalidDecisionError:
            idx = -1  # lenient
    assert idx == -1
```

Run: `pytest python/tests/test_error_handling.py -v`

### Definition of Done
- All `test_error_handling.py` tests pass.
- Deliberately returning an invalid node in strict mode kills the Python process with a readable traceback.
- Manually killing the Python process mid-simulation causes Java to log the crash and exit cleanly.
- Socket timeout is caught and the task fails gracefully.

---

## Sub-Phase 4.9 — RL Support

### What It Accomplishes
Completes `features.py` (stub implementations), validates `RLOrchestrator` end-to-end through a full simulation run using the `DQNOrchestrator` skeleton with real feature vectors and reward computation.

### Files to Create or Modify

#### [MODIFY] python/pureedgesim/features.py

Complete `node_static_to_array`:
```python
def node_static_to_array(node: 'Node') -> np.ndarray:
    # Shape: (11,)
    return np.array([
        node.total_mips,
        node.mips_per_core,
        float(node.num_cores),
        node.total_ram_mb,
        node.total_storage_mb,
        float(node.is_cloud),
        float(node.is_edge_server),
        float(node.is_edge_device),
        float(node.is_peripheral),
        node.base_location.x,
        node.base_location.y,
    ], dtype=np.float32)
```

Complete `state_to_array`:
```python
def state_to_array(state, include_pending=True, max_pending=10) -> np.ndarray:
    global_feats = np.array([
        state.clock,
        float(state.tasks_in_flight),
        state.network.wan_uplink_utilization,
    ], dtype=np.float32)

    node_feats = np.concatenate([node_to_array(n) for n in state.nodes])

    pending_feats = np.zeros(max_pending * 10, dtype=np.float32)
    if include_pending:
        for i, pt in enumerate(state.pending_tasks[:max_pending]):
            offset = i * 10
            pending_feats[offset:offset + 10] = [
                pt.scheduled_arrival, pt.length_mi, pt.input_size_mb,
                pt.output_size_mb, pt.container_size_mb, pt.deadline,
                float(pt.app_id), float(pt.origin_node_index),
                0.0, 0.0,  # reserved
            ]

    return np.concatenate([global_feats, node_feats, pending_feats])
```

#### [MODIFY] python/examples/run_dqn_skeleton.py

Replace the random stub with the full Phase 3 §14 `DQNOrchestrator` skeleton:
- `on_start`: initialise `self._pending: dict = {}`, `self._reward_fn = CompositeReward([(deadline_reward, 2.0), (latency_reward, 1.0)])`
- `on_episode_begin`: call `super()`, clear `self._pending`
- `select_node`: call `self.observation(task, state)`, pick `random.randint(0, self.action_space_size - 1)`, store `(obs, action)` in `self._pending[task.metadata['request_id']]`, return `state.nodes[action]` (or `candidates_for(task)` fallback)
- `on_task_complete`: pop from `self._pending`, call `self.reward(outcome)`, print reward
- `on_episode_end`: print episode summary with `self.episode_reward` and `summary.success_rate`

### Dependencies
Phase 4.8 (error handling stable).

### How to Test

**python/tests/test_features.py:**
```python
import numpy as np
import pytest
from pureedgesim.features import (
    task_to_array, node_to_array, node_static_to_array, state_to_array
)
from pureedgesim._bridge.protocol import (
    build_nodes_from_episode_init, build_state_from_decision_request
)
from tests.test_types import SAMPLE_EPISODE_INIT, SAMPLE_DECISION_REQUEST


def get_state():
    static = build_nodes_from_episode_init(SAMPLE_EPISODE_INIT)
    task, state = build_state_from_decision_request(SAMPLE_DECISION_REQUEST, static)
    return task, state, static


def test_task_to_array_shape():
    task, _, _ = get_state()
    arr = task_to_array(task)
    assert arr.dtype == np.float32
    assert arr.shape == (9,)
    assert np.all(np.isfinite(arr))


def test_node_to_array_shape():
    _, state, _ = get_state()
    arr = node_to_array(state.nodes[0])
    assert arr.dtype == np.float32
    assert arr.shape == (9,)
    assert np.all(np.isfinite(arr))


def test_node_static_array_shape():
    _, state, _ = get_state()
    arr = node_static_to_array(state.nodes[0])
    assert arr.dtype == np.float32
    assert arr.shape == (11,)
    assert np.all(np.isfinite(arr))


def test_state_to_array():
    _, state, _ = get_state()
    arr = state_to_array(state, include_pending=True, max_pending=5)
    assert arr.dtype == np.float32
    expected_len = 3 + len(state.nodes) * 9 + 5 * 10
    assert arr.shape == (expected_len,), f"Expected ({expected_len},), got {arr.shape}"
    assert np.all(np.isfinite(arr))


def test_ram_fraction_bounded():
    _, state, _ = get_state()
    for n in state.nodes:
        arr = node_to_array(n)
        ram_fraction = arr[2]  # available_ram_fraction
        assert 0.0 <= ram_fraction <= 1.0 + 1e-6
```

Run: `pytest python/tests/test_features.py -v`

**Manual integration test with DQN skeleton:**
```bash
PYTHONPATH=python mvn exec:java \
    -Dexec.mainClass=examples.ExamplePythonBridgeE2E \
    -Dexec.args="--orchestrator examples.run_dqn_skeleton.DQNOrchestrator"
```
Verify: reward values printed per task, episode summary printed on completion, no NaN/Inf errors.

### Definition of Done
- `test_features.py` passes.
- `DQNOrchestrator` skeleton completes a full simulation run.
- `observation()` returns a finite float32 array for every task.
- `reward()` returns a finite float for every outcome.
- `episode_reward` accumulates non-trivially across the episode.

---

## Sub-Phase 4.10 — Performance Benchmarking

### What It Accomplishes
Measures per-decision bridge overhead. Documents results. If overhead exceeds the budget (≤ 0.5 ms/decision), identifies and fixes the bottleneck.

### Files to Create or Modify

#### [NEW] python/tests/bench_latency.py

```python
"""
Measures Unix socket round-trip latency for a representative DECISION_REQUEST payload.
Run: python python/tests/bench_latency.py
"""
import threading
import socket
import struct
import time
import statistics
import json

DECISION_REQUEST_PAYLOAD = json.dumps({
    "type": "DECISION_REQUEST",
    "request_id": 0,
    "sim_clock_s": 10.0,
    "current_task": {
        "task_id": 1, "task_length_mi": 500.0, "task_file_size_bits": 8000000,
        "task_output_bits": 1000000, "task_container_mb": 50.0,
        "task_max_latency_s": 2.0, "task_app_id": 0,
        "task_app_type": "AUGMENTED_REALITY",
        "src_device_index": -1, "src_device_id": 10,
        "src_location_x": 50.0, "src_location_y": 100.0,
        "src_avg_cpu_pct": 0.3, "src_current_cpu_pct": 0.5,
    },
    "node_states": [
        {"node_index": i, "available_ram_mb": 1000.0, "available_storage_mb": 5000.0,
         "current_cpu_pct": 0.3, "avg_cpu_pct": 0.3, "is_idle": False, "is_dead": False,
         "queue_length": 2, "current_location_x": float(i * 10), "current_location_y": 0.0}
        for i in range(10)
    ],
    "pending_tasks": [],
    "wan_uplink_utilization": 0.1,
    "tasks_in_flight": 5,
}, separators=(',', ':'))

RESPONSE_PAYLOAD = json.dumps(
    {"type": "DECISION_RESPONSE", "request_id": 0, "node_index": 3},
    separators=(',', ':')
)


def run_bench(sock_path: str, n_samples: int = 10_000):
    def echo_server():
        srv = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        srv.bind(sock_path)
        srv.listen(1)
        conn, _ = srv.accept()
        for _ in range(n_samples + 100):  # extra buffer
            try:
                header = b''
                while len(header) < 4:
                    header += conn.recv(4 - len(header))
                n = struct.unpack('>I', header)[0]
                body = b''
                while len(body) < n:
                    body += conn.recv(n - len(body))
                resp = RESPONSE_PAYLOAD.encode()
                conn.sendall(struct.pack('>I', len(resp)) + resp)
            except Exception:
                break
        conn.close()
        srv.close()

    t = threading.Thread(target=echo_server, daemon=True)
    t.start()
    time.sleep(0.05)

    from pureedgesim._bridge.connection import Connection
    c = Connection(sock_path)

    # Warm up
    for _ in range(100):
        c.send(DECISION_REQUEST_PAYLOAD)
        c.recv()

    latencies = []
    for _ in range(n_samples):
        t0 = time.perf_counter()
        c.send(DECISION_REQUEST_PAYLOAD)
        c.recv()
        latencies.append((time.perf_counter() - t0) * 1000)

    c.close()

    print(f"Round-trip latency over Unix socket ({n_samples} samples):")
    print(f"  Payload size: {len(DECISION_REQUEST_PAYLOAD)} bytes")
    print(f"  Median:  {statistics.median(latencies):.3f} ms")
    print(f"  Mean:    {statistics.mean(latencies):.3f} ms")
    print(f"  p95:     {sorted(latencies)[int(0.95 * n_samples)]:.3f} ms")
    print(f"  p99:     {sorted(latencies)[int(0.99 * n_samples)]:.3f} ms")
    print(f"  Max:     {max(latencies):.3f} ms")
    print()
    median = statistics.median(latencies)
    if median <= 0.5:
        print(f"  PASS: median {median:.3f} ms <= 0.5 ms budget")
    else:
        print(f"  FAIL: median {median:.3f} ms exceeds 0.5 ms budget")


if __name__ == '__main__':
    import tempfile, os
    with tempfile.TemporaryDirectory() as d:
        run_bench(os.path.join(d, 'bench.sock'))
```

#### [NEW] python/tests/bench_e2e.py

```python
"""
Compares wall-clock time of Java-only simulation vs. Python-bridge simulation.
Run: python python/tests/bench_e2e.py

Requires: PROJECT_ROOT env var or defaults to hardcoded path.
"""
import subprocess, os, time

PROJECT_ROOT = os.environ.get(
    'PUREEDGESIM_ROOT',
    '/home/cotton/Projects/ML/Thesis/PureEdgeSim'
)
PYTHONPATH = os.path.join(PROJECT_ROOT, 'python')


def run_simulation(main_class: str, label: str) -> float:
    print(f"Running {label}...")
    t0 = time.monotonic()
    result = subprocess.run(
        ['mvn', '-q', 'exec:java', f'-Dexec.mainClass={main_class}'],
        cwd=PROJECT_ROOT,
        env={**os.environ, 'PYTHONPATH': PYTHONPATH},
        capture_output=True,
        text=True,
        timeout=300,
    )
    elapsed = time.monotonic() - t0
    if result.returncode != 0:
        print(f"  FAILED: {result.stderr[-500:]}")
        return float('inf')
    print(f"  Completed in {elapsed:.1f}s")
    return elapsed


def main():
    # Count decisions from a prior run's log or use a known scenario value.
    # For the default settings, approximate N_DECISIONS.
    N_DECISIONS_APPROX = 1000  # adjust based on actual simulation output

    t_java = run_simulation('examples.ExamplePythonBridge', 'Java-only (stub, all-fail)')
    t_bridge = run_simulation('examples.ExamplePythonBridgeE2E', 'Java+Python bridge')

    overhead_total = t_bridge - t_java
    overhead_per_decision = overhead_total / N_DECISIONS_APPROX * 1000  # ms

    print()
    print("=" * 50)
    print(f"Java-only wall-clock:   {t_java:.1f}s")
    print(f"Bridge wall-clock:      {t_bridge:.1f}s")
    print(f"Total overhead:         {overhead_total:.1f}s")
    print(f"Per-decision overhead:  ~{overhead_per_decision:.2f} ms")
    print(f"Budget:                 0.5 ms")
    if overhead_per_decision <= 0.5:
        print("PASS")
    else:
        print("FAIL — investigate bottleneck (see docs/performance_results.md)")


if __name__ == '__main__':
    main()
```

#### [NEW] docs/performance_results.md
Template to be filled in after running benchmarks:
```markdown
# Performance Results

## Machine Specification
- CPU: [fill in]
- RAM: [fill in]
- OS: [fill in]

## Simulation Scenario
- Devices: [fill in]
- Simulation duration: [fill in]s
- Approximate decisions per run: [fill in]

## Results
| Metric                  | Value   |
|-------------------------|---------|
| Baseline (Java-only)    | [X.X]s  |
| Bridge (Java+Python)    | [X.X]s  |
| Total overhead          | [X.X]s  |
| Per-decision overhead   | [X.X]ms |
| Socket round-trip p50   | [X.X]ms |
| Socket round-trip p99   | [X.X]ms |

## Conclusion
[PASS/FAIL] Per-decision overhead is [within/over] the 0.5ms budget.

## Bottlenecks Found (if any)
[Describe any profiling findings and fixes applied]
```

### Dependencies
Phase 4.9 (full feature set implemented).

### How to Test
```bash
python python/tests/bench_latency.py
python python/tests/bench_e2e.py
```

### Definition of Done
- `bench_latency.py` reports median ≤ 0.5 ms.
- `bench_e2e.py` reports per-decision overhead ≤ 0.5 ms.
- `docs/performance_results.md` is filled in with actual measured numbers.

---

## Phase Summary Table

| Sub-Phase | Key Deliverable | Test Command | New Files |
|---|---|---|---|
| 4.1 | Java stub compiles, plugs into PureEdgeSim | `mvn compile` | 3 Java |
| 4.2 | `JavaBridge` Unix socket I/O | `mvn test -Dtest=JavaBridgeTest` | 2 Java |
| 4.3 | `MessageBuilder`, `MessageParser`, wired orchestrator | `mvn test -Dtest=Message*` | 3 Java |
| 4.4 | Python `_bridge/` package | `pytest test_connection.py test_protocol.py` | 5 Python |
| 4.5 | All `types/` dataclasses + protocol factories | `pytest test_types.py` | 7 Python |
| 4.6 | `Orchestrator`, `RLOrchestrator`, rewards, features, examples | `pytest test_orchestrator.py` | 8 Python |
| 4.7 | First full simulation with Python orchestrator | Manual + `test_integration.py` | 2 Java + 1 Python |
| 4.8 | Error handling: invalid, crash, timeout | `pytest test_error_handling.py` | 1 Java + 1 Python |
| 4.9 | `features.py` complete, DQN skeleton e2e | `pytest test_features.py` + manual | 1 Python |
| 4.10 | Latency benchmark, e2e timing, written report | `bench_latency.py` + `bench_e2e.py` | 3 Python + 1 MD |
