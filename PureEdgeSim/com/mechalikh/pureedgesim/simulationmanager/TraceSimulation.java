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

import java.util.Calendar;
import java.util.Date;

import com.mechalikh.pureedgesim.scenariomanager.SimulationParameters;

/**
 * TraceSimulation — Milestone 5 (M5)
 * =====================================
 * Entry point for trace-driven PureEdgeSim simulations.
 *
 * <p>Subclasses {@link Simulation} and overrides {@link #launchSimulation()}
 * to use {@link TraceSimulationThread} instead of the default
 * {@link SimulationThread}. This is the only change — all other behaviour
 * (file checking, scenario loading, chart generation) is inherited unchanged.
 *
 * <p><b>Usage (thesis entry point / main class):</b>
 * <pre>{@code
 * TraceSimulation sim = new TraceSimulation();
 *
 * // Point to the bridge-test settings (10 edge devices, 900s simulation)
 * sim.setCustomSettingsFolder("PureEdgeSim/settings_bridge_test/");
 *
 * // Register custom classes (TraceSimulationManager wires the generator)
 * sim.setCustomSimulationManager(TraceSimulationManager.class);
 * sim.setCustomEdgeOrchestrator(PythonOrchestrator.class);
 *
 * // Configure the streaming generator (before launch)
 * TraceSimulationThread.TRACE_FILE_PATH = "data/processed/pureedgesim_tasks_15min.json";
 * TraceSimulationThread.BUFFER_SIZE     = 1000;
 * TraceSimulationThread.LOW_WATERMARK   = 200;
 *
 * // Launch — this uses TraceSimulationThread internally
 * sim.launchSimulation();
 * }</pre>
 *
 * @see TraceSimulationThread
 * @see TraceSimulationManager
 * @see StreamedTraceTaskGenerator
 */
public class TraceSimulation extends Simulation {

    /**
     * Override to use {@link TraceSimulationThread} instead of the default
     * {@link SimulationThread}.
     *
     * <p>The sequential (non-parallel) path is the only path supported for
     * trace-driven simulations — streaming I/O and the Unix domain socket to
     * Python are not thread-safe across parallel runs.
     */
    @Override
    public void launchSimulation() {
        SimLog.println("%s - Loading simulation files...", getClass().getSimpleName());

        if (!checkFiles()) return;

        Date startTime = Calendar.getInstance().getTime();

        loadScenarios();

        // Use TraceSimulationThread instead of the default SimulationThread
        new TraceSimulationThread(this, 0, 1).startSimulation();

        Date finishTime = Calendar.getInstance().getTime();
        SimLog.println("%s - Simulation took : %s",
                getClass().getSimpleName(), simulatioDuration(startTime, finishTime));
        SimLog.println("%s - results were saved to the folder: %s",
                getClass().getSimpleName(), SimulationParameters.outputFolder);
    }
}
