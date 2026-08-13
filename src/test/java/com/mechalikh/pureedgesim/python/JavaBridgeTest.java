package com.mechalikh.pureedgesim.python;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import org.newsclub.net.unix.AFUNIXServerSocket;
import org.newsclub.net.unix.AFUNIXSocket;
import org.newsclub.net.unix.AFUNIXSocketAddress;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for {@link JavaBridge} — Unix domain socket I/O layer.
 *
 * <p>Each test spins up a minimal Java echo/control server in a daemon thread
 * so the test exercises the full framing protocol (4-byte length prefix + UTF-8
 * payload) without requiring a live Python process.</p>
 *
 * @author Python Bridge — Phase 4.2
 */
class JavaBridgeTest {

    private static final Charset UTF8 = Charset.forName("UTF-8");

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /**
     * Write one length-prefixed frame to the given stream (mirrors JavaBridge.send).
     */
    private static void writeFrame(DataOutputStream out, String payload) throws IOException {
        byte[] bytes = payload.getBytes(UTF8);
        out.writeInt(bytes.length);
        out.write(bytes);
        out.flush();
    }

    /**
     * Read one length-prefixed frame from the given stream (mirrors JavaBridge.recv).
     */
    private static String readFrame(DataInputStream in) throws IOException {
        int len = in.readInt();
        byte[] buf = new byte[len];
        in.readFully(buf);
        return new String(buf, UTF8);
    }

    // -----------------------------------------------------------------------
    // Tests
    // -----------------------------------------------------------------------

    /**
     * Happy path: send a JSON string, receive the same string echoed back.
     * Verifies that the length-prefix framing is symmetric between JavaBridge
     * and a hand-written server.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testEchoRoundTrip(@TempDir Path tempDir) throws Exception {
        File sockFile = tempDir.resolve("test_echo.sock").toFile();
        AFUNIXSocketAddress address = AFUNIXSocketAddress.of(sockFile);

        CountDownLatch serverReady = new CountDownLatch(1);
        AtomicReference<Throwable> serverError = new AtomicReference<>();

        Thread server = new Thread(() -> {
            try (AFUNIXServerSocket srv = AFUNIXServerSocket.newInstance()) {
                srv.bind(address);
                serverReady.countDown();
                try (AFUNIXSocket conn = srv.accept()) {
                    DataInputStream  in  = new DataInputStream(conn.getInputStream());
                    DataOutputStream out = new DataOutputStream(conn.getOutputStream());
                    // Read one frame, echo it back, then stop
                    String received = readFrame(in);
                    writeFrame(out, received);
                }
            } catch (IOException e) {
                serverError.set(e);
                serverReady.countDown(); // unblock client even on error
            }
        });
        server.setDaemon(true);
        server.start();

        assertTrue(serverReady.await(5, TimeUnit.SECONDS), "Server did not start in time");
        assertNull(serverError.get(), "Server setup failed: " + serverError.get());

        JavaBridge bridge = new JavaBridge(sockFile.getAbsolutePath(), 2_000);
        try {
            String payload = "{\"type\":\"PING\",\"request_id\":42}";
            bridge.send(payload);
            String response = bridge.recv();
            assertEquals(payload, response, "Echoed payload must match exactly");
        } finally {
            bridge.close();
        }

        server.join(3_000);
        assertNull(serverError.get(), "Server encountered an error: " + serverError.get());
    }

    /**
     * Multi-frame: send 100 frames and receive each echoed back in order.
     * Exercises the {@code readFully} guarantee under repeated use.
     */
    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void testMultipleFrames(@TempDir Path tempDir) throws Exception {
        File sockFile = tempDir.resolve("test_multi.sock").toFile();
        AFUNIXSocketAddress address = AFUNIXSocketAddress.of(sockFile);
        int frameCount = 100;

        CountDownLatch serverReady = new CountDownLatch(1);

        Thread server = new Thread(() -> {
            try (AFUNIXServerSocket srv = AFUNIXServerSocket.newInstance()) {
                srv.bind(address);
                serverReady.countDown();
                try (AFUNIXSocket conn = srv.accept()) {
                    DataInputStream  in  = new DataInputStream(conn.getInputStream());
                    DataOutputStream out = new DataOutputStream(conn.getOutputStream());
                    for (int i = 0; i < frameCount; i++) {
                        writeFrame(out, readFrame(in));
                    }
                }
            } catch (IOException e) {
                serverReady.countDown();
            }
        });
        server.setDaemon(true);
        server.start();

        assertTrue(serverReady.await(5, TimeUnit.SECONDS));

        JavaBridge bridge = new JavaBridge(sockFile.getAbsolutePath(), 2_000);
        try {
            for (int i = 0; i < frameCount; i++) {
                String payload = "{\"seq\":" + i + "}";
                bridge.send(payload);
                assertEquals(payload, bridge.recv(), "Frame " + i + " mismatch");
            }
        } finally {
            bridge.close();
        }
    }

