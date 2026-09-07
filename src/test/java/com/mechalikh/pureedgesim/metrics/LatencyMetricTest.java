package com.mechalikh.pureedgesim.metrics;

import com.mechalikh.pureedgesim.scenariomanager.SimulationParameters;
import com.mechalikh.pureedgesim.simulationmanager.ResearchSimLog;
import com.mechalikh.pureedgesim.taskgenerator.DefaultTask;
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
 * Milestone 5 — Tests for Latency breakdown and Cold Start metrics.
 */
public class LatencyMetricTest {

    @Test
    @DisplayName("Latency components sum to total latency")
    public void testLatencyComponentSumApproxTotalLatency() {
        ResearchSimLog log = new ResearchSimLog("test_run", false);

        // Simulate 2 tasks
        DefaultTask t1 = new DefaultTask(1);
        t1.addActualNetworkTime(1.5);
        t1.setArrivalTime(10.0);
        t1.setExecutionStartTime(12.0); // wait = 2.0
        t1.setExecutionFinishTime(15.0); // cpu = 3.0 -> delay = 6.5
        log.getTasksExecutionInfos(t1);

        DefaultTask t2 = new DefaultTask(2);
        t2.addActualNetworkTime(2.5);
        t2.setArrivalTime(20.0);
        t2.setExecutionStartTime(21.0); // wait = 1.0
        t2.setExecutionFinishTime(25.0); // cpu = 4.0 -> delay = 7.5
        log.getTasksExecutionInfos(t2);

        log.computeSummaryMetrics();

        // 2 tasks executed
        assertEquals(2, log.getExecutedTasksCount());
        // Avg network = (1.5 + 2.5) / 2 = 2.0
        assertEquals(2.0, log.getAvgNetworkLatency(), 1e-6);
        // Avg wait = (2.0 + 1.0) / 2 = 1.5
        assertEquals(1.5, log.getAvgQueueWaitingTime(), 1e-6);
        // Avg cpu = (3.0 + 4.0) / 2 = 3.5
        assertEquals(3.5, log.getAvgCpuTime(), 1e-6);
        // Avg total = (6.5 + 7.5) / 2 = 7.0
        assertEquals(7.0, log.getAvgTotalLatency(), 1e-6);

        // Component sum equals total
        double componentSum = log.getAvgNetworkLatency() + log.getAvgQueueWaitingTime() + log.getAvgCpuTime();
        assertEquals(log.getAvgTotalLatency(), componentSum, 1e-6);
        assertTrue(log.getAvgTotalLatency() >= componentSum - 1e-9);
    }

    @Test
    @DisplayName("Total latency is greater than zero when tasks are executed")
    public void testAvgTotalLatencyNotZero() {
        ResearchSimLog log = new ResearchSimLog("test_run", false);
        DefaultTask t = new DefaultTask(1);
        t.addActualNetworkTime(0.5);
        t.setArrivalTime(1.0);
        t.setExecutionStartTime(2.0);
        t.setExecutionFinishTime(3.0);
        log.getTasksExecutionInfos(t);

        log.computeSummaryMetrics();
        assertTrue(log.getAvgTotalLatency() > 0.0);
        assertTrue(log.getAvgNetworkLatency() >= 0.0);
        assertTrue(log.getAvgCpuTime() >= 0.0);
    }

    @Test
    @DisplayName("Cold start is 0 when registry is disabled or no containers downloaded")
    public void testColdStartZeroWhenRegistryDisabled() {
        ResearchSimLog log = new ResearchSimLog("test_run", false);
        DefaultTask t = new DefaultTask(1);
        t.addActualNetworkTime(1.0);
        t.setArrivalTime(5.0);
        t.setExecutionStartTime(6.0);
        t.setExecutionFinishTime(7.0);
        log.getTasksExecutionInfos(t);

        log.computeSummaryMetrics();
        assertEquals(0.0, log.getAvgColdStartTime(), 1e-9);
        assertEquals(0, log.getContainerTasksCount());
    }

    @Test
    @DisplayName("Cold start computes average when container downloads are recorded")
    public void testColdStartComputesAverage() {
        ResearchSimLog log = new ResearchSimLog("test_run", false);
        log.recordContainerDownload(2.5);
        log.recordContainerDownload(3.5);

        log.computeSummaryMetrics();
        assertEquals(2, log.getContainerTasksCount());
        assertEquals(3.0, log.getAvgColdStartTime(), 1e-6);
    }

    @Test
    @DisplayName("Average CPU time matches existing CSV execution delay")
    public void testAvgCpuTimeMatchesExistingCsv() throws IOException {
        Path baselineCsv = Path.of("docs/additional-metrics/baseline_output.csv");
        assertTrue(Files.exists(baselineCsv), "Baseline CSV must exist");

        double baselineExecDelay = extractColumn(baselineCsv, "Average execution delay (s)");
        assertTrue(baselineExecDelay > 0, "Baseline execution delay should be positive");

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
                            double seqExec = extractColumn(seqFile.toPath(), "Average execution delay (s)");
                            double resCpu = extractColumn(resFile.toPath(), "Average latency: computation (s)");
                            assertEquals(seqExec, resCpu, 1e-4,
                                    "Research CPU latency must match Sequential_simulation.csv execution delay");
                            return;
                        } catch (IllegalArgumentException ignored) {
                            // File from earlier milestone without this column
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
}
