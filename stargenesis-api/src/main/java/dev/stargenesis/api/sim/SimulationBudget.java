package dev.stargenesis.api.sim;
public record SimulationBudget(int nodesPerAgentStep, int maxAgents, int pathRequestsPerStep, long maxSteps,
                               int sensorRadius, int messagesPerStep) {
    public SimulationBudget {
        if (nodesPerAgentStep < 1 || nodesPerAgentStep > 4096 || maxAgents < 1 || maxAgents > 256
                || pathRequestsPerStep < 0 || pathRequestsPerStep > 64 || maxSteps < 1 || maxSteps > 100_000
                || sensorRadius < 1 || sensorRadius > 64 || messagesPerStep < 0 || messagesPerStep > 1024)
            throw new IllegalArgumentException("Simulation budget outside hard safety limits");
    }
    public static SimulationBudget defaults() { return new SimulationBudget(128, 16, 8, 3000, 16, 64); }
}