    /**
     * Large payload: send a 1 MB JSON string and verify it arrives intact.
     * Exercises the {@code readFully} loop that accumulates multiple OS-level
     * {@code recv} calls into one complete frame.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testLargePayload(@TempDir Path tempDir) throws Exception {
        File sockFile = tempDir.resolve("test_large.sock").toFile();
        AFUNIXSocketAddress address = AFUNIXSocketAddress.of(sockFile);

        // Build a ~1 MB payload
        StringBuilder sb = new StringBuilder("{\"data\":\"");
        for (int i = 0; i < 100_000; i++) sb.append("ABCDEFGHIJ");
        sb.append("\"}");
        String largePayload = sb.toString();

        CountDownLatch serverReady = new CountDownLatch(1);

        Thread server = new Thread(() -> {
            try (AFUNIXServerSocket srv = AFUNIXServerSocket.newInstance()) {
                srv.bind(address);
                serverReady.countDown();
                try (AFUNIXSocket conn = srv.accept()) {
                    DataInputStream  in  = new DataInputStream(conn.getInputStream());
                    DataOutputStream out = new DataOutputStream(conn.getOutputStream());
                    writeFrame(out, readFrame(in));
                }
            } catch (IOException e) {
                serverReady.countDown();
            }
        });
        server.setDaemon(true);
        server.start();

        assertTrue(serverReady.await(5, TimeUnit.SECONDS));

        JavaBridge bridge = new JavaBridge(sockFile.getAbsolutePath(), 2_000);
        try {
            bridge.send(largePayload);
            String response = bridge.recv();
            assertEquals(largePayload.length(), response.length(), "Large payload length mismatch");
            assertEquals(largePayload, response, "Large payload content mismatch");
        } finally {
            bridge.close();
        }
    }

    /**
     * Timeout: connecting to a path where no server is listening should throw
     * {@link BridgeTimeoutException} within the specified timeout window.
     */
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testConnectionTimeout(@TempDir Path tempDir) throws Exception {
        File nonExistentSock = tempDir.resolve("no_server.sock").toFile();
        // 300 ms timeout → should fail fast
        assertThrows(BridgeTimeoutException.class, () ->
                new JavaBridge(nonExistentSock.getAbsolutePath(), 300));
    }

    /**
     * Crash detection: when the server closes the connection while Java is in
     * {@link JavaBridge#recv()}, a {@link BridgeCrashException} must be thrown.
     */
    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void testBridgeCrashDetection(@TempDir Path tempDir) throws Exception {
        File sockFile = tempDir.resolve("test_crash.sock").toFile();
        AFUNIXSocketAddress address = AFUNIXSocketAddress.of(sockFile);

        CountDownLatch serverReady   = new CountDownLatch(1);
        CountDownLatch clientSent    = new CountDownLatch(1);

        Thread server = new Thread(() -> {
            try (AFUNIXServerSocket srv = AFUNIXServerSocket.newInstance()) {
                srv.bind(address);
                serverReady.countDown();
                AFUNIXSocket conn = srv.accept();
                // Wait until the client has sent a frame, then abruptly close
                clientSent.await(5, TimeUnit.SECONDS);
                conn.close(); // simulate Python crash
            } catch (Exception e) {
                serverReady.countDown();
            }
        });
        server.setDaemon(true);
        server.start();

        assertTrue(serverReady.await(5, TimeUnit.SECONDS));

        JavaBridge bridge = new JavaBridge(sockFile.getAbsolutePath(), 2_000);
        try {
            bridge.send("{\"type\":\"DECISION_REQUEST\"}");
            clientSent.countDown();
            // recv() should detect the EOF and raise BridgeCrashException
            assertThrows(BridgeCrashException.class, bridge::recv);
        } finally {
            bridge.close();
        }
    }

    /**
     * Double-close: calling {@link JavaBridge#close()} twice must not throw.
     */
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void testDoubleCloseIsIdempotent(@TempDir Path tempDir) throws Exception {
        File sockFile = tempDir.resolve("test_close.sock").toFile();
        AFUNIXSocketAddress address = AFUNIXSocketAddress.of(sockFile);

        CountDownLatch serverReady = new CountDownLatch(1);

        Thread server = new Thread(() -> {
            try (AFUNIXServerSocket srv = AFUNIXServerSocket.newInstance()) {
                srv.bind(address);
                serverReady.countDown();
                try (AFUNIXSocket ignored = srv.accept()) {
                    Thread.sleep(500); // hold connection open briefly
                }
            } catch (Exception e) {
                serverReady.countDown();
            }
        });
        server.setDaemon(true);
        server.start();

        assertTrue(serverReady.await(5, TimeUnit.SECONDS));

        JavaBridge bridge = new JavaBridge(sockFile.getAbsolutePath(), 2_000);
        assertDoesNotThrow(bridge::close);
        assertDoesNotThrow(bridge::close); // second close must be a no-op
    }
}
