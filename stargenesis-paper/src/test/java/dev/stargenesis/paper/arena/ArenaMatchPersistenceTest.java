package dev.stargenesis.paper.arena;

import dev.stargenesis.sim.ArenaAction;
import dev.stargenesis.sim.ArenaConfig;
import dev.stargenesis.sim.ArenaMatchWorld;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.*;

class ArenaMatchPersistenceTest {
    private static final List<UUID> PLAYERS = List.of(
            new UUID(0, 701), new UUID(0, 702), new UUID(0, 703), new UUID(0, 704));
    private static final Map<UUID, String> TEAMS = Map.of(
            PLAYERS.get(0), "cedar", PLAYERS.get(1), "cedar",
            PLAYERS.get(2), "ember", PLAYERS.get(3), "ember");
    private static final String RULESET_HASH = "b".repeat(64);

    @Test void savedPartialRoundAndSpectatorSurviveRestart(@TempDir Path directory) throws Exception {
        ArenaMatchPersistence persistence = new ArenaMatchPersistence(directory);
        String matchId = ArenaMatchWorld.matchIdFor(RULESET_HASH, TEAMS);
        ArenaMatchPersistence.MatchSession initial;
        try (ArenaMatchWorld world = new ArenaMatchWorld(matchId, 88, new ArenaConfig(8, 8, 10, 4),
                "public:arena", "1.0.0", RULESET_HASH, TEAMS)) {
            initial = ArenaMatchPersistence.MatchSession.initial(world.replay());
        }
        ArenaMatchPersistence.MatchSession partial = new ArenaMatchPersistence.MatchSession(
                ArenaMatchPersistence.SCHEMA_VERSION, initial.replay(),
                Map.of(PLAYERS.getFirst(), ArenaAction.claim(PLAYERS.getFirst(), 1, 0, 2)),
                List.of(new UUID(0, 799)));
        persistence.save(partial);

        ArenaMatchPersistence.MatchSession restored = persistence.load(matchId).orElseThrow();
        assertEquals(partial, restored);
        assertEquals(restored, persistence.findForPlayer(new UUID(0, 799)).getFirst());
        assertEquals(restored, persistence.findForPlayer(PLAYERS.getFirst()).getFirst());
    }

    @Test void matchSessionRejectsUnknownFields(@TempDir Path directory) throws Exception {
        ArenaMatchPersistence persistence = new ArenaMatchPersistence(directory);
        String matchId = ArenaMatchWorld.matchIdFor(RULESET_HASH, TEAMS);
        try (ArenaMatchWorld world = new ArenaMatchWorld(matchId, 3, new ArenaConfig(8, 8, 10, 4),
                "public:arena", "1.0.0", RULESET_HASH, TEAMS)) {
            persistence.save(ArenaMatchPersistence.MatchSession.initial(world.replay()));
        }
        Path file = directory.resolve(matchId + ".match.json");
        String encoded = Files.readString(file);
        Files.writeString(file, encoded.replaceFirst("\\{", "{\"futureField\":true,"));
        assertThrows(java.io.IOException.class, () -> persistence.load(matchId));
    }

    @Test void preMatchTeamRosterSurvivesRestartAndRejectsOverlappingMembership(@TempDir Path directory) throws Exception {
        ArenaMatchPersistence persistence = new ArenaMatchPersistence(directory);
        Map<UUID, String> roster = Map.of(PLAYERS.get(0), "cedar", PLAYERS.get(1), "cedar");
        persistence.saveTeamRoster(roster);
        assertEquals(roster, new ArenaMatchPersistence(directory).loadTeamRoster());

        Map<UUID, String> invalid = new HashMap<>();
        invalid.put(PLAYERS.get(0), "cedar");
        invalid.put(PLAYERS.get(1), "cedar");
        invalid.put(PLAYERS.get(2), "ember");
        invalid.put(PLAYERS.get(3), "ember");
        invalid.put(new UUID(0, 799), "cedar");
        invalid.put(new UUID(0, 798), "cedar");
        invalid.put(new UUID(0, 797), "cedar");
        invalid.put(new UUID(0, 796), "ember");
        assertThrows(java.io.IOException.class, () -> persistence.saveTeamRoster(invalid));
        assertEquals(roster, persistence.loadTeamRoster());
    }

    @Test void twoTeamsMayHaveDifferentCountsWithinTheFourSeatLimit(@TempDir Path directory) throws Exception {
        ArenaMatchPersistence persistence = new ArenaMatchPersistence(directory);
        Map<UUID, String> roster = Map.of(
                PLAYERS.get(0), "cedar", PLAYERS.get(1), "cedar",
                PLAYERS.get(2), "cedar", PLAYERS.get(3), "cedar",
                new UUID(0, 711), "ember", new UUID(0, 712), "ember");

        persistence.saveTeamRoster(roster);

        assertEquals(roster, persistence.loadTeamRoster());
    }
}
