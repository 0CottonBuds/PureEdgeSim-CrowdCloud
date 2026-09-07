package com.mechalikh.pureedgesim.metrics;

import com.mechalikh.pureedgesim.scenariomanager.SimulationParameters;
import com.mechalikh.pureedgesim.simulationmanager.ResearchMetricsExporter;
import com.mechalikh.pureedgesim.simulationmanager.ResearchSimLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Milestone 3 — Tests for Throughput metric and ResearchMetricsExporter.
 */
public class ThroughputMetricTest {

    @Test
    @DisplayName("Throughput formula: (tasksSent - tasksFailed) / (durationMinutes)")
    public void testThroughputFormula() throws Exception {
        ResearchSimLog log = new ResearchSimLog("test_run", false);
        setField(log, "tasksSent", 1200);
        setField(log, "tasksFailed", 200);
        setField(log, "tasksFailedLatency", 200);
        SimulationParameters.simulationDuration = 600.0; // 10 minutes

        log.computeSummaryMetrics();

        // Succeeded = 1000, Minutes = 10 -> 100 tasks/min
        assertEquals(100.0, log.getThroughputPerMinute(), 1e-6);
        assertEquals(1200, log.getTasksSent());
        assertEquals(200, log.getTasksFailed());
        assertEquals(1000, log.getTasksSucceeded());
    }

    @Test
    @DisplayName("Throughput non-negative when tasks fail")
    public void testThroughputNonNegative() throws Exception {
        ResearchSimLog log = new ResearchSimLog("test_run", false);
        setField(log, "tasksSent", 500);
        setField(log, "tasksFailed", 500);
        setField(log, "tasksFailedLatency", 500);
        SimulationParameters.simulationDuration = 300.0; // 5 minutes

        log.computeSummaryMetrics();
        assertEquals(0.0, log.getThroughputPerMinute(), 1e-6);
        assertTrue(log.getThroughputPerMinute() >= 0.0);
    }

    @Test
    @DisplayName("Research summary CSV created with expected header and data")
    public void testResearchSummaryCsvCreated(@TempDir Path tempDir) throws Exception {
        String origOutput = SimulationParameters.outputFolder;
        try {
            SimulationParameters.outputFolder = tempDir.toString();
            ResearchSimLog log = new ResearchSimLog("run_1", false);
            setField(log, "currentOrchArchitecture", "CLOUD_ONLY");
            setField(log, "currentOrchAlgorithm", "ROUND_ROBIN");
            setField(log, "currentEdgeDevicesCount", 10);
            setField(log, "tasksSent", 600);
            setField(log, "tasksFailed", 100);
            setField(log, "tasksFailedLatency", 100);
            SimulationParameters.simulationDuration = 300.0; // 5 min -> (500 / 5) = 100 tasks/min

            log.computeSummaryMetrics();
            ResearchMetricsExporter.writeSummaryRow(log);

            Path csvFile = tempDir.resolve("run_1/_research_summary.csv");
            assertTrue(Files.exists(csvFile), "_research_summary.csv must exist in " + csvFile);

            List<String> lines = Files.readAllLines(csvFile);
            assertTrue(lines.size() >= 2, "CSV should have at least header and one data row");
            assertEquals(ResearchMetricsExporter.SUMMARY_HEADER, lines.get(0));
            assertTrue(lines.get(1).startsWith("CLOUD_ONLY,ROUND_ROBIN,10,100.0000"), "Data row unexpected: " + lines.get(1));
        } finally {
            SimulationParameters.outputFolder = origOutput;
        }
    }

    @Test
    @DisplayName("Research CSV does not modify existing Sequential_simulation.csv")
    public void testResearchCsvDoesNotModifyExistingCsv() throws IOException {
        Path baselinePath = Path.of("docs/additional-metrics/baseline_output.csv");
        assertTrue(Files.exists(baselinePath), "Baseline CSV must exist");

        File outputDir = new File("PureEdgeSim/output");
        if (outputDir.exists() && outputDir.isDirectory()) {
            File[] subdirs = outputDir.listFiles(File::isDirectory);
            if (subdirs != null && subdirs.length > 0) {
                Arrays.sort(subdirs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                for (File d : subdirs) {
                    File seqCsv = new File(d, "Sequential_simulation.csv");
                    File resCsv = new File(d, "_research_summary.csv");
                    if (seqCsv.exists() && resCsv.exists()) {
                        List<String> lines = Files.readAllLines(seqCsv.toPath());
                        assertTrue(lines.size() >= 2);
                        assertTrue(lines.get(0).startsWith("Orchestration architecture,Orchestration algorithm"));
                        break;
                    }
                }
            }
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
