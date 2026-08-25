/**
 *     PureEdgeSim:  A Simulation Framework for Performance Evaluation of Cloud, Edge and Mist Computing Environments
 *
 *     This file is part of PureEdgeSim Project.
 *
 *     PureEdgeSim is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     PureEdgeSim is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with PureEdgeSim. If not, see <http://www.gnu.org/licenses/>.
 *
 *     @author CottonBuds (Thesis extension for Google Cluster Trace v3 replay)
 **/
package com.mechalikh.pureedgesim.taskgenerator;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.List;
import java.util.logging.Logger;

import com.mechalikh.pureedgesim.datacentersmanager.ComputingNode;
import com.mechalikh.pureedgesim.simulationengine.FutureQueue;
import com.mechalikh.pureedgesim.simulationmanager.SimulationManager;

/**
 * StreamedTraceTaskGenerator — Milestone 5 (M5)
 * ================================================
 * A memory-efficient, streaming task generator that replays Google Cluster
 * Trace v3 workloads inside PureEdgeSim without pre-loading the entire trace
 * into JVM heap memory.
 *
 * <p><b>Memory model:</b> O(N_buffer + N_active_in_flight) heap usage,
 * regardless of trace duration (15 min, 1 h, 12 h, or 24 h). The full trace
 * is never held in memory simultaneously.
 *
 * <p><b>Streaming strategy:</b>
 * <ol>
 *   <li>{@link #generate()} reads an initial buffer of {@code bufferSize} tasks
 *       from the JSON Lines file and populates {@link #taskList}.</li>
 *   <li>The {@code NEXT_BATCH} event in {@link TraceSimulationManager} calls
 *       {@link #refillIfNeeded()} whenever {@code taskList.size()} drops below
 *       the {@code lowWatermark}. This triggers a disk read of the next chunk
 *       until the buffer is full again.</li>
 *   <li>Completed tasks are GC'd automatically once the simulation engine
 *       removes them from the event queue.</li>
 * </ol>
 *
 * <p><b>Task field mapping (from pureedgesim_tasks_*.json):</b>
 * <pre>
 *   JSON field            → Task setter
 *   ─────────────────────────────────────────────────
 *   time                  → task.setTime()
 *   length                → task.setLength()
 *   fileSizeInBits        → task.setFileSizeInBits()
 *   outputSizeInBits      → task.setOutputSizeInBits()
 *   containerSizeInBits   → task.setContainerSizeInBits()
 *   maxLatency            → task.setMaxLatency()
 *   edgeDevice (index)    → task.setEdgeDevice(devicesList.get(idx))
 *   applicationID         → task.setApplicationID()
 *   metadata.priority     → stored in task type string for RL bridge
 * </pre>
 *
 * <p><b>Configuration properties</b> (read from simulation_parameters.properties
 * or set programmatically before calling {@link #generate()}):
 * <pre>
 *   trace.file.path   = data/processed/pureedgesim_tasks_15min.json
 *   trace.buffer.size = 1000   (N_buffer: refill target)
 *   trace.low.watermark = 200  (N_low: trigger threshold)
 * </pre>
 *
 * <p><b>Zero impact on Python Bridge:</b> This class produces standard
 * {@link DefaultTask} instances in chronological order. The
 * {@code PythonOrchestrator}, Unix Domain Socket IPC, and
 * {@code task_to_array()} feature vectorizer are entirely unaffected.
 *
 * @see TraceSimulationManager
 * @see DefaultTaskGenerator
 */
public class StreamedTraceTaskGenerator extends TaskGenerator {

    private static final Logger LOG = Logger.getLogger(StreamedTraceTaskGenerator.class.getName());

    // -------------------------------------------------------------------------
    // Default configuration (override via setters before generate())
    // -------------------------------------------------------------------------

    /** Path to the pureedgesim_tasks_*.json JSON Lines file (M4 output). */
    private String traceFilePath = "data/processed/pureedgesim_tasks_15min.json";

    /**
     * Buffer target size (N_buffer).
     * The number of Task objects held in memory at any one time.
     * Memory footprint ≈ N_buffer × ~800 bytes ≈ ~800 KB for N_buffer=1000.
     */
    private int bufferSize = 1000;

    /**
     * Low-watermark threshold (N_low).
     * When taskList.size() < lowWatermark, a disk read is triggered to refill
     * to bufferSize. Must satisfy: 0 < lowWatermark < bufferSize.
     */
    private int lowWatermark = 200;

    // -------------------------------------------------------------------------
    // Internal state
    // -------------------------------------------------------------------------

    /** Buffered file reader kept open for the lifetime of the simulation. */
    private BufferedReader traceReader = null;

    /** Whether the trace file has been fully consumed. */
    private boolean traceExhausted = false;

