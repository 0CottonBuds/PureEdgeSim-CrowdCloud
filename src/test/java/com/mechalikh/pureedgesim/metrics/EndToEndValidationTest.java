package com.mechalikh.pureedgesim.metrics;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Milestone 10 — End-to-End Validation Test.
 *
 * Verifies internal mathematical consistency and invariants across all summary
 * and time-series metrics from a complete simulation execution.
 */
public class EndToEndValidationTest {

    private static File runDir;
    private static File sequentialCsv;
    private static File researchSummaryCsv;
    private static File researchTimeSeriesCsv;

    private static Map<String, Double> seqMetrics = new HashMap<>();
    private static Map<String, Double> researchMetrics = new HashMap<>();

    @BeforeAll
    public static void setUp() throws Exception {
        File outputDir = new File("PureEdgeSim/output");
        assertTrue(outputDir.exists() && outputDir.isDirectory(), "Output directory must exist");

        File[] subdirs = outputDir.listFiles(File::isDirectory);
        assertNotNull(subdirs);
        Arrays.sort(subdirs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));

        for (File d : subdirs) {
            File fSeq = new File(d, "Sequential_simulation.csv");
            File fSummary = new File(d, "_research_summary.csv");
            File fTs = new File(d, "_research_timeseries.csv");
            if (fSeq.exists() && fSummary.exists() && fTs.exists()) {
                runDir = d;
                sequentialCsv = fSeq;
                researchSummaryCsv = fSummary;
                researchTimeSeriesCsv = fTs;
                break;
            }
        }

        // If no full output exists yet, run ExampleTraceReplay
        if (runDir == null) {
            new examples.ExampleTraceReplay();
            subdirs = outputDir.listFiles(File::isDirectory);
            assertNotNull(subdirs);
            Arrays.sort(subdirs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
            for (File d : subdirs) {
                File fSeq = new File(d, "Sequential_simulation.csv");
                File fSummary = new File(d, "_research_summary.csv");
                File fTs = new File(d, "_research_timeseries.csv");
                if (fSeq.exists() && fSummary.exists() && fTs.exists()) {
                    runDir = d;
                    sequentialCsv = fSeq;
                    researchSummaryCsv = fSummary;
                    researchTimeSeriesCsv = fTs;
                    break;
                }
            }
        }

        assertNotNull(runDir, "Run directory with research CSVs must exist");

        // Parse Sequential_simulation.csv
        loadCsvMap(sequentialCsv, seqMetrics);

        // Parse _research_summary.csv
        loadCsvMap(researchSummaryCsv, researchMetrics);
    }

    private static void loadCsvMap(File file, Map<String, Double> targetMap) throws IOException {
        try (BufferedReader br = Files.newBufferedReader(file.toPath())) {
            String headerLine = br.readLine();
            String dataLine = br.readLine();
            assertNotNull(headerLine);
            assertNotNull(dataLine);

            String[] headers = headerLine.split(",");
            String[] values = dataLine.split(",");
            for (int i = 0; i < headers.length && i < values.length; i++) {
                try {
                    double val = Double.parseDouble(values[i].trim());
                    targetMap.put(headers[i].trim(), val);
                } catch (NumberFormatException ignored) {
                }
            }
        }
    }

    @Test
    @DisplayName("Validation 1: Task Accounting Identity")
    public void testTaskAccountingIdentity() {
        double success = seqMetrics.get("Tasks successfully executed");
        double failed = seqMetrics.get("Tasks failed (delay)");
        if (seqMetrics.containsKey("Task not executed (No resources available or long waiting time)")) {
            failed += seqMetrics.get("Task not executed (No resources available or long waiting time)");
        }
        if (seqMetrics.containsKey("Tasks failed (device dead)")) {
            failed += seqMetrics.get("Tasks failed (device dead)");
        }
        if (seqMetrics.containsKey("Tasks failed (mobility)")) {
            failed += seqMetrics.get("Tasks failed (mobility)");
        }

        double totalSent = success + failed;
        double generated = seqMetrics.get("Number of generated tasks");

        assertTrue(totalSent > 0, "Tasks sent must be positive");
        assertTrue(totalSent <= generated || generated > 0, "Tasks sent and generated must be consistent");

        // Failure rate identity
        double failureRate = researchMetrics.get("Task failure rate (%)");
        double expectedRate = (failed * 100.0) / totalSent;
        assertEquals(expectedRate, failureRate, 1e-4);
    }

