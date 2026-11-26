package dev.stargenesis.integrations;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Copied simulation coordinates only. A projection cannot write back into the lab. */
@FunctionalInterface
public interface LabProjectionSource {
    Optional<Frame> snapshot(UUID owner);

    record Agent(UUID id, int x, int z) {
        public Agent { Objects.requireNonNull(id, "id"); }
    }

    /** sessionId changes on every start/resume/replay, even if the persisted world ID is reused. */
    record Frame(String sessionId, String worldId, long tick, int width, int height, List<Agent> agents) {
        public Frame {
            if (sessionId == null || !sessionId.matches("[a-zA-Z0-9_-]{1,128}")
                    || worldId == null || !worldId.matches("[a-zA-Z0-9_-]{1,128}")
                    || tick < 0 || width < 1 || width > 16 || height < 1 || height > 16)
                throw new IllegalArgumentException("Invalid lab projection frame");
            Objects.requireNonNull(agents, "agents");
            if (agents.isEmpty() || agents.size() > 4) throw new IllegalArgumentException("Lab projection requires 1..4 agents");
            var ids = new HashSet<UUID>();
            for (Agent agent : agents) {
                if (agent == null || !ids.add(agent.id()) || agent.x() < 0 || agent.x() >= width
                        || agent.z() < 0 || agent.z() >= height)
                    throw new IllegalArgumentException("Invalid or repeated projection agent");
            }
            agents = List.copyOf(agents);
        }
    }
}
