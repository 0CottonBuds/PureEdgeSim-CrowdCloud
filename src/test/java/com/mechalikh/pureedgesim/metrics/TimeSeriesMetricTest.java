package com.mechalikh.pureedgesim.metrics;

import com.mechalikh.pureedgesim.scenariomanager.SimulationParameters;
import com.mechalikh.pureedgesim.simulationmanager.ResearchMetricsExporter;
import com.mechalikh.pureedgesim.simulationmanager.ResearchSimLog;
import com.mechalikh.pureedgesim.taskgenerator.DefaultTask;
import com.mechalikh.pureedgesim.taskgenerator.Task;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Milestone 8 — Unit tests for time-series metrics generation and validation.
 */
public class TimeSeriesMetricTest {

    @Test
    @DisplayName("onTaskCompleted populates throughput, completion, and wait time buckets")
    public void testOnTaskCompletedPopulatesBuckets() {
        ResearchSimLog log = new ResearchSimLog("test_run", false, 60.0);

        DefaultTask t1 = new DefaultTask(1);
        t1.setArrivalTime(10.0);
        t1.setExecutionStartTime(10.5);

        // Task completed at t = 30.0s -> bucket 0
        log.onTaskCompleted(t1, 30.0);

        // Task completed at t = 75.0s -> bucket 1
        DefaultTask t2 = new DefaultTask(2);
        log.onTaskCompleted(t2, 75.0);

        assertEquals(1, log.getThroughputBuckets().get(0));
        assertEquals(1, log.getThroughputBuckets().get(1));
        assertEquals(1, log.getCompletionBuckets().get(0));
        assertEquals(1, log.getCompletionBuckets().get(1));
    }

    @Test
    @DisplayName("Time-series CSV created with header and valid rows")
    public void testTimeSeriesCsvCreated(@TempDir Path tempDir) throws Exception {
        String origOutput = SimulationParameters.outputFolder;
        try {
            SimulationParameters.outputFolder = tempDir.toString();
            SimulationParameters.simulationDuration = 180.0; // 3 windows of 60s: 0, 1, 2

            ResearchSimLog log = new ResearchSimLog("ts_run", true, 60.0);
            setField(log, "currentOrchArchitecture", "EDGE_AND_CLOUD");
            setField(log, "currentOrchAlgorithm", "ROUND_ROBIN");
            setField(log, "currentEdgeDevicesCount", 20);

            DefaultTask t1 = new DefaultTask(1);
            log.onTaskCompleted(t1, 10.0); // bucket 0

            DefaultTask t2 = new DefaultTask(2);
            log.onTaskCompleted(t2, 130.0); // bucket 2

            ResearchMetricsExporter.writeTimeSeriesRows(log);

            File tsFile = new File(tempDir.toFile(), "ts_run/_research_timeseries.csv");
            assertTrue(tsFile.exists(), "Time series CSV should exist");

            List<String> lines = Files.readAllLines(tsFile.toPath());
            assertTrue(lines.size() >= 4, "Should have 1 header line + at least 3 window rows");
            assertEquals(ResearchMetricsExporter.TIME_SERIES_HEADER, lines.get(0));
        } finally {
            SimulationParameters.outputFolder = origOutput;
        }
    }

    @Test
    @DisplayName("Window indices are contiguous from 0 to maxBucket with zero gaps")
    public void testWindowIndicesContiguous(@TempDir Path tempDir) throws Exception {
        String origOutput = SimulationParameters.outputFolder;
        try {
            SimulationParameters.outputFolder = tempDir.toString();
            SimulationParameters.simulationDuration = 240.0; // 4 windows: 0, 1, 2, 3

            ResearchSimLog log = new ResearchSimLog("ts_run_contig", true, 60.0);
            // Only populate bucket 3
            DefaultTask t = new DefaultTask(1);
            log.onTaskCompleted(t, 200.0); // bucket 3

            ResearchMetricsExporter.writeTimeSeriesRows(log);

            File tsFile = new File(tempDir.toFile(), "ts_run_contig/_research_timeseries.csv");
            List<String> lines = Files.readAllLines(tsFile.toPath());

            // Check contiguous indices 0, 1, 2, 3
            int expectedIndex = 0;
            for (int i = 1; i < lines.size(); i++) {
                String[] parts = lines.get(i).split(",");
                int index = Integer.parseInt(parts[3].trim());
                assertEquals(expectedIndex, index, "Window indices must be contiguous starting from 0");
                expectedIndex++;
            }
            assertEquals(4, expectedIndex, "Should have exactly 4 contiguous windows (0 to 3)");
        } finally {
            SimulationParameters.outputFolder = origOutput;
        }
    }

