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

import com.mechalikh.pureedgesim.datacentersmanager.ComputingNode;
import com.mechalikh.pureedgesim.simulationengine.FutureQueue;
import com.mechalikh.pureedgesim.simulationmanager.SimulationManager;
import com.mechalikh.pureedgesim.taskgenerator.Task;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Utility class for constructing JSON messages sent from Java to Python across
 * the Unix Domain Socket bridge.
 *
 * <h2>Design &amp; Performance Notes</h2>
 * <ul>
 *   <li>Constructs JSON payloads directly using {@link StringBuilder} for zero-dependency
 *       serialization, avoiding heavy reflection or external JSON libraries (Gson/Jackson)
 *       to strictly comply with Java 8 target and minimize latency.</li>
 *   <li>Floating-point numbers are formatted with {@code %.6f} in {@link Locale#US} to
 *       ensure consistent dot-decimal parsing in Python regardless of host system locale.</li>
 *   <li>Handles {@code NaN} and {@code Infinite} values by falling back safely to {@code 0.0}.</li>
 * </ul>
 *
 * <h2>Protocol Message Types Built</h2>
 * <ul>
 *   <li>{@code EPISODE_INIT}: Sent once upon simulation setup, transmitting static topology
 *       and node capacity properties (MIPS, RAM, Storage, Cores, Base Location).</li>
 *   <li>{@code DECISION_REQUEST}: Sent synchronously inside {@code findComputingNode()}, transmitting
 *       the incoming task's requirements, current dynamic states of all computing nodes (RAM, Storage, CPU, Queue Length, Location),
 *       lookahead pending tasks from the simulation task queue, and WAN uplink utilization.</li>
 *   <li>{@code TASK_RESULT}: Sent asynchronously when a task finishes execution or fails,
 *       transmitting actual CPU execution time, waiting time, network delay, and failure reason.</li>
 *   <li>{@code EPISODE_END}: Sent upon simulation teardown to instruct Python to wrap up episode statistics.</li>
 * </ul>
 *
 * @author Python Bridge — Phase 4.3
 * @see MessageParser
 * @see PythonOrchestrator
 */
public class MessageBuilder {

    /**
     * Builds an {@code EPISODE_INIT} JSON message to notify Python of static topology and node hardware capacities.
     *
     * <p>Sent once immediately after process launch and socket handshake completion during orchestrator initialization.</p>
     *
     * @param episodeId unique identifier for the current simulation run/iteration
     * @param sm the active simulation manager instance
     * @param nodeList the global ordered list of computing nodes (Cloud, Edge Datacenters, Edge Devices)
     * @return a valid UTF-8 formatted JSON string for {@code EPISODE_INIT}
     */
    public static String buildEpisodeInit(int episodeId, SimulationManager sm, List<ComputingNode> nodeList) {
        StringBuilder sb = new StringBuilder(1024);
        sb.append("{\"type\":\"EPISODE_INIT\",");
        sb.append("\"episode_id\":").append(episodeId).append(",");
        sb.append("\"nodes\":[");

        if (nodeList != null) {
            for (int i = 0; i < nodeList.size(); i++) {
                if (i > 0) sb.append(",");
                ComputingNode node = nodeList.get(i);
                sb.append("{");
                sb.append("\"node_id\":").append(node.getId()).append(",");
                sb.append("\"node_index\":").append(i).append(",");
                sb.append("\"node_type\":\"").append(node.getType() != null ? node.getType().toString() : "UNKNOWN").append("\",");
                sb.append("\"total_mips\":").append(formatDouble(node.getTotalMipsCapacity())).append(",");
                sb.append("\"mips_per_core\":").append(formatDouble(node.getMipsPerCore())).append(",");
                sb.append("\"num_cores\":").append((int) node.getNumberOfCPUCores()).append(",");
                sb.append("\"total_ram_mb\":").append(formatDouble(node.getRamCapacity())).append(",");
                sb.append("\"total_storage_mb\":").append(formatDouble(node.getTotalStorage())).append(",");

                double locX = 0.0;
                double locY = 0.0;
                if (node.getMobilityModel() != null && node.getMobilityModel().getCurrentLocation() != null) {
                    locX = node.getMobilityModel().getCurrentLocation().getXPos();
                    locY = node.getMobilityModel().getCurrentLocation().getYPos();
                }
                sb.append("\"base_location_x\":").append(formatDouble(locX)).append(",");
                sb.append("\"base_location_y\":").append(formatDouble(locY)).append(",");
                sb.append("\"is_peripheral\":").append(node.isPeripheral());
                sb.append("}");
            }
        }
        sb.append("]}");
        return sb.toString();
    }

    /**
     * Builds a {@code DECISION_REQUEST} JSON message requesting offloading placement from Python.
     *
     * <p>Sent synchronously from {@code PythonOrchestrator.findComputingNode()} for every task offloading request.
     * Includes current task specifications, live dynamic state of all nodes in the topology,
     * a lookahead window of pending tasks in the simulation engine's task queue, and current WAN utilization.</p>
     *
     * @param requestId monotonically increasing request sequence number for matching responses
     * @param task the incoming offloading request needing placement
     * @param sm active simulation manager instance
     * @param nodeList global ordered list of all computing nodes
     * @param lookAheadWindowSize maximum number of future tasks to extract from the simulation task queue
     * @return a valid UTF-8 formatted JSON string for {@code DECISION_REQUEST}
     */
    public static String buildDecisionRequest(int requestId, Task task, SimulationManager sm,
                                               List<ComputingNode> nodeList, int lookAheadWindowSize) {
        StringBuilder sb = new StringBuilder(2048);
        sb.append("{\"type\":\"DECISION_REQUEST\",");
        sb.append("\"request_id\":").append(requestId).append(",");

        sb.append("\"current_task\":");
        appendTaskJson(sb, task, nodeList);
        sb.append(",");

        // Dynamic state of all nodes in scope
        sb.append("\"node_states\":[");
        if (nodeList != null) {
            for (int i = 0; i < nodeList.size(); i++) {
                if (i > 0) sb.append(",");
                ComputingNode node = nodeList.get(i);
                sb.append("{");
                sb.append("\"node_id\":").append(node.getId()).append(",");
                sb.append("\"available_ram_mb\":").append(formatDouble(node.getAvailableRam())).append(",");
                sb.append("\"available_storage_mb\":").append(formatDouble(node.getAvailableStorage())).append(",");
                sb.append("\"current_cpu_pct\":").append(formatDouble(node.getCurrentCpuUtilization())).append(",");
                sb.append("\"avg_cpu_pct\":").append(formatDouble(node.getAvgCpuUtilization())).append(",");
                sb.append("\"is_idle\":").append(node.isIdle()).append(",");
                sb.append("\"is_dead\":").append(node.isDead()).append(",");
                sb.append("\"queue_length\":").append(node.getTasksQueue() != null ? node.getTasksQueue().size() : 0).append(",");

                double locX = 0.0;
                double locY = 0.0;
                if (node.getMobilityModel() != null && node.getMobilityModel().getCurrentLocation() != null) {
                    locX = node.getMobilityModel().getCurrentLocation().getXPos();
                    locY = node.getMobilityModel().getCurrentLocation().getYPos();
                }
                sb.append("\"current_location_x\":").append(formatDouble(locX)).append(",");
                sb.append("\"current_location_y\":").append(formatDouble(locY));
                sb.append("}");
            }
        }
        sb.append("],");

        // Pending tasks (lookahead window)
        sb.append("\"pending_tasks\":[");
        List<Task> pending = getPendingTasks(sm, lookAheadWindowSize);
        for (int i = 0; i < pending.size(); i++) {
            if (i > 0) sb.append(",");
            appendTaskJson(sb, pending.get(i), nodeList);
        }
        sb.append("],");

        // WAN uplink utilization (handled safely if one_shared_wan_network is disabled)
        double wanUtil = 0.0;
        if (sm != null && sm.getNetworkModel() != null) {
            try {
                wanUtil = sm.getNetworkModel().getWanUpUtilization();
            } catch (Exception ignored) {
                // getWanUpUtilization() throws Exception when one_shared_wan_network is false. Fallback to 0.0.
            }
        }
        sb.append("\"wan_uplink_utilization\":").append(formatDouble(wanUtil));
        sb.append("}");
        return sb.toString();
    }

    /**
     * Builds a {@code TASK_RESULT} JSON message notifying Python of task execution completion or failure.
     *
     * <p>Sent asynchronously from {@code PythonOrchestrator.resultsReturned()} when a task completes execution
     * or fails due to latency, resource exhaustion, or node mobility/death.</p>
     *
     * @param requestId original request ID issued during placement decision, or -1 if unmapped
     * @param task the completed or failed task object
     * @param chosenNodeIndex the index of the computing node to which the task was assigned
     * @return a valid UTF-8 formatted JSON string for {@code TASK_RESULT}
     */
    public static String buildTaskResult(int requestId, Task task, int chosenNodeIndex) {
        StringBuilder sb = new StringBuilder(512);
        sb.append("{\"type\":\"TASK_RESULT\",");
        sb.append("\"request_id\":").append(requestId).append(",");
        sb.append("\"task_id\":").append(task != null ? task.getId() : -1).append(",");
        sb.append("\"chosen_node_index\":").append(chosenNodeIndex).append(",");
        sb.append("\"status\":\"").append(task != null && task.getStatus() != null ? task.getStatus().toString() : "UNKNOWN").append("\",");
        
        if (task != null && task.getFailureReason() != null) {
            sb.append("\"failure_reason\":\"").append(task.getFailureReason().toString()).append("\",");
        } else {
            sb.append("\"failure_reason\":null,");
        }

        sb.append("\"execution_time_s\":").append(formatDouble(task != null ? task.getActualCpuTime() : 0.0)).append(",");
        sb.append("\"waiting_time_s\":").append(formatDouble(task != null ? task.getWatingTime() : 0.0)).append(",");
        sb.append("\"network_time_s\":").append(formatDouble(task != null ? task.getActualNetworkTime() : 0.0)).append(",");
        sb.append("\"total_delay_s\":").append(formatDouble(task != null ? task.getTotalDelay() : 0.0));
        sb.append("}");
        return sb.toString();
    }

    /**
     * Builds an {@code EPISODE_END} JSON message signaling simulation termination.
     *
     * <p>Sent from {@code PythonOrchestrator.onSimulationEnd()} when the DES engine finishes processing all events.</p>
     *
     * @param episodeId unique identifier for the ending simulation episode
     * @param sm active simulation manager instance
     * @return a valid UTF-8 formatted JSON string for {@code EPISODE_END}
     */
    public static String buildEpisodeEnd(int episodeId, SimulationManager sm) {
        StringBuilder sb = new StringBuilder(128);
        sb.append("{\"type\":\"EPISODE_END\",");
        sb.append("\"episode_id\":").append(episodeId);
        sb.append("}");
        return sb.toString();
    }

    // -----------------------------------------------------------------------
    // Internal Helper Methods
    // -----------------------------------------------------------------------

    /**
     * Serializes a single {@link Task} object into JSON.
     *
     * @param sb target StringBuilder
     * @param task task instance to serialize
     * @param nodeList global node list for index lookup
     */
    private static void appendTaskJson(StringBuilder sb, Task task, List<ComputingNode> nodeList) {
        if (task == null) {
            sb.append("null");
            return;
        }
        int edgeDevId = task.getEdgeDevice() != null ? task.getEdgeDevice().getId() : -1;
        int edgeDevIndex = -1;
        if (nodeList != null && task.getEdgeDevice() != null) {
            edgeDevIndex = nodeList.indexOf(task.getEdgeDevice());
        }

        sb.append("{");
        sb.append("\"task_id\":").append(task.getId()).append(",");
        sb.append("\"edge_device_id\":").append(edgeDevId).append(",");
        sb.append("\"edge_device_index\":").append(edgeDevIndex).append(",");
        sb.append("\"application_id\":").append(task.getApplicationID()).append(",");
        sb.append("\"task_type\":\"").append(task.getType() != null ? task.getType() : "").append("\",");
        sb.append("\"length_mips\":").append(formatDouble(task.getLength())).append(",");
        sb.append("\"task_file_size_bits\":").append(formatDouble(task.getFileSizeInBits())).append(",");
        sb.append("\"task_output_bits\":").append(formatDouble(task.getOutputSizeInBits())).append(",");
        sb.append("\"task_container_mb\":").append(formatDouble(task.getContainerSizeInMBytes())).append(",");
        sb.append("\"max_latency_s\":").append(formatDouble(task.getMaxLatency()));
        sb.append("}");
    }

    /**
     * Extracts up to {@code maxCount} pending tasks from {@code SimulationManager.taskList} using reflection.
     *
     * <p>Preserves 100% additive design without modifying existing PureEdgeSim files.</p>
     *
     * @param sm simulation manager instance
     * @param maxCount maximum tasks to return
     * @return list of pending tasks
     */
    @SuppressWarnings("unchecked")
    private static List<Task> getPendingTasks(SimulationManager sm, int maxCount) {
        List<Task> result = new ArrayList<>();
        if (sm == null || maxCount <= 0) return result;
        try {
            Field field = SimulationManager.class.getDeclaredField("taskList");
            field.setAccessible(true);
            FutureQueue<Task> taskList = (FutureQueue<Task>) field.get(sm);
            if (taskList != null) {
                java.util.Iterator<Task> it = taskList.iterator();
                while (it.hasNext()) {
                    result.add(it.next());
                    if (result.size() >= maxCount) break;
                }
            }
        } catch (Exception ignored) {
        }
        return result;
    }

    /**
     * Formats a double value with 6 decimal places in US locale, defaulting NaN or Infinity to 0.0.
     *
     * @param val numeric value
     * @return formatted string
     */
    private static String formatDouble(double val) {
        if (Double.isNaN(val) || Double.isInfinite(val)) {
            return "0.0";
        }
        return String.format(Locale.US, "%.6f", val);
    }
}
