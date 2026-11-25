package dev.stargenesis.sim;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FactoryMatchIdentityTest {
    private static final List<UUID> PLAYERS = List.of(
            new UUID(0, 3001), new UUID(0, 3002), new UUID(0, 3003), new UUID(0, 3004));
    private static final FactoryConfig CONFIG = new FactoryConfig(100, 16, 16, 4, 2, 30, 16, 1_000, 5_000);

    @Test void independentRunsRetainEconomicOutcomesButHaveDistinctEvidence() {
        try (var first = new FactoryWorld(new UUID(0, 101), PLAYERS, 77, CONFIG);
             var second = new FactoryWorld(new UUID(0, 102), PLAYERS, 77, CONFIG)) {
            assertNotEquals(first.matchId(), second.matchId());
            assertNotEquals(first.stateHash(), second.stateHash());
            for (var action : List.of(
                    new FactoryAction(PLAYERS.get(0), FactoryAction.Type.BID, "ore_1", 10),
                    new FactoryAction(PLAYERS.get(2), FactoryAction.Type.BID, "ore_1", 15),
                    FactoryAction.pass(PLAYERS.get(1)), FactoryAction.pass(PLAYERS.get(3)),
                    new FactoryAction(PLAYERS.get(2), FactoryAction.Type.PLACE_ORDER, "press", 2),
                    FactoryAction.pass(PLAYERS.get(3)))) {
                first.step(new FactoryInput(first.tick(), action));
                second.step(new FactoryInput(second.tick(), action));
                assertEquals(first.ledgerSnapshot(), second.ledgerSnapshot());
            }
            assertEquals(1, first.view().recoveredOrders());
            assertEquals(first.view().orders(), second.view().orders());
            assertNotEquals(first.replay().frames().getLast().eventHash(), second.replay().frames().getLast().eventHash());
            for (var replay : List.of(first.replay(), second.replay())) {
                assertEquals(2, replay.schemaVersion());
                assertEquals(FactoryWorld.ENGINE_VERSION, replay.engineVersion());
                assertEquals(replay.matchId(), FactoryWorld.matchIdFor(PLAYERS, UUID.fromString(replay.runId())));
                assertTrue(FactoryReplayVerifier.verify(replay).passed());
                assertTrue(replay.frames().stream().flatMap(frame -> frame.events().stream())
                        .allMatch(event -> event.evidenceId().world().equals(replay.matchId())));
            }
            try (var restored = FactoryWorld.restore(first.replay())) {
                var input = new FactoryInput(first.tick(), FactoryAction.pass(PLAYERS.getFirst()));
                assertEquals(first.step(input), restored.step(input));
                assertEquals(first.replay(), restored.replay());
            }
        }
    }

    @Test void runIdentityBindsOrderedTeamsAndRejectsSchemaEngineMismatches() {
        try (var world = new FactoryWorld(new UUID(0, 101), PLAYERS, 77, CONFIG)) {
            var replay = world.replay();
            var reordered = List.of(PLAYERS.get(0), PLAYERS.get(2), PLAYERS.get(1), PLAYERS.get(3));
            assertNotEquals(world.matchId(), FactoryWorld.matchIdFor(reordered, UUID.fromString(replay.runId())));
            assertThrows(IllegalArgumentException.class, () -> new FactoryReplay(2, replay.engineVersion(), replay.matchId(),
                    reordered, replay.seed(), CONFIG, replay.initialStateHash(), List.of(), replay.runId()));
            for (String runId : List.of(new UUID(0, 102).toString(), "0-0-0-0-101", "invalid"))
                assertThrows(IllegalArgumentException.class, () -> copy(replay, 2, replay.engineVersion(), runId, 77));
            assertThrows(IllegalArgumentException.class, () -> copy(replay, 2, replay.engineVersion(), null, 77));
            assertThrows(IllegalArgumentException.class, () -> copy(replay, 1, replay.engineVersion(), replay.runId(), 77));
            assertThrows(IllegalArgumentException.class, () -> copy(replay, 2, FactoryWorld.LEGACY_ENGINE_VERSION, replay.runId(), 77));
            assertThrows(IllegalArgumentException.class, () -> copy(replay, 3, replay.engineVersion(), replay.runId(), 77));
            var changedSeed = copy(replay, 2, replay.engineVersion(), replay.runId(), 78);
            assertFalse(FactoryReplayVerifier.verify(changedSeed).passed());
            assertThrows(IllegalArgumentException.class, () -> FactoryWorld.restore(changedSeed));
        }
    }

    @Test void legacyConstructorRetainsV1SemanticsAndRejectsAnInjectedRunId() {
        try (var world = new FactoryWorld(FactoryWorld.matchIdFor(PLAYERS), PLAYERS, 77, CONFIG)) {
            var replay = world.replay();
            assertEquals(1, replay.schemaVersion());
            assertEquals(FactoryWorld.LEGACY_ENGINE_VERSION, replay.engineVersion());
            assertNull(replay.runId());
            assertTrue(FactoryReplayVerifier.verify(replay).passed());
            assertThrows(IllegalArgumentException.class,
                    () -> copy(replay, 1, replay.engineVersion(), new UUID(0, 101).toString(), 77));
        }
    }

    private static FactoryReplay copy(FactoryReplay replay, int schema, String engine, String runId, long seed) {
        return new FactoryReplay(schema, engine, replay.matchId(), replay.participants(), seed,
                replay.config(), replay.initialStateHash(), replay.frames(), runId);
    }
}
