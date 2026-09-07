package com.mechalikh.pureedgesim.simulationmanager;

import java.io.File;
import java.util.List;
import java.util.TreeMap;

import com.mechalikh.pureedgesim.scenariomanager.SimulationParameters;
import com.mechalikh.pureedgesim.taskgenerator.Task;

/**
 * ResearchSimLog — Drop-in subclass of SimLog for research metrics extension.
 *
 * Preserves all standard SimLog outputs while computing and exporting
 * expanded metrics (throughput, breakdown latencies, fine-grained failure categories,
 * and time-series snapshots).
 */
public class ResearchSimLog extends SimLog {

    public static final double DEFAULT_WINDOW_SECONDS = 60.0;

    private final double timeWindowSeconds;

    // Time-series buckets
    private final TreeMap<Integer, Integer> throughputBuckets = new TreeMap<>();
    private final TreeMap<Integer, Integer> completionBuckets = new TreeMap<>();
    private final TreeMap<Integer, Integer> failureBuckets = new TreeMap<>();
    private final TreeMap<Integer, Double> waitSumBuckets = new TreeMap<>();
    private final TreeMap<Integer, Integer> waitCountBuckets = new TreeMap<>();

    // Latency accumulation fields
    private double totalNetworkTime = 0.0;
    private double totalEndToEndDelay = 0.0;
    private double totalContainerDownloadTime = 0.0;
    private int containerTasksCount = 0;

    // Computed summary metrics
    private double throughputPerMinute = 0.0;
    private double avgQueueWaitingTime = 0.0;
    private double avgNetworkLatency = 0.0;
    private double avgCpuTime = 0.0;
    private double avgTotalLatency = 0.0;
    private double avgColdStartTime = 0.0;
    private double failureRatePct = 0.0;

    // Failure categories (counts and percentages)
    private int tasksFailedOOM = 0;
    private int tasksFailedNoDestination = 0;
    private double deadlinePct = 0.0;
    private double batteryPct = 0.0;
    private double oomPct = 0.0;
    private double networkPct = 0.0;
    private double mobilityPct = 0.0;

    public ResearchSimLog(String startTime, boolean isFirstIteration, double timeWindowSeconds) {
        super(startTime, isFirstIteration);
        this.timeWindowSeconds = timeWindowSeconds;
    }

    public ResearchSimLog(String startTime, boolean isFirstIteration) {
        this(startTime, isFirstIteration, DEFAULT_WINDOW_SECONDS);
    }

    @Override
    public void incrementTasksFailed(Task task) {
        super.incrementTasksFailed(task);
        double clock = (simulationManager != null && simulationManager.getSimulation() != null)
                ? simulationManager.getSimulation().clock()
                : 0.0;
        int bucket = (int) (clock / timeWindowSeconds);
        failureBuckets.merge(bucket, 1, Integer::sum);
        completionBuckets.merge(bucket, 1, Integer::sum);
    }

    @Override
    public void incrementTasksFailedLackOfRessources(Task task) {
        Task.FailureReason reason = task != null ? task.getFailureReason() : null;
        if (reason == Task.FailureReason.INSUFFICIENT_RESOURCES) {
            tasksFailedOOM++;
        } else {
            // NO_OFFLOADING_DESTINATIONS or other lack of reachable resources
            tasksFailedNoDestination++;
        }
        super.incrementTasksFailedLackOfRessources(task);
    }

    @Override
    public String getFileName(String extension) {
        if (extension.startsWith("_") || extension.startsWith("/_")) {
            String dir = SimulationParameters.outputFolder + "/" + simStartTime;
            new File(dir).mkdirs();
            String prefix = extension.startsWith("/") ? extension : "/" + extension;
            return dir + prefix;
        }
        return super.getFileName(extension);
    }

    @Override
    public void getTasksExecutionInfos(Task task) {
        super.getTasksExecutionInfos(task); // Existing accumulation unchanged
        this.totalNetworkTime += task.getActualNetworkTime();
        this.totalEndToEndDelay += task.getTotalDelay();
    }

    public void recordContainerDownload(double duration) {
        this.totalContainerDownloadTime += duration;
        this.containerTasksCount++;
    }

    @Override
    public void onTaskCompleted(Task task, double clock) {
        super.onTaskCompleted(task, clock);
        int bucket = (int) (clock / timeWindowSeconds);
        throughputBuckets.merge(bucket, 1, Integer::sum);
        completionBuckets.merge(bucket, 1, Integer::sum);
        double wait = task != null ? task.getWatingTime() : 0.0;
        waitSumBuckets.merge(bucket, wait, Double::sum);
        waitCountBuckets.merge(bucket, 1, Integer::sum);
    }

    @Override
    public void showIterationResults(List<Task> finishedTasks) {
        super.showIterationResults(finishedTasks); // Preserves ALL existing output unchanged
        computeSummaryMetrics();
        ResearchMetricsExporter.writeSummaryRow(this);
        ResearchMetricsExporter.writeTimeSeriesRows(this);
    }

