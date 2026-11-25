package dev.stargenesis.sim;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ArenaMatchIdentityTest {
    private static final UUID ALICE = new UUID(0, 41), BOB = new UUID(0, 42);
    private static final Map<UUID, String> TEAMS = Map.of(ALICE, "cedar", BOB, "ember");
    private static final String HASH = "a".repeat(64);

    @Test void sameRunReplaysDeterministicallyWhileRepeatedGamesHaveIndependentEvidence() {
        UUID run = new UUID(0, 101);
        try (var first = world(run); var same = world(run); var next = world(new UUID(0, 102))) {
            ArenaInput input = new ArenaInput(0, List.of(ArenaAction.claim(ALICE, 1, 0, 1), ArenaAction.pass(BOB)));
            var firstStep = first.step(input);
            assertEquals(firstStep, same.step(input));
            var nextStep = next.step(input);
            assertEquals(first.view().territory(), next.view().territory());
            assertNotEquals(first.matchId(), next.matchId());
            assertNotEquals(firstStep.events().getFirst().evidenceId(), nextStep.events().getFirst().evidenceId());
            assertNotEquals(firstStep.stateHash(), nextStep.stateHash());
            assertEquals(2, first.replay().schemaVersion());
            assertEquals(ArenaMatchWorld.ENGINE_VERSION, first.replay().engineVersion());
            assertEquals(run.toString(), first.replay().runId());
            assertTrue(ArenaMatchReplayVerifier.verify(first.replay()).passed());
            assertTrue(ArenaMatchReplayVerifier.verify(next.replay()).passed());
            try (var restored = ArenaMatchWorld.restore(first.replay())) {
                var finalRound = new ArenaInput(1, List.of(ArenaAction.pass(ALICE), ArenaAction.pass(BOB)));
                assertEquals(first.step(finalRound), restored.step(finalRound));
                assertEquals(first.replay(), restored.replay());
            }
        }
    }

    @Test void runRulesAndRosterAreAllBoundToTheMatchId() {
        UUID run = new UUID(0, 101);
        String expected = ArenaMatchWorld.matchIdFor(HASH, TEAMS, run);
        assertEquals(expected, ArenaMatchWorld.matchIdFor(HASH, new LinkedHashMap<>(TEAMS), run));
        assertNotEquals(expected, ArenaMatchWorld.matchIdFor("b".repeat(64), TEAMS, run));
        assertNotEquals(expected, ArenaMatchWorld.matchIdFor(HASH, Map.of(ALICE, "ember", BOB, "cedar"), run));
        try (var world = world(run)) {
            var r = world.replay();
            assertThrows(IllegalArgumentException.class, () -> new ArenaMatchReplay(2, r.engineVersion(), r.matchId(),
                    r.seed(), r.config(), r.teams(), r.rulesetId(), r.rulesetVersion(), r.rulesetHash(),
                    r.initialStateHash(), r.frames(), new UUID(0, 102).toString()));
        }
    }

    @Test void futureVersionMismatchedEngineAndAbsentRunAreRejected() {
        try (var world = world(new UUID(0, 101))) {
            var r = world.replay();
            for (int schema : List.of(0, 1, 3)) {
                assertThrows(IllegalArgumentException.class, () -> new ArenaMatchReplay(schema, r.engineVersion(),
                        r.matchId(), r.seed(), r.config(), r.teams(), r.rulesetId(), r.rulesetVersion(), r.rulesetHash(),
                        r.initialStateHash(), r.frames(), r.runId()));
            }
            assertThrows(IllegalArgumentException.class, () -> new ArenaMatchReplay(2, ArenaMatchWorld.LEGACY_ENGINE_VERSION,
                    r.matchId(), r.seed(), r.config(), r.teams(), r.rulesetId(), r.rulesetVersion(), r.rulesetHash(),
                    r.initialStateHash(), r.frames(), r.runId()));
            assertThrows(IllegalArgumentException.class, () -> new ArenaMatchReplay(2, r.engineVersion(), r.matchId(),
                    r.seed(), r.config(), r.teams(), r.rulesetId(), r.rulesetVersion(), r.rulesetHash(),
                    r.initialStateHash(), r.frames()));
        }
    }

    @Test void changedSeedDoesNotValidateWithTheOriginalCommitments() {
        try (var world = world(new UUID(0, 101))) {
            var r = world.replay();
            var tampered = new ArenaMatchReplay(r.schemaVersion(), r.engineVersion(), r.matchId(), r.seed() + 1,
                    r.config(), r.teams(), r.rulesetId(), r.rulesetVersion(), r.rulesetHash(),
                    r.initialStateHash(), r.frames(), r.runId());
            assertFalse(ArenaMatchReplayVerifier.verify(tampered).passed());
            assertThrows(IllegalArgumentException.class, () -> ArenaMatchWorld.restore(tampered));
        }
    }

    private ArenaMatchWorld world(UUID run) {
        return new ArenaMatchWorld(run, 42, new ArenaConfig(4, 4, 2, 4), "public:arena", "1.0.0", HASH, TEAMS);
    }
}