    /** Running task ID counter (1-based, monotonically increasing). */
    private int nextTaskId = 1;

    /** Total tasks loaded from file (for logging and validation). */
    private long totalTasksLoaded = 0;

    /** Total tasks skipped due to parse errors or missing devices. */
    private long totalTasksSkipped = 0;

    // -------------------------------------------------------------------------
    // Constructor
    // -------------------------------------------------------------------------

    /**
     * Creates a new StreamedTraceTaskGenerator.
     *
     * @param simulationManager the PureEdgeSim simulation manager
     */
    public StreamedTraceTaskGenerator(SimulationManager simulationManager) {
        super(simulationManager);
    }

    // -------------------------------------------------------------------------
    // Configuration setters (call before generate())
    // -------------------------------------------------------------------------

    /**
     * Sets the path to the JSON Lines trace file produced by M4 translate script.
     *
     * @param path absolute or relative path to pureedgesim_tasks_*.json
     * @return this generator (builder pattern)
     */
    public StreamedTraceTaskGenerator setTraceFilePath(String path) {
        this.traceFilePath = path;
        return this;
    }

    /**
     * Sets the buffer target size (N_buffer).
     * Controls the maximum number of Task objects in JVM heap at once.
     *
     * @param size buffer size (recommended: 500–2000)
     * @return this generator (builder pattern)
     */
    public StreamedTraceTaskGenerator setBufferSize(int size) {
        if (size <= 0) throw new IllegalArgumentException("bufferSize must be > 0");
        this.bufferSize = size;
        return this;
    }

    /**
     * Sets the low-watermark threshold (N_low).
     * A disk read is triggered when taskList.size() drops below this value.
     *
     * @param watermark low watermark (must be 0 < watermark < bufferSize)
     * @return this generator (builder pattern)
     */
    public StreamedTraceTaskGenerator setLowWatermark(int watermark) {
        if (watermark <= 0) throw new IllegalArgumentException("lowWatermark must be > 0");
        this.lowWatermark = watermark;
        return this;
    }

    // -------------------------------------------------------------------------
    // TaskGenerator contract
    // -------------------------------------------------------------------------

    /**
     * Opens the trace file and loads the initial buffer of tasks into
     * {@link #taskList}. This method satisfies the {@link TaskGenerator#generate()}
     * contract and is called once during simulation initialization.
     *
     * <p>Only {@code bufferSize} tasks are instantiated — the remainder stay on
     * disk until needed by {@link #refillIfNeeded()}.
     *
     * @return the initially populated {@link FutureQueue} of tasks
     */
    @Override
    public FutureQueue<Task> generate() {
        LOG.info(String.format(
            "[StreamedTraceTaskGenerator] Opening trace: %s  (buffer=%d, lowWatermark=%d)",
            traceFilePath, bufferSize, lowWatermark));

        openTraceFile();

        // Load the first window of tasks
        int loaded = readNextChunk(bufferSize);

        LOG.info(String.format(
            "[StreamedTraceTaskGenerator] Initial buffer loaded: %d tasks (trace exhausted=%s)",
            loaded, traceExhausted));

        if (taskList.isEmpty()) {
            throw new RuntimeException(
                "[StreamedTraceTaskGenerator] No tasks loaded from trace file: " + traceFilePath +
                "\n  Run M4 first: python3 python/scripts/translate_to_pureedgesim.py");
        }

        return taskList;
    }

    // -------------------------------------------------------------------------
    // Streaming / refill API (called by TraceSimulationManager on NEXT_BATCH)
    // -------------------------------------------------------------------------

    /**
     * Checks whether the task buffer needs refilling and reads the next chunk
     * from disk if required.
     *
     * <p>This method is called by {@link TraceSimulationManager} on every
     * {@code NEXT_BATCH} event, after the standard batch scheduling loop.
     * It is the core of the sliding-window streaming mechanism.
     *
     * @return the number of new tasks added to taskList, or 0 if not needed
     *         or trace is exhausted
     */
    public int refillIfNeeded() {
        if (traceExhausted) return 0;
        if (taskList.size() >= lowWatermark) return 0;

        int needed = bufferSize - taskList.size();
        int added  = readNextChunk(needed);

        if (added > 0) {
            LOG.fine(String.format(
                "[StreamedTraceTaskGenerator] Refilled %d tasks (buffer now=%d, exhausted=%s)",
                added, taskList.size(), traceExhausted));
        }

        return added;
    }

    /**
     * Returns {@code true} if all tasks from the trace file have been loaded.
     * Once exhausted, {@link #refillIfNeeded()} is a no-op.
     *
     * @return true when trace file is fully consumed
     */
    public boolean isTraceExhausted() {
        return traceExhausted;
    }