    @Test
    @DisplayName("In each window: completions == (throughput * windowMin) + failures")
    public void testSuccessPlusFailureEqualsCompletions(@TempDir Path tempDir) throws Exception {
        String origOutput = SimulationParameters.outputFolder;
        try {
            SimulationParameters.outputFolder = tempDir.toString();
            SimulationParameters.simulationDuration = 120.0;

            ResearchSimLog log = new ResearchSimLog("ts_run_sum", true, 60.0);
            log.getThroughputBuckets().put(0, 10);
            log.getFailureBuckets().put(0, 3);
            log.getCompletionBuckets().put(0, 13);

            log.getThroughputBuckets().put(1, 5);
            log.getFailureBuckets().put(1, 2);
            log.getCompletionBuckets().put(1, 7);

            ResearchMetricsExporter.writeTimeSeriesRows(log);

            File tsFile = new File(tempDir.toFile(), "ts_run_sum/_research_timeseries.csv");
            List<String> lines = Files.readAllLines(tsFile.toPath());

            for (int i = 1; i < lines.size(); i++) {
                String[] parts = lines.get(i).split(",");
                double tput = Double.parseDouble(parts[6].trim());
                int completions = Integer.parseInt(parts[7].trim());
                int failures = Integer.parseInt(parts[8].trim());

                double windowMin = 60.0 / 60.0;
                double calculatedCompletions = (tput * windowMin) + failures;
                assertEquals(completions, Math.round(calculatedCompletions),
                        "Completions must equal throughput * windowMinutes + failures in row " + i);
            }
        } finally {
            SimulationParameters.outputFolder = origOutput;
        }
    }

    @Test
    @DisplayName("Sum of throughput buckets equals total successful tasks")
    public void testThroughputBucketsSumEqualsOverallSucceeded() throws Exception {
        ResearchSimLog log = new ResearchSimLog("ts_test", false, 60.0);
        setField(log, "tasksSent", 100);
        setField(log, "tasksFailed", 20);

        log.getThroughputBuckets().put(0, 35);
        log.getThroughputBuckets().put(1, 45);

        int sumSuccess = log.getThroughputBuckets().values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(log.getTasksSucceeded(), sumSuccess, "Bucket sum must equal tasksSucceeded (tasksSent - tasksFailed)");
    }

    @Test
    @DisplayName("Sum of failure buckets equals tasksFailed")
    public void testFailureBucketsSumEqualsTasksFailed() throws Exception {
        ResearchSimLog log = new ResearchSimLog("ts_test", false, 60.0);
        setField(log, "tasksFailed", 25);

        log.getFailureBuckets().put(0, 10);
        log.getFailureBuckets().put(1, 15);

        int sumFailures = log.getFailureBuckets().values().stream().mapToInt(Integer::intValue).sum();
        assertEquals(log.getTasksFailed(), sumFailures, "Failure bucket sum must equal tasksFailed");
    }

    @Test
    @DisplayName("All numeric cells in time-series CSV are finite (no NaN or Infinity)")
    public void testNoNanOrInfinityInTimeSeries(@TempDir Path tempDir) throws Exception {
        String origOutput = SimulationParameters.outputFolder;
        try {
            SimulationParameters.outputFolder = tempDir.toString();
            SimulationParameters.simulationDuration = 120.0;

            ResearchSimLog log = new ResearchSimLog("ts_finite", true, 60.0);
            // Completely empty log (zero tasks executed, zero waiting)
            ResearchMetricsExporter.writeTimeSeriesRows(log);

            File tsFile = new File(tempDir.toFile(), "ts_finite/_research_timeseries.csv");
            List<String> lines = Files.readAllLines(tsFile.toPath());

            for (int i = 1; i < lines.size(); i++) {
                String[] parts = lines.get(i).split(",");
                for (int col = 3; col < parts.length; col++) {
                    double val = Double.parseDouble(parts[col].trim());
                    assertFalse(Double.isNaN(val), "Value at row " + i + " col " + col + " must not be NaN");
                    assertFalse(Double.isInfinite(val), "Value at row " + i + " col " + col + " must not be Infinite");
                }
            }
        } finally {
            SimulationParameters.outputFolder = origOutput;
        }
    }

    @Test
    @DisplayName("Window start and end difference strictly equals timeWindowSeconds")
    public void testTimeSeriesWindowSizeRespected(@TempDir Path tempDir) throws Exception {
        String origOutput = SimulationParameters.outputFolder;
        try {
            SimulationParameters.outputFolder = tempDir.toString();
            SimulationParameters.simulationDuration = 180.0;
            double windowSec = 45.0;

            ResearchSimLog log = new ResearchSimLog("ts_win_size", true, windowSec);
            ResearchMetricsExporter.writeTimeSeriesRows(log);

            File tsFile = new File(tempDir.toFile(), "ts_win_size/_research_timeseries.csv");
            List<String> lines = Files.readAllLines(tsFile.toPath());

            for (int i = 1; i < lines.size(); i++) {
                String[] parts = lines.get(i).split(",");
                double start = Double.parseDouble(parts[4].trim());
                double end = Double.parseDouble(parts[5].trim());
                assertEquals(windowSec, end - start, 1e-6, "Window duration must match timeWindowSeconds");
            }
        } finally {
            SimulationParameters.outputFolder = origOutput;
        }
    }

    private static void setField(Object obj, String fieldName, Object value) throws Exception {
        Class<?> cls = obj.getClass();
        while (cls != null) {
            try {
                Field f = cls.getDeclaredField(fieldName);
                f.setAccessible(true);
                f.set(obj, value);
                return;
            } catch (NoSuchFieldException e) {
                cls = cls.getSuperclass();
            }
        }
        throw new NoSuchFieldException("Field " + fieldName + " not found on " + obj.getClass());
    }
}
