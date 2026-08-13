package examples;

import com.mechalikh.pureedgesim.taskorchestrator.DefaultOrchestrator;
import com.mechalikh.pureedgesim.simulationmanager.Simulation;

public class ExampleJavaRoundRobin {
    public ExampleJavaRoundRobin() {
        Simulation sim = new Simulation();
        sim.setCustomEdgeOrchestrator(DefaultOrchestrator.class);
        sim.launchSimulation();
    }

    public static void main(String[] args) {
        new ExampleJavaRoundRobin();
    }
}
