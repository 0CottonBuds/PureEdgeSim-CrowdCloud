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

import com.mechalikh.pureedgesim.scenariomanager.Scenario;
import com.mechalikh.pureedgesim.scenariomanager.SimulationParameters;
import com.mechalikh.pureedgesim.simulationengine.Event;
import com.mechalikh.pureedgesim.simulationengine.PureEdgeSim;
import com.mechalikh.pureedgesim.taskgenerator.StreamedTraceTaskGenerator;

/**
 * TraceSimulationManager — Milestone 5 (M5)
 * ============================================
 * Extends {@link DefaultSimulationManager} to wire the streaming sliding-window
 * refill mechanism into the standard {@code NEXT_BATCH} event processing loop.
 *
 * <p><b>Design principle:</b> All simulation logic from
 * {@link DefaultSimulationManager} is preserved verbatim. Only the
 * {@code NEXT_BATCH} case is overridden to add a single call to
 * {@link StreamedTraceTaskGenerator#refillIfNeeded()} after the standard batch
 * scheduling loop completes.
 *
 * <p><b>Streaming lifecycle in NEXT_BATCH:</b>
 * <ol>
 *   <li>Dequeue and schedule up to {@code batchSize} tasks as {@code SEND_TO_ORCH}
 *       events (identical to {@link DefaultSimulationManager#processEvent}).</li>
 *   <li>Call {@code streamedGenerator.refillIfNeeded()}: if
 *       {@code taskList.size() < lowWatermark}, read the next chunk from disk
 *       until {@code taskList.size() == bufferSize}. This is O(chunk_size) I/O,
 *       amortized over many batch events.</li>
 *   <li>Schedule the next {@code NEXT_BATCH} event if {@code taskList} is
 *       non-empty (same as base class).</li>
 * </ol>
 *
 * <p><b>Zero-impact invariant:</b> If no {@link StreamedTraceTaskGenerator} is
 * set (i.e., {@link #setStreamedGenerator} is never called), this class behaves
 * identically to {@link DefaultSimulationManager}. It can safely replace it
 * for any simulation, traced or not.
 *
 * <p><b>Progress reporting:</b> When the trace is fully exhausted, a single
 * info log line is emitted via the {@link SimLog} to aid in result validation.
 *
 * @see StreamedTraceTaskGenerator
 * @see DefaultSimulationManager
 */
public class TraceSimulationManager extends DefaultSimulationManager {

    /**
     * The streaming task generator. May be {@code null} if a standard
     * (non-streaming) generator is used — the class degrades gracefully.
     */
    private StreamedTraceTaskGenerator streamedGenerator = null;

    /**
     * Flag to log the trace-exhaustion message only once.
     */
    private boolean traceExhaustedLogged = false;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * Creates a TraceSimulationManager.
     *
     * @param simLog       the simulation logger
     * @param pureEdgeSim  the discrete-event engine
     * @param simulationId the simulation ID (from scenario runner)
     * @param iteration    the iteration number within this simulation run
     * @param scenario     the scenario (algorithm + architecture + device count)
     */
    public TraceSimulationManager(SimLog simLog, PureEdgeSim pureEdgeSim,
                                   int simulationId, int iteration, Scenario scenario) {
        super(simLog, pureEdgeSim, simulationId, iteration, scenario);
    }

    // -------------------------------------------------------------------------
    // Streaming generator wiring
    // -------------------------------------------------------------------------

    /**
     * Wires the {@link StreamedTraceTaskGenerator} into this simulation manager.
     * Must be called before simulation start (i.e., before
     * {@link #startSimulation()}).
     *
     * <p>The generator's {@link StreamedTraceTaskGenerator#generate()} will
     * have already been called by {@link SimulationThread} at this point, so
     * the initial buffer is already populated in {@code taskList}.
     *
     * @param generator the initialized {@link StreamedTraceTaskGenerator}
     * @return this manager (builder pattern)
     */
    public TraceSimulationManager setStreamedGenerator(StreamedTraceTaskGenerator generator) {
        this.streamedGenerator = generator;
        return this;
    }

    // -------------------------------------------------------------------------
    // NEXT_BATCH override — streaming hook
    // -------------------------------------------------------------------------

    /**
     * Overrides {@link DefaultSimulationManager#processEvent(Event)} to inject
     * the streaming refill step into the {@code NEXT_BATCH} event handler.
     *
     * <p>All other event types (SEND_TO_ORCH, EXECUTE_TASK, etc.) are
     * delegated unchanged to the parent class.
     *
     * <p>The refill happens <em>after</em> the standard batch scheduling loop,
     * so newly loaded tasks are available for the immediately following
     * {@code NEXT_BATCH} schedule call. This ensures no gap in task delivery
     * to the orchestrator.
     *
     * @param ev the simulation event to process
     */
    @Override
    public void processEvent(Event ev) {
        // Only intercept NEXT_BATCH; delegate everything else to parent
        if (ev.getTag() != NEXT_BATCH || streamedGenerator == null) {
            super.processEvent(ev);
            return;
        }

        // ── Standard batch scheduling (from DefaultSimulationManager) ─────────
        for (int i = 0; i < Math.min(taskList.size(), SimulationParameters.batchSize); i++) {
            schedule(this, taskList.first().getTime() - simulation.clock(), SEND_TO_ORCH, taskList.first());
            taskList.remove(taskList.first());
        }

        // ── Streaming refill (M5 extension) ───────────────────────────────────
        if (!streamedGenerator.isTraceExhausted()) {
            int added = streamedGenerator.refillIfNeeded();
            if (added > 0) {
                simLog.deepLog(String.format(
                    "TraceSimulationManager - Refilled %d tasks from trace (buffer now=%d, total loaded=%d)",
                    added, taskList.size(), streamedGenerator.getTotalTasksLoaded()));
            }
        } else if (!traceExhaustedLogged) {
            traceExhaustedLogged = true;
            simLog.print(String.format(
                "TraceSimulationManager - Trace fully consumed. Total tasks loaded=%d, skipped=%d",
                streamedGenerator.getTotalTasksLoaded(),
                streamedGenerator.getTotalTasksSkipped()));
        }

        // ── Schedule next NEXT_BATCH (from DefaultSimulationManager) ─────────
        if (taskList.size() > 0) {
            schedule(this, taskList.first().getTime() - simulation.clock(), NEXT_BATCH);
        }
    }
}