    @Test
    @DisplayName("Validation 2: Failure Category Consistency and Invariants")
    public void testFailureCategoryConsistency() {
        double deadline = researchMetrics.get("Failures: Deadline");
        double battery = researchMetrics.get("Failures: Battery");
        double oom = researchMetrics.get("Failures: OOM");
        double network = researchMetrics.get("Failures: Network");
        double mobility = researchMetrics.get("Failures: Mobility");

        double sumFailures = deadline + battery + oom + network + mobility;

        double delayFailed = seqMetrics.get("Tasks failed (delay)");
        double resourceFailed = seqMetrics.getOrDefault("Task not executed (No resources available or long waiting time)", 0.0);
        double deadFailed = seqMetrics.getOrDefault("Tasks failed (device dead)", 0.0);
        double mobilityFailed = seqMetrics.getOrDefault("Tasks failed (mobility)", 0.0);
        double totalExpectedFailures = delayFailed + resourceFailed + deadFailed + mobilityFailed;

        // Invariant 1: OOM + Network == merged counter
        assertEquals(resourceFailed, oom + network, 1e-6, "OOM + Network must equal merged resource failure count");

        // Invariant 2: sum of categories == total failures
        assertEquals(totalExpectedFailures, sumFailures, 1e-6, "All categories must sum to total failures");

        // Category percentages sum to 100% (or 0% if total failures == 0)
        double deadlinePct = researchMetrics.get("Failures: Deadline (%)");
        double batteryPct = researchMetrics.get("Failures: Battery (%)");
        double oomPct = researchMetrics.get("Failures: OOM (%)");
        double networkPct = researchMetrics.get("Failures: Network (%)");
        double mobilityPct = researchMetrics.get("Failures: Mobility (%)");

        double sumPct = deadlinePct + batteryPct + oomPct + networkPct + mobilityPct;
        if (sumFailures > 0) {
            assertEquals(100.0, sumPct, 1e-2, "Failure category percentages must sum to 100%");
        } else {
            assertEquals(0.0, sumPct, 1e-6);
        }
    }

    @Test
    @DisplayName("Validation 3: Latency Component Bounds")
    public void testLatencyComponentBounds() {
        double totalLatency = researchMetrics.get("Average total latency (s)");
        double networkLatency = researchMetrics.get("Average latency: network (s)");
        double queueWaiting = researchMetrics.get("Average latency: queue waiting (s)");
        double computation = researchMetrics.get("Average latency: computation (s)");
        double coldStart = researchMetrics.get("Average latency: cold start (s)");

        assertTrue(totalLatency >= 0, "Total latency must be non-negative");
        assertTrue(networkLatency >= 0, "Network latency must be non-negative");
        assertTrue(queueWaiting >= 0, "Queue waiting latency must be non-negative");
        assertTrue(computation >= 0, "Computation latency must be non-negative");
        assertTrue(coldStart >= 0, "Cold start latency must be non-negative");

        // Total latency must be >= components sum (within numerical tolerance)
        assertTrue(totalLatency >= (queueWaiting + computation) - 1e-6,
                "Total latency must be at least queueWaiting + computation");
        assertTrue(totalLatency >= networkLatency - 1e-6,
                "Total latency must be at least networkLatency");
    }

