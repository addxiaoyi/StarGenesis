package dev.stargenesis.paper.arena;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.stargenesis.api.design.DesignId;
import dev.stargenesis.design.ArenaRulesetWorkspace;
import dev.stargenesis.sim.ArenaAction;
import dev.stargenesis.sim.ArenaConfig;
import dev.stargenesis.sim.ArenaInput;
import dev.stargenesis.sim.ArenaMatchReplay;
import dev.stargenesis.sim.ArenaMatchWorld;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ArenaMatchStorageBudgetTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test void unsafeLongRulesAreRejectedBeforeCreatingMatchOrSelection(@TempDir Path directory) throws Exception {
        UUID alice = new UUID(0, 1);
        var rulesets = new ArenaRulesetStore(directory.resolve("rulesets"));
        var draft = rulesets.create(alice, new DesignId("test:long_game"));
        var workspace = new ArenaRulesetWorkspace(draft);
        workspace.duration(6_000);
        workspace.publicRead(true);
        workspace.allowClone(true);
        var published = rulesets.saveDraft(alice, workspace.current(), draft.metadata().hash());
        rulesets.submitForReview(alice, published.metadata().id(), published.metadata().hash());
        rulesets.approveReview(new UUID(0, 99), published.metadata().id(), published.metadata().version());
        var persistence = new ArenaMatchPersistence(directory.resolve("matches"));
        var roster = roster(8, 1);
        persistence.saveTeamRoster(roster);
        var service = new ArenaMatchService(rulesets, persistence);
        IOException failure = assertThrows(IOException.class, () -> service.start(alice, published.metadata().id(),
                published.metadata().version(), 42, roster.keySet()));
        assertTrue(failure.getMessage().contains("8 MiB"));
        assertTrue(persistence.catalog().isEmpty());
        assertTrue(service.restorePlayer(alice).isEmpty());
        assertEquals(roster, persistence.loadTeamRoster());
    }

    @Test void maximumEngineDurationProducesAReplayLargerThanTheFileBudget() throws Exception {
        // Stream the serialization to a counter so the regression does not retain a second 17 MiB JSON tree.
        try (var world = world(8, 6_000, 1)) {
            var actions = world.replay().teams().keySet().stream().map(ArenaAction::pass).toList();
            for (int tick = 0; tick < 6_000; tick++) world.step(new ArenaInput(tick, actions));
            long bytes = encodedBytes(ArenaMatchPersistence.MatchSession.initial(world.replay()));
            assertTrue(bytes > ArenaMatchPersistence.MAX_FILE_BYTES, "6000-round eight-player pass replay bytes=" + bytes);
            System.out.println("P8_STORAGE rejectedDuration=6000 players=8 actualPassReplayBytes=" + bytes);
        }
    }

    @Test void upperBoundIncludesContestsReinforcementsSettlementPendingAndAllObservers() throws Exception {
        try (var world = world(4, 80, 1)) {
            var players = world.replay().teams().keySet().stream().sorted().toList();
            var bound = ArenaMatchStorageBudget.estimate(world.replay(), MAPPER);
            boolean contested = false, regenerated = false, settled = false;
            for (int tick = 0; tick < 80; tick++) {
                List<ArenaAction> actions = switch (tick) {
                    case 0 -> List.of(ArenaAction.claim(players.get(0), 1, 0, 2), ArenaAction.pass(players.get(1)),
                            ArenaAction.claim(players.get(2), 2, 3, 2), ArenaAction.pass(players.get(3)));
                    case 1 -> List.of(ArenaAction.pass(players.get(0)), ArenaAction.claim(players.get(1), 1, 1, 2),
                            ArenaAction.pass(players.get(2)), ArenaAction.claim(players.get(3), 2, 2, 2));
                    case 2 -> List.of(ArenaAction.claim(players.get(0), 1, 2, 2), ArenaAction.pass(players.get(1)),
                            ArenaAction.claim(players.get(2), 1, 2, 2), ArenaAction.pass(players.get(3)));
                    default -> players.stream().map(ArenaAction::pass).toList();
                };
                var result = world.step(new ArenaInput(tick, actions));
                var frame = world.replay().frames().getLast();
                assertTrue(MAPPER.writeValueAsBytes(frame).length + 1 <= bound.frameBytes());
                contested |= result.events().stream().anyMatch(event -> event.type().equals("arena.territory_contested"));
                regenerated |= result.events().stream().anyMatch(event -> event.type().equals("arena.units_regenerated"));
                settled |= result.terminal();
            }
            assertTrue(contested && regenerated && settled);
            Map<UUID, ArenaAction> pending = new HashMap<>();
            players.forEach(player -> pending.put(player, ArenaAction.claim(player, 127, 127, 64)));
            List<UUID> observers = java.util.stream.IntStream.range(0, 128).mapToObj(i -> new UUID(1, i)).toList();
            var maximumReaders = new ArenaMatchPersistence.MatchSession(1, world.replay(), pending, observers);
            assertTrue(encodedBytes(maximumReaders) <= bound.maxBytes());
        }
    }

    @Test void defaultDurationFitsAndAdmissionRejectsBeyondTheComputedBound(@TempDir Path directory) throws Exception {
        var persistence = new ArenaMatchPersistence(directory);
        for (int players : List.of(4, 8)) {
            try (var world = world(players, 1_200, players)) {
                var bound = ArenaMatchStorageBudget.estimate(world.replay(), MAPPER);
                assertTrue(bound.maxBytes() < ArenaMatchPersistence.MAX_FILE_BYTES);
                persistence.create(ArenaMatchPersistence.MatchSession.initial(world.replay()));
                assertTrue(persistence.load(world.matchId()).isPresent());
                assertThrows(IOException.class, () -> persistence.create(ArenaMatchPersistence.MatchSession.initial(world.replay())));
                System.out.println("P8_STORAGE players=" + players + " duration=1200 reservedBytes=" + bound.maxBytes()
                        + " maxAdmittedRounds=" + bound.maxAdmissibleRounds());
                try (var tooLong = world(players, (int) bound.maxAdmissibleRounds() + 1, players + 100)) {
                    var initial = ArenaMatchPersistence.MatchSession.initial(tooLong.replay());
                    assertThrows(IOException.class, () -> persistence.create(initial));
                    assertTrue(persistence.load(tooLong.matchId()).isEmpty());
                }
            }
        }
    }

    @Test void admissionReservesActiveGrowthAcrossRestartAndCompletedHistoryReleasesCapacity(@TempDir Path directory) throws Exception {
        var persistence = new ArenaMatchPersistence(directory);
        ArenaMatchReplay first = null;
        long reserved = 0;
        int run = 1;
        while (true) {
            try (var world = world(8, 1_200, run)) {
                var replay = world.replay();
                long next = ArenaMatchStorageBudget.estimate(replay, MAPPER).maxBytes();
                if (reserved + next > ArenaMatchPersistence.MAX_CATALOG_BYTES) break;
                persistence.create(ArenaMatchPersistence.MatchSession.initial(replay));
                if (first == null) first = replay;
                reserved += next;
                run++;
            }
        }
        assertNotNull(first);
        try (var extra = world(8, 1_200, run)) {
            var session = ArenaMatchPersistence.MatchSession.initial(extra.replay());
            assertTrue(assertThrows(IOException.class, () -> persistence.create(session)).getMessage().contains("64 MiB"));
            var restarted = new ArenaMatchPersistence(directory);
            assertThrows(IOException.class, () -> restarted.create(session));
            assertTrue(restarted.load(extra.matchId()).isEmpty());
            try (var finishing = ArenaMatchWorld.restore(first)) {
                var actions = first.teams().keySet().stream().map(ArenaAction::pass).toList();
                for (int tick = 0; tick < 1_200; tick++) finishing.step(new ArenaInput(tick, actions));
                restarted.save(ArenaMatchPersistence.MatchSession.initial(finishing.replay()));
                var completed = restarted.load(first.matchId()).orElseThrow();
                try (var restored = ArenaMatchWorld.restore(completed.replay())) {
                    assertEquals(finishing.view(), restored.view());
                    assertTrue(restored.view().terminal());
                }
            }
            restarted.create(session);
            assertEquals(session, restarted.load(extra.matchId()).orElseThrow());
        }
    }

    @Test void existingLegacyLongGamesRemainReadableAndSavable(@TempDir Path directory) throws Exception {
        var persistence = new ArenaMatchPersistence(directory);
        Map<UUID, String> roster = roster(8, 1);
        try (var world = new ArenaMatchWorld(ArenaMatchWorld.matchIdFor("a".repeat(64), roster), 42,
                new ArenaConfig(4, 4, 6_000, 64), "test:long", "1.0.0", "a".repeat(64), roster)) {
            var initial = ArenaMatchPersistence.MatchSession.initial(world.replay());
            persistence.save(initial);
            assertEquals(initial, persistence.load(world.matchId()).orElseThrow());
            world.step(new ArenaInput(0, roster.keySet().stream().map(ArenaAction::pass).toList()));
            var updated = ArenaMatchPersistence.MatchSession.initial(world.replay());
            persistence.save(updated);
            assertEquals(updated, persistence.load(world.matchId()).orElseThrow());
            assertEquals(ArenaMatchPersistence.MAX_FILE_BYTES,
                    ArenaMatchStorageBudget.reservedBytes(updated, encodedBytes(updated), MAPPER));
        }
    }

    private static ArenaMatchWorld world(int players, int duration, int run) {
        return new ArenaMatchWorld(new UUID(0, run), 42, new ArenaConfig(4, 4, duration, 64),
                "test:capacity", "999999.999999.999999", "a".repeat(64), roster(players, run * 10));
    }

    private static long encodedBytes(Object value) throws IOException {
        var counter = new java.io.OutputStream() {
            long bytes;
            @Override public void write(int value) { bytes++; }
            @Override public void write(byte[] data, int offset, int length) { bytes += length; }
        };
        MAPPER.writeValue(counter, value);
        return counter.bytes;
    }

    private static Map<UUID, String> roster(int players, int start) {
        Map<UUID, String> result = new HashMap<>();
        for (int i = 0; i < players; i++) result.put(new UUID(0, start + i), i < players / 2 ? "c".repeat(32) : "e".repeat(32));
        return result;
    }
}
