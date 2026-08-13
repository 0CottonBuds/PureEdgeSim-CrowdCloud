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
 * End-to-end integration test example for Phase 4.7.
 *
 * <p>Runs a full simulation using {@link PythonOrchestrator} with the Python-side
 * {@code RoundRobinOrchestrator}. Tasks should successfully offload and execute.</p>
 *
 * @author Python Bridge — Phase 4.7
 */
public class ExamplePythonBridgeE2E {

    public ExamplePythonBridgeE2E(String orchestratorClass) {
        if (orchestratorClass != null && !orchestratorClass.trim().isEmpty()) {
            PythonOrchestrator.setOrchestratorClass(orchestratorClass);
        } else {
            PythonOrchestrator.setOrchestratorClass(
                    "examples.run_round_robin.RoundRobinOrchestrator"
            );
        }

        Simulation sim = new Simulation();
        sim.setCustomSettingsFolder("PureEdgeSim/settings_bridge_test/");
        sim.setCustomEdgeOrchestrator(PythonOrchestrator.class);
        sim.launchSimulation();
    }

    public static void main(String[] args) {
        String orch = (args != null && args.length > 0) ? args[0] : null;
        new ExamplePythonBridgeE2E(orch);
    }
}
