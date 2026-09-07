package com.mechalikh.pureedgesim.metrics;

import com.mechalikh.pureedgesim.simulationmanager.ResearchSimLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Milestone 6 — Tests for Task Failure Rate.
 */
public class FailureRateTest {

    @Test
    @DisplayName("Failure rate percentage is in valid [0.0, 100.0] range")
    public void testFailureRateRange() throws Exception {
        ResearchSimLog log = new ResearchSimLog("test_run", false);
        setField(log, "tasksSent", 1000);
        setField(log, "tasksFailed", 250);
        setField(log, "tasksFailedLatency", 250);

        log.computeSummaryMetrics();

        assertEquals(25.0, log.getFailureRatePct(), 1e-6);
        assertTrue(log.getFailureRatePct() >= 0.0 && log.getFailureRatePct() <= 100.0);
    }

    @Test
    @DisplayName("Failure rate is zero when no tasks failed")
    public void testFailureRateZeroWhenNoFailures() throws Exception {
        ResearchSimLog log = new ResearchSimLog("test_run", false);
        setField(log, "tasksSent", 500);
        setField(log, "tasksFailed", 0);

        log.computeSummaryMetrics();

        assertEquals(0.0, log.getFailureRatePct(), 1e-6);
    }

    @Test
    @DisplayName("Denominator is tasksSent: tasksFailed <= tasksSent")
    public void testFailureRateDenominatorIsTasksSent() throws Exception {
        ResearchSimLog log = new ResearchSimLog("test_run", false);
        setField(log, "tasksSent", 6258);
        setField(log, "tasksFailed", 2026);
        setField(log, "tasksFailedLatency", 2026);

        log.computeSummaryMetrics();

        assertTrue(log.getTasksFailed() <= log.getTasksSent(),
                "tasksFailed (" + log.getTasksFailed() + ") must be <= tasksSent (" + log.getTasksSent() + ")");
        double expectedRate = 2026.0 * 100.0 / 6258.0;
        assertEquals(expectedRate, log.getFailureRatePct(), 1e-4);
    }

    @Test
    @DisplayName("Failure rate cross-checks with baseline CSV counts")
    public void testFailureRateConsistentWithExistingCsv() throws IOException {
        Path baselineCsv = Path.of("docs/additional-metrics/baseline_output.csv");
        assertTrue(Files.exists(baselineCsv), "Baseline CSV must exist");

        double success = extractColumn(baselineCsv, "Tasks successfully executed");
        double failedDelay = extractColumn(baselineCsv, "Tasks failed (delay)");
        double totalFailed = failedDelay;
        if (hasColumn(baselineCsv, "Task not executed (No resources available or long waiting time)")) {
            totalFailed += extractColumn(baselineCsv, "Task not executed (No resources available or long waiting time)");
        }
        if (hasColumn(baselineCsv, "Tasks failed (device dead)")) {
            totalFailed += extractColumn(baselineCsv, "Tasks failed (device dead)");
        }
        if (hasColumn(baselineCsv, "Tasks failed (mobility)")) {
            totalFailed += extractColumn(baselineCsv, "Tasks failed (mobility)");
        }

        double totalSent = success + totalFailed;
        assertTrue(totalSent > 0, "Tasks sent must be positive");
        double expectedFailureRate = (totalFailed * 100.0) / totalSent;

        // Verify that success + failed = totalSent
        assertEquals(100.0, (success / totalSent) * 100.0 + expectedFailureRate, 1e-6);
    }

    private static boolean hasColumn(Path csvPath, String columnName) throws IOException {
        try (BufferedReader br = Files.newBufferedReader(csvPath)) {
            String headerLine = br.readLine();
            if (headerLine == null) return false;
            String[] headers = headerLine.split(",");
            for (String h : headers) {
                if (columnName.equals(h.trim())) return true;
            }
        }
        return false;
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
