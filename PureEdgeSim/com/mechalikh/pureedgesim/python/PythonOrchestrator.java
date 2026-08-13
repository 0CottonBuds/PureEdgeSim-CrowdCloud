/**
 *     PureEdgeSim:  A Simulation Framework for Performance Evaluation of Cloud, Edge and Mist Computing Environments
 *
 *     This file is part of PureEdgeSim Project.
 *
 *     PureEdgeSim is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     PureEdgeSim is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with PureEdgeSim. If not, see <http://www.gnu.org/licenses/>.
 **/
package com.mechalikh.pureedgesim.python;

import com.mechalikh.pureedgesim.datacentersmanager.ComputingNode;
import com.mechalikh.pureedgesim.simulationengine.Event;
import com.mechalikh.pureedgesim.simulationmanager.SimulationManager;
import com.mechalikh.pureedgesim.taskgenerator.Task;
import com.mechalikh.pureedgesim.taskorchestrator.Orchestrator;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Custom PureEdgeSim {@link Orchestrator} that delegates offloading placement decisions
 * to an external Python process connected over Unix Domain Sockets.
 *
 * <h2>Architecture &amp; Lifecycle</h2>
 * <ol>
 *   <li><b>Process Launch &amp; Handshake:</b> Upon instantiation, computes a unique socket path
 *       (e.g., {@code /tmp/pureedgesim_orch_<pid>_<episodeId>.sock}), launches Python via {@link ProcessBuilder},
 *       and completes the 3-step handshake ({@code READY} &rarr; {@code EPISODE_INIT} &rarr; {@code READY_ACK}).</li>
 *   <li><b>Decision Request / Response:</b> In {@link #findComputingNode(String[], Task)}, formats a synchronous
 *       {@code DECISION_REQUEST} message via {@link MessageBuilder}, transmits it over {@link JavaBridge}, and waits
 *       for a {@code DECISION_RESPONSE}. Returns the target {@code node_index} to PureEdgeSim.</li>
 *   <li><b>Asynchronous Result Feedback:</b> In {@link #resultsReturned(Task)}, sends a fire-and-forget
 *       {@code TASK_RESULT} message to Python with execution delays and failure statuses.</li>
 *   <li><b>Episode Teardown:</b> In {@link #onSimulationEnd()}, sends {@code EPISODE_END}, waits for {@code SHUTDOWN_ACK},
 *       and terminates the Python child process cleanly.</li>
 * </ol>
 *
 * <h2>Configuring the Python Algorithm</h2>
 * <p>To specify which Python orchestrator class to execute, call {@link #setOrchestratorClass(String)}
 * prior to launching the simulation, e.g.:</p>
 * <pre>{@code
 *   PythonOrchestrator.setOrchestratorClass("examples.run_round_robin.RoundRobinOrchestrator");
 *   sim.setCustomEdgeOrchestrator(PythonOrchestrator.class);
 *   sim.launchSimulation();
 * }</pre>
 *
 * @author Python Bridge — Phase 4.3
 * @see JavaBridge
 * @see MessageBuilder
 * @see MessageParser
 */
public class PythonOrchestrator extends Orchestrator {

    /** Fully-qualified Python module and class name to instantiate as the orchestrator. */
    private static volatile String pythonOrchestratorClass =
            "examples.run_round_robin.RoundRobinOrchestrator";

    /** Global atomic counter ensuring unique episode IDs per instance. */
    private static final AtomicInteger instanceCounter = new AtomicInteger(0);

    /** Thread-safe set tracking active socket paths to prevent concurrent socket collisions. */
    private static final Set<String> activeSockets = Collections.synchronizedSet(new HashSet<>());

    /** Maximum lookahead window size for pending tasks sent in decision requests. */
    private static final int LOOK_AHEAD_WINDOW_SIZE = 20;

    /** Low-level socket I/O bridge connection to Python. */
    private JavaBridge bridge;

    /** Handle to the launched Python subprocess. */
    private Process pythonProcess;

    /** Path to the Unix domain socket file created for this episode. */
    private String socketPath;

    /** Sequence counter for decision requests. */
    private int requestIdCounter = 0;

    /** Unique simulation episode ID. */
    private int episodeId;

    /** Maps task ID to request ID for correlation when reporting task results. */
    private final Map<Integer, Integer> taskIdToRequestId = new HashMap<>();

    /** Maps task ID to chosen node index for task result reporting. */
    private final Map<Integer, Integer> taskIdToNodeIndex = new HashMap<>();

    /**
     * Sets the fully-qualified Python orchestrator class name to be loaded by the Python server.
     *
     * @param dotted fully-qualified module.ClassName string (e.g. {@code "examples.run_dqn.DQNOrchestrator"})
     */
    public static void setOrchestratorClass(String dotted) {
        pythonOrchestratorClass = dotted;
    }

    /**
     * Constructs a new PythonOrchestrator, launching the Python subprocess and performing handshake.
     *
     * @param simulationManager the active simulation manager instance
     */
    public PythonOrchestrator(SimulationManager simulationManager) {
        super(simulationManager);
        initBridge();
    }

    /**
     * Package-private constructor for unit testing with a pre-configured or mocked {@link JavaBridge}.
     *
     * @param simulationManager active simulation manager
     * @param bridge pre-initialized or mocked JavaBridge instance
     */
    PythonOrchestrator(SimulationManager simulationManager, JavaBridge bridge) {
        super(simulationManager);
        this.bridge = bridge;
        this.socketPath = null;
        this.pythonProcess = null;
    }

    /**
     * Computes socket path, spawns the Python subprocess, connects {@link JavaBridge}, and executes the initial handshake.
     */
    private void initBridge() {
        int pid = getPid();
        this.episodeId = instanceCounter.incrementAndGet();
        this.socketPath = "/tmp/pureedgesim_orch_" + pid + "_" + episodeId + ".sock";

        if (!activeSockets.add(socketPath)) {
            throw new IllegalStateException("Socket path already in active set: " + socketPath);
        }

        File venvPy = new File("python/.venv/bin/python");
        String pythonExec = venvPy.exists() ? venvPy.getAbsolutePath() : "python3";

        try {
            ProcessBuilder pb = new ProcessBuilder(
                    pythonExec, "-m", "pureedgesim._bridge.server",
                    "--socket", socketPath,
                    "--orchestrator", pythonOrchestratorClass
            );
            File pyDir = new File("python");
            if (pyDir.exists()) {
                pb.environment().put("PYTHONPATH", pyDir.getAbsolutePath());
            }
            pb.inheritIO();
            this.pythonProcess = pb.start();
        } catch (IOException e) {
            activeSockets.remove(socketPath);
            simLog.deepLog("Failed to launch Python bridge process: " + e.getMessage());
            return;
        }

        try {
            this.bridge = new JavaBridge(socketPath, 10_000);

            // Handshake step 1: receive READY
            String readyMsg = bridge.recv();
            if (!"READY".equals(MessageParser.getType(readyMsg))) {
                throw new IllegalStateException("Expected READY message from Python, got: " + readyMsg);
            }

            // Handshake step 2: send EPISODE_INIT
            bridge.send(MessageBuilder.buildEpisodeInit(episodeId, simulationManager, nodeList));

            // Handshake step 3: receive READY_ACK
            String ackMsg = bridge.recv();
            if (!"READY".equalsIgnoreCase(MessageParser.getStatus(ackMsg))) {
                throw new IllegalStateException("Expected READY status in READY_ACK, got: " + ackMsg);
            }
        } catch (Exception e) {
            simLog.deepLog("PythonOrchestrator bridge init failed: " + e.getMessage());
            closeBridge();
        }
    }

    /**
     * Determines placement destination for an incoming task by requesting a decision from Python.
     *
     * @param architectureLayers target architecture layers (e.g. Edge, Cloud)
     * @param task the incoming task to place
     * @return the selected node index into {@link #nodeList}, or {@code -1} if placement fails or error occurs
     */
    @Override
    protected int findComputingNode(String[] architectureLayers, Task task) {
        if (bridge == null || task == null) {
            return -1;
        }

        int reqId = requestIdCounter++;
        taskIdToRequestId.put(task.getId(), reqId);

        try {
            String reqJson = MessageBuilder.buildDecisionRequest(
                    reqId, task, simulationManager, nodeList, LOOK_AHEAD_WINDOW_SIZE);

            bridge.send(reqJson);

            String respJson = bridge.recv();

            int chosenIndex = MessageParser.getNodeIndex(respJson);

            if (nodeList != null && chosenIndex >= 0 && chosenIndex < nodeList.size()) {
                taskIdToNodeIndex.put(task.getId(), chosenIndex);
                return chosenIndex;
            } else if (nodeList == null && chosenIndex >= 0) {
                taskIdToNodeIndex.put(task.getId(), chosenIndex);
                return chosenIndex;
            }

            taskIdToNodeIndex.put(task.getId(), -1);
            return -1;
        } catch (BridgeCrashException e) {
            simLog.deepLog("Python bridge process crashed during decision request: " + e.getMessage());
            return -1;
        } catch (SocketTimeoutException e) {
            simLog.deepLog("Python bridge timed out waiting for decision response for task " + task.getId());
            return -1;
        } catch (IOException e) {
            simLog.deepLog("I/O error communicating with Python bridge: " + e.getMessage());
            return -1;
        }
    }

    /**
     * Transmits task execution outcome (completion/failure metrics) back to Python asynchronously.
     *
     * @param task the task whose results are being reported
     */
    @Override
    public void resultsReturned(Task task) {
        if (bridge == null || task == null) {
            return;
        }

        int reqId = taskIdToRequestId.getOrDefault(task.getId(), -1);
        int nodeIndex = taskIdToNodeIndex.getOrDefault(task.getId(), -1);
        taskIdToRequestId.remove(task.getId());
        taskIdToNodeIndex.remove(task.getId());

        try {
            String resultJson = MessageBuilder.buildTaskResult(reqId, task, nodeIndex);
            bridge.send(resultJson);
            // Fire-and-forget message, no response expected
        } catch (IOException e) {
            simLog.deepLog("Failed to send TASK_RESULT to Python bridge: " + e.getMessage());
        }
    }

    /**
     * Lifecycle callback invoked at simulation conclusion to perform clean shutdown of socket and Python process.
     */
    @Override
    public void processEvent(Event event) {
        // No custom simulation events processed by PythonOrchestrator
    }

    public void onSimulationEnd() {
        if (bridge != null) {
            try {
                bridge.send(MessageBuilder.buildEpisodeEnd(episodeId, simulationManager));
                bridge.recv(); // Wait for SHUTDOWN_ACK
            } catch (Exception e) {
                simLog.deepLog("Error sending EPISODE_END: " + e.getMessage());
            }
        }
        closeBridge();
    }

    /**
     * Closes socket bridge, destroys Python subprocess if running, and cleans up active socket registry.
     */
    private synchronized void closeBridge() {
        if (bridge != null) {
            bridge.close();
            bridge = null;
        }
        if (pythonProcess != null) {
            pythonProcess.destroy();
            pythonProcess = null;
        }
        if (socketPath != null) {
            activeSockets.remove(socketPath);
            socketPath = null;
        }
    }

    /**
     * Helper to obtain JVM process PID in Java 8 compatible manner.
     *
     * @return current process PID integer
     */
    private static int getPid() {
        try {
            String processName = ManagementFactory.getRuntimeMXBean().getName();
            return Integer.parseInt(processName.split("@")[0]);
        } catch (Exception e) {
            return (int) (System.currentTimeMillis() % 10000);
        }
    }
}
