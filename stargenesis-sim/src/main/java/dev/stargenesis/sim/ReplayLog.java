package dev.stargenesis.sim;

import dev.stargenesis.api.sim.EventRecord;
import dev.stargenesis.api.sim.StepInput;
import java.util.List;
import java.util.Objects;

/** Versioned, bounded data only. A host may persist it without handing filesystem access to sim. */
public record ReplayLog(String engineVersion, int schemaVersion, String worldId, long seed,
                        SandboxConfig config, List<Frame> frames) {
    public ReplayLog {
        Objects.requireNonNull(engineVersion); Objects.requireNonNull(worldId); Objects.requireNonNull(config);
        frames = List.copyOf(frames);
        if (schemaVersion != 1 || frames.size() > 6000) throw new IllegalArgumentException("Unsupported or unbounded replay");
    }
    public List<StepInput> inputs() { return frames.stream().map(Frame::input).toList(); }
    public List<String> hashes() { return frames.stream().map(Frame::stateHash).toList(); }
    public record Frame(StepInput input, String stateHash, List<EventRecord> events) {
        public Frame {
            Objects.requireNonNull(input);
            if (stateHash == null || !stateHash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid replay state hash");
            events = List.copyOf(events);
            if (events.size() > 128) throw new IllegalArgumentException("Unbounded replay events");
        }
    }
}