    /**
     * Returns the total number of tasks successfully loaded from the trace file.
     *
     * @return total tasks loaded
     */
    public long getTotalTasksLoaded() {
        return totalTasksLoaded;
    }

    /**
     * Returns the total number of tasks skipped (parse errors, missing devices).
     *
     * @return total tasks skipped
     */
    public long getTotalTasksSkipped() {
        return totalTasksSkipped;
    }

    // -------------------------------------------------------------------------
    // Internal implementation
    // -------------------------------------------------------------------------

    /**
     * Opens the trace file for streaming. Keeps the {@link BufferedReader} open
     * for the entire simulation lifetime for efficient sequential access.
     */
    private void openTraceFile() {
        try {
            traceReader = new BufferedReader(new FileReader(traceFilePath));
            traceExhausted = false;
        } catch (IOException e) {
            throw new RuntimeException(
                "[StreamedTraceTaskGenerator] Cannot open trace file: " + traceFilePath, e);
        }
    }

    /**
     * Reads up to {@code count} task records from the trace file and adds them
     * to {@link #taskList}.
     *
     * <p>Each line in the file is a self-contained JSON object (JSON Lines format)
     * matching the schema produced by M4 {@code translate_to_pureedgesim.py}.
     *
     * @param count maximum number of records to read in this call
     * @return actual number of tasks added to taskList
     */
    private int readNextChunk(int count) {
        if (traceReader == null || traceExhausted) return 0;

        int added = 0;
        try {
            String line;
            while (added < count && (line = traceReader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                Task task = parseTaskRecord(line);
                if (task != null) {
                    taskList.add(task);
                    added++;
                    totalTasksLoaded++;
                } else {
                    totalTasksSkipped++;
                }
            }

            // If we couldn't read the requested count, the file is exhausted
            if (added < count) {
                traceExhausted = true;
                closeTraceFile();
                LOG.info(String.format(
                    "[StreamedTraceTaskGenerator] Trace fully consumed. " +
                    "Total loaded=%d, skipped=%d",
                    totalTasksLoaded, totalTasksSkipped));
            }

        } catch (IOException e) {
            LOG.warning("[StreamedTraceTaskGenerator] IO error reading trace: " + e.getMessage());
            traceExhausted = true;
            closeTraceFile();
        }

        return added;
    }

    /**
     * Parses a single JSON Lines record from the M4 output file into a
     * {@link DefaultTask}.
     *
     * <p>This uses a hand-written parser to avoid adding a JSON library
     * dependency to the PureEdgeSim core. The JSON Lines format is simple and
     * well-defined — all fields are at the top level or in a flat "metadata"
     * object.
     *
     * <p>Field extraction uses string scanning rather than a full JSON tree
     * to keep GC pressure low for large traces.
     *
     * @param line a single JSON Lines record
     * @return a populated {@link Task}, or {@code null} on parse error
     */
    private Task parseTaskRecord(String line) {
        try {
            // ── Core fields ───────────────────────────────────────────────────
            double time               = extractDouble(line, "\"time\"");
            long   length             = (long) extractDouble(line, "\"length\"");
            long   fileSizeInBits     = (long) extractDouble(line, "\"fileSizeInBits\"");
            long   outputSizeInBits   = (long) extractDouble(line, "\"outputSizeInBits\"");
            long   containerSizeInBits= (long) extractDouble(line, "\"containerSizeInBits\"");
            double maxLatency         = extractDouble(line, "\"maxLatency\"");
            int    edgeDeviceIdx      = (int) extractDouble(line, "\"edgeDevice\"");
            int    applicationID      = (int) extractDouble(line, "\"applicationID\"");

            // ── Resolve edge device from index ────────────────────────────────
            // When devicesList is empty (unit-test mode), fall back to ComputingNode.NULL.
            List<ComputingNode> devices = devicesList;
            ComputingNode device;
            if (devices == null || devices.isEmpty()) {
                device = ComputingNode.NULL;
            } else {
                int clampedIdx = Math.max(0, Math.min(edgeDeviceIdx, devices.size() - 1));
                device = devices.get(clampedIdx);
            }

            // ── Registry node (cloud node 0, as per DefaultTaskGenerator) ─────
            // When SimulationManager is null (unit-test mode), fall back to ComputingNode.NULL.
            ComputingNode registry;
            if (getSimulationManager() != null) {
                registry = getSimulationManager()
                        .getDataCentersManager()
                        .getComputingNodesGenerator()
                        .getCloudOnlyList()
                        .get(0);
            } else {
                registry = ComputingNode.NULL;
            }

            // ── Metadata: encode priority/scheduling_class in task type string ─
            // Format: "SC<class>_P<priority>_<finish_status>"
            // The PythonOrchestrator parses this via task.getType() for RL features.
            String metadataType = extractMetadataType(line);

            // ── Construct Task ────────────────────────────────────────────────
            Task task = createTask(nextTaskId++);
            if (task == null) return null;

            task.setTime(time);
            task.setLength((double) length);
            task.setFileSizeInBits(fileSizeInBits);
            task.setOutputSizeInBits(outputSizeInBits);
            task.setContainerSizeInBits(containerSizeInBits);
            task.setMaxLatency(maxLatency);
            task.setEdgeDevice(device);
            task.setApplicationID(applicationID);
            task.setRegistry(registry);
            task.setType(metadataType);

            return task;

        } catch (Exception e) {
            LOG.fine("[StreamedTraceTaskGenerator] Skipping malformed record: " + e.getMessage()
                    + "\n  Line: " + line.substring(0, Math.min(120, line.length())));
            return null;
        }
    }

    /**
     * Builds a compact type string from the metadata block for RL feature access.
     * Format: "SC{scheduling_class}_P{priority}_{finish_status}"
     * Example: "SC1_P200_KILL"
     *
     * <p>This is the bridge between the trace metadata and PureEdgeSim's
     * {@code task.getType()} accessor, which the Python bridge reads via
     * the {@code task_to_array()} feature vectorizer.
     *
     * @param line raw JSON Lines record
     * @return compact metadata type string
     */
    private String extractMetadataType(String line) {
        try {
            int sc     = (int) extractDouble(line, "\"scheduling_class\"");
            int pri    = (int) extractDouble(line, "\"priority\"");
            String fin = extractString(line, "\"finish_status\"");
            if (fin == null || fin.isEmpty()) fin = "UNKNOWN";
            return String.format("SC%d_P%d_%s", sc, pri, fin);
        } catch (Exception e) {
            return "SC0_P0_UNKNOWN";
        }
    }

    // -------------------------------------------------------------------------
    // Minimal JSON field extractors (no external dependency)
    // -------------------------------------------------------------------------

    /**
     * Extracts a numeric (double) value from a JSON string by field name.
     * Handles integers, decimals, and scientific notation.
     *
     * @param json      the raw JSON string
     * @param fieldName field key including quotes, e.g. {@code "\"time\""}
     * @return parsed double value
     * @throws IllegalArgumentException if the field is not found
     */
    private static double extractDouble(String json, String fieldName) {
        int keyIdx = json.indexOf(fieldName);
        if (keyIdx < 0)
            throw new IllegalArgumentException("Field not found: " + fieldName);

        // Skip past the key and colon (possibly with whitespace)
        int colonIdx = json.indexOf(':', keyIdx + fieldName.length());
        if (colonIdx < 0)
            throw new IllegalArgumentException("No colon after field: " + fieldName);

        // Skip whitespace
        int start = colonIdx + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;

        // Read until separator (comma, closing brace, or closing bracket)
        int end = start;
        while (end < json.length()) {
            char c = json.charAt(end);
            if (c == ',' || c == '}' || c == ']') break;
            end++;
        }

        String numStr = json.substring(start, end).trim();
        return Double.parseDouble(numStr);
    }

    /**
     * Extracts a string value from a JSON object by field name.
     * Returns the value without surrounding quotes.
     *
     * @param json      the raw JSON string
     * @param fieldName field key including quotes, e.g. {@code "\"finish_status\""}
     * @return string value, or {@code null} if not found
     */
    private static String extractString(String json, String fieldName) {
        int keyIdx = json.indexOf(fieldName);
        if (keyIdx < 0) return null;

        int colonIdx = json.indexOf(':', keyIdx + fieldName.length());
        if (colonIdx < 0) return null;

        // Skip whitespace
        int start = colonIdx + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;

        if (start >= json.length() || json.charAt(start) != '"') return null;
        start++; // skip opening quote

        int end = json.indexOf('"', start);
        if (end < 0) return null;

        return json.substring(start, end);
    }

    /**
     * Closes the trace file reader. Called when the trace is exhausted or the
     * simulation ends abnormally.
     */
    private void closeTraceFile() {
        if (traceReader != null) {
            try {
                traceReader.close();
            } catch (IOException e) {
                LOG.fine("[StreamedTraceTaskGenerator] Error closing trace reader: " + e.getMessage());
            } finally {
                traceReader = null;
            }
        }
    }

    /**
     * Creates a new Task instance using the configured task class.
     * Identical to {@link DefaultTaskGenerator#createTask(int)}.
     *
     * @param id the task ID
     * @return new Task instance, or {@code null} on reflection failure
     */
    private Task createTask(int id) {
        try {
            return taskClass.getConstructor(int.class).newInstance(id);
        } catch (Exception e) {
            LOG.warning("[StreamedTraceTaskGenerator] Failed to instantiate task: " + e.getMessage());
            return null;
        }
    }
}
