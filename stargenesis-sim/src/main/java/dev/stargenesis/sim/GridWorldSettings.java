package dev.stargenesis.sim;

import dev.stargenesis.api.sim.SimulationBudget;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record GridWorldSettings(String worldId, long seed, int width, int height, long stepDurationMillis,
                               SimulationBudget budget, List<GridAgentStart> agents) {
    public GridWorldSettings {
        if (worldId == null || !worldId.matches("[a-z0-9][a-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("Invalid world ID");
        }
        if (width < 1 || width > 64 || height < 1 || height > 64 || (long) width * height > 4096) {
            throw new IllegalArgumentException("Grid dimensions exceed the 4096-cell limit");
        }
        if (stepDurationMillis < 1 || stepDurationMillis > 60_000) {
            throw new IllegalArgumentException("Invalid logical step duration");
        }
        Objects.requireNonNull(budget, "budget");
        agents = List.copyOf(agents);
        if (agents.isEmpty() || agents.size() > budget.maxAgents()) {
            throw new IllegalArgumentException("Agent count is outside the configured budget");
        }
        Set<Object> ids = new HashSet<>();
        Set<Long> positions = new HashSet<>();
        for (GridAgentStart agent : agents) {
            Objects.requireNonNull(agent, "agent");
            if (agent.x() >= width || agent.y() >= height) {
                throw new IllegalArgumentException("Agent spawn is outside the grid");
            }
            if (!ids.add(agent.id())) throw new IllegalArgumentException("Duplicate agent ID");
            long position = ((long) agent.y() << 32) | Integer.toUnsignedLong(agent.x());
            if (!positions.add(position)) throw new IllegalArgumentException("Agents cannot share a spawn cell");
        }
    }
}
