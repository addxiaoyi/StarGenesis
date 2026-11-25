package dev.stargenesis.paper.arena;

import dev.stargenesis.sim.ArenaAction;
import dev.stargenesis.sim.ArenaConfig;
import dev.stargenesis.sim.ArenaMatchReplayVerifier;
import dev.stargenesis.sim.ArenaMatchWorld;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ArenaMatchRoundTest {
    private static final List<UUID> PLAYERS = List.of(
            new UUID(0, 701), new UUID(0, 702), new UUID(0, 703), new UUID(0, 704));
    private static final UUID OBSERVER = new UUID(0, 799);
    private static final Map<UUID, String> TEAMS = Map.of(
            PLAYERS.get(0), "cedar", PLAYERS.get(1), "cedar",
            PLAYERS.get(2), "ember", PLAYERS.get(3), "ember");
    private static final String RULESET_HASH = "b".repeat(64);
    private static final String MATCH_ID = ArenaMatchWorld.matchIdFor(RULESET_HASH, TEAMS);
    @TempDir Path directory;

    @ParameterizedTest
    @CsvSource({"8,0,2", "0,8,2", "1,0,5", "4,4,2"})
    void illegalEarlyClaimDoesNotSealThePlayerOutOfTheRound(int x, int y, int force) throws Exception {
        ArenaMatchPersistence store = initial(3);
        byte[] before = savedBytes();

        assertThrows(IllegalArgumentException.class,
                () -> submit(store, ArenaAction.claim(PLAYERS.getFirst(), x, y, force)));
        assertArrayEquals(before, savedBytes());

        var accepted = submit(store, ArenaAction.claim(PLAYERS.getFirst(), 1, 0, 2));
        assertEquals(1, accepted.session().pending().size());
        assertEquals(0, accepted.view().tick());
    }

    @Test void invalidSubmissionAfterRestartPreservesPendingActionsAndCanBeRetried() throws Exception {
        ArenaMatchPersistence store = initial(2);
        var first = submit(store, ArenaAction.claim(PLAYERS.getFirst(), 1, 0, 2));
        ArenaMatchPersistence restarted = new ArenaMatchPersistence(directory);
        assertEquals(first.session(), restarted.load(MATCH_ID).orElseThrow());
        byte[] before = savedBytes();

        assertThrows(IllegalArgumentException.class,
                () -> submit(restarted, ArenaAction.claim(PLAYERS.get(1), 7, 7, 2)));
        assertArrayEquals(before, savedBytes());
        submit(restarted, ArenaAction.pass(PLAYERS.get(1)));
        submit(restarted, ArenaAction.pass(PLAYERS.get(2)));
        var completed = submit(restarted, ArenaAction.pass(PLAYERS.get(3)));

        assertEquals(1, completed.view().tick());
        assertTrue(completed.session().pending().isEmpty());
        assertEquals(List.of(OBSERVER), completed.session().spectators());
        assertEquals(2, completed.view().territory().get("cedar"));
        assertTrue(ArenaMatchReplayVerifier.verify(completed.session().replay()).passed());
        assertEquals(completed.session(), new ArenaMatchPersistence(directory).load(MATCH_ID).orElseThrow());
    }

    @Test void teamBudgetIncludesPreviouslySealedActions() throws Exception {
        ArenaMatchPersistence store = initial(3);
        submit(store, ArenaAction.claim(PLAYERS.get(0), 1, 0, 4));
        submit(store, ArenaAction.claim(PLAYERS.get(1), 1, 0, 4));
        submit(store, ArenaAction.pass(PLAYERS.get(2)));
        var firstRound = submit(store, ArenaAction.pass(PLAYERS.get(3)));
        assertEquals(2, firstRound.view().remainingUnits().get("cedar"));
        submit(store, ArenaAction.claim(PLAYERS.get(0), 0, 0, 2));
        byte[] before = savedBytes();

        assertThrows(IllegalArgumentException.class,
                () -> submit(store, ArenaAction.claim(PLAYERS.get(1), 0, 0, 2)));
        assertArrayEquals(before, savedBytes());

        submit(store, ArenaAction.pass(PLAYERS.get(1)));
        submit(store, ArenaAction.pass(PLAYERS.get(2)));
        var completed = submit(store, ArenaAction.pass(PLAYERS.get(3)));
        assertEquals(2, completed.view().tick());
        assertTrue(ArenaMatchReplayVerifier.verify(completed.session().replay()).passed());
    }

    @Test void invalidFinalClaimLeavesTheOtherPlayersCommittedAndAllowsRetry() throws Exception {
        ArenaMatchPersistence store = initial(2);
        for (int i = 0; i < 3; i++) submit(store, ArenaAction.pass(PLAYERS.get(i)));
        byte[] before = savedBytes();
        assertThrows(IllegalArgumentException.class,
                () -> submit(store, ArenaAction.claim(PLAYERS.get(3), 0, 0, 2)));
        assertArrayEquals(before, savedBytes());
        var completed = submit(store, ArenaAction.pass(PLAYERS.get(3)));
        assertEquals(1, completed.view().tick());
        assertTrue(completed.session().pending().isEmpty());
    }

    @Test void endedMatchRejectsNewPendingActionsWithoutChangingItsReplay() throws Exception {
        ArenaMatchPersistence store = initial(1);
        for (UUID player : PLAYERS) submit(store, ArenaAction.pass(player));
        byte[] before = savedBytes();
        assertThrows(IllegalStateException.class, () -> submit(store, ArenaAction.pass(PLAYERS.getFirst())));
        assertArrayEquals(before, savedBytes());
        assertTrue(ArenaMatchReplayVerifier.verify(store.load(MATCH_ID).orElseThrow().replay()).passed());
    }

    @Test void observerImpersonationAndDuplicateSubmissionsCannotChangePendingActions() throws Exception {
        ArenaMatchPersistence store = initial(2);
        var first = submit(store, ArenaAction.pass(PLAYERS.getFirst()));
        byte[] before = savedBytes();
        assertThrows(SecurityException.class, () -> submit(store, ArenaAction.pass(OBSERVER)));
        assertThrows(SecurityException.class, () -> ArenaMatchRound.submit(first.session(), PLAYERS.get(1),
                ArenaAction.pass(PLAYERS.get(2))));
        assertThrows(IllegalStateException.class, () -> submit(store, ArenaAction.pass(PLAYERS.getFirst())));
        assertArrayEquals(before, savedBytes());
    }

    private ArenaMatchPersistence initial(int duration) throws Exception {
        ArenaMatchPersistence store = new ArenaMatchPersistence(directory);
        try (ArenaMatchWorld world = new ArenaMatchWorld(MATCH_ID, 88, new ArenaConfig(8, 8, duration, 4),
                "public:arena", "1.0.0", RULESET_HASH, TEAMS)) {
            store.save(new ArenaMatchPersistence.MatchSession(ArenaMatchPersistence.SCHEMA_VERSION,
                    world.replay(), Map.of(), List.of(OBSERVER)));
        }
        return store;
    }

    private ArenaMatchRound.Submission submit(ArenaMatchPersistence store, ArenaAction action) throws Exception {
        var result = ArenaMatchRound.submit(store.load(MATCH_ID).orElseThrow(), action.actor(), action);
        store.save(result.session());
        return result;
    }

    private byte[] savedBytes() throws Exception { return Files.readAllBytes(directory.resolve(MATCH_ID + ".match.json")); }
}
