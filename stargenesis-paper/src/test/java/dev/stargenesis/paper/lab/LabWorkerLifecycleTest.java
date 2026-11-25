package dev.stargenesis.paper.lab;

import dev.stargenesis.api.sim.SimulationBudget;
import dev.stargenesis.paper.runtime.PersistentWorker;
import dev.stargenesis.sim.ReplayVerifier;
import dev.stargenesis.sim.SandboxConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class LabWorkerLifecycleTest {
    @Test void shutdownCheckpointRunsOnTheWorldOwnerBeforeReleasingItsDirectory(@TempDir Path directory) throws Exception {
        var owner = new UUID(0, 1402);
        var persistence = new LabPersistence(directory);
        var failure = new AtomicReference<Throwable>();
        var experiment = new AtomicReference<LabExperiment>();
        var ready = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var worker = PersistentWorker.scheduled("lab-checkpoint", directory);
        worker.execute(() -> {
            try {
                var running = new LabExperiment(owner, "lab-worker-shutdown", 42, SandboxConfig.defaults(),
                        LabExperiment.Mode.NEW, SimulationBudget.defaults(), persistence);
                experiment.set(running);
                for (int tick = 0; tick < 12; tick++) running.advance(false);
            } catch (Throwable error) { failure.set(error); }
            ready.countDown();
            while (true) { try { release.await(); break; } catch (InterruptedException ignored) { } }
        });
        try {
            assertTrue(ready.await(3, TimeUnit.SECONDS));
            assertNull(failure.get());
            assertEquals(0, persistence.loadSnapshot(owner).orElseThrow().tick());
            worker.execute(() -> { try { experiment.get().close(); } catch (Throwable error) { failure.set(error); } });
            var closing = CompletableFuture.supplyAsync(() -> worker.shutdown(Duration.ofSeconds(3), Duration.ofSeconds(3)));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!worker.isShutdown() && System.nanoTime() < deadline) Thread.sleep(1);
            assertTrue(worker.isShutdown());
            release.countDown();
            assertTrue(closing.get(5, TimeUnit.SECONDS).complete());
            assertNull(failure.get());
            assertEquals(12, persistence.loadSnapshot(owner).orElseThrow().tick());
            assertEquals(LabExperiment.Phase.STOPPED, experiment.get().phase());
            assertTrue(ReplayVerifier.verify(persistence.loadReplay(owner).orElseThrow()).passed());
        } finally { release.countDown(); worker.close(); }
        try (var next = PersistentWorker.scheduled("lab-next", directory)) {
            var resumed = new CompletableFuture<Long>();
            next.execute(() -> {
                try (var running = new LabExperiment(owner, "unused", 0, SandboxConfig.defaults(), LabExperiment.Mode.RESUME,
                        SimulationBudget.defaults(), new LabPersistence(directory))) {
                    running.advance(false);
                    resumed.complete(running.view().tick());
                } catch (Throwable error) { resumed.completeExceptionally(error); }
            });
            assertEquals(13, resumed.get(5, TimeUnit.SECONDS));
        }
    }
}
