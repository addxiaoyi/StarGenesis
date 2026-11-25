package dev.stargenesis.sim;

import dev.stargenesis.api.sim.AgentId;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public record GridWorldSnapshot(String engineVersion, String worldId, long seed, int width, int height, long tick,
                                long sharedStock, List<Cell> cells, List<Agent> agents, String stateHash) {
    public GridWorldSnapshot {
        if (!GridSimulationWorld.ENGINE_VERSION.equals(engineVersion)) {
            throw new IllegalArgumentException("Unsupported grid engine version");
        }
        if (worldId == null || !worldId.matches("[a-z0-9][a-z0-9_-]{0,63}")
                || width < 1 || width > 64 || height < 1 || height > 64 || (long) width * height > 4096
                || tick < 0 || sharedStock < 0 || stateHash == null || !stateHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid grid snapshot metadata");
        }
        cells = List.copyOf(cells);
        agents = List.copyOf(agents);
        if (cells.size() != width * height || agents.isEmpty() || agents.size() > 256) {
            throw new IllegalArgumentException("Invalid grid snapshot size");
        }
        for (int i = 0; i < cells.size(); i++) {
            Cell cell = Objects.requireNonNull(cells.get(i), "cell");
            if (cell.x() != i % width || cell.y() != i / width) {
                throw new IllegalArgumentException("Cells must use canonical row-major order");
            }
        }
        Set<AgentId> ids = new HashSet<>();
        Set<Long> positions = new HashSet<>();
        for (Agent agent : agents) {
            Objects.requireNonNull(agent, "agent");
            if (agent.x() >= width || agent.y() >= height || cells.get(agent.y() * width + agent.x()).blocked()) {
                throw new IllegalArgumentException("Agent is outside the grid or inside a blocked cell");
            }
            if (!ids.add(agent.id())) throw new IllegalArgumentException("Duplicate agent in snapshot");
            long position = ((long) agent.y() << 32) | Integer.toUnsignedLong(agent.x());
            if (!positions.add(position)) throw new IllegalArgumentException("Agents cannot share a cell");
        }
    }

    public record Cell(int x, int y, boolean blocked, long resourceUnits) {
        public Cell {
            if (x < 0 || y < 0 || resourceUnits < 0 || blocked && resourceUnits != 0) {
                throw new IllegalArgumentException("Invalid grid cell");
            }
        }
    }

    public record Agent(AgentId id, int x, int y, long inventory) {
        public Agent {
            Objects.requireNonNull(id, "id");
            if (x < 0 || y < 0 || inventory < 0) throw new IllegalArgumentException("Invalid grid agent");
        }
    }
}
