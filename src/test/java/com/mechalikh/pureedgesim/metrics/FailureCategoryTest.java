package com.mechalikh.pureedgesim.metrics;

import com.mechalikh.pureedgesim.simulationmanager.ResearchSimLog;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Milestone 7 — Tests for fine-grained failure categories and invariants.
 */
public class FailureCategoryTest {

    @Test
    @DisplayName("Invariant 1: tasksFailedOOM + tasksFailedNoDestination == tasksFailedRessourcesUnavailable")
    public void testOomPlusNoDestinationEqualsMergedCounter() throws Exception {
        ResearchSimLog log = new ResearchSimLog("test_run", false);
        setField(log, "tasksFailedOOM", 15);
        setField(log, "tasksFailedNoDestination", 25);
        setField(log, "tasksFailedRessourcesUnavailable", 40);
        setField(log, "tasksSent", 100);
        setField(log, "tasksFailed", 40);

        log.computeSummaryMetrics();

        assertEquals(40, log.getTasksFailedOOM() + log.getTasksFailedNetwork());
        assertEquals(log.getTasksFailedRessourcesUnavailable(), log.getTasksFailedOOM() + log.getTasksFailedNetwork());
    }

    @Test
    @DisplayName("Invariant 2: All five categories sum to tasksFailed")
    public void testAllCategoriesSumToTasksFailed() throws Exception {
        ResearchSimLog log = new ResearchSimLog("test_run", false);
        setField(log, "tasksFailedLatency", 50);
        setField(log, "tasksFailedBeacauseDeviceDead", 10);
        setField(log, "tasksFailedMobility", 5);
        setField(log, "tasksFailedOOM", 20);
        setField(log, "tasksFailedNoDestination", 15);
        setField(log, "tasksFailedRessourcesUnavailable", 35);
        setField(log, "tasksFailed", 100);
        setField(log, "tasksSent", 200);

        log.computeSummaryMetrics();

        int sum = log.getTasksFailedDeadline()
                + log.getTasksFailedBattery()
                + log.getTasksFailedMobility()
                + log.getTasksFailedOOM()
                + log.getTasksFailedNetwork();

        assertEquals(log.getTasksFailed(), sum);
        assertEquals(100, sum);
    }

    @Test
    @DisplayName("Category percentages sum to 100% when failures > 0, and 0% when failures == 0")
    public void testCategoryPercentagesSumTo100() throws Exception {
        ResearchSimLog log = new ResearchSimLog("test_run", false);
        setField(log, "tasksFailedLatency", 30);
        setField(log, "tasksFailedBeacauseDeviceDead", 20);
        setField(log, "tasksFailedMobility", 10);
        setField(log, "tasksFailedOOM", 25);
        setField(log, "tasksFailedNoDestination", 15);
        setField(log, "tasksFailedRessourcesUnavailable", 40);
        setField(log, "tasksFailed", 100);
        setField(log, "tasksSent", 200);

        log.computeSummaryMetrics();

        double pctSum = log.getDeadlinePct()
                + log.getBatteryPct()
                + log.getMobilityPct()
                + log.getOomPct()
                + log.getNetworkPct();

        assertEquals(100.0, pctSum, 1e-4);
        assertEquals(30.0, log.getDeadlinePct(), 1e-4);
        assertEquals(20.0, log.getBatteryPct(), 1e-4);
        assertEquals(10.0, log.getMobilityPct(), 1e-4);
        assertEquals(25.0, log.getOomPct(), 1e-4);
        assertEquals(15.0, log.getNetworkPct(), 1e-4);

        // Edge case: zero failures
        ResearchSimLog zeroLog = new ResearchSimLog("test_zero", false);
        setField(zeroLog, "tasksFailed", 0);
        setField(zeroLog, "tasksSent", 100);
        zeroLog.computeSummaryMetrics();

        assertEquals(0.0, zeroLog.getDeadlinePct(), 1e-6);
        assertEquals(0.0, zeroLog.getBatteryPct(), 1e-6);
        assertEquals(0.0, zeroLog.getMobilityPct(), 1e-6);
        assertEquals(0.0, zeroLog.getOomPct(), 1e-6);
        assertEquals(0.0, zeroLog.getNetworkPct(), 1e-6);
    }

