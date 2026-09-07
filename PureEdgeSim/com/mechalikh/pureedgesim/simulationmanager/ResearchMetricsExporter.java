package com.mechalikh.pureedgesim.simulationmanager;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Locale;

import com.mechalikh.pureedgesim.scenariomanager.SimulationParameters;

/**
 * ResearchMetricsExporter — Writes research-specific summary and time-series CSVs.
 */
public class ResearchMetricsExporter {

    public static final String SUMMARY_HEADER =
        "Orchestration architecture,Orchestration algorithm,Edge devices count," +
        "Throughput (tasks/min),Average queue waiting time (s)," +
        "Average total latency (s),Average latency: network (s),Average latency: queue waiting (s),Average latency: computation (s),Average latency: cold start (s)," +
        "Task failure rate (%)," +
        "Failures: Deadline,Failures: Battery,Failures: OOM,Failures: Network,Failures: Mobility," +
        "Failures: Deadline (%),Failures: Battery (%),Failures: OOM (%),Failures: Network (%),Failures: Mobility (%)";

    public static final String TIME_SERIES_HEADER =
        "Orchestration architecture,Orchestration algorithm,Edge devices count," +
        "Window index,Window start (s),Window end (s)," +
        "Throughput in window (tasks/min),Completions in window,Failures in window," +
        "Average queue waiting in window (s)";

    public static void writeSummaryRow(ResearchSimLog log) {
        String fileName = log.getFileName("_research_summary.csv");
        boolean writeHeader = !new File(fileName).exists();
        try (PrintWriter pw = new PrintWriter(new FileWriter(fileName, true))) {
            if (writeHeader) {
                pw.println(SUMMARY_HEADER);
            }
            pw.printf(Locale.US, "%s,%s,%d,%.4f,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%.4f,%d,%d,%d,%d,%d,%.2f,%.2f,%.2f,%.2f,%.2f%n",
                log.getOrchArchitecture(),
                log.getOrchAlgorithm(),
                log.getDevicesCount(),
                log.getThroughputPerMinute(),
                log.getAvgQueueWaitingTime(),
                log.getAvgTotalLatency(),
                log.getAvgNetworkLatency(),
                log.getAvgQueueWaitingTime(),
                log.getAvgCpuTime(),
                log.getAvgColdStartTime(),
                log.getFailureRatePct(),
                log.getTasksFailedDeadline(),
                log.getTasksFailedBattery(),
                log.getTasksFailedOOM(),
                log.getTasksFailedNetwork(),
                log.getTasksFailedMobility(),
                log.getDeadlinePct(),
                log.getBatteryPct(),
                log.getOomPct(),
                log.getNetworkPct(),
                log.getMobilityPct());
        } catch (IOException e) {
            SimLog.println("ResearchMetricsExporter - Failed to write summary: " + e.getMessage());
        }
    }

    public static void writeTimeSeriesRows(ResearchSimLog log) {
        String fileName = log.getFileName("_research_timeseries.csv");
        boolean writeHeader = !new File(fileName).exists();
        int maxBucket = 0;
        if (!log.getThroughputBuckets().isEmpty()) {
            maxBucket = Math.max(maxBucket, log.getThroughputBuckets().lastKey());
        }
        if (!log.getFailureBuckets().isEmpty()) {
            maxBucket = Math.max(maxBucket, log.getFailureBuckets().lastKey());
        }
        if (!log.getCompletionBuckets().isEmpty()) {
            maxBucket = Math.max(maxBucket, log.getCompletionBuckets().lastKey());
        }
        double durationSeconds = SimulationParameters.simulationDuration;
        if (durationSeconds <= 0 && log.getSimulationManager() != null && log.getSimulationManager().getSimulation() != null) {
            durationSeconds = log.getSimulationManager().getSimulation().clock();
        }
        if (durationSeconds > 0 && log.getTimeWindowSeconds() > 0) {
            int durationBucket = (int) Math.max(0, Math.ceil(durationSeconds / log.getTimeWindowSeconds()) - 1);
            maxBucket = Math.max(maxBucket, durationBucket);
        }

        try (PrintWriter pw = new PrintWriter(new FileWriter(fileName, true))) {
            if (writeHeader) {
                pw.println(TIME_SERIES_HEADER);
            }
            double windowSec = log.getTimeWindowSeconds();
            double windowMin = (windowSec > 0) ? windowSec / 60.0 : 1.0;
            for (int b = 0; b <= maxBucket; b++) {
                int successes = log.getThroughputBuckets().getOrDefault(b, 0);
                int failures = log.getFailureBuckets().getOrDefault(b, 0);
                int completions = log.getCompletionBuckets().getOrDefault(b, successes + failures);
                double waitSum = log.getWaitSumBuckets().getOrDefault(b, 0.0);
                int waitCnt = log.getWaitCountBuckets().getOrDefault(b, 0);
                double avgWait = (waitCnt > 0) ? waitSum / waitCnt : 0.0;
                double winStart = b * windowSec;
                double winEnd = winStart + windowSec;
                double tputPerMin = successes / windowMin;

                pw.printf(Locale.US, "%s,%s,%d,%d,%.1f,%.1f,%.4f,%d,%d,%.6f%n",
                    log.getOrchArchitecture(),
                    log.getOrchAlgorithm(),
                    log.getDevicesCount(),
                    b,
                    winStart,
                    winEnd,
                    tputPerMin,
                    completions,
                    failures,
                    avgWait);
            }
        } catch (IOException e) {
            SimLog.println("ResearchMetricsExporter - Failed to write time series: " + e.getMessage());
        }
    }
}
