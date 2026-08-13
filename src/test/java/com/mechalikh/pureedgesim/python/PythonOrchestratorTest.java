package com.mechalikh.pureedgesim.python;

import com.mechalikh.pureedgesim.datacentersmanager.ComputingNode;
import com.mechalikh.pureedgesim.scenariomanager.Scenario;
import com.mechalikh.pureedgesim.simulationengine.PureEdgeSim;
import com.mechalikh.pureedgesim.simulationmanager.SimLog;
import com.mechalikh.pureedgesim.simulationmanager.SimulationManager;
import com.mechalikh.pureedgesim.taskgenerator.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class PythonOrchestratorTest {

    private SimulationManager mockSm;
    private Scenario mockScenario;
    private PureEdgeSim mockPureEdgeSim;
    private SimLog mockSimLog;
    private JavaBridge mockBridge;
    private Task mockTask;
    private ComputingNode mockNode;

    @BeforeEach
    void setUp() {
        mockSm = mock(SimulationManager.class);
        mockScenario = mock(Scenario.class);
        PureEdgeSim realSim = new PureEdgeSim();
        mockSimLog = mock(SimLog.class);
        mockBridge = mock(JavaBridge.class);
        mockTask = mock(Task.class);
        mockNode = mock(ComputingNode.class);

        when(mockScenario.getStringOrchAlgorithm()).thenReturn("PYTHON");
        when(mockSm.getScenario()).thenReturn(mockScenario);
        when(mockSm.getSimulation()).thenReturn(realSim);
        when(mockSm.getSimulationLogger()).thenReturn(mockSimLog);

        when(mockTask.getId()).thenReturn(100);
        when(mockTask.getEdgeDevice()).thenReturn(mockNode);
    }

    @Test
    void testFindComputingNode() throws IOException {
        when(mockBridge.recv()).thenReturn("{\"type\":\"DECISION_RESPONSE\",\"request_id\":0,\"node_index\":3}");

        PythonOrchestrator orch = new PythonOrchestrator(mockSm, mockBridge);
        int chosen = orch.findComputingNode(new String[]{"ALL"}, mockTask);

        assertEquals(3, chosen);
        verify(mockBridge).send(contains("\"type\":\"DECISION_REQUEST\""));
        verify(mockBridge).recv();
    }

    @Test
    void testResultsReturned() throws IOException {
        when(mockBridge.recv()).thenReturn("{\"type\":\"DECISION_RESPONSE\",\"request_id\":0,\"node_index\":3}");

        PythonOrchestrator orch = new PythonOrchestrator(mockSm, mockBridge);
        orch.findComputingNode(new String[]{"ALL"}, mockTask);

        when(mockTask.getStatus()).thenReturn(Task.Status.SUCCESS);
        orch.resultsReturned(mockTask);

        verify(mockBridge).send(contains("\"type\":\"TASK_RESULT\""));
    }

    @Test
    void testOnSimulationEnd() throws IOException {
        PythonOrchestrator orch = new PythonOrchestrator(mockSm, mockBridge);
        orch.onSimulationEnd();

        verify(mockBridge).send(contains("\"type\":\"EPISODE_END\""));
        verify(mockBridge).recv(); // wait for SHUTDOWN_ACK
        verify(mockBridge).close();
    }
}
