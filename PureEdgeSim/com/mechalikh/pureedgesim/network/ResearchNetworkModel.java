package com.mechalikh.pureedgesim.network;

import java.util.HashMap;
import java.util.Map;

import com.mechalikh.pureedgesim.simulationmanager.ResearchSimLog;
import com.mechalikh.pureedgesim.simulationmanager.SimLog;
import com.mechalikh.pureedgesim.simulationmanager.SimulationManager;
import com.mechalikh.pureedgesim.taskgenerator.Task;

/**
 * ResearchNetworkModel — Extends DefaultNetworkModel to measure container download (cold-start) latencies.
 */
public class ResearchNetworkModel extends DefaultNetworkModel {

    // Tracks in-progress container downloads: task -> start clock
    private final Map<Task, Double> containerStartTimes = new HashMap<>();

    public ResearchNetworkModel(SimulationManager simulationManager) {
        super(simulationManager);
    }

    @Override
    public void addContainer(Task task) {
        // Record start time BEFORE delegating to super (which schedules the transfer)
        if (simulation != null) {
            containerStartTimes.put(task, simulation.clock());
        }
        super.addContainer(task);
    }

    @Override
    protected void containerDownloadFinished(TransferProgress transfer) {
        Task task = transfer.getTask();
        Double startTime = containerStartTimes.remove(task);
        if (startTime != null && simulation != null) {
            double duration = simulation.clock() - startTime;
            SimLog logger = simulationManager.getSimulationLogger();
            if (logger instanceof ResearchSimLog) {
                ((ResearchSimLog) logger).recordContainerDownload(duration);
            }
        }
        super.containerDownloadFinished(transfer); // Schedules EXECUTE_TASK
    }
}
