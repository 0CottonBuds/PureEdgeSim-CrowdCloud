package examples;

import com.mechalikh.pureedgesim.simulationmanager.TraceSimulation;
import com.mechalikh.pureedgesim.simulationmanager.TraceSimulationThread;

/**
 * ExampleTraceReplay — M5 Integration Smoke Test
 * ================================================
 * Runs a complete PureEdgeSim simulation using the Google Cluster Trace v3
 * streaming generator with the bridge-test settings (10 edge devices, 900s).
 *
 * <p>Expected outcomes:
 * <ul>
 *   <li>Simulation completes without exception</li>
 *   <li>All 6,258 tasks from the 15-min trace are dispatched</li>
 *   <li>Results CSV written to PureEdgeSim/output/</li>
 *   <li>Console shows "Trace fully consumed" message</li>
 * </ul>
 *
 * <p>Run with:
 * <pre>
 *   mvn exec:exec -Dexec.mainClass=examples.ExampleTraceReplay
 * </pre>
 */
public class ExampleTraceReplay {

    public ExampleTraceReplay() {
        TraceSimulation sim = new TraceSimulation();

        // Use settings_trace_test: CLOUD_ONLY arch, 1000s simulation, batch_size=100.
        // This avoids the null-network-link NPE in settings_bridge_test/ALL.
        sim.setCustomSettingsFolder("PureEdgeSim/settings_trace_test/");

        // Configure the streaming generator before launch.
        TraceSimulationThread.TRACE_FILE_PATH = "data/processed/pureedgesim_tasks_15min.json";
        TraceSimulationThread.BUFFER_SIZE     = 1000;
        TraceSimulationThread.LOW_WATERMARK   = 200;

        // Register TraceSimulationManager — it wires refillIfNeeded() into NEXT_BATCH.
        sim.setCustomSimulationManager(
            com.mechalikh.pureedgesim.simulationmanager.TraceSimulationManager.class);

        // Register StreamedTraceTaskGenerator — TraceSimulationThread detects this class
        // and constructs it with the configured trace file path and buffer params.
        sim.setCustomTaskGenerator(
            com.mechalikh.pureedgesim.taskgenerator.StreamedTraceTaskGenerator.class);

        // Launch using TraceSimulationThread (via TraceSimulation.launchSimulation override).
        sim.launchSimulation();
    }

    public static void main(String[] args) {
        new ExampleTraceReplay();
    }
}
