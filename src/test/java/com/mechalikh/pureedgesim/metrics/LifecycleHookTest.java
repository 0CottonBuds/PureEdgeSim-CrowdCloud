package com.mechalikh.pureedgesim.metrics;

import com.mechalikh.pureedgesim.datacentersmanager.ComputingNodeNull;
import com.mechalikh.pureedgesim.simulationengine.Event;
import com.mechalikh.pureedgesim.simulationengine.PureEdgeSim;
import com.mechalikh.pureedgesim.simulationmanager.DefaultSimulationManager;
import com.mechalikh.pureedgesim.simulationmanager.SimLog;
import com.mechalikh.pureedgesim.simulationmanager.SimulationManager;
import com.mechalikh.pureedgesim.taskgenerator.DefaultTask;
import com.mechalikh.pureedgesim.taskgenerator.Task;
import com.mechalikh.pureedgesim.taskorchestrator.Orchestrator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Milestone 2 — Unit tests for the task lifecycle hook onTaskCompleted().
 *
 * Verifies that:
 * 1. Base SimLog.onTaskCompleted() is a safe no-op.
 * 2. Subclasses of SimLog receive onTaskCompleted() callbacks for successful tasks at RESULT_RETURN_FINISHED.
 * 3. Tasks failing at phase 3 (e.g. latency failure) exit early and do NOT trigger onTaskCompleted().
 */
public class LifecycleHookTest {

    private static class TrackingSimLog extends SimLog {
        final List<Task> completedTasks = new ArrayList<>();
        final List<Double> completionTimes = new ArrayList<>();

        public TrackingSimLog() {
            super("2026-01-01_00-00-00", false);
        }

        @Override
        public void onTaskCompleted(Task task, double clock) {
            completedTasks.add(task);
            completionTimes.add(clock);
        }
    }

    private static class DummyOrchestrator extends Orchestrator {
        List<Task> returnedTasks;

        public DummyOrchestrator() {
            super(null);
        }

        @Override
        public void resultsReturned(Task task) {
            if (returnedTasks != null) {
                returnedTasks.add(task);
            }
        }

        @Override
        protected int findComputingNode(String[] architectureLayers, Task task) {
            return -1;
        }

        @Override
        public void processEvent(Event ev) {
        }
    }

    private TrackingSimLog simLog;
    private DefaultSimulationManager simulationManager;

    @BeforeEach
    public void setUp() throws Exception {
        simLog = new TrackingSimLog();

        // Allocate DefaultSimulationManager bypassing constructor requiring Scenario/files
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
        theUnsafe.setAccessible(true);
        Object unsafe = theUnsafe.get(null);
        Method allocate = unsafeClass.getMethod("allocateInstance", Class.class);

        simulationManager = (DefaultSimulationManager) allocate.invoke(unsafe, DefaultSimulationManager.class);

        PureEdgeSim simulation = new PureEdgeSim();

        // Allocate dummy Orchestrator
        DummyOrchestrator orchestrator = (DummyOrchestrator) allocate.invoke(unsafe, DummyOrchestrator.class);
        orchestrator.returnedTasks = new ArrayList<>();

        // Inject fields into simulationManager and its superclasses
        setField(simulationManager, "simLog", simLog);
        setField(simulationManager, "simulation", simulation);
        setField(simulationManager, "edgeOrchestrator", orchestrator);
        setField(simulationManager, "finishedTasks", new ArrayList<Task>());
        setField(simulationManager, "tasksCount", 0);
        setField(simulationManager, "failedTasksCount", 0);
    }

    private static void setField(Object obj, String fieldName, Object value) throws Exception {
        Class<?> cls = obj.getClass();
        while (cls != null) {
            try {
                Field f = cls.getDeclaredField(fieldName);
                f.setAccessible(true);
                f.set(obj, value);
                return;
            } catch (NoSuchFieldException e) {
                cls = cls.getSuperclass();
            }
        }
        throw new NoSuchFieldException("Field " + fieldName + " not found on " + obj.getClass());
    }

    @Test
    @DisplayName("Base SimLog onTaskCompleted is a safe no-op")
    public void testBaseSimLogNoOp() {
        SimLog baseLog = new SimLog("2026-01-01_00-00-00", false);
        DefaultTask task = new DefaultTask(1);
        assertDoesNotThrow(() -> baseLog.onTaskCompleted(task, 12.34));
    }

    @Test
    @DisplayName("onTaskCompleted is invoked for successfully completed tasks")
    public void testOnTaskCompletedCalledForSuccessfulTask() {
        DefaultTask task = new DefaultTask(101);
        task.setEdgeDevice(ComputingNodeNull.getInstance());
        task.setMaxLatency(10.0);
        task.addActualNetworkTime(1.0);
        task.setArrivalTime(10.0);
        task.setExecutionStartTime(11.0); // waiting time = 1.0
        task.setExecutionFinishTime(12.0); // cpu time = 1.0 -> total delay = 3.0 (< 10.0)

        Event ev = new Event(simulationManager, 25.0, SimulationManager.RESULT_RETURN_FINISHED, task);
        simulationManager.processEvent(ev);

        assertEquals(1, simLog.completedTasks.size(), "onTaskCompleted should be called once for successful task");
        assertSame(task, simLog.completedTasks.get(0), "Completed task passed to hook must match");
    }

    @Test
    @DisplayName("onTaskCompleted is NOT invoked for tasks failing due to latency at phase 3")
    public void testOnTaskCompletedCalledForFailedTask() {
        DefaultTask task = new DefaultTask(202);
        task.setEdgeDevice(ComputingNodeNull.getInstance());
        task.setMaxLatency(5.0);
        task.addActualNetworkTime(3.0);
        task.setArrivalTime(10.0);
        task.setExecutionStartTime(12.0); // waiting time = 2.0
        task.setExecutionFinishTime(15.0); // cpu time = 3.0 -> total delay = 8.0 (>= 5.0)

        Event ev = new Event(simulationManager, 30.0, SimulationManager.RESULT_RETURN_FINISHED, task);
        simulationManager.processEvent(ev);

        assertEquals(0, simLog.completedTasks.size(),
                "onTaskCompleted must NOT be called for tasks that failed phase 3 latency checks");
        assertEquals(Task.FailureReason.FAILED_DUE_TO_LATENCY, task.getFailureReason(),
                "Task should be marked as FAILED_DUE_TO_LATENCY");
    }
}
