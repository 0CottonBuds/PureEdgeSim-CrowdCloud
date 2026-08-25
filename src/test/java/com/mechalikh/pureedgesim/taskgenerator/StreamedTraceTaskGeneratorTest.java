package com.mechalikh.pureedgesim.taskgenerator;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit and streaming-integration tests for {@link StreamedTraceTaskGenerator}.
 *
 * <p>All tests that need a {@link StreamedTraceTaskGenerator} instance use
 * {@link #makeGenerator()} which bypasses the {@link TaskGenerator} constructor's
 * live {@code SimulationManager} dependency via {@code sun.misc.Unsafe}.
 * This is the standard approach for testing classes whose constructors have
 * mandatory framework dependencies.
 *
 * <p><b>Test catalogue:</b>
 * <ul>
 *   <li>T1–T5   : {@code extractDouble} — integers, decimals, scientific notation, missing</li>
 *   <li>T6–T7   : {@code extractString} — present and missing</li>
 *   <li>T8      : {@code extractMetadataType} — SC_P_STATUS format</li>
 *   <li>T9–T10  : Initial buffer load size</li>
 *   <li>T11     : Full drain — total count == records in file, no duplicates</li>
 *   <li>T12     : Exhaustion flag becomes true after last record</li>
 *   <li>T13     : refillIfNeeded returns 0 when exhausted</li>
 *   <li>T14     : Malformed lines skipped, skipped counter incremented</li>
 *   <li>T15     : Missing file throws RuntimeException</li>
 *   <li>T16     : Chunk boundary — N tasks across M chunks, all accounted for</li>
 * </ul>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class StreamedTraceTaskGeneratorTest {

    // =========================================================================
    // Fixture helpers
    // =========================================================================

    /**
     * Creates a {@link StreamedTraceTaskGenerator} instance without going through
     * {@link TaskGenerator#TaskGenerator(com.mechalikh.pureedgesim.simulationmanager.SimulationManager)}
     * which would NPE on a null SimulationManager.
     *
     * <p>Uses {@code sun.misc.Unsafe.allocateInstance()} to skip the constructor,
     * then manually initialises the fields that the streaming logic actually reads.
     */
    @SuppressWarnings("unchecked")
    private static StreamedTraceTaskGenerator makeGenerator() throws Exception {
        // Allocate without constructor
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        Method allocate = unsafeClass.getMethod("allocateInstance", Class.class);
        StreamedTraceTaskGenerator gen =
                (StreamedTraceTaskGenerator) allocate.invoke(unsafe, StreamedTraceTaskGenerator.class);

        // Initialise taskClass (protected in TaskGenerator)
        Field taskClassField = gen.getClass().getSuperclass().getDeclaredField("taskClass");
        taskClassField.setAccessible(true);
        taskClassField.set(gen, DefaultTask.class);

        // Initialise taskList (accessed by readNextChunk via TaskGenerator.taskList)
        Field taskListField = gen.getClass().getSuperclass().getDeclaredField("taskList");
        taskListField.setAccessible(true);
        taskListField.set(gen, new com.mechalikh.pureedgesim.simulationengine.FutureQueue<>());

        // Initialise devicesList
        Field devicesListField = gen.getClass().getSuperclass().getDeclaredField("devicesList");
        devicesListField.setAccessible(true);
        devicesListField.set(gen, new java.util.ArrayList<>());

        // Initialise private fields with defaults
        setPrivate(gen, "traceFilePath",  "");
        setPrivate(gen, "bufferSize",     1000);
        setPrivate(gen, "lowWatermark",   200);
        setPrivate(gen, "traceReader",    null);
        setPrivate(gen, "traceExhausted", false);
        setPrivate(gen, "nextTaskId",     1);
        setPrivate(gen, "totalTasksLoaded", 0L);
        setPrivate(gen, "totalTasksSkipped", 0L);

        return gen;
    }

    private static void setPrivate(Object obj, String name, Object value) throws Exception {
        // Search both declared class and all superclasses
        Class<?> cls = obj.getClass();
        while (cls != null) {
            try {
                Field f = cls.getDeclaredField(name);
                f.setAccessible(true);
                f.set(obj, value);
                return;
            } catch (NoSuchFieldException e) {
                cls = cls.getSuperclass();
            }
        }
        throw new NoSuchFieldException(name + " not found in class hierarchy");
    }

    // ── Reflection-based access to private methods ───────────────────────────

    private static double extractDouble(String json, String fieldName) throws Exception {
        Method m = StreamedTraceTaskGenerator.class
                .getDeclaredMethod("extractDouble", String.class, String.class);
        m.setAccessible(true);
        return (double) m.invoke(null, json, fieldName);
    }

    private static String extractString(String json, String fieldName) throws Exception {
        Method m = StreamedTraceTaskGenerator.class
                .getDeclaredMethod("extractString", String.class, String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, json, fieldName);
    }

    private static String extractMetadataType(StreamedTraceTaskGenerator gen, String json)
            throws Exception {
        Method m = StreamedTraceTaskGenerator.class
                .getDeclaredMethod("extractMetadataType", String.class);
        m.setAccessible(true);
        return (String) m.invoke(gen, json);
    }

    private static void openTraceFile(StreamedTraceTaskGenerator gen) throws Exception {
        Method m = StreamedTraceTaskGenerator.class.getDeclaredMethod("openTraceFile");
        m.setAccessible(true);
        try {
            m.invoke(gen);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    private static int readNextChunk(StreamedTraceTaskGenerator gen, int count) throws Exception {
        Method m = StreamedTraceTaskGenerator.class
                .getDeclaredMethod("readNextChunk", int.class);
        m.setAccessible(true);
        try {
            return (int) m.invoke(gen, count);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw (Exception) e.getCause();
        }
    }

    // ── JSON fixture ─────────────────────────────────────────────────────────

    /** A complete, valid task record matching the M4 output schema exactly. */
    private static final String VALID_LINE =
        "{\"id\":1,\"time\":0.077407,\"length\":4442," +
        "\"fileSizeInBits\":1348147723,\"outputSizeInBits\":207123319," +
        "\"containerSizeInBits\":4630511857,\"maxLatency\":127.313564444," +
        "\"edgeDevice\":4,\"applicationID\":0," +
        "\"metadata\":{\"priority\":200,\"scheduling_class\":1," +
        "\"finish_status\":\"KILL\",\"collection_logical_name\":\"VRgko\"," +
        "\"collection_id\":375000665669,\"instance_index\":0," +
        "\"delta_t_exec_s\":55.89376,\"delta_t_queue_s\":0.055534," +
        "\"req_cpus\":0.039733887,\"req_memory\":0.008422852," +
        "\"slack_factor\":1.277778}}";

    /**
     * Writes N synthetic task records to a temp file.
     * Each record has a monotonically increasing t_arrival.
     */
    private Path writeFixtureFile(Path dir, int count, String filename) throws IOException {
        Path file = dir.resolve(filename);
        try (BufferedWriter w = Files.newBufferedWriter(file)) {
            for (int i = 1; i <= count; i++) {
                double time = i * 0.5;
                w.write(String.format(
                    "{\"id\":%d,\"time\":%.3f,\"length\":1000," +
                    "\"fileSizeInBits\":1000000,\"outputSizeInBits\":100000," +
                    "\"containerSizeInBits\":10000000,\"maxLatency\":60.0," +
                    "\"edgeDevice\":0,\"applicationID\":0," +
                    "\"metadata\":{\"priority\":100,\"scheduling_class\":1," +
                    "\"finish_status\":\"FINISH\",\"collection_logical_name\":\"test\"," +
                    "\"collection_id\":%d,\"instance_index\":0," +
                    "\"delta_t_exec_s\":30.0,\"delta_t_queue_s\":1.0," +
                    "\"req_cpus\":0.01,\"req_memory\":0.001," +
                    "\"slack_factor\":1.0}}%n",
                    i, time, (long) i));
            }
        }
        return file;
    }

    // =========================================================================
    // T1–T5: extractDouble
    // =========================================================================

    @Test @Order(1)
    @DisplayName("T1: extractDouble reads integer field")
    void t1_extractDoubleInteger() throws Exception {
        assertEquals(1.0, extractDouble(VALID_LINE, "\"id\""), 1e-9);
    }

    @Test @Order(2)
    @DisplayName("T2: extractDouble reads decimal field")
    void t2_extractDoubleDecimal() throws Exception {
        assertEquals(0.077407, extractDouble(VALID_LINE, "\"time\""), 1e-9);
    }

    @Test @Order(3)
    @DisplayName("T3: extractDouble reads scientific notation")
    void t3_extractDoubleScientific() throws Exception {
        String json = "{\"bytes\":1.5e6}";
        assertEquals(1_500_000.0, extractDouble(json, "\"bytes\""), 1.0);
    }

    @Test @Order(4)
    @DisplayName("T4: extractDouble throws on missing field")
    void t4_extractDoubleMissing() {
        assertThrows(Exception.class, () -> extractDouble("{\"other\":42}", "\"notThere\""));
    }

    @Test @Order(5)
    @DisplayName("T5: extractDouble reads large long value (containerSizeInBits)")
    void t5_extractDoubleLargeValue() throws Exception {
        double val = extractDouble(VALID_LINE, "\"containerSizeInBits\"");
        assertEquals(4630511857L, (long) val);
    }

    // =========================================================================
    // T6–T7: extractString
    // =========================================================================

    @Test @Order(6)
    @DisplayName("T6: extractString returns correct value")
    void t6_extractStringPresent() throws Exception {
        assertEquals("KILL", extractString(VALID_LINE, "\"finish_status\""));
    }

    @Test @Order(7)
    @DisplayName("T7: extractString returns null for missing field")
    void t7_extractStringMissing() throws Exception {
        assertNull(extractString(VALID_LINE, "\"nonexistent\""));
    }

    // =========================================================================
    // T8: extractMetadataType
    // =========================================================================

    @Test @Order(8)
    @DisplayName("T8: extractMetadataType produces SC{sc}_P{pri}_{status}")
    void t8_extractMetadataType() throws Exception {
        StreamedTraceTaskGenerator gen = makeGenerator();
        // scheduling_class=1, priority=200, finish_status=KILL
        assertEquals("SC1_P200_KILL", extractMetadataType(gen, VALID_LINE));
    }

    // =========================================================================
    // T9–T10: Buffer loading
    // =========================================================================

    @Test @Order(9)
    @DisplayName("T9: readNextChunk loads exactly N tasks from file (N < total)")
    void t9_initialBufferSize(@TempDir Path tmpDir) throws Exception {
        int total = 500;
        int chunk = 200;
        Path file = writeFixtureFile(tmpDir, total, "t9.json");

        StreamedTraceTaskGenerator gen = makeGenerator();
        gen.setTraceFilePath(file.toString());

        openTraceFile(gen);
        int loaded = readNextChunk(gen, chunk);

        assertEquals(chunk, loaded, "Should load exactly chunk size tasks");
        assertFalse(gen.isTraceExhausted(), "Not exhausted yet (500 total, only 200 read)");
        assertEquals(chunk, gen.getTotalTasksLoaded());
        assertEquals(0, gen.getTotalTasksSkipped());
    }

    @Test @Order(10)
    @DisplayName("T10: Reading past end loads remaining tasks, sets exhausted")
    void t10_readPastEnd(@TempDir Path tmpDir) throws Exception {
        int total = 47;
        Path file = writeFixtureFile(tmpDir, total, "t10.json");

        StreamedTraceTaskGenerator gen = makeGenerator();
        gen.setTraceFilePath(file.toString());

        openTraceFile(gen);
        int loaded = readNextChunk(gen, 1000); // ask for more than exist

        assertEquals(total, loaded, "Should load all " + total + " tasks");
        assertTrue(gen.isTraceExhausted(), "Must be exhausted after reading past end");
        assertEquals(total, gen.getTotalTasksLoaded());
    }

    // =========================================================================
    // T11: Full drain — all records loaded, none duplicated
    // =========================================================================

    @Test @Order(11)
    @DisplayName("T11: Full drain: totalTasksLoaded == N, zero skipped")
    void t11_fullDrain(@TempDir Path tmpDir) throws Exception {
        int total = 750;
        int chunkSize = 200;
        Path file = writeFixtureFile(tmpDir, total, "t11.json");

        StreamedTraceTaskGenerator gen = makeGenerator();
        gen.setTraceFilePath(file.toString());

        openTraceFile(gen);

        long totalLoaded = 0;
        while (!gen.isTraceExhausted()) {
            totalLoaded += readNextChunk(gen, chunkSize);
        }

        assertEquals(total, totalLoaded, "Every record must be loaded exactly once");
        assertEquals(total, gen.getTotalTasksLoaded());
        assertEquals(0, gen.getTotalTasksSkipped());
    }

    // =========================================================================
    // T12: Exhaustion flag
    // =========================================================================

    @Test @Order(12)
    @DisplayName("T12: isTraceExhausted becomes true after final record")
    void t12_exhaustionFlag(@TempDir Path tmpDir) throws Exception {
        Path file = writeFixtureFile(tmpDir, 10, "t12.json");

        StreamedTraceTaskGenerator gen = makeGenerator();
        gen.setTraceFilePath(file.toString());

        openTraceFile(gen);
        assertFalse(gen.isTraceExhausted(), "Not exhausted before reading");

        readNextChunk(gen, 100);
        assertTrue(gen.isTraceExhausted(), "Must be exhausted after reading all records");
    }

    // =========================================================================
    // T13: refillIfNeeded returns 0 when exhausted
    // =========================================================================

    @Test @Order(13)
    @DisplayName("T13: refillIfNeeded returns 0 when trace is exhausted")
    void t13_refillNoOpWhenExhausted(@TempDir Path tmpDir) throws Exception {
        Path file = writeFixtureFile(tmpDir, 5, "t13.json");

        StreamedTraceTaskGenerator gen = makeGenerator();
        gen.setTraceFilePath(file.toString())
           .setBufferSize(100)
           .setLowWatermark(1);

        openTraceFile(gen);
        readNextChunk(gen, 100); // exhaust
        assertTrue(gen.isTraceExhausted());

        assertEquals(0, gen.refillIfNeeded(), "refillIfNeeded must return 0 when exhausted");
    }

    // =========================================================================
    // T14: Malformed line skipping
    // =========================================================================

    @Test @Order(14)
    @DisplayName("T14: Malformed lines skipped; valid lines loaded; skipped counter correct")
    void t14_malformedLineSkipped(@TempDir Path tmpDir) throws IOException, Exception {
        Path file = tmpDir.resolve("t14_malformed.json");
        try (BufferedWriter w = Files.newBufferedWriter(file)) {
            // Valid
            w.write("{\"id\":1,\"time\":0.5,\"length\":1000," +
                "\"fileSizeInBits\":1000,\"outputSizeInBits\":100," +
                "\"containerSizeInBits\":10000,\"maxLatency\":60.0," +
                "\"edgeDevice\":0,\"applicationID\":0," +
                "\"metadata\":{\"priority\":100,\"scheduling_class\":1," +
                "\"finish_status\":\"FINISH\",\"collection_logical_name\":\"\"," +
                "\"collection_id\":1,\"instance_index\":0," +
                "\"delta_t_exec_s\":30.0,\"delta_t_queue_s\":1.0," +
                "\"req_cpus\":0.01,\"req_memory\":0.001,\"slack_factor\":1.0}}\n");
            // Malformed
            w.write("NOT JSON AT ALL\n");
            // Valid
            w.write("{\"id\":2,\"time\":1.0,\"length\":2000," +
                "\"fileSizeInBits\":2000,\"outputSizeInBits\":200," +
                "\"containerSizeInBits\":20000,\"maxLatency\":60.0," +
                "\"edgeDevice\":0,\"applicationID\":0," +
                "\"metadata\":{\"priority\":200,\"scheduling_class\":0," +
                "\"finish_status\":\"KILL\",\"collection_logical_name\":\"\"," +
                "\"collection_id\":2,\"instance_index\":0," +
                "\"delta_t_exec_s\":20.0,\"delta_t_queue_s\":0.5," +
                "\"req_cpus\":0.02,\"req_memory\":0.002,\"slack_factor\":0.8}}\n");
        }

        StreamedTraceTaskGenerator gen = makeGenerator();
        gen.setTraceFilePath(file.toString());

        openTraceFile(gen);
        int loaded = readNextChunk(gen, 100);

        assertEquals(2, loaded, "2 valid lines should load; malformed line skipped");
        assertEquals(1, gen.getTotalTasksSkipped(), "Skipped count must be 1");
        assertTrue(gen.isTraceExhausted());
    }

    // =========================================================================
    // T15: Missing file
    // =========================================================================

    @Test @Order(15)
    @DisplayName("T15: Missing file throws RuntimeException on openTraceFile")
    void t15_missingFileThrows() throws Exception {
        StreamedTraceTaskGenerator gen = makeGenerator();
        gen.setTraceFilePath("/nonexistent/dir/no_file.json");
        assertThrows(RuntimeException.class, () -> openTraceFile(gen));
    }

    // =========================================================================
    // T16: Chunk boundary arithmetic
    // =========================================================================

    @Test @Order(16)
    @DisplayName("T16: Chunk boundary — all N tasks loaded across ceil(N/chunk) chunks")
    void t16_chunkBoundary(@TempDir Path tmpDir) throws Exception {
        int total = 105;
        int chunkSize = 50;
        Path file = writeFixtureFile(tmpDir, total, "t16.json");

        StreamedTraceTaskGenerator gen = makeGenerator();
        gen.setTraceFilePath(file.toString());

        openTraceFile(gen);

        AtomicInteger chunks = new AtomicInteger(0);
        long totalLoaded = 0;
        while (!gen.isTraceExhausted()) {
            int added = readNextChunk(gen, chunkSize);
            totalLoaded += added;
            chunks.incrementAndGet();
            if (chunks.get() > 50) fail("Infinite loop guard");
        }

        assertEquals(total, totalLoaded, "All 105 tasks must be loaded");
        // ceil(105 / 50) = 3
        assertEquals(3, chunks.get(), "Exactly 3 chunks needed for 105 tasks at chunk=50");
    }
}
