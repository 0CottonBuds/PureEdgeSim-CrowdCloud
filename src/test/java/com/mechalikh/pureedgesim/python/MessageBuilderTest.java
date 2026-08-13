package com.mechalikh.pureedgesim.python;

import com.mechalikh.pureedgesim.datacentersmanager.ComputingNode;
import com.mechalikh.pureedgesim.locationmanager.Location;
import com.mechalikh.pureedgesim.locationmanager.MobilityModel;
import com.mechalikh.pureedgesim.network.NetworkModel;
import com.mechalikh.pureedgesim.scenariomanager.SimulationParameters;
import com.mechalikh.pureedgesim.simulationmanager.SimulationManager;
import com.mechalikh.pureedgesim.taskgenerator.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MessageBuilderTest {

    private SimulationManager mockSm;
    private NetworkModel mockNm;
    private ComputingNode mockNode;
    private MobilityModel mockMm;
    private Location mockLoc;
    private Task mockTask;
    private List<ComputingNode> nodeList;

    @BeforeEach
    void setUp() {
        mockSm = mock(SimulationManager.class);
        mockNm = mock(NetworkModel.class);
        mockNode = mock(ComputingNode.class);
        mockMm = mock(MobilityModel.class);
        mockLoc = mock(Location.class);
        mockTask = mock(Task.class);

        when(mockSm.getNetworkModel()).thenReturn(mockNm);

        when(mockNode.getId()).thenReturn(101);
        when(mockNode.getType()).thenReturn(SimulationParameters.TYPES.EDGE_DATACENTER);
        when(mockNode.getTotalMipsCapacity()).thenReturn(5000.0);
        when(mockNode.getMipsPerCore()).thenReturn(2500.0);
        when(mockNode.getNumberOfCPUCores()).thenReturn(2.0);
        when(mockNode.getRamCapacity()).thenReturn(8192.0);
        when(mockNode.getTotalStorage()).thenReturn(100000.0);
        when(mockNode.getAvailableRam()).thenReturn(4096.0);
        when(mockNode.getAvailableStorage()).thenReturn(50000.0);
        when(mockNode.getCurrentCpuUtilization()).thenReturn(25.5);
        when(mockNode.getAvgCpuUtilization()).thenReturn(20.0);
        when(mockNode.isIdle()).thenReturn(false);
        when(mockNode.isDead()).thenReturn(false);
        when(mockNode.isPeripheral()).thenReturn(true);
        when(mockNode.getTasksQueue()).thenReturn(new ArrayList<>());
        when(mockNode.getMobilityModel()).thenReturn(mockMm);

        when(mockMm.getCurrentLocation()).thenReturn(mockLoc);
        when(mockLoc.getXPos()).thenReturn(12.34);
        when(mockLoc.getYPos()).thenReturn(56.78);

        when(mockTask.getId()).thenReturn(42);
        when(mockTask.getEdgeDevice()).thenReturn(mockNode);
        when(mockTask.getApplicationID()).thenReturn(1);
        when(mockTask.getType()).thenReturn("LATENCY_SENSITIVE");
        when(mockTask.getLength()).thenReturn(1500.0);
        when(mockTask.getFileSizeInBits()).thenReturn(800000.0);
        when(mockTask.getOutputSizeInBits()).thenReturn(400000.0);
        when(mockTask.getContainerSizeInMBytes()).thenReturn(50.0);
        when(mockTask.getMaxLatency()).thenReturn(2.5);
        when(mockTask.getStatus()).thenReturn(Task.Status.SUCCESS);
        when(mockTask.getFailureReason()).thenReturn(null);
        when(mockTask.getActualCpuTime()).thenReturn(0.45);
        when(mockTask.getWatingTime()).thenReturn(0.10);
        when(mockTask.getActualNetworkTime()).thenReturn(0.05);
        when(mockTask.getTotalDelay()).thenReturn(0.60);

        nodeList = new ArrayList<>();
        nodeList.add(mockNode);
    }

    @Test
    void testBuildEpisodeInit() {
        String json = MessageBuilder.buildEpisodeInit(1, mockSm, nodeList);
        assertNotNull(json);
        assertTrue(json.contains("\"type\":\"EPISODE_INIT\""));
        assertTrue(json.contains("\"episode_id\":1"));
        assertTrue(json.contains("\"node_id\":101"));
        assertTrue(json.contains("\"node_type\":\"EDGE_DATACENTER\""));
        assertTrue(json.contains("\"total_mips\":5000.000000"));
        assertTrue(json.contains("\"is_peripheral\":true"));
    }

    @Test
    void testBuildDecisionRequest() {
        String json = MessageBuilder.buildDecisionRequest(5, mockTask, mockSm, nodeList, 3);
        assertNotNull(json);
        assertTrue(json.contains("\"type\":\"DECISION_REQUEST\""));
        assertTrue(json.contains("\"request_id\":5"));
        assertTrue(json.contains("\"task_id\":42"));
        assertTrue(json.contains("\"edge_device_id\":101"));
        assertTrue(json.contains("\"task_type\":\"LATENCY_SENSITIVE\""));
        assertTrue(json.contains("\"node_states\":["));
        assertTrue(json.contains("\"available_ram_mb\":4096.000000"));
        assertTrue(json.contains("\"pending_tasks\":["));
    }

    @Test
    void testBuildTaskResult() {
        String json = MessageBuilder.buildTaskResult(5, mockTask, 0);
        assertNotNull(json);
        assertTrue(json.contains("\"type\":\"TASK_RESULT\""));
        assertTrue(json.contains("\"request_id\":5"));
        assertTrue(json.contains("\"task_id\":42"));
        assertTrue(json.contains("\"chosen_node_index\":0"));
        assertTrue(json.contains("\"status\":\"SUCCESS\""));
        assertTrue(json.contains("\"failure_reason\":null"));
        assertTrue(json.contains("\"execution_time_s\":0.450000"));
    }

    @Test
    void testBuildEpisodeEnd() {
        String json = MessageBuilder.buildEpisodeEnd(1, mockSm);
        assertNotNull(json);
        assertTrue(json.contains("\"type\":\"EPISODE_END\""));
        assertTrue(json.contains("\"episode_id\":1"));
    }
}
