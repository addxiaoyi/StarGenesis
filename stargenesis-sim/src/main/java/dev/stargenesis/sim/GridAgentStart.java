package dev.stargenesis.sim;

import dev.stargenesis.api.sim.AgentId;

import java.util.Objects;

public record GridAgentStart(AgentId id, int x, int y) {
    public GridAgentStart {
        Objects.requireNonNull(id, "id");
        if (x < 0 || y < 0) throw new IllegalArgumentException("Agent coordinates cannot be negative");
    }
}
