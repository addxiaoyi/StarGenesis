package dev.stargenesis.sim;

import dev.stargenesis.api.sim.EventRecord;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Complete, bounded inputs and commitments for one four-player factory match. */
public record FactoryReplay(int schemaVersion, String engineVersion, String matchId,
                            List<UUID> participants, long seed, FactoryConfig config,
                            String initialStateHash, List<Frame> frames) {
    public FactoryReplay {
        Objects.requireNonNull(engineVersion, "engineVersion");
        Objects.requireNonNull(config, "config");
        participants = List.copyOf(participants);
        frames = List.copyOf(frames);
        if (schemaVersion != 1 || !FactoryWorld.ENGINE_VERSION.equals(engineVersion)
                || matchId == null || !matchId.matches("factory-[0-9a-f]{32}")
                || participants.size() != FactoryWorld.PLAYER_COUNT || participants.stream().anyMatch(Objects::isNull)
                || participants.stream().distinct().count() != FactoryWorld.PLAYER_COUNT
                || !validHash(initialStateHash) || frames.size() > config.maxTicks())
            throw new IllegalArgumentException("Invalid or unbounded factory replay");
        for (int i = 0; i < frames.size(); i++) {
            if (frames.get(i).input().tick() != i
                    || !participants.contains(frames.get(i).input().action().actor())
                    || frames.get(i).events().stream().anyMatch(event -> !event.evidenceId().world().equals(matchId)))
                throw new IllegalArgumentException("Factory replay inputs are not sequential or authenticated");
        }
    }

    public record Frame(FactoryInput input, String stateHash, List<EventRecord> events, String eventHash) {
        public Frame {
            Objects.requireNonNull(input, "input");
            events = List.copyOf(events);
            if (events.size() > FactoryWorld.MAX_EVENTS_PER_TICK || !validHash(stateHash) || !validHash(eventHash)
                    || events.stream().anyMatch(event -> event == null || event.evidenceId().tick() != input.tick()))
                throw new IllegalArgumentException("Invalid factory replay commitment");
        }
    }

    private static boolean validHash(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }
}