    @Test
    @DisplayName("Category counts are non-negative")
    public void testCategoryCountsNonNegative() {
        ResearchSimLog log = new ResearchSimLog("test_run", false);
        log.computeSummaryMetrics();

        assertTrue(log.getTasksFailedDeadline() >= 0);
        assertTrue(log.getTasksFailedBattery() >= 0);
        assertTrue(log.getTasksFailedMobility() >= 0);
        assertTrue(log.getTasksFailedOOM() >= 0);
        assertTrue(log.getTasksFailedNetwork() >= 0);
    }

    @Test
    @DisplayName("Deadline failure count matches existing CSV")
    public void testExistingDeadlineCountMatchesCsv() throws Exception {
        Path baselineCsv = Path.of("docs/additional-metrics/baseline_output.csv");
        assertTrue(Files.exists(baselineCsv));

        double baselineDeadline = extractColumn(baselineCsv, "Tasks failed (delay)");

        ResearchSimLog log = new ResearchSimLog("test_run", false);
        setField(log, "tasksFailedLatency", (int) baselineDeadline);
        setField(log, "tasksFailed", (int) baselineDeadline);
        setField(log, "tasksSent", 6258);
        log.computeSummaryMetrics();

        assertEquals((int) baselineDeadline, log.getTasksFailedDeadline());
    }

    @Test
    @DisplayName("Battery failure count matches existing CSV")
    public void testExistingBatteryCountMatchesCsv() throws Exception {
        Path baselineCsv = Path.of("docs/additional-metrics/baseline_output.csv");
        assertTrue(Files.exists(baselineCsv));

        double baselineBattery = extractColumn(baselineCsv, "Tasks failed (device dead)");

        ResearchSimLog log = new ResearchSimLog("test_run", false);
        setField(log, "tasksFailedBeacauseDeviceDead", (int) baselineBattery);
        setField(log, "tasksFailedLatency", 2026);
        setField(log, "tasksFailed", 2026 + (int) baselineBattery);
        setField(log, "tasksSent", 6258);
        log.computeSummaryMetrics();

        assertEquals((int) baselineBattery, log.getTasksFailedBattery());
    }

    @Test
    @DisplayName("Mobility failure count matches existing CSV")
    public void testExistingMobilityCountMatchesCsv() throws Exception {
        Path baselineCsv = Path.of("docs/additional-metrics/baseline_output.csv");
        assertTrue(Files.exists(baselineCsv));

        double baselineMobility = extractColumn(baselineCsv, "Tasks failed (mobility)");

        ResearchSimLog log = new ResearchSimLog("test_run", false);
        setField(log, "tasksFailedMobility", (int) baselineMobility);
        setField(log, "tasksFailedLatency", 2026);
        setField(log, "tasksFailed", 2026 + (int) baselineMobility);
        setField(log, "tasksSent", 6258);
        log.computeSummaryMetrics();

        assertEquals((int) baselineMobility, log.getTasksFailedMobility());
    }

    @Test
    @DisplayName("OOM + Network failures sum to merged column from existing CSV")
    public void testOomPlusNetworkMatchesMergedCsvColumn() throws Exception {
        Path baselineCsv = Path.of("docs/additional-metrics/baseline_output.csv");
        assertTrue(Files.exists(baselineCsv));

        double baselineMerged = extractColumn(baselineCsv, "Task not executed (No resources available or long waiting time)");

        ResearchSimLog log = new ResearchSimLog("test_run", false);
        setField(log, "tasksFailedOOM", 0);
        setField(log, "tasksFailedNoDestination", (int) baselineMerged);
        setField(log, "tasksFailedRessourcesUnavailable", (int) baselineMerged);
        setField(log, "tasksFailedLatency", 2026);
        setField(log, "tasksFailed", 2026 + (int) baselineMerged);
        setField(log, "tasksSent", 6258);
        log.computeSummaryMetrics();

        assertEquals((int) baselineMerged, log.getTasksFailedOOM() + log.getTasksFailedNetwork());
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
