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
 *
 *     @author CottonBuds — Milestone 6 (Google Cluster Trace × Python RL Bridge)
 **/
package examples;

import com.mechalikh.pureedgesim.python.PythonOrchestrator;
import com.mechalikh.pureedgesim.simulationmanager.TraceSimulation;
import com.mechalikh.pureedgesim.simulationmanager.TraceSimulationManager;
import com.mechalikh.pureedgesim.simulationmanager.TraceSimulationThread;
import com.mechalikh.pureedgesim.taskgenerator.StreamedTraceTaskGenerator;

/**
 * ExampleTraceE2E — Milestone 6 End-to-End Entry Point
 * =======================================================
 * Runs a full Google Cluster Trace v3 replay with the Python RL orchestrator.
 *
 * <p>Component stack:
 * <ul>
 *   <li>{@link TraceSimulation}          — overrides launchSimulation() to use TraceSimulationThread</li>
 *   <li>{@link TraceSimulationThread}    — wires StreamedTraceTaskGenerator into TraceSimulationManager</li>
 *   <li>{@link StreamedTraceTaskGenerator} — streams 6,258 tasks from 15-min JSONL trace</li>
 *   <li>{@link TraceSimulationManager}  — NEXT_BATCH hook calls refillIfNeeded()</li>
 *   <li>{@link PythonOrchestrator}       — delegates every placement decision to Python via UDS</li>
 * </ul>
 *
 * <p>Expected outcomes:
 * <ul>
 *   <li>Python receives 6,258 {@code DECISION_REQUEST} messages</li>
 *   <li>Python receives 6,258 {@code TASK_RESULT} feedback messages</li>
 *   <li>Zero IPC socket timeouts or handshake failures</li>
 *   <li>Episode summary printed by Python orchestrator at shutdown</li>
 *   <li>CSV results written to {@code PureEdgeSim/output/}</li>
 * </ul>
 *
 * <p>Run with:
 * <pre>
 *   # Default (TraceRoundRobinOrchestrator):
 *   mvn exec:exec -Dexec.mainClass=examples.ExampleTraceE2E
 *
 *   # Override orchestrator:
 *   mvn exec:exec -Dexec.mainClass=examples.ExampleTraceE2E \
 *       -Dexec.args="examples.run_trace_nearest_cloud.TraceNearestCloudOrchestrator"
 *
 *   # Or use the run_m6_replay.sh launcher for logged runs:
 *   bash scripts/run_m6_replay.sh
 * </pre>
 *
 * @see TraceSimulation
 * @see StreamedTraceTaskGenerator
 * @see TraceSimulationManager
 * @see PythonOrchestrator
 */
public class ExampleTraceE2E {

    /**
     * Default Python orchestrator class for M6.
     * Priority-aware round-robin that understands Google Cluster scheduling classes.
     */
    private static final String DEFAULT_ORCHESTRATOR =
            "examples.run_trace_round_robin.TraceRoundRobinOrchestrator";

    public ExampleTraceE2E(String pythonOrchestratorClass) {
        // ── Python orchestrator class ─────────────────────────────────────────
        String orchClass = (pythonOrchestratorClass != null && !pythonOrchestratorClass.isBlank())
                ? pythonOrchestratorClass
                : DEFAULT_ORCHESTRATOR;
        PythonOrchestrator.setOrchestratorClass(orchClass);
        System.out.printf("[ExampleTraceE2E] Python orchestrator: %s%n", orchClass);

        // ── Streaming generator parameters ────────────────────────────────────
        String traceFile = System.getProperty("trace.file.path", "data/processed/pureedgesim_tasks_15min.json");
        int bufferSize = Integer.getInteger("trace.buffer.size", 1000);
        int lowWatermark = Integer.getInteger("trace.low.watermark", 200);

        TraceSimulationThread.TRACE_FILE_PATH = traceFile;
        TraceSimulationThread.BUFFER_SIZE     = bufferSize;
        TraceSimulationThread.LOW_WATERMARK   = lowWatermark;
        System.out.printf("[ExampleTraceE2E] Trace File: %s | Buffer: %d | LowWatermark: %d%n",
                traceFile, bufferSize, lowWatermark);

        // ── Simulation object ─────────────────────────────────────────────────
        TraceSimulation sim = new TraceSimulation();

        // Settings: CLOUD_ONLY arch, 1000s simulation, batch_size=100, 10 edge devices.
        sim.setCustomSettingsFolder("PureEdgeSim/settings_trace_test/");

        // Custom components
        sim.setCustomSimulationManager(TraceSimulationManager.class);
        sim.setCustomTaskGenerator(StreamedTraceTaskGenerator.class);
        sim.setCustomEdgeOrchestrator(PythonOrchestrator.class);

        // ── Launch ────────────────────────────────────────────────────────────
        sim.launchSimulation();
    }

    public static void main(String[] args) {
        String orch = (args != null && args.length > 0) ? args[0] : null;
        new ExampleTraceE2E(orch);
    }
}
