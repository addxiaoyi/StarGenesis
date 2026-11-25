package dev.stargenesis.sim;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ArenaMatchWorldTest {
    private static final List<UUID> PLAYERS = List.of(
            new UUID(0, 501), new UUID(0, 502), new UUID(0, 503), new UUID(0, 504));
    private static final Map<UUID, String> TEAMS = Map.of(
            PLAYERS.get(0), "cedar", PLAYERS.get(1), "cedar",
            PLAYERS.get(2), "ember", PLAYERS.get(3), "ember");
    private static final String RULESET_ID = "public:arena_small";
    private static final String RULESET_VERSION = "1.2.0";
    private static final String RULESET_HASH = "a".repeat(64);

    @Test void simultaneousClaimsUseDeterministicInitiativeAndAwardTerritoryOnce() {
        ArenaConfig config = new ArenaConfig(4, 4, 6, 4);
        try (ArenaMatchWorld world = world(42, config)) {
            world.step(input(0, ArenaAction.claim(PLAYERS.get(0), 1, 0, 2),
                    ArenaAction.pass(PLAYERS.get(1)), ArenaAction.claim(PLAYERS.get(2), 2, 3, 2),
                    ArenaAction.pass(PLAYERS.get(3))));
            world.step(input(1, ArenaAction.claim(PLAYERS.get(1), 1, 1, 2),
                    ArenaAction.pass(PLAYERS.get(0)), ArenaAction.claim(PLAYERS.get(3), 2, 2, 2),
                    ArenaAction.pass(PLAYERS.get(2))));

            ArenaMatchStepResult result = world.step(input(2,
                    ArenaAction.claim(PLAYERS.get(0), 1, 2, 2), ArenaAction.pass(PLAYERS.get(1)),
                    ArenaAction.claim(PLAYERS.get(2), 1, 2, 2), ArenaAction.pass(PLAYERS.get(3))));
            ArenaMatchView view = world.view();

            assertEquals(3, result.tick());
            assertTrue(result.events().stream().anyMatch(event -> event.type().equals("arena.territory_contested")));
            assertEquals(7, view.territory().values().stream().mapToInt(Integer::intValue).sum());
            assertTrue(view.territory().values().stream().allMatch(score -> score >= 1));
            assertNotEquals(view.scores().get("cedar"), view.scores().get("ember"));
        }
    }

    @Test void missingRosterActionAndDisconnectedClaimLeaveStateUntouched() {
        try (ArenaMatchWorld world = world(9,
                new ArenaConfig(8, 8, 5, 4))) {
            String before = world.stateHash();
            assertThrows(IllegalArgumentException.class, () -> world.step(new ArenaInput(0,
                    List.of(ArenaAction.pass(PLAYERS.getFirst())))));
            assertThrows(IllegalArgumentException.class, () -> world.step(input(0,
                    ArenaAction.claim(PLAYERS.getFirst(), 7, 0, 1), ArenaAction.pass(PLAYERS.get(1)),
                    ArenaAction.pass(PLAYERS.get(2)), ArenaAction.pass(PLAYERS.get(3)))));
            assertThrows(SecurityException.class, () -> world.step(input(0,
                    ArenaAction.pass(PLAYERS.getFirst()), ArenaAction.pass(PLAYERS.get(1)),
                    ArenaAction.pass(new UUID(0, 999)), ArenaAction.pass(PLAYERS.get(3)))));
            assertEquals(0, world.view().tick());
            assertEquals(before, world.stateHash());
        }
    }

    @Test void matchSettlesTerritoryScoreAndObserversReceiveReadOnlyProjection() {
        try (ArenaMatchWorld world = world(17,
                new ArenaConfig(8, 8, 2, 4))) {
            ArenaMatchView observed = world.observe(new UUID(0, 777));
            assertEquals(world.view(), observed);
            assertEquals(1, observed.leaderboard().get("cedar"));
            assertEquals(1, observed.leaderboard().get("ember"));
            assertThrows(NullPointerException.class, () -> world.observe(null));
            world.step(input(0, ArenaAction.claim(PLAYERS.getFirst(), 1, 0, 1), ArenaAction.pass(PLAYERS.get(1)),
                    ArenaAction.pass(PLAYERS.get(2)), ArenaAction.pass(PLAYERS.get(3))));
            ArenaMatchStepResult finalStep = world.step(input(1, ArenaAction.pass(PLAYERS.getFirst()),
                    ArenaAction.pass(PLAYERS.get(1)), ArenaAction.pass(PLAYERS.get(2)), ArenaAction.pass(PLAYERS.get(3))));

            assertTrue(finalStep.terminal());
            assertEquals("TERRITORY_SCORE", finalStep.reason());
            assertEquals(2, world.view().tick());
            assertFalse(world.view().settledWinner().isEmpty());
            assertThrows(IllegalStateException.class, () -> world.step(input(2, ArenaAction.pass(PLAYERS.getFirst()),
                    ArenaAction.pass(PLAYERS.get(1)), ArenaAction.pass(PLAYERS.get(2)), ArenaAction.pass(PLAYERS.get(3)))));
        }
    }

    @Test void replayRestoresTheSameMatchAndRejectsStateOrEventTampering() {
        ArenaMatchReplay replay;
        ArenaMatchView expected;
        try (ArenaMatchWorld world = world(72,
                new ArenaConfig(8, 8, 5, 4))) {
            world.step(input(0, ArenaAction.claim(PLAYERS.getFirst(), 1, 0, 2), ArenaAction.pass(PLAYERS.get(1)),
                    ArenaAction.pass(PLAYERS.get(2)), ArenaAction.pass(PLAYERS.get(3))));
            world.step(input(1, ArenaAction.pass(PLAYERS.getFirst()), ArenaAction.pass(PLAYERS.get(1)),
                    ArenaAction.claim(PLAYERS.get(2), 6, 7, 2), ArenaAction.pass(PLAYERS.get(3))));
            replay = world.replay();
            expected = world.view();
        }

        assertTrue(ArenaMatchReplayVerifier.verify(replay).passed());
        try (ArenaMatchWorld restored = ArenaMatchWorld.restore(replay)) { assertEquals(expected, restored.view()); }
        ArenaMatchReplay.Frame frame = replay.frames().getFirst();
        ArenaMatchReplay.Frame altered = new ArenaMatchReplay.Frame(frame.input(),
                List.of(), frame.stateHash(), frame.eventHash());
        ArenaMatchReplay tampered = new ArenaMatchReplay(replay.schemaVersion(), replay.engineVersion(), replay.matchId(),
                replay.seed(), replay.config(), replay.teams(), replay.rulesetId(), replay.rulesetVersion(),
                replay.rulesetHash(), replay.initialStateHash(), List.of(altered));
        assertFalse(ArenaMatchReplayVerifier.verify(tampered).passed());
        assertThrows(IllegalArgumentException.class, () -> ArenaMatchWorld.restore(tampered));
    }

    @Test void rosterAndRulesetIdentityArePinnedToTheMatchId() {
        assertThrows(IllegalArgumentException.class, () -> new ArenaMatchWorld(
                "arena-00000000000000000000000000000005", 1, new ArenaConfig(4, 4, 4, 2),
                RULESET_ID, RULESET_VERSION, RULESET_HASH, TEAMS));
        assertEquals(ArenaMatchWorld.matchIdFor(RULESET_HASH, TEAMS), ArenaMatchWorld.matchIdFor(RULESET_HASH, new LinkedHashMap<>(TEAMS)));
    }

    @Test void maximumMapAndEightPlayerRosterCompleteABoundedScaleRun() {
        Map<UUID, String> fullRoster = new java.util.TreeMap<>(java.util.Comparator.comparing(UUID::toString));
        for (int i = 0; i < ArenaMatchWorld.MAX_PLAYERS; i++)
            fullRoster.put(new UUID(0, 10_000 + i), i < 4 ? "cedar" : "ember");
        ArenaConfig config = new ArenaConfig(128, 128, 128, 64);
        String matchId = ArenaMatchWorld.matchIdFor(RULESET_HASH, fullRoster);
        long started = System.nanoTime();
        try (ArenaMatchWorld world = new ArenaMatchWorld(matchId, 8_888, config,
                RULESET_ID, RULESET_VERSION, RULESET_HASH, fullRoster)) {
            for (int round = 0; round < 128; round++) {
                List<ArenaAction> actions = fullRoster.keySet().stream().map(ArenaAction::pass).toList();
                world.step(new ArenaInput(world.view().tick(), actions));
            }
            long elapsedMillis = (System.nanoTime() - started) / 1_000_000;
            assertEquals(128, world.view().tick());
            assertTrue(world.view().terminal());
            assertEquals(128, world.replay().frames().size());
            assertEquals(8L * 128, world.replay().frames().stream().flatMap(frame -> frame.events().stream())
                    .filter(event -> event.type().equals("arena.player_passed")).count());
            System.out.println("P11_SCALE arena=128x128 players=8 rounds=128 elapsedMillis=" + elapsedMillis
                    + " finalHash=" + world.stateHash());
        }
    }

    private ArenaMatchWorld world(long seed, ArenaConfig config) {
        String pinnedId = ArenaMatchWorld.matchIdFor(RULESET_HASH, TEAMS);
        return new ArenaMatchWorld(pinnedId, seed, config, RULESET_ID, RULESET_VERSION, RULESET_HASH, TEAMS);
    }

    private ArenaInput input(long tick, ArenaAction... actions) { return new ArenaInput(tick, List.of(actions)); }
}
