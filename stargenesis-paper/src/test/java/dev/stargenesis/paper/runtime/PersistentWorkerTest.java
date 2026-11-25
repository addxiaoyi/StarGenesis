package dev.stargenesis.paper.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

class PersistentWorkerTest {
    private static final Duration WAIT = Duration.ofSeconds(3);

    @Test void drainsAcceptedCommandsInOrderRejectsOverflowAndStopsAdmission(@TempDir Path root) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var order = new ArrayList<Integer>();
        try (var worker = PersistentWorker.commands("worker-drain", root, 2)) {
            worker.execute(() -> { entered.countDown(); awaitIgnoringInterrupt(release); order.add(1); });
            await(entered);
            worker.execute(() -> order.add(2));
            worker.execute(() -> order.add(3));
            assertThrows(RejectedExecutionException.class, () -> worker.execute(() -> order.add(4)));
            var closing = CompletableFuture.supplyAsync(() -> worker.shutdown(WAIT, WAIT));
            try {
                until(worker::isShutdown);
                assertThrows(RejectedExecutionException.class, () -> worker.execute(() -> order.add(5)));
                assertThrows(IOException.class, () -> PersistentWorker.commands("overlap", root, 2));
            } finally { release.countDown(); }
            var result = closing.get(5, TimeUnit.SECONDS);
            assertTrue(result.complete());
            assertFalse(result.forced());
            assertEquals(0, result.cancelledTasks());
            assertEquals(List.of(1, 2, 3), order);
        } finally { release.countDown(); }
        try (var next = PersistentWorker.commands("next-generation", root, 2)) {
            assertFalse(next.isShutdown());
        }
    }

    @Test void timedOutCommandAndScheduledWorkersRetainTheirLeaseUntilTheLastWriteFinishes(@TempDir Path root) throws Exception {
        for (boolean scheduled : List.of(false, true)) {
            Path directory = root.resolve(Boolean.toString(scheduled));
            Path output = directory.resolve("result.txt");
            var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1);
            var worker = scheduled ? PersistentWorker.scheduled("unresponsive-ticker", directory)
                    : PersistentWorker.commands("unresponsive-command", directory, 2);
            var task = new CompletableFuture<Void>();
            worker.execute(() -> {
                entered.countDown();
                awaitIgnoringInterrupt(release);
                try { Files.writeString(output, "old operation completed"); task.complete(null); }
                catch (Throwable failure) { task.completeExceptionally(failure); }
            });
            try {
                await(entered);
                var result = worker.shutdown(Duration.ZERO, Duration.ofMillis(20));
                assertTrue(result.forced());
                assertFalse(result.terminated());
                assertFalse(result.directoryReleased());
                assertFalse(result.complete());
                assertFalse(worker.isStopped());
                assertFalse(Files.exists(output));
                assertThrows(IOException.class, () -> PersistentWorker.commands("premature-restart", directory, 2));
            } finally {
                release.countDown();
                assertTrue(worker.shutdown(WAIT, WAIT).complete());
            }
            task.get(5, TimeUnit.SECONDS);
            try (var next = PersistentWorker.commands("safe-restart", directory, 2)) {
                assertEquals("old operation completed", Files.readString(output));
                assertFalse(next.isShutdown());
            }
        }
    }

    @Test void forcedShutdownCancelsPendingWritesAndWaitsForAnInterruptibleTask(@TempDir Path root) throws Exception {
        var entered = new CountDownLatch(1);
        var interrupted = new AtomicBoolean();
        var queuedWrites = new AtomicInteger();
        try (var worker = PersistentWorker.commands("cancel-queue", root, 2)) {
            worker.execute(() -> {
                entered.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException expected) { interrupted.set(true); }
            });
            await(entered);
            worker.execute(queuedWrites::incrementAndGet);
            worker.execute(queuedWrites::incrementAndGet);
            var result = worker.shutdown(Duration.ZERO, WAIT);
            assertTrue(result.complete());
            assertTrue(result.forced());
            assertTrue(interrupted.get());
            assertEquals(2, result.cancelledTasks());
            assertEquals(0, queuedWrites.get());
            assertEquals(2, worker.shutdown(Duration.ZERO, WAIT).cancelledTasks());
        }
    }

    @Test void callerInterruptionIsPreservedWhileTheWorkerIsStillStopped(@TempDir Path root) throws Exception {
        var entered = new CountDownLatch(1);
        try (var worker = PersistentWorker.commands("caller-interruption", root, 2)) {
            worker.execute(() -> {
                entered.countDown();
                try { new CountDownLatch(1).await(); }
                catch (InterruptedException expected) { Thread.currentThread().interrupt(); }
            });
            await(entered);
            try {
                Thread.currentThread().interrupt();
                var result = worker.shutdown(WAIT, WAIT);
                assertTrue(result.complete());
                assertTrue(result.interrupted());
                assertTrue(result.forced());
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); }
        }
    }

    @Test void scheduledShutdownRunsTheFinalCheckpointAndDoesNotRunTheNextTick(@TempDir Path root) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var order = new ArrayList<String>();
        try (var worker = PersistentWorker.scheduled("lab-final-checkpoint", root)) {
            worker.scheduleWithFixedDelay(() -> {
                order.add("tick");
                entered.countDown();
                awaitIgnoringInterrupt(release);
            }, 0, 1, TimeUnit.MILLISECONDS);
            await(entered);
            worker.execute(() -> order.add("checkpoint"));
            var closing = CompletableFuture.supplyAsync(() -> worker.shutdown(WAIT, WAIT));
            try { until(worker::isShutdown); }
            finally { release.countDown(); }
            var result = closing.get(5, TimeUnit.SECONDS);
            assertTrue(result.complete());
            assertEquals(List.of("tick", "checkpoint"), order);
        } finally { release.countDown(); }
    }

    @Test void anIdleWorkerOwnsItsDirectoryAndReleasesItOnClose(@TempDir Path root) throws Exception {
        try (var worker = PersistentWorker.commands("idle-owner", root, 1)) {
            assertThrows(IOException.class, () -> PersistentWorker.scheduled("other-owner", root.resolve(".")));
            try (var independent = PersistentWorker.commands("other-directory", root.resolve("separate"), 1)) {
                assertTrue(independent.shutdown(Duration.ZERO, WAIT).complete());
            }
            assertTrue(worker.shutdown(Duration.ZERO, WAIT).complete());
            assertTrue(worker.shutdown(Duration.ZERO, WAIT).complete());
        }
        assertTrue(Files.isRegularFile(root.resolve(".worker.lock")), "Do not unlink a shared lock inode");
        try (var next = PersistentWorker.scheduled("new-owner", root)) { assertFalse(next.isShutdown()); }
    }

    @Test void invalidLockPathsAndBudgetsRejectWithoutTakingDirectoryOwnership(@TempDir Path root) throws Exception {
        Files.createDirectory(root.resolve(".worker.lock"));
        assertThrows(IOException.class, () -> PersistentWorker.commands("bad-lock", root, 1));
        Files.delete(root.resolve(".worker.lock"));
        assertThrows(IllegalArgumentException.class, () -> PersistentWorker.commands("bad-budget", root, 129));
        try (var worker = PersistentWorker.commands("valid-owner", root, 1)) {
            assertThrows(IllegalArgumentException.class, () -> worker.shutdown(Duration.ofNanos(-1), WAIT));
            assertFalse(worker.isShutdown());
            assertThrows(IllegalStateException.class, () -> worker.scheduleWithFixedDelay(() -> { }, 0, 1, TimeUnit.SECONDS));
        }
    }

    @Test void taskFailureDoesNotReleaseOwnershipWhileTheReplacementWorkerContinues(@TempDir Path root) throws Exception {
        var failed = new CountDownLatch(1);
        var ranNext = new CountDownLatch(1);
        try (var worker = PersistentWorker.commands("failed-command", root, 2)) {
            worker.execute(() -> {
                Thread.currentThread().setUncaughtExceptionHandler((thread, failure) -> failed.countDown());
                throw new IllegalStateException("deliberate task failure");
            });
            worker.execute(ranNext::countDown);
            await(failed);
            await(ranNext);
            assertThrows(IOException.class, () -> PersistentWorker.commands("cannot-overlap", root, 1));
            assertTrue(worker.shutdown(WAIT, WAIT).complete());
        }
    }

    @Test void anIndependentPluginClassloaderCannotOverlapTheExistingRuntime(@TempDir Path root) throws Exception {
        var location = PersistentWorker.class.getProtectionDomain().getCodeSource().getLocation();
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{location}, ClassLoader.getPlatformClassLoader())) {
            var independentType = loader.loadClass(PersistentWorker.class.getName());
            assertNotSame(PersistentWorker.class, independentType);
            var commands = independentType.getMethod("commands", String.class, Path.class, int.class);
            try (var worker = PersistentWorker.commands("old-plugin-loader", root, 1)) {
                var failure = assertThrows(java.lang.reflect.InvocationTargetException.class,
                        () -> commands.invoke(null, "new-plugin-loader", root, 1));
                assertInstanceOf(IOException.class, failure.getCause());
                assertFalse(worker.isStopped());
            }
            try (var restarted = (AutoCloseable) commands.invoke(null, "new-plugin-loader", root, 1)) {
                assertThrows(IOException.class, () -> PersistentWorker.commands("reverse-contender", root, 1));
            }
            try (var finalOwner = PersistentWorker.commands("released-across-loaders", root, 1)) {
                assertFalse(finalOwner.isShutdown());
            }
        }
    }

    private static void await(CountDownLatch latch) throws InterruptedException { assertTrue(latch.await(3, TimeUnit.SECONDS)); }

    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try { latch.await(); break; }
            catch (InterruptedException ignored) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private static void until(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + WAIT.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(1);
        assertTrue(condition.getAsBoolean());
    }
}
