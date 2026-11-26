package dev.stargenesis.paper.factory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.stargenesis.sim.FactoryAction;
import dev.stargenesis.sim.FactoryConfig;
import dev.stargenesis.sim.FactoryInput;
import dev.stargenesis.sim.FactoryReplay;
import dev.stargenesis.sim.FactoryReplayVerifier;
import dev.stargenesis.sim.FactoryWorld;
import dev.stargenesis.paper.runtime.PersistentWorker;
import dev.stargenesis.paper.runtime.PlayerCommandQueue;
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

class FactoryServiceTest {
    private static final UUID ALICE = new UUID(0, 3001), BOB = new UUID(0, 3002),
            CAROL = new UUID(0, 3003), DAVE = new UUID(0, 3004), EVE = new UUID(0, 3005), FRANK = new UUID(0, 3006);
    private static final List<UUID> PLAYERS = List.of(ALICE, BOB, CAROL, DAVE);
    private static final Map<UUID, UUID> PARTNERS = Map.of(ALICE, BOB, BOB, ALICE, CAROL, DAVE, DAVE, CAROL);
    private static final FactoryConfig SHORT = new FactoryConfig(100, 16, 16, 4, 2, 6, 16, 1_000, 5_000);
    @TempDir Path directory;
    private FactoryPersistence persistence;
    private FactoryService service;

    @BeforeEach void setup() throws Exception {
        persistence = new FactoryPersistence(directory);
        persistence.saveOrganizations(PARTNERS);
        service = new FactoryService(persistence);
    }

    @Test void repeatRunsPreserveHistoryAndRestartOrReconnectSelectsActiveGame() throws Exception {
        var first = start();
        finish(first);
        byte[] firstBytes = Files.readAllBytes(file(first));
        var second = start();
        service.act(ALICE, new FactoryAction(ALICE, FactoryAction.Type.BID, "ore_1", 10));
        assertNotEquals(first.matchId(), second.matchId());
        assertNotEquals(first.runId(), second.runId());
        assertEquals(first.seed(), second.seed());
        var restarted = restart();
        for (UUID player : PLAYERS) {
            assertEquals(second.matchId(), restarted.current(player).matchId());
            assertEquals(1, restarted.current(player).frames().size());
            assertEquals(2, restarted.matches(player, false).size());
        }
        service.select(ALICE, first.matchId());
        service.disconnected(ALICE);
        assertEquals(second.matchId(), service.current(ALICE).matchId());
        assertArrayEquals(firstBytes, Files.readAllBytes(file(first)));
        assertTrue(FactoryReplayVerifier.verify(restarted.current(ALICE)).passed());
    }

    @Test void historicalSelectionCannotBypassActiveMembershipOrSubmitOnBehalfOfOthers() throws Exception {
        var first = start();
        finish(first);
        var active = start();
        service.select(ALICE, first.matchId());
        byte[] history = Files.readAllBytes(file(first));
        assertThrows(IllegalStateException.class, this::start);
        assertThrows(IllegalStateException.class, () -> service.leave(ALICE));
        assertThrows(IllegalStateException.class, () -> service.act(ALICE, FactoryAction.pass(ALICE)));
        assertThrows(SecurityException.class, () -> service.act(ALICE, FactoryAction.pass(BOB)));
        assertThrows(SecurityException.class, () -> service.select(EVE, first.matchId()));
        assertTrue(service.restorePlayer(EVE).isEmpty());
        assertTrue(service.matches(EVE, false).isEmpty());
        assertEquals(2, service.matches(EVE, true).size());
        assertEquals(first.matchId(), service.step(ALICE, 1).matchId());
        assertArrayEquals(history, Files.readAllBytes(file(first)));
        service.select(ALICE, active.matchId());
        assertEquals(1, service.step(ALICE, 1).frames().size());
    }

    @Test void leaveAndReorganizationSurviveRestartWithoutHistoryResurrectingMembers() throws Exception {
        var first = start();
        finish(first);
        assertTrue(service.leave(ALICE));
        assertFalse(service.leave(ALICE));
        service.invite(BOB, EVE);
        assertEquals(BOB, service.accept(EVE));
        var restarted = restart();
        byte[] organizations = Files.readAllBytes(directory.resolve("organizations.json"));
        assertEquals(first.matchId(), restarted.current(ALICE).matchId());
        assertFalse(restarted.organizations().containsKey(ALICE));
        assertEquals(EVE, restarted.organizations().get(BOB));
        assertArrayEquals(organizations, Files.readAllBytes(directory.resolve("organizations.json")));
        assertThrows(IllegalStateException.class, () -> restarted.start(ALICE, 42, Set.copyOf(PLAYERS)));
        var next = restarted.start(BOB, 42, restarted.organizations().keySet());
        assertFalse(next.participants().contains(ALICE));
        assertTrue(next.participants().contains(EVE));
        assertEquals(PLAYERS, persistence.load(first.matchId()).orElseThrow().participants());
    }

