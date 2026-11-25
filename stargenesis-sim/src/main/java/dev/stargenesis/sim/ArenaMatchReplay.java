package dev.stargenesis.sim;

import dev.stargenesis.api.sim.EventRecord;
import dev.stargenesis.api.design.DesignId;
import dev.stargenesis.api.design.DesignMetadata;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

public record ArenaMatchReplay(int schemaVersion, String engineVersion, String matchId, long seed,
                               ArenaConfig config, Map<UUID, String> teams, String rulesetId,
                               String rulesetVersion, String rulesetHash, String initialStateHash,
                               List<Frame> frames) {
    public ArenaMatchReplay {
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(teams, "teams");
        teams = Map.copyOf(teams);
        frames = List.copyOf(frames);
        if (schemaVersion != 1 || !ArenaMatchWorld.ENGINE_VERSION.equals(engineVersion)
                || matchId == null || !matchId.matches("arena-[0-9a-f]{32}")
                || teams.size() < 2 || teams.size() > ArenaMatchWorld.MAX_PLAYERS
                || teams.values().stream().distinct().count() != 2
                || !validHash(rulesetHash) || !validHash(initialStateHash)
                || !matchId.equals(ArenaMatchWorld.matchIdFor(rulesetHash, teams))
                || rulesetId == null || rulesetVersion == null || frames.size() > config.durationSteps())
            throw new IllegalArgumentException("Invalid or unbounded arena replay");
        DesignMetadataBounds.validate(rulesetId, rulesetVersion, teams);
        java.util.Set<UUID> roster = teams.keySet();
        for (int i = 0; i < frames.size(); i++) {
            if (frames.get(i).input().tick() != i
                    || frames.get(i).input().actions().size() != teams.size()
                    || frames.get(i).input().actions().stream().anyMatch(action -> !roster.contains(action.actor())))
                throw new IllegalArgumentException("Arena replay frames are incomplete or unauthenticated");
        }
    }

    public record Frame(ArenaInput input, List<EventRecord> events, String stateHash, String eventHash) {
        public Frame {
            Objects.requireNonNull(input, "input");
            events = List.copyOf(events);
            if (!validHash(stateHash) || !validHash(eventHash)
                    || events.stream().anyMatch(event -> event == null || event.evidenceId().tick() != input.tick()))
                throw new IllegalArgumentException("Invalid arena replay commitment");
        }
    }

    private static boolean validHash(String value) {
        return value != null && value.matches("[0-9a-f]{64}");
    }

    private static final class DesignMetadataBounds {
        private static void validate(String id, String version, Map<UUID, String> teams) {
            try {
                new DesignId(id);
                DesignMetadata.requireVersion(version);
            } catch (IllegalArgumentException invalid) {
                throw new IllegalArgumentException("Invalid arena replay design or team identity", invalid);
            }
            if (teams.keySet().stream().anyMatch(Objects::isNull)
                    || teams.values().stream().anyMatch(team -> team == null || !team.matches("[a-z][a-z0-9_-]{0,31}")))
                throw new IllegalArgumentException("Invalid arena replay design or team identity");
        }
    }
}
