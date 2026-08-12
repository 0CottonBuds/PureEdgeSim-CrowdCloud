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
package examples;

import com.mechalikh.pureedgesim.python.PythonOrchestrator;
import com.mechalikh.pureedgesim.simulationmanager.Simulation;

/**
 * Phase 4.1 smoke-test example.
 *
 * <p>Runs a standard PureEdgeSim simulation using {@link PythonOrchestrator}
 * as the orchestrator. In Phase 4.1 the orchestrator is a stub that always
 * returns {@code -1}, so every task fails with
 * {@code NO_OFFLOADING_DESTINATIONS}. This is the expected result — it
 * confirms that the new class compiles and integrates with PureEdgeSim's
 * reflection-based injection without any exception.</p>
 *
 * <p>Expected simulation outcome at Phase 4.1:
 * <ul>
 *   <li>Task success rate: 0%</li>
 *   <li>Failure reason for all tasks: {@code NO_OFFLOADING_DESTINATIONS}</li>
 *   <li>No {@link RuntimeException} thrown</li>
 * </ul>
 * </p>
 *
 * <p>Run with:
 * <pre>
 *   mvn exec:exec -Dexec.mainClass=examples.ExamplePythonBridge
 * </pre>
 * (Uses exec:exec to spawn a child JVM with -Xmx2g. Do NOT use exec:java —
 * it runs in-process and will OOM on the default Maven heap.)</p>
 *
 * @author Python Bridge — Phase 4.1
 */
public class ExamplePythonBridge {

    public ExamplePythonBridge() {
        Simulation sim = new Simulation();

        // Use a lightweight settings folder: 10 devices, 200s simulation time.
        // The default settings (200 devices × 3200s) OOM on MobilityModel.generatePath().
        // For full-scale runs use the default settings folder (or increase -Xmx).
        sim.setCustomSettingsFolder("PureEdgeSim/settings_bridge_test/");

        // Inject our Python-delegating orchestrator.
        // In Phase 4.1 this is a stub: all tasks will fail gracefully.
        sim.setCustomEdgeOrchestrator(PythonOrchestrator.class);

        // Uses the lightweight settings folder configured above.
        sim.launchSimulation();
    }

    public static void main(String[] args) {
        new ExamplePythonBridge();
    }
}
