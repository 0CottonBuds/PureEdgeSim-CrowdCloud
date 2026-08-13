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

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Lightweight regex-based JSON parser for parsing incoming control and response
 * messages received from the Python orchestrator.
 *
 * <h2>Design Principles</h2>
 * <ul>
 *   <li>Incoming responses from Python contain at most 4 top-level key-value fields,
 *       making regular expression extraction significantly faster and simpler than importing
 *       a full JSON parsing library.</li>
 *   <li>Pre-compiles {@link Pattern} instances for maximum efficiency under continuous
 *       per-task evaluation.</li>
 *   <li>Null-safe parsing methods return sensible fallbacks ({@code -1} for IDs/indices,
 *       {@code null} for absent strings).</li>
 * </ul>
 *
 * <h2>Supported Incoming Fields</h2>
 * <ul>
 *   <li>{@code "type"}: Identifies message type, e.g. {@code "READY"}, {@code "DECISION_RESPONSE"}, {@code "SHUTDOWN_ACK"}.</li>
 *   <li>{@code "node_index"}: Selected computing node index returned in {@code DECISION_RESPONSE}.</li>
 *   <li>{@code "status"}: Status string returned in {@code READY_ACK} or error responses.</li>
 *   <li>{@code "episode_id"}: Episode identifier returned in handshake/shutdown ACKs.</li>
 * </ul>
 *
 * @author Python Bridge — Phase 4.3
 * @see MessageBuilder
 * @see PythonOrchestrator
 */
public class MessageParser {

    /** Regex pattern to extract string value of "type" field. */
    private static final Pattern TYPE_PATTERN =
            Pattern.compile("\"type\"\\s*:\\s*\"([^\"]+)\"");

    /** Regex pattern to extract integer value of "node_index" field. */
    private static final Pattern NODE_INDEX_PATTERN =
            Pattern.compile("\"node_index\"\\s*:\\s*(-?\\d+)");

    /** Regex pattern to extract string value of "status" field. */
    private static final Pattern STATUS_PATTERN =
            Pattern.compile("\"status\"\\s*:\\s*\"([^\"]+)\"");

    /** Regex pattern to extract integer value of "episode_id" field. */
    private static final Pattern EPISODE_ID_PATTERN =
            Pattern.compile("\"episode_id\"\\s*:\\s*(-?\\d+)");

    /**
     * Extracts the string value of the {@code "type"} JSON field.
     *
     * @param json the raw JSON string received from Python
     * @return the string type value (e.g., {@code "DECISION_RESPONSE"}), or {@code null} if missing/invalid
     */
    public static String getType(String json) {
        if (json == null) return null;
        Matcher m = TYPE_PATTERN.matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Extracts the integer value of the {@code "node_index"} JSON field.
     *
     * @param json the raw JSON string received from Python
     * @return the target node index integer, or {@code -1} if missing or unparseable
     */
    public static int getNodeIndex(String json) {
        if (json == null) return -1;
        Matcher m = NODE_INDEX_PATTERN.matcher(json);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }

    /**
     * Extracts the string value of the {@code "status"} JSON field.
     *
     * @param json the raw JSON string received from Python
     * @return the status string (e.g., {@code "READY"}), or {@code null} if missing/invalid
     */
    public static String getStatus(String json) {
        if (json == null) return null;
        Matcher m = STATUS_PATTERN.matcher(json);
        return m.find() ? m.group(1) : null;
    }

    /**
     * Extracts the integer value of the {@code "episode_id"} JSON field.
     *
     * @param json the raw JSON string received from Python
     * @return the episode ID integer, or {@code -1} if missing or unparseable
     */
    public static int getEpisodeId(String json) {
        if (json == null) return -1;
        Matcher m = EPISODE_ID_PATTERN.matcher(json);
        if (m.find()) {
            try {
                return Integer.parseInt(m.group(1));
            } catch (NumberFormatException ignored) {
            }
        }
        return -1;
    }
}
