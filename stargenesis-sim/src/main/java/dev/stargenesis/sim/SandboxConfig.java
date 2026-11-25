package dev.stargenesis.sim;

import dev.stargenesis.api.sim.SimulationBudget;
import java.util.Objects;

public record SandboxConfig(int width, int height, int agentCount, int actionIntervalSteps,
                            long durationSteps, long energyPerAgent, long nodeStock,
                            long carryCapacity, SimulationBudget budget) {
    public SandboxConfig {
        Objects.requireNonNull(budget);
        if (width < 9 || width > 32 || height < 5 || height > 32 || agentCount < 1 || agentCount > 4
                || agentCount > budget.maxAgents() || actionIntervalSteps < 1 || actionIntervalSteps > 100
                || durationSteps < 1 || durationSteps > 6000 || energyPerAgent < 1 || energyPerAgent > 100_000
                || nodeStock < 1 || nodeStock > 100_000 || carryCapacity < 1 || carryCapacity > 64)
            throw new IllegalArgumentException("Sandbox configuration exceeds bounded P1 limits");
    }
    public static SandboxConfig defaults() { return new SandboxConfig(9, 5, 2, 5, 2400, 2000, 180, 6, SimulationBudget.defaults()); }
    public SandboxConfig withAgentCount(int count) { return new SandboxConfig(width,height,count,actionIntervalSteps,durationSteps,energyPerAgent,nodeStock,carryCapacity,budget); }
    public SandboxConfig withBudget(SimulationBudget value) { return new SandboxConfig(width,height,agentCount,actionIntervalSteps,durationSteps,energyPerAgent,nodeStock,carryCapacity,value); }
}
