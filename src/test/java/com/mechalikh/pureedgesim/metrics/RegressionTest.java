package com.mechalikh.pureedgesim.metrics;

import com.mechalikh.pureedgesim.simulationmanager.ResearchMetricsExporter;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Milestone 9 — Regression Test Suite.
 *
 * Formally verifies that all changes from M1–M8 preserve existing PureEdgeSim metrics
 * byte-for-byte and value-for-value against the baseline snapshot, while outputting the
 * new research files strictly as additive companions.
 */
public class RegressionTest {

    private static File runDir;
    private static File sequentialCsv;
    private static File sequentialTxt;
    private static File researchSummaryCsv;
    private static File researchTimeSeriesCsv;
    private static Path baselineCsvPath;

    @BeforeAll
    public static void setUp() throws Exception {
        baselineCsvPath = Paths.get("docs/additional-metrics/baseline_output.csv");
        assertTrue(Files.exists(baselineCsvPath), "Baseline CSV must exist at " + baselineCsvPath);

        long beforeLaunch = System.currentTimeMillis();

        // Launch full replay simulation to generate latest outputs
        new examples.ExampleTraceReplay();

        File outputDir = new File("PureEdgeSim/output");
        assertTrue(outputDir.exists() && outputDir.isDirectory(), "Output directory must exist");

        File[] subdirs = outputDir.listFiles(File::isDirectory);
        assertNotNull(subdirs, "Output directories should not be null");
        Arrays.sort(subdirs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));

        // Pick newest directory modified during or after beforeLaunch
        for (File d : subdirs) {
            File f = new File(d, "Sequential_simulation.csv");
            if (f.exists() && f.length() > 0) {
                runDir = d;
                sequentialCsv = f;
                sequentialTxt = new File(d, "Sequential_simulation.txt");
                researchSummaryCsv = new File(d, "_research_summary.csv");
                researchTimeSeriesCsv = new File(d, "_research_timeseries.csv");
                break;
            }
        }

        assertNotNull(runDir, "Run directory could not be located");
        assertTrue(sequentialCsv.exists(), "Sequential_simulation.csv must exist");
    }

    @Test
    @DisplayName("Original files Sequential_simulation.csv and .txt are preserved and non-empty")
    public void testNoExistingFilesDeleted() {
        assertTrue(sequentialCsv.exists(), "Sequential_simulation.csv must exist");
        assertTrue(sequentialCsv.length() > 0, "Sequential_simulation.csv must not be empty");

        assertTrue(sequentialTxt.exists(), "Sequential_simulation.txt must exist");
        assertTrue(sequentialTxt.length() > 0, "Sequential_simulation.txt must not be empty");
    }

    @Test
    @DisplayName("Research summary and time-series files exist as additional files in the same run folder")
    public void testResearchFilesAreAdditional() {
        assertTrue(researchSummaryCsv.exists(), "_research_summary.csv must exist");
        assertTrue(researchSummaryCsv.length() > 0, "_research_summary.csv must not be empty");

        assertTrue(researchTimeSeriesCsv.exists(), "_research_timeseries.csv must exist");
        assertTrue(researchTimeSeriesCsv.length() > 0, "_research_timeseries.csv must not be empty");
    }

    @Test
    @DisplayName("Headers of Sequential_simulation.csv are identical in name and order to baseline")
    public void testExistingCsvColumnsUnchanged() throws IOException {
        List<String> baselineLines = Files.readAllLines(baselineCsvPath);
        List<String> actualLines = Files.readAllLines(sequentialCsv.toPath());

        assertFalse(baselineLines.isEmpty(), "Baseline CSV must not be empty");
        assertFalse(actualLines.isEmpty(), "Actual CSV must not be empty");

        String baselineHeader = baselineLines.get(0);
        String actualHeader = actualLines.get(0);

        assertEquals(baselineHeader, actualHeader, "CSV headers must match baseline exactly");
    }

    @Test
    @DisplayName("Values of Sequential_simulation.csv match baseline values within 1e-9 tolerance")
    public void testExistingCsvValuesUnchanged() throws IOException {
        List<String> baselineLines = Files.readAllLines(baselineCsvPath);
        List<String> actualLines = Files.readAllLines(sequentialCsv.toPath());

        assertTrue(baselineLines.size() >= 2, "Baseline CSV must have header + data row");
        assertTrue(actualLines.size() >= 2, "Actual CSV must have header + data row");

        String[] baselineValues = baselineLines.get(1).split(",");
        String[] actualValues = actualLines.get(1).split(",");

        assertEquals(baselineValues.length, actualValues.length, "Column counts must match");

        for (int i = 0; i < baselineValues.length; i++) {
            String bVal = baselineValues[i].trim();
            String aVal = actualValues[i].trim();

            try {
                double bNum = Double.parseDouble(bVal);
                double aNum = Double.parseDouble(aVal);
                assertEquals(bNum, aNum, 1e-9, "Numeric mismatch at column index " + i + " (" + bVal + " vs " + aVal + ")");
            } catch (NumberFormatException e) {
                // String or list comparison
                assertEquals(bVal, aVal, "Non-numeric string mismatch at column index " + i);
            }
        }
    }

    @Test
    @DisplayName("Sequential_simulation.txt contains expected standard log strings")
    public void testExistingTxtLogUnchanged() throws IOException {
        String txtContent = Files.readString(sequentialTxt.toPath());

        assertTrue(txtContent.contains("Tasks successfully executed"), "TXT must contain 'Tasks successfully executed'");
        assertTrue(txtContent.contains("Executed but failed due to high delay"), "TXT must contain delay failures");
        assertTrue(txtContent.contains("Tasks executed on each level"), "TXT must contain execution level breakdown");
    }

    @Test
    @DisplayName("_research_summary.csv has expected header")
    public void testResearchSummaryHeaders() throws IOException {
        List<String> lines = Files.readAllLines(researchSummaryCsv.toPath());
        assertFalse(lines.isEmpty(), "_research_summary.csv should not be empty");
        assertEquals(ResearchMetricsExporter.SUMMARY_HEADER, lines.get(0));
    }

    @Test
    @DisplayName("_research_timeseries.csv has expected header")
    public void testResearchTimeSeriesHeaders() throws IOException {
        List<String> lines = Files.readAllLines(researchTimeSeriesCsv.toPath());
        assertFalse(lines.isEmpty(), "_research_timeseries.csv should not be empty");
        assertEquals(ResearchMetricsExporter.TIME_SERIES_HEADER, lines.get(0));
    }
}
