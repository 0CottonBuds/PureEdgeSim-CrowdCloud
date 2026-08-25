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
 *     @author CottonBuds (Thesis extension for Google Cluster Trace v3 replay)
 **/
package com.mechalikh.pureedgesim.simulationmanager;

import java.lang.reflect.Constructor;

import com.mechalikh.pureedgesim.datacentersmanager.DataCentersManager;
import com.mechalikh.pureedgesim.network.NetworkModel;
import com.mechalikh.pureedgesim.simulationengine.FutureQueue;
import com.mechalikh.pureedgesim.taskgenerator.StreamedTraceTaskGenerator;
import com.mechalikh.pureedgesim.taskgenerator.Task;
import com.mechalikh.pureedgesim.taskgenerator.TaskGenerator;

/**
 * TraceSimulationThread — Milestone 5 (M5)
 * ==========================================
 * A thin override of {@link SimulationThread} that wires a
 * {@link StreamedTraceTaskGenerator} into a {@link TraceSimulationManager}
 * after both are constructed.
 *
 * <p><b>Why a custom SimulationThread?</b><br>
 * The standard {@link SimulationThread#loadModels(SimulationManager)} creates
 * the {@link TaskGenerator} and immediately discards its reference after calling
 * {@link TaskGenerator#generate()}. This class overrides {@code loadModels()} to
 * retain the generator reference and call
 * {@link TraceSimulationManager#setStreamedGenerator(StreamedTraceTaskGenerator)}
 * when both objects are available. No other behaviour changes.
 *
 * <p><b>Usage (in your main / thesis entry point):</b>
 * <pre>{@code
 * Simulation sim = new Simulation();
 *
 * // Point to bridge-test settings (10 edge devices, Python bridge)
 * sim.setCustomSettingsFolder("PureEdgeSim/settings_bridge_test");
 *
 * // Register custom classes
 * sim.setCustomTaskGenerator(StreamedTraceTaskGenerator.class);
 * sim.setCustomSimulationManager(TraceSimulationManager.class);
 * sim.setCustomEdgeOrchestrator(PythonOrchestrator.class);
 *
 * // Set trace file path and buffer parameters
 * TraceSimulationThread.TRACE_FILE_PATH = "data/processed/pureedgesim_tasks_15min.json";
 * TraceSimulationThread.BUFFER_SIZE     = 1000;
 * TraceSimulationThread.LOW_WATERMARK   = 200;
 *
 * // Launch simulation using the custom thread
 * sim.launchSimulation(TraceSimulationThread::new);
 * }</pre>
 *
 * @see StreamedTraceTaskGenerator
 * @see TraceSimulationManager
 * @see SimulationThread
 */
public class TraceSimulationThread extends SimulationThread {

    // -------------------------------------------------------------------------
    // Static configuration (set before launching the simulation)
    // -------------------------------------------------------------------------

    /**
     * Path to the JSON Lines task file produced by M4.
     * Default: {@code data/processed/pureedgesim_tasks_15min.json}
     */
    public static String TRACE_FILE_PATH = "data/processed/pureedgesim_tasks_15min.json";

    /**
     * Buffer target size N_buffer. The generator refills up to this count.
     * Default: 1000
     */
    public static int BUFFER_SIZE = 1000;

    /**
     * Low-watermark threshold N_low. Refill is triggered when buffer size
     * drops below this value. Default: 200
     */
    public static int LOW_WATERMARK = 200;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * Creates a TraceSimulationThread.
     *
     * @param simulation    PureEdgeSim simulation object
     * @param fromIteration first iteration index
     * @param step          iteration step for parallel distribution
     */
    public TraceSimulationThread(Simulation simulation, int fromIteration, int step) {
        super(simulation, fromIteration, step);
    }

    // -------------------------------------------------------------------------
    // loadModels override — streaming wiring
    // -------------------------------------------------------------------------