    @Test void startSkipsOfflineAndBusyOpponentPairs() throws Exception {
        service.invite(EVE, FRANK);
        service.accept(FRANK);
        assertThrows(IllegalStateException.class, () -> service.start(ALICE, 42, Set.of(ALICE, CAROL, DAVE)));
        var first = service.start(ALICE, 42, Set.of(ALICE, BOB, CAROL, EVE, FRANK));
        assertEquals(List.of(ALICE, BOB, EVE, FRANK), first.participants());
        assertThrows(IllegalStateException.class, () -> service.start(CAROL, 42, service.organizations().keySet()));
        UUID grace = new UUID(0, 3007), hank = new UUID(0, 3008);
        service.invite(grace, hank);
        service.accept(hank);
        var second = service.start(CAROL, 42, service.organizations().keySet());
        assertEquals(List.of(CAROL, DAVE, grace, hank), second.participants());
    }

    @Test void invitationsCannotBeOverwrittenAndDisconnectOrLeaveReleasesThem() throws Exception {
        UUID grace = new UUID(0, 3007);
        service.invite(EVE, FRANK);
        assertThrows(IllegalStateException.class, () -> service.invite(grace, FRANK));
        assertThrows(IllegalStateException.class, () -> service.invite(EVE, grace));
        assertThrows(IllegalStateException.class, () -> service.invite(FRANK, grace));
        assertThrows(IllegalStateException.class, () -> service.invite(EVE, EVE));
        service.disconnected(EVE);
        assertThrows(IllegalStateException.class, () -> service.accept(FRANK));
        service.invite(grace, FRANK);
        service.disconnected(FRANK);
        service.invite(EVE, FRANK);
        assertFalse(service.leave(FRANK));
        assertThrows(IllegalStateException.class, () -> service.accept(FRANK));
        service.invite(EVE, FRANK);
        assertThrows(IllegalStateException.class, () -> restart().accept(FRANK));
        assertEquals(EVE, service.accept(FRANK));
        assertEquals(EVE, restart().organizations().get(FRANK));
    }

    @Test void invitationBudgetIsReclaimedAfterDisconnect() throws Exception {
        for (int i = 0; i < 64; i++) service.invite(new UUID(0, 10_000 + i * 2), new UUID(0, 10_001 + i * 2));
        assertThrows(IllegalStateException.class, () -> service.invite(EVE, FRANK));
        service.disconnected(new UUID(0, 10_000));
        service.invite(EVE, FRANK);
        assertEquals(EVE, service.accept(FRANK));
    }

    @Test void failedOrganizationWritesPreserveInvitationsMemoryAndBytesForRetry() throws Exception {
        service.invite(EVE, FRANK);
        byte[] before = Files.readAllBytes(directory.resolve("organizations.json"));
        Path temporary = directory.resolve("organizations.json.tmp");
        Files.createDirectory(temporary);
        assertThrows(IOException.class, () -> service.accept(FRANK));
        assertThrows(IOException.class, () -> service.leave(ALICE));
        assertEquals(PARTNERS, service.organizations());
        assertEquals(PARTNERS, restart().organizations());
        assertArrayEquals(before, Files.readAllBytes(directory.resolve("organizations.json")));
        Files.delete(temporary);
        assertEquals(EVE, service.accept(FRANK));
        assertTrue(service.leave(ALICE));
        assertFalse(restart().organizations().containsKey(BOB));
        assertEquals(EVE, restart().organizations().get(FRANK));
    }

