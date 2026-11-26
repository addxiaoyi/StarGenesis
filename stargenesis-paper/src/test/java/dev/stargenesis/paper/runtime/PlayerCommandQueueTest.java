package dev.stargenesis.paper.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PlayerCommandQueueTest {
    private static final UUID ALICE = new UUID(0, 701), BOB = new UUID(0, 702);
    private static final Duration WAIT = Duration.ofSeconds(5);
    @TempDir Path root;

    @Test void saturatedQueueCannotLoseDisconnectedStateCleanup() throws Exception {
        Map<UUID, String> state = new HashMap<>();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var worker = PersistentWorker.commands("full-disconnect", root, 1)) {
            var queue = queue(worker, state);
            var alice = queue.connected(ALICE);
            queue.execute(alice, () -> {
                state.put(ALICE, "old selection");
                entered.countDown();
                await(release);
            });
            await(entered);
            var bob = queue.connected(BOB);
            queue.execute(bob, () -> {});
            queue.disconnected(ALICE);
            release.countDown();
            assertTrue(worker.shutdown(WAIT, WAIT).complete());
            assertFalse(state.containsKey(ALICE), "A full command queue must not discard disconnect cleanup");
        } finally { release.countDown(); }
    }

    @Test void queuedCommandFromOldConnectionCannotRunAfterReconnect() throws Exception {
        Map<UUID, String> state = new HashMap<>();
        var writes = new java.util.concurrent.atomic.AtomicInteger();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var worker = PersistentWorker.commands("stale-player-command", root, 2)) {
            var queue = queue(worker, state);
            var alice = queue.connected(ALICE);
            queue.execute(alice, () -> { entered.countDown(); await(release); });
            await(entered);
            queue.execute(alice, () -> { writes.incrementAndGet(); state.put(ALICE, "stale command"); });
            queue.disconnected(ALICE);
            queue.connected(ALICE);
            release.countDown();
            assertTrue(worker.shutdown(WAIT, WAIT).complete());
            assertEquals(0, writes.get(), "An old connection must not commit queued commands after reconnect");
            assertFalse(state.containsKey(ALICE), "An old queued command must not write into a reconnected session");
        } finally { release.countDown(); }
    }

    @Test void closeRetiresQueuedCommandsButAllowsAnAlreadyRunningOperationToFinish() throws Exception {
        Map<UUID, String> state = new HashMap<>();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var committed = new CompletableFuture<String>();
        try (var worker = PersistentWorker.commands("close-player-commands", root, 1)) {
            var queue = queue(worker, state);
            var alice = queue.connected(ALICE);
            queue.execute(alice, () -> {
                entered.countDown(); await(release);
                committed.complete("accepted operation committed");
                state.put(ALICE, "late selection");
            });
            await(entered);
            queue.execute(alice, () -> state.put(BOB, "queued mutation"));
            queue.close();
            release.countDown();
            assertTrue(worker.shutdown(WAIT, WAIT).complete());
            assertEquals("accepted operation committed", committed.get(5, TimeUnit.SECONDS));
            assertTrue(state.isEmpty(), "Shutdown must retire transient state and queued player commands");
        } finally { release.countDown(); }
    }

    @Test void idleDisconnectRunsCleanupWithoutNeedingAnotherPlayerCommand() throws Exception {
        Map<UUID, String> state = new HashMap<>();
        try (var worker = PersistentWorker.commands("idle-disconnect", root, 2)) {
            var queue = queue(worker, state);
            var alice = queue.connected(ALICE);
            var initialized = new CompletableFuture<Void>();
            queue.execute(alice, () -> { state.put(ALICE, "selection"); initialized.complete(null); });
            initialized.get(5, TimeUnit.SECONDS);
            queue.disconnected(ALICE);
            assertTrue(worker.shutdown(WAIT, WAIT).complete());
            assertTrue(state.isEmpty());
            assertThrows(RejectedExecutionException.class, () -> queue.execute(alice, () -> fail("Retired connection")));
        }
    }

    @Test void reconnectClearsPreviousSelectionBeforeFreshCommandsAndKeepsOtherPlayers() throws Exception {
        Map<UUID, String> state = new HashMap<>();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var worker = PersistentWorker.commands("fresh-connection", root, 3)) {
            var queue = queue(worker, state);
            var alice = queue.connected(ALICE);
            queue.connected(BOB);
            queue.execute(alice, () -> {
                state.put(ALICE, "old"); state.put(BOB, "unaffected");
                entered.countDown(); await(release);
            });
            await(entered);
            queue.disconnected(ALICE);
            var fresh = queue.connected(ALICE);
            assertNotSame(alice, fresh);
            var finished = new CompletableFuture<Void>();
            queue.execute(fresh, () -> {
                try {
                    assertFalse(state.containsKey(ALICE));
                    assertEquals("unaffected", state.get(BOB));
                    state.put(ALICE, "fresh"); finished.complete(null);
                } catch (Throwable failure) { finished.completeExceptionally(failure); }
            });
            release.countDown();
            finished.get(5, TimeUnit.SECONDS);
            assertTrue(worker.shutdown(WAIT, WAIT).complete());
            assertEquals(Map.of(ALICE, "fresh", BOB, "unaffected"), state);
        } finally { release.countDown(); }
    }

    @Test void aQueuedInvitationRequiresBothOriginalConnections() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var invoked = new AtomicInteger();
        try (var worker = PersistentWorker.commands("two-party-admission", root, 1)) {
            var queue = queue(worker, new HashMap<>());
            var alice = queue.connected(ALICE);
            var bob = queue.connected(BOB);
            queue.execute(alice, () -> { entered.countDown(); await(release); });
            await(entered);
            queue.execute(alice, bob, invoked::incrementAndGet);
            queue.disconnected(BOB);
            queue.connected(BOB);
            release.countDown();
            assertTrue(worker.shutdown(WAIT, WAIT).complete());
            assertEquals(0, invoked.get());
        } finally { release.countDown(); }
    }

    @Test void commandFailureStillCleansLateStateAndTheWorkerCanContinue() throws Exception {
        Map<UUID, String> state = new HashMap<>();
        var failure = new CompletableFuture<Throwable>();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var worker = PersistentWorker.commands("failed-player-command", root, 1)) {
            var queue = queue(worker, state);
            var alice = queue.connected(ALICE);
            queue.execute(alice, () -> {
                Thread.currentThread().setUncaughtExceptionHandler((thread, thrown) -> failure.complete(thrown));
                entered.countDown(); await(release);
                state.put(ALICE, "late state");
                throw new IllegalStateException("injected command failure");
            });
            await(entered);
            var bob = queue.connected(BOB);
            var finished = new CompletableFuture<Void>();
            queue.execute(bob, () -> {
                try { assertFalse(state.containsKey(ALICE)); finished.complete(null); }
                catch (Throwable thrown) { finished.completeExceptionally(thrown); }
            });
            queue.disconnected(ALICE);
            release.countDown();
            finished.get(5, TimeUnit.SECONDS);
            assertEquals("injected command failure", failure.get(5, TimeUnit.SECONDS).getMessage());
            assertTrue(worker.shutdown(WAIT, WAIT).complete());
        } finally { release.countDown(); }
    }

    @Test void identityChurnRetainsOnlyCurrentConnectionsAndCleanupStaysOnTheWorker() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var retirements = new AtomicInteger();
        var callbacks = new java.util.concurrent.ConcurrentLinkedQueue<String>();
        try (var worker = PersistentWorker.commands("bounded-connection-history", root, 1)) {
            var queue = new PlayerCommandQueue(worker, player -> {
                callbacks.add(Thread.currentThread().getName()); retirements.incrementAndGet();
            }, online -> callbacks.add(Thread.currentThread().getName()));
            var alice = queue.connected(ALICE);
            queue.execute(alice, () -> { entered.countDown(); await(release); });
            await(entered);
            queue.execute(alice, () -> {});
            for (int index = 0; index < 10_000; index++) {
                UUID churned = new UUID(1, index);
                queue.connected(churned);
                queue.disconnected(churned);
            }
            queue.disconnected(ALICE);
            // Observe retention only; connection semantics are asserted through actual commands above.
            var connections = PlayerCommandQueue.class.getDeclaredField("connections");
            connections.setAccessible(true);
            assertEquals(0, ((Map<?, ?>) connections.get(queue)).size());
            release.countDown();
            assertTrue(worker.shutdown(WAIT, WAIT).complete());
            var applied = PlayerCommandQueue.class.getDeclaredField("applied");
            applied.setAccessible(true);
            assertEquals(0, ((Map<?, ?>) applied.get(queue)).size());
            assertEquals(1, retirements.get());
            assertTrue(callbacks.stream().allMatch("bounded-connection-history"::equals));
        } finally { release.countDown(); }
    }

    @Test void missingForeignAndClosedConnectionsCannotAdmitCommands() throws Exception {
        try (var worker = PersistentWorker.commands("connection-admission", root, 2)) {
            var queue = queue(worker, new HashMap<>());
            var foreign = queue(worker, new HashMap<>()).connected(ALICE);
            var alice = queue.connected(ALICE);
            assertThrows(RejectedExecutionException.class, () -> queue.execute(null, () -> fail("Missing connection")));
            assertThrows(RejectedExecutionException.class, () -> queue.execute(foreign, () -> fail("Foreign connection")));
            queue.close();
            queue.close();
            assertThrows(RejectedExecutionException.class, () -> queue.connected(BOB));
            assertThrows(RejectedExecutionException.class, () -> queue.execute(alice, () -> fail("Closed queue")));
            assertTrue(worker.shutdown(WAIT, WAIT).complete());
        }
    }

    @Test void forcedShutdownKeepsTheDirectoryUntilAdmittedWorkAndItsCleanupFinish() throws Exception {
        Map<UUID, String> state = new HashMap<>();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var worker = PersistentWorker.commands("uninterruptible-player-command", root, 1);
        try {
            var queue = queue(worker, state);
            var alice = queue.connected(ALICE);
            queue.execute(alice, () -> {
                entered.countDown();
                boolean released = false;
                while (!released) {
                    try { released = release.await(5, TimeUnit.SECONDS); }
                    catch (InterruptedException ignored) { }
                }
                state.put(ALICE, "late selection");
            });
            await(entered);
            queue.execute(alice, () -> state.put(BOB, "cancelled command"));
            queue.close();
            var result = worker.shutdown(Duration.ZERO, Duration.ofMillis(20));
            assertFalse(result.complete());
            assertEquals(1, result.cancelledTasks());
            assertThrows(java.io.IOException.class, () -> PersistentWorker.commands("premature-reopen", root, 1));
        } finally {
            release.countDown();
            assertTrue(worker.shutdown(WAIT, WAIT).complete());
        }
        assertTrue(state.isEmpty());
        try (var restarted = PersistentWorker.commands("safe-reopen", root, 1)) { assertFalse(restarted.isShutdown()); }
    }

    private static PlayerCommandQueue queue(PersistentWorker worker, Map<UUID, String> state) {
        return new PlayerCommandQueue(worker, state::remove, online -> state.keySet().retainAll(online));
    }

    private static void await(CountDownLatch latch) {
        try { assertTrue(latch.await(5, TimeUnit.SECONDS), "Worker coordination timed out"); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
    }
}
