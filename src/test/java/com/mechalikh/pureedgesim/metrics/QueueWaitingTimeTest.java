package com.mechalikh.pureedgesim.metrics;

import com.mechalikh.pureedgesim.scenariomanager.SimulationParameters;
import com.mechalikh.pureedgesim.simulationmanager.ResearchMetricsExporter;
import com.mechalikh.pureedgesim.simulationmanager.ResearchSimLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Milestone 4 — Tests for Average Queue Waiting Time.
 */
public class QueueWaitingTimeTest {

    @Test
    @DisplayName("Average queue waiting time is non-negative")
    public void testAvgQueueWaitingNonNegative() throws Exception {
        ResearchSimLog log = new ResearchSimLog("test_run", false);
        setField(log, "totalWaitingTime", 15.5);
        setField(log, "executedTasksCount", 5);

        log.computeSummaryMetrics();

        assertEquals(3.1, log.getAvgQueueWaitingTime(), 1e-6);
        assertTrue(log.getAvgQueueWaitingTime() >= 0.0);
    }

    @Test
    @DisplayName("Defensive: avgQueueWaitingTime is 0.0 when no tasks executed")
    public void testQueueWaitingZeroWhenNoTasksExecuted() throws Exception {
        ResearchSimLog log = new ResearchSimLog("test_run", false);
        setField(log, "totalWaitingTime", 0.0);
        setField(log, "executedTasksCount", 0);

        log.computeSummaryMetrics();

        assertEquals(0.0, log.getAvgQueueWaitingTime(), 1e-9);
        assertFalse(Double.isNaN(log.getAvgQueueWaitingTime()));
        assertFalse(Double.isInfinite(log.getAvgQueueWaitingTime()));
    }

    @Test
    @DisplayName("Average queue waiting time matches formula and exporter output")
    public void testAvgQueueWaitingMatchesExistingCsv(@TempDir Path tempDir) throws Exception {
        ResearchSimLog log = new ResearchSimLog("run_test", false);
        setField(log, "totalWaitingTime", 12.3456);
        setField(log, "executedTasksCount", 100);
        setField(log, "currentOrchArchitecture", "CLOUD_ONLY");
        setField(log, "currentOrchAlgorithm", "ROUND_ROBIN");
        setField(log, "currentEdgeDevicesCount", 10);

        log.computeSummaryMetrics();
        assertEquals(0.123456, log.getAvgQueueWaitingTime(), 1e-6);

        String orig = SimulationParameters.outputFolder;
        try {
            SimulationParameters.outputFolder = tempDir.toString();
            ResearchMetricsExporter.writeSummaryRow(log);

            Path csv = tempDir.resolve("run_test/_research_summary.csv");
            assertTrue(Files.exists(csv));
            double readVal = extractColumn(csv, "Average queue waiting time (s)");
            assertEquals(log.getAvgQueueWaitingTime(), readVal, 1e-6);
        } finally {
            SimulationParameters.outputFolder = orig;
        }

        // If an output folder exists with the column, verify exact match with Sequential_simulation.csv
        File outputDir = new File("PureEdgeSim/output");
        if (outputDir.exists() && outputDir.isDirectory()) {
            File[] subdirs = outputDir.listFiles(File::isDirectory);
            if (subdirs != null) {
                Arrays.sort(subdirs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                for (File d : subdirs) {
                    File resFile = new File(d, "_research_summary.csv");
                    File seqFile = new File(d, "Sequential_simulation.csv");
                    if (resFile.exists() && seqFile.exists()) {
                        try {
                            double seqWait = extractColumn(seqFile.toPath(), "Average waiting time (s)");
                            double resWait = extractColumn(resFile.toPath(), "Average queue waiting time (s)");
                            assertEquals(seqWait, resWait, 1e-6,
                                    "Research queue wait should match Sequential_simulation.csv waiting time");
                            break;
                        } catch (IllegalArgumentException ignored) {
                            // Previous run created before column was added
                        }
                    }
                }
            }
        }
    }

    private static double extractColumn(Path csvPath, String columnName) throws IOException {
        try (BufferedReader br = Files.newBufferedReader(csvPath)) {
            String headerLine = br.readLine();
            String dataLine = br.readLine();
            assertNotNull(headerLine);
            assertNotNull(dataLine);

            String[] headers = headerLine.split(",");
            String[] values = dataLine.split(",");
            for (int i = 0; i < headers.length && i < values.length; i++) {
                if (columnName.equals(headers[i].trim())) {
                    return Double.parseDouble(values[i].trim());
                }
            }
        }
        throw new IllegalArgumentException("Column not found: " + columnName + " in " + csvPath);
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