    @Test void resetAndPruneCommandsRequireManagerAndOnlyRemoveCompletedHistory() throws Exception {
        var first = start();
        byte[] before = Files.readAllBytes(file(first));
        for (UUID caller : List.of(ALICE, EVE)) {
            assertThrows(SecurityException.class, () -> service.run(caller, new String[]{"reset"}, Set.of(), false));
            assertThrows(SecurityException.class, () -> service.run(caller, new String[]{"reset", first.matchId()}, Set.of(), false));
            assertThrows(SecurityException.class, () -> service.run(caller, new String[]{"prune", first.matchId()}, Set.of(), false));
            assertThrows(IllegalStateException.class, () -> service.run(caller, new String[]{"reset", first.matchId()}, Set.of(), true));
        }
        assertArrayEquals(before, Files.readAllBytes(file(first)));
        finish(first);
        assertThrows(SecurityException.class, () -> service.run(ALICE, new String[]{"reset"}, Set.of(), false));
        var active = start();
        service.select(BOB, first.matchId());
        service.run(EVE, new String[]{"prune", first.matchId()}, Set.of(), true);
        assertTrue(persistence.load(first.matchId()).isEmpty());
        assertEquals(active, persistence.load(active.matchId()).orElseThrow());
        assertEquals(active.matchId(), service.current(BOB).matchId());
        finish(active);
        service.run(ALICE, new String[]{"reset"}, Set.of(), true);
        assertTrue(persistence.catalog().isEmpty());
        assertEquals(PARTNERS, restart().organizations());
    }

    @Test void fullHistoryRejectsStartWithoutChangingSelectionAndPruneAllowsRetry() throws Exception {
        FactoryReplay first = null;
        for (int i = 0; i < FactoryPersistence.MAX_RETAINED_MATCHES; i++) {
            try (var world = new FactoryWorld(new UUID(0, i), PLAYERS, 42, SHORT)) {
                for (int tick = 0; tick < SHORT.maxTicks(); tick++) world.step(new FactoryInput(tick, FactoryAction.pass(ALICE)));
                persistence.save(world.replay());
                if (first == null) first = world.replay();
            }
        }
        service.select(ALICE, first.matchId());
        byte[] before = Files.readAllBytes(file(first));
        assertThrows(IOException.class, this::start);
        assertEquals(first, service.current(ALICE));
        assertArrayEquals(before, Files.readAllBytes(file(first)));
        service.prune(first.matchId(), true);
        var active = start();
        assertEquals(active, restart().current(ALICE));
        assertEquals(FactoryPersistence.MAX_RETAINED_MATCHES, persistence.catalog().size());
    }

    @Test void failedBidSaveLeavesNoEscrowAndRetryAppliesOnce() throws Exception {
        var initial = start();
        byte[] before = Files.readAllBytes(file(initial));
        Path temporary = blockWrite(initial);
        var bid = new FactoryAction(ALICE, FactoryAction.Type.BID, "ore_1", 10);
        assertThrows(IOException.class, () -> service.act(ALICE, bid));
        assertArrayEquals(before, Files.readAllBytes(file(initial)));
        assertEquals(initial, restart().current(ALICE));
        Files.delete(temporary);
        try (var world = FactoryWorld.restore(service.act(ALICE, bid))) {
            assertEquals(1, world.tick());
            assertEquals(90, world.view().organizations().get("org_cedar").credits());
            assertEquals(1, world.view().auction().bids().size());
            assertConserved(world);
        }
    }

    @Test void failedReservationAndCompensationSavesPreserveExactBytesAndRetryOnceAfterRestart() throws Exception {
        FactoryReplay auction;
        try (var world = new FactoryWorld(new UUID(0, 999), PLAYERS, 77,
                new FactoryConfig(100, 16, 16, 4, 2, 30, 16, 1_000, 5_000))) {
            world.step(new FactoryInput(0, new FactoryAction(ALICE, FactoryAction.Type.BID, "ore_1", 10)));
            world.step(new FactoryInput(1, new FactoryAction(CAROL, FactoryAction.Type.BID, "ore_1", 15)));
            world.step(new FactoryInput(2, FactoryAction.pass(BOB)));
            world.step(new FactoryInput(3, FactoryAction.pass(DAVE)));
            auction = world.replay();
            persistence.save(auction);
        }
        var order = new FactoryAction(CAROL, FactoryAction.Type.PLACE_ORDER, "press", 2);
        byte[] before = Files.readAllBytes(file(auction));
        Path temporary = blockWrite(auction);
        assertThrows(IOException.class, () -> service.act(CAROL, order));
        assertArrayEquals(before, Files.readAllBytes(file(auction)));
        assertEquals(auction, restart().current(CAROL));
        Files.delete(temporary);
        var pending = service.act(CAROL, order);
        try (var world = FactoryWorld.restore(pending)) {
            assertEquals("RUNNING", world.view().orders().getFirst().state());
            assertEquals(79, world.view().organizations().get("org_ember").credits());
            assertEquals(12, world.view().organizations().get("org_ember").ore());
            assertConserved(world);
        }
        before = Files.readAllBytes(file(pending));
        temporary = blockWrite(pending);
        assertThrows(IOException.class, () -> service.step(DAVE, 1));
        assertArrayEquals(before, Files.readAllBytes(file(pending)));
        assertEquals(pending, restart().current(CAROL));
        Files.delete(temporary);
        var restarted = restart();
        var recovered = restarted.step(DAVE, 1);
        try (var world = FactoryWorld.restore(recovered)) {
            assertEquals(6, world.tick());
            assertEquals(1, world.view().recoveredOrders());
            assertEquals("FAILED_RECOVERED", world.view().orders().getFirst().state());
            assertEquals(85, world.view().organizations().get("org_ember").credits());
            assertEquals(16, world.view().organizations().get("org_ember").ore());
            assertConserved(world);
        }
        assertThrows(IllegalArgumentException.class, () -> restarted.act(CAROL, order));
        try (var world = FactoryWorld.restore(restarted.step(DAVE, 1))) {
            assertEquals(1, world.view().recoveredOrders());
            assertConserved(world);
        }
        assertTrue(FactoryReplayVerifier.verify(restart().current(CAROL)).passed());
    }

