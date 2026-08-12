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

/**
 * Thrown when the Python bridge process terminates unexpectedly while Java is
 * waiting for a response. This is detected in {@link JavaBridge#recv()} when
 * an {@link java.io.EOFException} is received from the underlying socket stream.
 *
 * <p>The simulation manager catches this exception in
 * {@code PythonOrchestrator.findComputingNode()} and returns {@code -1} so the
 * affected task fails gracefully rather than crashing the DES thread.</p>
 *
 * @author Python Bridge — Phase 4.2 / Phase 4.8
 */
public class BridgeCrashException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message human-readable description (should hint user to check Python stderr)
     */
    public BridgeCrashException(String message) {
        super(message);
    }

    /**
     * @param message human-readable description
     * @param cause   the underlying {@link java.io.EOFException} or similar
     */
    public BridgeCrashException(String message, Throwable cause) {
        super(message, cause);
    }
}