    /**
     * Overrides {@link SimulationThread#loadModels(SimulationManager)} to:
     * <ol>
     *   <li>Construct a {@link StreamedTraceTaskGenerator} configured with the
     *       static trace file path and buffer parameters.</li>
     *   <li>Call {@link StreamedTraceTaskGenerator#generate()} to load the
     *       initial buffer into {@code taskList}.</li>
     *   <li>Inject the generator into the {@link TraceSimulationManager} via
     *       {@link TraceSimulationManager#setStreamedGenerator}.</li>
     *   <li>Proceed with the rest of standard model initialization unchanged.</li>
     * </ol>
     *
     * <p>If the configured {@code simulationManager} class is NOT a
     * {@link TraceSimulationManager}, this method falls back to the standard
     * {@link SimulationThread#loadModels(SimulationManager)} behaviour.
     *
     * @param simulationManager the already-constructed simulation manager
     */
    @Override
    protected void loadModels(SimulationManager simulationManager) throws Exception {

        // ── Network model ─────────────────────────────────────────────────────
        SimLog.println(getClass().getSimpleName() + " - Initializing the Network Module...");
        Constructor<?> networkConstructor =
                simulation.networkModel.getConstructor(SimulationManager.class);
        networkConstructor.newInstance(simulationManager);

        // ── Data centers & computing nodes ────────────────────────────────────
        SimLog.println(getClass().getSimpleName() + " - Initializing the Datacenters Manager Module...");
        new DataCentersManager(simulationManager,
                simulation.mobilityModel,
                simulation.computingNode,
                simulation.computingNodesGenerator,
                simulation.topologyCreator);

        // ── Task generator: build StreamedTraceTaskGenerator ─────────────────
        SimLog.println(getClass().getSimpleName() + " - Initializing the Streaming Task Generator...");

        StreamedTraceTaskGenerator streamedGenerator;

        if (simulation.tasksGenerator.equals(StreamedTraceTaskGenerator.class)) {
            // Create and configure the streamed generator
            streamedGenerator = new StreamedTraceTaskGenerator(simulationManager)
                    .setTraceFilePath(TRACE_FILE_PATH)
                    .setBufferSize(BUFFER_SIZE)
                    .setLowWatermark(LOW_WATERMARK);

            FutureQueue<Task> taskList = streamedGenerator.generate();
            simulationManager.setTaskList(taskList);

            // Wire the generator into the TraceSimulationManager
            if (simulationManager instanceof TraceSimulationManager) {
                ((TraceSimulationManager) simulationManager).setStreamedGenerator(streamedGenerator);
                SimLog.println(getClass().getSimpleName() +
                        " - StreamedTraceTaskGenerator wired into TraceSimulationManager.");
                SimLog.println(getClass().getSimpleName() +
                        String.format(" - Trace: %s  buffer=%d  lowWatermark=%d",
                                TRACE_FILE_PATH, BUFFER_SIZE, LOW_WATERMARK));
            } else {
                SimLog.println("[WARN] " + getClass().getSimpleName() +
                        " - SimulationManager is not a TraceSimulationManager. " +
                        "Streaming refill will not be triggered. " +
                        "Use TraceSimulationManager or expect OOM on large traces.");
            }

        } else {
            // Fall back to standard task generator construction
            SimLog.println(getClass().getSimpleName() +
                    " - Using standard task generator: " + simulation.tasksGenerator.getSimpleName());
            Constructor<?> taskGenConstructor =
                    simulation.tasksGenerator.getConstructor(SimulationManager.class);
            TaskGenerator tasksGenerator =
                    (TaskGenerator) taskGenConstructor.newInstance(simulationManager);
            FutureQueue<Task> taskList = tasksGenerator.generate();
            simulationManager.setTaskList(taskList);
        }

        // ── Orchestrator ──────────────────────────────────────────────────────
        SimLog.println(getClass().getSimpleName() + " - Initializing the Task Orchestrator...");
        Constructor<?> orchestratorConstructor =
                simulation.orchestrator.getConstructor(SimulationManager.class);
        orchestratorConstructor.newInstance(simulationManager);

        SimLog.println(getClass().getSimpleName() + " - All modules were successfully launched...");
    }
}