    @Test void corruptedEventHistoryCannotBeRestoredAdvancedOrPruned() throws Exception {
        var initial = start();
        finish(initial);
        Path file = file(initial);
        var document = (ObjectNode) new ObjectMapper().readTree(Files.readString(file));
        ((ObjectNode) document.path("replay").path("frames").get(0)).putArray("events");
        Files.writeString(file, document.toString());
        byte[] corrupted = Files.readAllBytes(file);
        assertThrows(IllegalArgumentException.class, () -> restart().current(ALICE));
        assertThrows(IllegalArgumentException.class, () -> service.step(ALICE, 1));
        assertThrows(IllegalArgumentException.class, () -> service.prune(initial.matchId(), true));
        assertArrayEquals(corrupted, Files.readAllBytes(file));
    }

    @Test void orphanedActiveParticipationBlocksReorganizationAndConflictingGamesFailClosed() throws Exception {
        var initial = start();
        persistence.saveOrganizations(Map.of());
        var restarted = restart();
        assertEquals(initial, restarted.current(ALICE));
        assertTrue(restarted.organizations().isEmpty());
        assertThrows(IllegalStateException.class, () -> restarted.invite(ALICE, EVE));
        assertThrows(IllegalStateException.class, () -> restarted.leave(ALICE));
        try (var duplicate = new FactoryWorld(new UUID(0, 777), PLAYERS, 42, FactoryConfig.defaults())) {
            persistence.save(duplicate.replay());
        }
        assertThrows(IOException.class, () -> restart().current(ALICE));
        assertThrows(IOException.class, () -> restarted.invite(EVE, FRANK));
    }

    @Test void commandBoundsRejectWithoutChangingMatchAndDashboardUsesSavedDuration() throws Exception {
        FactoryReplay shortMatch;
        try (var world = new FactoryWorld(new UUID(0, 999), PLAYERS, 42, SHORT)) {
            shortMatch = world.replay();
            persistence.save(shortMatch);
        }
        byte[] before = Files.readAllBytes(file(shortMatch));
        for (String[] args : List.of(new String[]{"step", "0"}, new String[]{"step", "11"},
                new String[]{"step", "1", "extra"}, new String[]{"bid"}, new String[]{"order", "press", "0"},
                new String[]{"select"}, new String[]{"prune"})) {
            assertThrows(IllegalArgumentException.class, () -> service.run(ALICE, args, Set.of(), true));
        }
        assertArrayEquals(before, Files.readAllBytes(file(shortMatch)));
        var lines = service.run(ALICE, new String[]{"dashboard"}, Set.of(), false);
        assertTrue(lines.getFirst().contains("0/6"));
        service.run(ALICE, new String[]{"step", "10"}, Set.of(), false);
        assertEquals(6, service.current(ALICE).frames().size());
        assertTrue(service.run(ALICE, new String[]{"replay"}, Set.of(), false).getFirst().contains("回放通过"));
    }

