package dev.stargenesis.paper.ecology;

import dev.stargenesis.paper.runtime.PersistentWorker;
import dev.stargenesis.sim.EcologyConfig;
import dev.stargenesis.sim.EcologyInput;
import dev.stargenesis.sim.EcologyReplayVerifier;
import dev.stargenesis.sim.EcologyWorld;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class EcologyWorkerLifecycleTest {
    private static final UUID OWNER = new UUID(0, 1401);
    private static final Duration WAIT = Duration.ofSeconds(3);

    @Test void gracefulClosePersistsEveryAcceptedCommandBeforeRestartAndContinuesExactly(@TempDir Path directory) throws Exception {
        var saved = new EcologyPersistence(directory);
        var failure = new AtomicReference<Throwable>();
        try (var worker = PersistentWorker.commands("ecology-drain", directory, 8)) {
            var service = new EcologyService(saved);
            for (String[] args : new String[][]{{"start", "42", "1536"}, {"step", "20"}, {"step", "20"}})
                worker.execute(() -> { try { service.execute(OWNER, args); } catch (Throwable error) { failure.compareAndSet(null, error); } });
            assertTrue(worker.shutdown(WAIT, WAIT).complete());
        }
        assertNull(failure.get());
        var replay = saved.load(OWNER).orElseThrow();
        assertEquals(40, replay.frames().size());
        assertTrue(EcologyReplayVerifier.verify(replay).passed());
        try (var next = PersistentWorker.commands("ecology-restart", directory, 2);
             var expected = new EcologyWorld("ecology-" + OWNER.toString().replace("-", ""), 42, EcologyConfig.forDuration(1536))) {
            var continued = new CompletableFuture<Void>();
            next.execute(() -> {
                try { new EcologyService(new EcologyPersistence(directory)).execute(OWNER, new String[]{"step", "20"}); continued.complete(null); }
                catch (Throwable error) { continued.completeExceptionally(error); }
            });
            continued.get(5, TimeUnit.SECONDS);
            for (int tick = 0; tick < 60; tick++) expected.step(EcologyInput.idle(tick));
            assertEquals(expected.replay(), saved.load(OWNER).orElseThrow());
        }
    }

    @Test void cancelledQueueCannotResetTheTankAndRestartWaitsForTheUnresponsiveOperation(@TempDir Path directory) throws Exception {
        var saved = new EcologyPersistence(directory);
        var service = new EcologyService(saved);
        service.execute(OWNER, new String[]{"start", "42"});
        Path file = directory.resolve(OWNER + ".ecology.json");
        byte[] before = Files.readAllBytes(file);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var continued = new CompletableFuture<Void>();
        var worker = PersistentWorker.commands("ecology-delayed", directory, 2);
        worker.execute(() -> {
            entered.countDown();
            // Model a provider/operation which ignores cancellation, then reaches its write boundary.
            while (true) { try { release.await(); break; } catch (InterruptedException ignored) { } }
            try { service.execute(OWNER, new String[]{"step", "20"}); continued.complete(null); }
            catch (Throwable error) { continued.completeExceptionally(error); }
        });
        try {
            assertTrue(entered.await(3, TimeUnit.SECONDS));
            worker.execute(() -> {
                try { service.execute(OWNER, new String[]{"reset"}); }
                catch (IOException error) { throw new java.io.UncheckedIOException(error); }
            });
            var result = worker.shutdown(Duration.ZERO, Duration.ofMillis(20));
            assertFalse(result.complete());
            assertEquals(1, result.cancelledTasks());
            assertArrayEquals(before, Files.readAllBytes(file));
            assertThrows(IOException.class, () -> PersistentWorker.commands("ecology-too-early", directory, 2));
        } finally {
            release.countDown();
            assertTrue(worker.shutdown(WAIT, WAIT).complete());
        }
        continued.get(5, TimeUnit.SECONDS);
        try (var next = PersistentWorker.commands("ecology-after-write", directory, 2)) {
            assertEquals(20, new EcologyPersistence(directory).load(OWNER).orElseThrow().frames().size());
            assertTrue(EcologyReplayVerifier.verify(saved.load(OWNER).orElseThrow()).passed());
            assertFalse(next.isShutdown());
        }
    }
}
