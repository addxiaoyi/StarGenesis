package dev.stargenesis.sim;

import dev.stargenesis.api.sim.EvidenceId;

import java.util.List;
import java.util.Objects;

/** Bounded, data-only input and commitment history for one deterministic ecology run. */
public record EcologyReplay(int schemaVersion, String engineVersion, String worldId, long seed,
                            EcologyConfig config, String initialStateHash, List<Frame> frames) {
    public EcologyReplay {
        Objects.requireNonNull(engineVersion, "engineVersion");
        Objects.requireNonNull(config, "config");
        frames = List.copyOf(frames);
        new EvidenceId(worldId, 0, 0);
        if (schemaVersion != 1 || !engineVersion.matches("[a-z0-9][a-z0-9_./-]{0,63}")
                || !validHash(initialStateHash)
                || frames.size() > config.maxTicks())
            throw new IllegalArgumentException("Unsupported or unbounded ecology replay");
        for (int i = 0; i < frames.size(); i++) {
            if (frames.get(i).input().tick() != i) throw new IllegalArgumentException("Nonsequential ecology replay");
        }
    }

    public record Frame(EcologyInput input, String stateHash, String eventHash) {
        public Frame {
            Objects.requireNonNull(input, "input");
            if (!EcologyReplay.validHash(stateHash) || !EcologyReplay.validHash(eventHash))
                throw new IllegalArgumentException("Invalid ecology replay commitment");
        }
    }

    private static boolean validHash(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }
}