    @Test void saturatedDisconnectAfterFailedOrganizationSaveCancelsTheInvitationEvenAfterReconnect() throws Exception {
        service.invite(EVE, FRANK);
        byte[] before = Files.readAllBytes(directory.resolve("organizations.json"));
        Path blocker = Files.createDirectory(directory.resolve("organizations.json.tmp"));
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var failedSave = new java.util.concurrent.CompletableFuture<Void>();
        try (var worker = PersistentWorker.commands("factory-failed-accept-disconnect", directory, 1)) {
            var commands = new PlayerCommandQueue(worker, service::disconnected, service::retainOnline);
            var eve = commands.connected(EVE);
            var frank = commands.connected(FRANK);
            commands.execute(frank, () -> {
                try {
                    entered.countDown(); await(release);
                    assertThrows(IOException.class, () -> service.accept(FRANK));
                    failedSave.complete(null);
                } catch (Throwable failure) { failedSave.completeExceptionally(failure); }
            });
            await(entered);
            commands.execute(eve, () -> fail("Retired queued command"));
            commands.disconnected(EVE);
            commands.connected(EVE);
            release.countDown();
            failedSave.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertTrue(worker.shutdown(java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(5)).complete());
        } finally { release.countDown(); }
        assertArrayEquals(before, Files.readAllBytes(directory.resolve("organizations.json")));
        assertEquals(PARTNERS, restart().organizations());
        Files.delete(blocker);
        assertThrows(IllegalStateException.class, () -> service.accept(FRANK));
        service.invite(EVE, FRANK);
        assertEquals(EVE, service.accept(FRANK));
        assertEquals(EVE, restart().organizations().get(FRANK));
    }

    @Test void admittedOrganizationSaveCanFinishAfterCloseAndRecoverWithoutTransientInvitations() throws Exception {
        service.invite(EVE, FRANK);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var saved = new java.util.concurrent.CompletableFuture<UUID>();
        try (var worker = PersistentWorker.commands("factory-close-admitted-save", directory, 1)) {
            var commands = new PlayerCommandQueue(worker, service::disconnected, service::retainOnline);
            commands.connected(EVE);
            var frank = commands.connected(FRANK);
            commands.execute(frank, () -> {
                try { entered.countDown(); await(release); saved.complete(service.accept(FRANK)); }
                catch (Throwable failure) { saved.completeExceptionally(failure); }
            });
            await(entered);
            commands.close();
            release.countDown();
            assertEquals(EVE, saved.get(5, java.util.concurrent.TimeUnit.SECONDS));
            assertTrue(worker.shutdown(java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(5)).complete());
        } finally { release.countDown(); }
        assertEquals(EVE, restart().organizations().get(FRANK));
        assertEquals(FRANK, restart().organizations().get(EVE));
    }

    @Test void saturatedDisconnectClearsHistorySelectionAndFreshConnectionRestoresTheActiveMatch() throws Exception {
        var first = start();
        finish(first);
        var active = start();
        byte[] history = Files.readAllBytes(file(first)), running = Files.readAllBytes(file(active));
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        try (var worker = PersistentWorker.commands("factory-selection-disconnect", directory, 1)) {
            var commands = new PlayerCommandQueue(worker, service::disconnected, service::retainOnline);
            var alice = commands.connected(ALICE);
            commands.execute(alice, () -> {
                try { service.select(ALICE, first.matchId()); entered.countDown(); await(release); }
                catch (IOException failure) { throw new java.io.UncheckedIOException(failure); }
            });
            await(entered);
            commands.execute(alice, () -> fail("Retired selection command"));
            commands.disconnected(ALICE);
            release.countDown();
            assertTrue(worker.shutdown(java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(5)).complete());
        } finally { release.countDown(); }
        assertEquals(active.matchId(), service.current(ALICE).matchId());
        assertArrayEquals(history, Files.readAllBytes(file(first)));
        assertArrayEquals(running, Files.readAllBytes(file(active)));
    }

    private static void await(java.util.concurrent.CountDownLatch latch) {
        try { assertTrue(latch.await(5, java.util.concurrent.TimeUnit.SECONDS)); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }

    private FactoryReplay start() throws IOException { return service.start(ALICE, 42, PARTNERS.keySet()); }
    private FactoryService restart() throws IOException { return new FactoryService(new FactoryPersistence(directory)); }
    private Path file(FactoryReplay replay) { return directory.resolve(replay.matchId() + ".factory.json"); }

    private void finish(FactoryReplay replay) throws IOException {
        try (var world = FactoryWorld.restore(replay)) {
            while (!world.view().terminal()) world.step(new FactoryInput(world.tick(), FactoryAction.pass(ALICE)));
            persistence.save(world.replay());
        }
    }

    private Path blockWrite(FactoryReplay replay) throws IOException {
        Path temporary = directory.resolve(replay.matchId() + ".factory.json.tmp");
        Files.createDirectory(temporary);
        return temporary;
    }

    private void assertConserved(FactoryWorld world) {
        for (var expected : Map.of(FactoryWorld.CREDITS, 200L, FactoryWorld.ORE, 16L, FactoryWorld.PARTS, 16L).entrySet())
            assertEquals(expected.getValue().longValue(), world.ledgerSnapshot().values().stream()
                    .mapToLong(account -> account.getOrDefault(expected.getKey(), 0L)).sum());
    }
}
