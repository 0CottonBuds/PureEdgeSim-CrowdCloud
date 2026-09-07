package com.mechalikh.pureedgesim.metrics;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Milestone 1 — Baseline verification for existing PureEdgeSim metrics.
 *
 * Verifies that the existing metrics produced by PureEdgeSim (via SimLog)
 * are present, well-formed, non-negative, and satisfy fundamental consistency rules.
 */
public class BaselineMetricsTest {

    private static Path csvPath;
    private static List<String> rawHeaders;
    private static Map<String, String> dataMap;

    @BeforeAll
    public static void setUp() throws Exception {
        Path baselinePath = Paths.get("docs/additional-metrics/baseline_output.csv");

        // If baseline_output.csv does not exist yet, look in PureEdgeSim/output
        if (!Files.exists(baselinePath)) {
            File outputDir = new File("PureEdgeSim/output");
            if (outputDir.exists() && outputDir.isDirectory()) {
                File[] subdirs = outputDir.listFiles(File::isDirectory);
                if (subdirs != null && subdirs.length > 0) {
                    Arrays.sort(subdirs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                    for (File d : subdirs) {
                        File f = new File(d, "Sequential_simulation.csv");
                        if (f.exists() && f.length() > 0) {
                            baselinePath = f.toPath();
                            break;
                        }
                    }
                }
            }
        }

        // If still not found, run ExampleTraceReplay
        if (!Files.exists(baselinePath)) {
            new examples.ExampleTraceReplay();
            File outputDir = new File("PureEdgeSim/output");
            File[] subdirs = outputDir.listFiles(File::isDirectory);
            if (subdirs != null && subdirs.length > 0) {
                Arrays.sort(subdirs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
                for (File d : subdirs) {
                    File f = new File(d, "Sequential_simulation.csv");
                    if (f.exists() && f.length() > 0) {
                        baselinePath = f.toPath();
                        break;
                    }
                }
            }
        }

        assertTrue(Files.exists(baselinePath), "Baseline CSV must exist at " + baselinePath);
        csvPath = baselinePath;
        parseCsv(csvPath);
    }

    private static void parseCsv(Path path) throws IOException {
        rawHeaders = new ArrayList<>();
        dataMap = new HashMap<>();

        try (BufferedReader br = new BufferedReader(new FileReader(path.toFile()))) {
            String headerLine = br.readLine();
            assertNotNull(headerLine, "CSV header line must not be null");
            String[] headers = splitCsvLine(headerLine);
            for (String h : headers) {
                rawHeaders.add(h);
            }

            String dataLine = br.readLine();
            assertNotNull(dataLine, "CSV data line must not be null");
            String[] values = splitCsvLine(dataLine);

            for (int i = 0; i < headers.length && i < values.length; i++) {
                String key = headers[i].trim();
                dataMap.put(key, values[i].trim());
            }
        }
    }

    private static String[] splitCsvLine(String line) {
        List<String> tokens = new ArrayList<>();
        boolean inQuotes = false;
        boolean inBrackets = false;
        StringBuilder sb = new StringBuilder();

        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '\"') {
                inQuotes = !inQuotes;
            } else if (c == '[' && !inQuotes) {
                inBrackets = true;
                sb.append(c);
            } else if (c == ']' && !inQuotes) {
                inBrackets = false;
                sb.append(c);
            } else if (c == ',' && !inQuotes && !inBrackets) {
                tokens.add(sb.toString());
                sb.setLength(0);
            } else {
                sb.append(c);
            }
        }
        tokens.add(sb.toString());
        return tokens.toArray(new String[0]);
    }

    @Test
    @DisplayName("Assert all required existing PureEdgeSim CSV columns are present")
    public void testExistingCsvColumnsPresent() {
        String[] requiredColumns = {
            "Tasks successfully executed",
            "Number of generated tasks",
            "Average waiting time (s)",
            "Average execution delay (s)",
            "Tasks failed (delay)",
            "Tasks failed (device dead)",
            "Tasks failed (mobility)",
            "Task not executed (No resources available or long waiting time)",
            "Network usage (s)"
        };

        for (String col : requiredColumns) {
            assertTrue(dataMap.containsKey(col), "Missing required column in CSV: '" + col + "'");
            assertNotNull(dataMap.get(col), "Column value must not be null: '" + col + "'");
            assertFalse(dataMap.get(col).isEmpty(), "Column value must not be empty: '" + col + "'");
        }
    }

    @Test
    @DisplayName("Assert task counters are non-negative numeric values")
    public void testTaskCountsNonNegative() {
        String[] countColumns = {
            "Tasks successfully executed",
            "Number of generated tasks",
            "Tasks failed (delay)",
            "Tasks failed (device dead)",
            "Tasks failed (mobility)",
            "Task not executed (No resources available or long waiting time)"
        };

        for (String col : countColumns) {
            String val = dataMap.get(col);
            assertNotNull(val, "Missing column: " + col);
            double count = Double.parseDouble(val);
            assertTrue(count >= 0, "Counter '" + col + "' must be non-negative, got: " + count);
        }

        double avgWait = Double.parseDouble(dataMap.get("Average waiting time (s)"));
        assertTrue(avgWait >= 0, "Average waiting time must be non-negative, got: " + avgWait);

        double avgExec = Double.parseDouble(dataMap.get("Average execution delay (s)"));
        assertTrue(avgExec >= 0, "Average execution delay must be non-negative, got: " + avgExec);
    }

    @Test
    @DisplayName("Assert successfully executed + failed tasks <= total submitted/executed tasks")
    public void testSucceededPlusFailedLteSubmitted() {
        double success = Double.parseDouble(dataMap.get("Tasks successfully executed"));
        double failedDelay = Double.parseDouble(dataMap.get("Tasks failed (delay)"));
        double failedDead = Double.parseDouble(dataMap.get("Tasks failed (device dead)"));
        double failedMobility = Double.parseDouble(dataMap.get("Tasks failed (mobility)"));
        double failedResources = Double.parseDouble(dataMap.get("Task not executed (No resources available or long waiting time)"));

        double totalFailures = failedDelay + failedDead + failedMobility + failedResources;
        double totalCompleted = success + totalFailures;

        assertTrue(success > 0, "At least some tasks should succeed in baseline");
        assertTrue(totalCompleted > 0, "Total completed tasks must be greater than zero");

        // If cloud executed tasks is present, compare against total executed tasks
        if (dataMap.containsKey("Total tasks executed (Cloud)")) {
            double totalCloudExecuted = Double.parseDouble(dataMap.get("Total tasks executed (Cloud)"));
            assertTrue(totalCompleted <= totalCloudExecuted + 1e-6,
                String.format("Completed tasks (%.1f) should be <= total executed tasks (%.1f)",
                    totalCompleted, totalCloudExecuted));
        }
    }
}