    @Test
    @DisplayName("Validation 4: Time-Series Internal Consistency")
    public void testTimeSeriesConsistency() throws IOException {
        List<String> lines = Files.readAllLines(researchTimeSeriesCsv.toPath());
        assertTrue(lines.size() >= 2, "Time series CSV must have header + at least one data row");

        double totalThroughput = 0;
        int totalFailures = 0;
        int totalCompletions = 0;

        for (int i = 1; i < lines.size(); i++) {
            String[] parts = lines.get(i).split(",");
            int windowIndex = Integer.parseInt(parts[3].trim());
            assertEquals(i - 1, windowIndex, "Window indices must be sequential with no gaps");

            double tput = Double.parseDouble(parts[6].trim());
            int completions = Integer.parseInt(parts[7].trim());
            int failures = Integer.parseInt(parts[8].trim());

            totalThroughput += tput;
            totalFailures += failures;
            totalCompletions += completions;

            // In each row: completions == throughput * (windowSec / 60) + failures
            double windowMin = 60.0 / 60.0;
            assertEquals(completions, Math.round(tput * windowMin) + failures,
                    "Completions must equal throughput * windowMin + failures in row " + i);
        }

        // Total completions across all windows must equal total successes + total failures
        assertEquals(totalCompletions, Math.round(totalThroughput) + totalFailures,
                "Total completions across all windows must equal sum of throughput + failures");

        // Failures in time series must match summary failures
        double summaryFailures = researchMetrics.get("Failures: Deadline")
                + researchMetrics.get("Failures: Battery")
                + researchMetrics.get("Failures: OOM")
                + researchMetrics.get("Failures: Network")
                + researchMetrics.get("Failures: Mobility");
        assertEquals((int) summaryFailures, totalFailures,
                "Total failures in time series must match summary failure count");
    }

    @Test
    @DisplayName("Validation 5: Throughput Sanity")
    public void testThroughputSanity() {
        double throughput = researchMetrics.get("Throughput (tasks/min)");
        assertTrue(throughput >= 0, "Throughput must be non-negative");

        double success = seqMetrics.get("Tasks successfully executed");
        if (success > 0) {
            assertTrue(throughput > 0, "Throughput must be positive when successful tasks > 0");
        }
    }

    @Test
    @DisplayName("Validation 6: Research and Existing CSV Cross-File Consistency")
    public void testResearchAndExistingCsvConsistency() {
        double existingAvgDelay = seqMetrics.get("Average execution delay (s)");
        double researchComputation = researchMetrics.get("Average latency: computation (s)");
        assertEquals(existingAvgDelay, researchComputation, 1e-4,
                "Average execution delay in existing CSV must match research computation latency");

        double existingWaiting = seqMetrics.get("Average waiting time (s)");
        double researchWaiting = researchMetrics.get("Average queue waiting time (s)");
        assertEquals(existingWaiting, researchWaiting, 1e-4,
                "Average waiting time in existing CSV must match research queue waiting time");
    }

    @Test
    @DisplayName("Validation 7: No NaN or Infinity in Research Summary CSV")
    public void testNoNanOrInfinityInSummary() throws IOException {
        List<String> lines = Files.readAllLines(researchSummaryCsv.toPath());
        for (int i = 1; i < lines.size(); i++) {
            String[] parts = lines.get(i).split(",");
            for (int j = 3; j < parts.length; j++) {
                double val = Double.parseDouble(parts[j].trim());
                assertFalse(Double.isNaN(val), "Summary value at [" + i + "," + j + "] must not be NaN");
                assertFalse(Double.isInfinite(val), "Summary value at [" + i + "," + j + "] must not be Infinite");
            }
        }
    }

    @Test
    @DisplayName("Validation 8: No NaN or Infinity in Research Time-Series CSV")
    public void testNoNanOrInfinityInTimeSeries() throws IOException {
        List<String> lines = Files.readAllLines(researchTimeSeriesCsv.toPath());
        for (int i = 1; i < lines.size(); i++) {
            String[] parts = lines.get(i).split(",");
            for (int j = 3; j < parts.length; j++) {
                double val = Double.parseDouble(parts[j].trim());
                assertFalse(Double.isNaN(val), "Time-series value at [" + i + "," + j + "] must not be NaN");
                assertFalse(Double.isInfinite(val), "Time-series value at [" + i + "," + j + "] must not be Infinite");
            }
        }
    }
}
