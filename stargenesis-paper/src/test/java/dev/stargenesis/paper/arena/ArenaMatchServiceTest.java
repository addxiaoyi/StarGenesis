package dev.stargenesis.paper.arena;

import dev.stargenesis.api.design.DesignId;
import dev.stargenesis.design.ArenaRuleset;
import dev.stargenesis.design.ArenaRulesetWorkspace;
import dev.stargenesis.sim.ArenaAction;
import dev.stargenesis.sim.ArenaConfig;
import dev.stargenesis.sim.ArenaInput;
import dev.stargenesis.sim.ArenaMatchReplayVerifier;
import dev.stargenesis.sim.ArenaMatchWorld;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ArenaMatchServiceTest {
    private static final UUID ALICE = new UUID(0, 1), BOB = new UUID(0, 2), CAROL = new UUID(0, 3), DAVE = new UUID(0, 4);
    private static final UUID OBSERVER = new UUID(0, 90), OUTSIDER = new UUID(0, 91);
    private static final List<UUID> PLAYERS = List.of(ALICE, BOB, CAROL, DAVE);
    private static final Map<UUID, String> TEAMS = Map.of(ALICE, "cedar", BOB, "cedar", CAROL, "ember", DAVE, "ember");
    @TempDir Path directory;
    private ArenaRulesetStore rulesets;
    private ArenaMatchPersistence persistence;
    private ArenaRuleset ruleset;
    private ArenaMatchService service;

    @BeforeEach void setup() throws Exception {
        rulesets = new ArenaRulesetStore(directory.resolve("rulesets"));
        var draft = rulesets.create(ALICE, new DesignId("test:short_arena"));
        var workspace = new ArenaRulesetWorkspace(draft);
        workspace.resize(4, 4);
        workspace.duration(2);
        workspace.publicRead(true);
        workspace.allowClone(true);
        ruleset = rulesets.saveDraft(ALICE, workspace.current(), draft.metadata().hash());
        rulesets.submitForReview(ALICE, ruleset.metadata().id(), ruleset.metadata().hash());
        rulesets.approveReview(OUTSIDER, ruleset.metadata().id(), ruleset.metadata().version());
        persistence = new ArenaMatchPersistence(directory.resolve("matches"));
        persistence.saveTeamRoster(TEAMS);
        service = new ArenaMatchService(rulesets, persistence);
    }

    @Test void repeatedGamesKeepIndependentHistoryAndRestartPrefersTheActivePartialRound() throws Exception {
        var first = start();
        finish();
        byte[] firstBytes = Files.readAllBytes(file(first));
        var second = start();
        service.submit(ALICE, ArenaAction.pass(ALICE));
        assertNotEquals(first.replay().matchId(), second.replay().matchId());
        assertEquals(first.replay().seed(), second.replay().seed());
        assertNotEquals(first.replay().runId(), second.replay().runId());
        var restarted = restart();
        for (UUID player : PLAYERS) {
            var selected = restarted.current(player);
            assertEquals(second.replay().matchId(), selected.replay().matchId());
            assertEquals(Set.of(ALICE), selected.pending().keySet());
            assertTrue(restarted.hasActiveMatch(player));
        }
        for (UUID player : PLAYERS.subList(1, 4)) restarted.submit(player, ArenaAction.pass(player));
        var resumed = persistence.load(second.replay().matchId()).orElseThrow();
        assertEquals(1, resumed.replay().frames().size());
        assertTrue(ArenaMatchReplayVerifier.verify(resumed.replay()).passed());
        assertArrayEquals(firstBytes, Files.readAllBytes(file(first)));
    }

    @Test void watchingSwitchesDashboardAndReconnectPrefersActiveObservation() throws Exception {
        var first = start();
        service.watch(OBSERVER, first.replay().matchId());
        finish();
        var second = start();
        service.watch(OBSERVER, second.replay().matchId());
        assertEquals(second.replay().matchId(), service.current(OBSERVER).replay().matchId());
        assertThrows(SecurityException.class, () -> service.submit(OBSERVER, ArenaAction.pass(OBSERVER)));
        service.select(OBSERVER, first.replay().matchId());
        service.disconnected(OBSERVER);
        assertEquals(second.replay().matchId(), service.current(OBSERVER).replay().matchId());
        var restarted = restart();
        assertEquals(second.replay().matchId(), restarted.current(OBSERVER).replay().matchId());
        restarted.unwatch(OBSERVER);
        assertFalse(persistence.load(second.replay().matchId()).orElseThrow().spectators().contains(OBSERVER));
        assertEquals(first.replay().matchId(), restart().current(OBSERVER).replay().matchId());
    }

    @Test void selectingHistoryDoesNotAllowAnotherStartOrRosterExitAndRejectsForeignReaders() throws Exception {
        var first = start();
        finish();
        var second = start();
        service.select(ALICE, first.replay().matchId());
        assertEquals(first.replay().matchId(), service.current(ALICE).replay().matchId());
        assertThrows(IllegalStateException.class, () -> service.submit(ALICE, ArenaAction.pass(ALICE)));
        assertThrows(IllegalStateException.class, () -> service.leaveTeam(ALICE));
        assertThrows(IllegalStateException.class, this::start);
        assertThrows(SecurityException.class, () -> service.select(OUTSIDER, first.replay().matchId()));
        service.select(ALICE, second.replay().matchId());
        assertEquals(1, service.submit(ALICE, ArenaAction.pass(ALICE)).session().pending().size());
    }

    @Test void leavingAndReorganizingPersistsWithoutHistoryReinsertingOldMembers() throws Exception {
        var first = start();
        finish();
        assertTrue(service.leaveTeam(ALICE));
        assertFalse(service.leaveTeam(ALICE));
        assertEquals(first.replay().matchId(), restart().current(ALICE).replay().matchId());
        assertFalse(restart().roster().containsKey(ALICE));
        assertThrows(IllegalStateException.class, this::start);
        service.joinTeam(BOB, OUTSIDER, "cedar");
        var reorganized = restart();
        assertFalse(reorganized.roster().containsKey(ALICE));
        assertEquals("cedar", reorganized.roster().get(OUTSIDER));
        var next = reorganized.start(BOB, ruleset.metadata().id(), "1.0.0", 42, reorganized.roster().keySet());
        assertFalse(next.replay().teams().containsKey(ALICE));
        assertEquals(TEAMS, persistence.load(first.replay().matchId()).orElseThrow().replay().teams());
    }

    @Test void rosterWriteFailurePreservesMemoryAndDiskAndCanBeRetried() throws Exception {
        Path temporary = directory.resolve("matches/teams.json.tmp");
        byte[] before = Files.readAllBytes(directory.resolve("matches/teams.json"));
        Files.createDirectory(temporary);
        assertThrows(IOException.class, () -> service.leaveTeam(ALICE));
        assertThrows(IOException.class, () -> service.joinTeam(ALICE, OUTSIDER, "cedar"));
        assertEquals(TEAMS, service.roster());
        assertEquals(TEAMS, restart().roster());
        assertArrayEquals(before, Files.readAllBytes(directory.resolve("matches/teams.json")));
        Files.delete(temporary);
        assertTrue(service.leaveTeam(ALICE));
        assertFalse(restart().roster().containsKey(ALICE));
    }

    @Test void failedObserverSavePreservesThePreviousSelectionAndBothMatchFiles() throws Exception {
        var first = start();
        service.watch(OBSERVER, first.replay().matchId());
        finish();
        var second = start();
        byte[] before = Files.readAllBytes(file(second));
        Path temporary = file(second).resolveSibling(file(second).getFileName() + ".tmp");
        Files.createDirectory(temporary);
        assertThrows(IOException.class, () -> service.watch(OBSERVER, second.replay().matchId()));
        assertEquals(first.replay().matchId(), service.current(OBSERVER).replay().matchId());
        assertArrayEquals(before, Files.readAllBytes(file(second)));
        Files.delete(temporary);
        service.watch(OBSERVER, second.replay().matchId());
        assertEquals(second.replay().matchId(), service.current(OBSERVER).replay().matchId());
    }

    @Test void failedFinalSubmissionPreservesPendingActionsAndRetryCommitsExactlyOneRound() throws Exception {
        var initial = start();
        for (UUID player : PLAYERS.subList(0, 3)) service.submit(player, ArenaAction.pass(player));
        var before = persistence.load(initial.replay().matchId()).orElseThrow();
        var expected = ArenaMatchRound.submit(before, DAVE, ArenaAction.pass(DAVE));
        byte[] beforeBytes = Files.readAllBytes(file(initial));
        Path temporary = file(initial).resolveSibling(file(initial).getFileName() + ".tmp");
        Files.createDirectory(temporary);
        assertThrows(IOException.class, () -> service.submit(DAVE, ArenaAction.pass(DAVE)));
        assertArrayEquals(beforeBytes, Files.readAllBytes(file(initial)));
        assertEquals(before, restart().current(DAVE));
        Files.delete(temporary);
        assertEquals(expected, service.submit(DAVE, ArenaAction.pass(DAVE)));
        assertEquals(expected.session(), restart().current(DAVE));
    }

    @Test void startRequiresAnOnlineRosteredStarterAndNoAlreadyActiveParticipant() throws Exception {
        assertThrows(SecurityException.class, () -> service.start(OUTSIDER, ruleset.metadata().id(), "1.0.0", 42, TEAMS.keySet()));
        assertThrows(IllegalStateException.class, () -> service.start(ALICE, ruleset.metadata().id(), "1.0.0", 42, Set.of(ALICE)));
        assertTrue(persistence.catalog().isEmpty());
        start();
        assertThrows(IllegalStateException.class, this::start);
        assertThrows(IllegalStateException.class, () -> service.joinTeam(ALICE, OUTSIDER, "cedar"));
        assertEquals(1, persistence.catalog().size());
        assertEquals(TEAMS, service.roster());
    }

    @Test void onlyOperatorsCanPruneCompletedHistoryAndActiveGamesRemainIntact() throws Exception {
        var first = start();
        assertThrows(SecurityException.class, () -> service.pruneCompleted(first.replay().matchId(), false));
        assertThrows(IllegalStateException.class, () -> service.pruneCompleted(first.replay().matchId(), true));
        finish();
        service.watch(OBSERVER, first.replay().matchId());
        var second = start();
        assertThrows(SecurityException.class, () -> service.pruneCompleted(first.replay().matchId(), false));
        service.pruneCompleted(first.replay().matchId(), true);
        assertTrue(persistence.load(first.replay().matchId()).isEmpty());
        assertEquals(second, persistence.load(second.replay().matchId()).orElseThrow());
        assertTrue(service.restorePlayer(OBSERVER).isEmpty());
    }

    @Test void aPlayerCanWithdrawTheirOwnInvalidLegacyPendingActionWithoutLosingOtherInputs() throws Exception {
        ArenaMatchPersistence.MatchSession initial;
        try (var world = new ArenaMatchWorld(ArenaMatchWorld.matchIdFor(ruleset.metadata().hash(), TEAMS), 42,
                new ArenaConfig(4, 4, 2, ruleset.unitBudget()), ruleset.metadata().id().value(), "1.0.0",
                ruleset.metadata().hash(), TEAMS)) {
            initial = ArenaMatchPersistence.MatchSession.initial(world.replay());
        }
        var poisoned = new ArenaMatchPersistence.MatchSession(1, initial.replay(),
                Map.of(ALICE, ArenaAction.claim(ALICE, 100, 100, 1), BOB, ArenaAction.pass(BOB)), List.of(OBSERVER));
        persistence.save(poisoned);
        assertThrows(SecurityException.class, () -> service.withdraw(OBSERVER));
        service.withdraw(ALICE);
        var repaired = persistence.load(initial.replay().matchId()).orElseThrow();
        assertEquals(Map.of(BOB, ArenaAction.pass(BOB)), repaired.pending());
        assertEquals(poisoned.replay(), repaired.replay());
        service.submit(ALICE, ArenaAction.pass(ALICE));
        service.submit(CAROL, ArenaAction.pass(CAROL));
        var advanced = service.submit(DAVE, ArenaAction.pass(DAVE));
        assertEquals(1, advanced.session().replay().frames().size());
        assertThrows(IllegalStateException.class, () -> service.withdraw(ALICE));
    }

    @Test void observerLimitCanBeReclaimedByUnwatchWithoutChangingRoundState() throws Exception {
        var initial = start();
        List<UUID> spectators = java.util.stream.IntStream.range(0, 128).mapToObj(i -> new UUID(0, 1000 + i)).toList();
        persistence.save(new ArenaMatchPersistence.MatchSession(1, initial.replay(), Map.of(), spectators));
        assertThrows(IOException.class, () -> service.watch(OBSERVER, initial.replay().matchId()));
        assertTrue(service.restorePlayer(OBSERVER).isEmpty());
        service.unwatch(spectators.getFirst());
        service.watch(OBSERVER, initial.replay().matchId());
        var saved = persistence.load(initial.replay().matchId()).orElseThrow();
        assertEquals(128, saved.spectators().size());
        assertEquals(initial.replay(), saved.replay());
        assertEquals(initial.replay().matchId(), restart().current(OBSERVER).replay().matchId());
    }

    @Test void fullHistoryRejectsNewGamesUntilAnOperatorPrunesACompletedMatch() throws Exception {
        ArenaMatchPersistence.MatchSession oldest = null;
        for (int i = 0; i < ArenaMatchPersistence.MAX_RETAINED_MATCHES; i++) {
            try (var world = new ArenaMatchWorld(new UUID(0, 1000 + i), 42,
                    new ArenaConfig(4, 4, 2, ruleset.unitBudget()), ruleset.metadata().id().value(), "1.0.0",
                    ruleset.metadata().hash(), TEAMS)) {
                for (int tick = 0; tick < 2; tick++)
                    world.step(new ArenaInput(tick, PLAYERS.stream().map(ArenaAction::pass).toList()));
                var session = ArenaMatchPersistence.MatchSession.initial(world.replay());
                if (oldest == null) oldest = session;
                persistence.save(session);
            }
        }
        service.select(ALICE, oldest.replay().matchId());
        assertThrows(IOException.class, this::start);
        assertEquals(oldest, service.current(ALICE));
        assertFalse(service.hasActiveMatch(ALICE));
        service.pruneCompleted(oldest.replay().matchId(), true);
        var started = start();
        assertEquals(started, service.current(ALICE));
        assertEquals(ArenaMatchPersistence.MAX_RETAINED_MATCHES, persistence.catalog().size());
    }

    private ArenaMatchPersistence.MatchSession start() throws IOException {
        return service.start(ALICE, ruleset.metadata().id(), "1.0.0", 42, TEAMS.keySet());
    }

    private void finish() throws IOException {
        for (int round = 0; round < 2; round++)
            for (UUID player : PLAYERS) service.submit(player, ArenaAction.pass(player));
    }

    private ArenaMatchService restart() throws IOException {
        return new ArenaMatchService(new ArenaRulesetStore(directory.resolve("rulesets")),
                new ArenaMatchPersistence(directory.resolve("matches")));
    }

    private Path file(ArenaMatchPersistence.MatchSession session) {
        return directory.resolve("matches").resolve(session.replay().matchId() + ".match.json");
    }
}