    public void computeSummaryMetrics() {
        double durationSeconds = SimulationParameters.simulationDuration;
        if (durationSeconds <= 0 && simulationManager != null && simulationManager.getSimulation() != null) {
            durationSeconds = simulationManager.getSimulation().clock();
        }
        double durationMinutes = (durationSeconds > 0) ? durationSeconds / 60.0 : 1.0;
        int succeeded = tasksSent - tasksFailed;
        this.throughputPerMinute = succeeded / durationMinutes;

        int n = executedTasksCount;
        this.avgQueueWaitingTime = (n > 0) ? totalWaitingTime / n : 0.0;
        this.avgNetworkLatency = (n > 0) ? totalNetworkTime / n : 0.0;
        this.avgCpuTime = (n > 0) ? totalExecutionTime / n : 0.0;
        this.avgTotalLatency = (n > 0) ? totalEndToEndDelay / n : 0.0;
        this.avgColdStartTime = (containerTasksCount > 0)
            ? totalContainerDownloadTime / containerTasksCount : 0.0;

        int sent = tasksSent;
        this.failureRatePct = (sent > 0)
            ? ((double) tasksFailed * 100.0 / sent)
            : 0.0;

        // Invariant 1: split must equal merged
        assert tasksFailedOOM + tasksFailedNoDestination == tasksFailedRessourcesUnavailable
            : "Split failure counts don't sum to merged counter";

        // Invariant 2: all categories sum to total
        int sumCategories = tasksFailedLatency
            + tasksFailedBeacauseDeviceDead
            + tasksFailedMobility
            + tasksFailedOOM
            + tasksFailedNoDestination;
        assert sumCategories == tasksFailed
            : "Category sum " + sumCategories + " != tasksFailed " + tasksFailed;

        int failed = tasksFailed;
        double inv = (failed > 0) ? 100.0 / failed : 0.0;
        this.deadlinePct = tasksFailedLatency * inv;
        this.batteryPct  = tasksFailedBeacauseDeviceDead * inv;
        this.mobilityPct = tasksFailedMobility * inv;
        this.oomPct      = tasksFailedOOM * inv;
        this.networkPct  = tasksFailedNoDestination * inv;
    }

    // Getters for ResearchMetricsExporter and testing
    public double getThroughputPerMinute() {
        return throughputPerMinute;
    }

    public double getFailureRatePct() {
        return failureRatePct;
    }

    public int getTasksFailedDeadline() {
        return tasksFailedLatency;
    }

    public int getTasksFailedBattery() {
        return tasksFailedBeacauseDeviceDead;
    }

    public int getTasksFailedMobility() {
        return tasksFailedMobility;
    }

    public int getTasksFailedOOM() {
        return tasksFailedOOM;
    }

    public int getTasksFailedNetwork() {
        return tasksFailedNoDestination;
    }

    public int getTasksFailedRessourcesUnavailable() {
        return tasksFailedRessourcesUnavailable;
    }

    public double getDeadlinePct() {
        return deadlinePct;
    }

    public double getBatteryPct() {
        return batteryPct;
    }

    public double getOomPct() {
        return oomPct;
    }

    public double getNetworkPct() {
        return networkPct;
    }

    public double getMobilityPct() {
        return mobilityPct;
    }

    public double getAvgQueueWaitingTime() {
        return avgQueueWaitingTime;
    }

    public double getAvgNetworkLatency() {
        return avgNetworkLatency;
    }

    public double getAvgCpuTime() {
        return avgCpuTime;
    }

    public double getAvgTotalLatency() {
        return avgTotalLatency;
    }

    public double getAvgColdStartTime() {
        return avgColdStartTime;
    }

    public double getTotalWaitingTime() {
        return totalWaitingTime;
    }

    public double getTotalNetworkTime() {
        return totalNetworkTime;
    }

    public double getTotalEndToEndDelay() {
        return totalEndToEndDelay;
    }

    public double getTotalExecutionTime() {
        return totalExecutionTime;
    }

    public int getContainerTasksCount() {
        return containerTasksCount;
    }

    public int getExecutedTasksCount() {
        return executedTasksCount;
    }

    public double getTimeWindowSeconds() {
        return timeWindowSeconds;
    }

    public String getOrchArchitecture() {
        return currentOrchArchitecture;
    }

    public String getOrchAlgorithm() {
        return currentOrchAlgorithm;
    }

    public int getDevicesCount() {
        return currentEdgeDevicesCount;
    }

    @Override
    public int getTasksSent() {
        return tasksSent;
    }

    @Override
    public int getTasksFailed() {
        return tasksFailed;
    }

    @Override
    public int getTasksSucceeded() {
        return tasksSent - tasksFailed;
    }

    public TreeMap<Integer, Integer> getThroughputBuckets() {
        return throughputBuckets;
    }

    public TreeMap<Integer, Integer> getCompletionBuckets() {
        return completionBuckets;
    }

    public TreeMap<Integer, Integer> getFailureBuckets() {
        return failureBuckets;
    }

    public TreeMap<Integer, Double> getWaitSumBuckets() {
        return waitSumBuckets;
    }

    public TreeMap<Integer, Integer> getWaitCountBuckets() {
        return waitCountBuckets;
    }

    public SimulationManager getSimulationManager() {
        return simulationManager;
    }
}
