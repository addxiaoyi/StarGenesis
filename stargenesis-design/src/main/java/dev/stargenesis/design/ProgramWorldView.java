package dev.stargenesis.design;

import dev.stargenesis.api.sim.AgentId;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Minimal immutable sensor input supplied by a simulation adapter. */
public record ProgramWorldView(int width, int height, long tick, long sharedStock,
                               List<Cell> cells, Map<AgentId, Agent> agents) {
    public ProgramWorldView {
        if (width < 1 || height < 1 || (long) width * height > 4096 || tick < 0 || sharedStock < 0)
            throw new IllegalArgumentException("Invalid program world view bounds");
        cells = List.copyOf(cells);
        agents = Map.copyOf(agents);
        if (cells.size() != width * height || agents.isEmpty() || agents.size() > 256)
            throw new IllegalArgumentException("Invalid program world view size");
        for (int index = 0; index < cells.size(); index++) {
            Cell cell = Objects.requireNonNull(cells.get(index), "cell");
            if (cell.x() != index % width || cell.y() != index / width)
                throw new IllegalArgumentException("Program world cells must use row-major order");
        }
        for (Map.Entry<AgentId, Agent> entry : agents.entrySet()) {
            AgentId id = entry.getKey();
            Agent agent = entry.getValue();
            if (!id.equals(agent.id()) || agent.x() >= width || agent.y() >= height
                    || cells.get(agent.y() * width + agent.x()).blocked())
                throw new IllegalArgumentException("Program agent is outside the world view");
        }
    }

    public Cell cellAt(int x, int y) {
        if (x < 0 || x >= width || y < 0 || y >= height)
            return new Cell(Math.clamp(x, 0, width - 1), Math.clamp(y, 0, height - 1), true, 0);
        return cells.get(y * width + x);
    }

    public record Cell(int x, int y, boolean blocked, long resourceUnits) {
        public Cell {
            if (x < 0 || y < 0 || resourceUnits < 0 || blocked && resourceUnits != 0)
                throw new IllegalArgumentException("Invalid program world cell");
        }
    }

    public record Agent(AgentId id, int x, int y, long inventory) {
        public Agent {
            Objects.requireNonNull(id, "id");
            if (x < 0 || y < 0 || inventory < 0) throw new IllegalArgumentException("Invalid program actor");
        }
    }
}
