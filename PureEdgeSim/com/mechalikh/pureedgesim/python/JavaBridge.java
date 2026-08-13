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
 **/
package com.mechalikh.pureedgesim.python;

import org.newsclub.net.unix.AFUNIXSocket;
import org.newsclub.net.unix.AFUNIXSocketAddress;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.File;
import java.net.SocketException;
import java.io.IOException;
import java.nio.charset.Charset;

/**
 * Low-level Unix domain socket I/O layer for the Java/Python bridge.
 *
 * <h2>Protocol</h2>
 * <p>Every message is framed as:
 * <ol>
 *   <li>A 4-byte big-endian signed integer: the byte length of the UTF-8 payload.</li>
 *   <li>Exactly {@code N} bytes of UTF-8 encoded JSON text.</li>
 * </ol>
 * This matches Python's {@code struct.pack('>I', n)} / {@code struct.unpack('>I', header)[0]}
 * convention (the signed/unsigned difference is irrelevant for payloads &lt; 2 GB).</p>
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>Construct with the socket path and a connection timeout.</li>
 *   <li>Call {@link #send} / {@link #recv} in lock-step from the DES thread.</li>
 *   <li>Call {@link #close} when the simulation ends.</li>
 * </ol>
 *
 * <p>This class is <b>not thread-safe</b>: {@link #send} and {@link #recv} must
 * be called from the same thread.</p>
 *
 * @author Python Bridge — Phase 4.2
 */
public class JavaBridge {

    /** UTF-8 charset constant — avoids repeated lookup. */
    private static final Charset UTF8 = Charset.forName("UTF-8");

    /**
     * Read timeout applied to the socket after connecting.
     * If Python takes longer than this to respond to a DECISION_REQUEST, the
     * caller receives a {@link java.net.SocketTimeoutException}.
     */
    private static final int READ_TIMEOUT_MS = 30_000;

    private AFUNIXSocket socket;
    private DataInputStream in;
    private DataOutputStream out;
    private boolean closed = false;

    // -----------------------------------------------------------------------
    // Constructor
    // -----------------------------------------------------------------------

    /**
     * Connect to an existing Unix socket file at {@code socketPath}.
     *
     * <p>Retries every 100 ms until the socket becomes available or
     * {@code timeoutMs} milliseconds have elapsed. This handles the race between
     * Java starting before Python has had time to bind and listen.</p>
     *
     * @param socketPath path to the Unix socket file (e.g. {@code /tmp/pes.sock})
     * @param timeoutMs  maximum milliseconds to wait for the socket to appear
     * @throws IOException          if a non-transient I/O error occurs
     * @throws BridgeTimeoutException if the socket is not available within {@code timeoutMs}
     */
    public JavaBridge(String socketPath, int timeoutMs) throws IOException {
        File socketFile = new File(socketPath);
        AFUNIXSocketAddress address = AFUNIXSocketAddress.of(socketFile);

        long deadline = System.currentTimeMillis() + timeoutMs;
        IOException lastError = null;

        while (System.currentTimeMillis() < deadline) {
            AFUNIXSocket attempt = null;
            try {
                attempt = AFUNIXSocket.newInstance();
                attempt.connect(address);
                // Connected — apply read timeout and wrap streams
                attempt.setSoTimeout(READ_TIMEOUT_MS);
                this.socket = attempt;
                this.in  = new DataInputStream(socket.getInputStream());
                this.out = new DataOutputStream(socket.getOutputStream());
                return; // success
            } catch (IOException e) {
                lastError = e;
                // Close the failed attempt before retrying
                if (attempt != null) {
                    try { attempt.close(); } catch (IOException ignored) { }
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted while waiting for socket at " + socketPath, ie);
                }
            }
        }

        throw new BridgeTimeoutException(
                "Could not connect to Unix socket '" + socketPath + "' within " + timeoutMs + " ms. "
                + "Last error: " + (lastError != null ? lastError.getMessage() : "unknown"));
    }

    // -----------------------------------------------------------------------
    // I/O methods
    // -----------------------------------------------------------------------

    /**
     * Write one framed message: a 4-byte big-endian length followed by the
     * UTF-8 encoded JSON bytes.
     *
     * @param jsonPayload the JSON string to send
     * @throws IOException if a write error occurs
     */
    public void send(String jsonPayload) throws IOException {
        byte[] bytes = jsonPayload.getBytes(UTF8);
        out.writeInt(bytes.length);
        out.write(bytes);
        out.flush();
    }

    /**
     * Read one framed message: read the 4-byte length header, then read exactly
     * N bytes using {@link DataInputStream#readFully} (guaranteed to not return
     * partial data even if the OS delivers the payload in multiple chunks).
     *
     * @return the received JSON string
     * @throws BridgeCrashException if the connection closes unexpectedly (Python process died)
     * @throws java.net.SocketTimeoutException if no data arrives within {@value #READ_TIMEOUT_MS} ms
     * @throws IOException for other I/O errors
     */
    public String recv() throws IOException {
        int length;
        try {
            length = in.readInt();
        } catch (EOFException | SocketException e) {
            throw new BridgeCrashException(
                    "Python orchestrator process died unexpectedly while reading message length. "
                    + "Check Python stderr for the traceback.", e);
        }

        byte[] buf = new byte[length];
        try {
            in.readFully(buf);
        } catch (EOFException | SocketException e) {
            throw new BridgeCrashException(
                    "Python orchestrator process died unexpectedly while reading message body ("
                    + length + " bytes expected). "
                    + "Check Python stderr for the traceback.", e);
        }

        return new String(buf, UTF8);
    }

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    /**
     * Close the underlying socket. Safe to call multiple times; subsequent calls
     * are no-ops.
     */
    public void close() {
        if (closed) return;
        closed = true;
        if (socket != null) {
            try { socket.close(); } catch (IOException ignored) { }
        }
    }
}
