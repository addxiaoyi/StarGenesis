import dev.stargenesis.paper.runtime.PersistentWorker;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Isolated process fixture; never points at a live plugin data directory. */
public final class WorkerDirectoryLockProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("WorkerDirectoryLockProbe <hold|timed-out|reject|acquire> <temporary-directory>");
        Path root = Path.of(args[1]), data = root.resolve("data");
        switch (args[0]) {
            case "reject" -> {
                try (var unexpected = PersistentWorker.commands("unexpected-owner", data, 1)) {
                    throw new AssertionError("Another process acquired an active worker directory");
                } catch (IOException expected) { Files.writeString(root.resolve("rejected"), "locked"); }
            }
            case "acquire" -> {
                try (var next = PersistentWorker.commands("new-owner", data, 1)) {
                    Files.writeString(root.resolve("acquired"), "locked");
                }
            }
            case "hold", "timed-out" -> {
                try (var worker = PersistentWorker.commands("original-owner", data, 1)) {
                    var entered = new CountDownLatch(1);
                    var finished = new CompletableFuture<Void>();
                    worker.execute(() -> {
                        entered.countDown();
                        try { waitForRelease(root); finished.complete(null); }
                        catch (Throwable failure) { finished.completeExceptionally(failure); }
                    });
                    if (!entered.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("Worker did not start");
                    if (args[0].equals("timed-out")) {
                        if (worker.shutdown(Duration.ZERO, Duration.ZERO).complete())
                            throw new AssertionError("Unresponsive operation incorrectly reported terminated");
                        Files.writeString(root.resolve("incomplete"), "shutdown incomplete");
                    }
                    try (var unexpected = PersistentWorker.commands("same-jvm-contender", data, 1)) {
                        throw new AssertionError("Same JVM acquired an active worker directory");
                    } catch (IOException expected) { }
                    // A reload creates a separate plugin classloader. Its rejected attempt must not
                    // open/close a second POSIX file descriptor and accidentally drop the OS lock.
                    var location = PersistentWorker.class.getProtectionDomain().getCodeSource().getLocation();
                    try (var loader = new java.net.URLClassLoader(new java.net.URL[]{location}, ClassLoader.getPlatformClassLoader())) {
                        var factory = loader.loadClass(PersistentWorker.class.getName()).getMethod("commands", String.class, Path.class, int.class);
                        try (var unexpected = (AutoCloseable) factory.invoke(null, "new-plugin-loader", data, 1)) {
                            throw new AssertionError("Another classloader acquired an active directory");
                        } catch (java.lang.reflect.InvocationTargetException expected) {
                            if (!(expected.getCause() instanceof IOException)) throw expected;
                        }
                    }
                    Files.writeString(root.resolve("holding"), "same JVM and classloader contenders rejected");
                    finished.get(25, TimeUnit.SECONDS);
                    if (!worker.shutdown(Duration.ofSeconds(3), Duration.ofSeconds(3)).complete())
                        throw new AssertionError("Released operation did not terminate");
                    Files.writeString(root.resolve("released"), "closed");
                }
            }
            default -> throw new IllegalArgumentException("Unknown mode");
        }
    }

    private static void waitForRelease(Path root) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (!Files.exists(root.resolve("release"))) {
            if (System.nanoTime() >= deadline) throw new IllegalStateException("Probe release timed out");
            try { Thread.sleep(10); } catch (InterruptedException ignored) { }
        }
    }
}
