package dev.stargenesis.paper.runtime;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Objects;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;

/** A serial worker owns its storage directory until its executor has actually terminated. */
public final class PersistentWorker implements AutoCloseable {
    private static final Duration GRACE = Duration.ofSeconds(5);
    private static final Duration INTERRUPT_GRACE = Duration.ofSeconds(1);
    private final DirectoryLease lease;
    private final ThreadPoolExecutor executor;
    private final AtomicInteger cancelled = new AtomicInteger();
    private final AtomicBoolean forced = new AtomicBoolean();
    private volatile IOException releaseFailure;

    public static PersistentWorker commands(String name, Path directory, int capacity) throws IOException {
        if (capacity < 1 || capacity > 128) throw new IllegalArgumentException("Worker queue capacity must be 1..128");
        return new PersistentWorker(name, directory, capacity, false);
    }

    /** The lab has one periodic task and one final checkpoint task; no general delayed-command queue. */
    public static PersistentWorker scheduled(String name, Path directory) throws IOException {
        return new PersistentWorker(name, directory, 0, true);
    }

    private PersistentWorker(String name, Path directory, int capacity, boolean scheduled) throws IOException {
        if (name == null || name.isBlank()) throw new IllegalArgumentException("Missing worker name");
        lease = DirectoryLease.acquire(directory);
        ThreadFactory factory = task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        };
        try {
            if (scheduled) {
                var scheduler = new ScheduledThreadPoolExecutor(1, factory, new ThreadPoolExecutor.AbortPolicy()) {
                    @Override protected void terminated() { releaseDirectory(); }
                };
                scheduler.setRemoveOnCancelPolicy(true);
                scheduler.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
                scheduler.setContinueExistingPeriodicTasksAfterShutdownPolicy(false);
                executor = scheduler;
            } else {
                executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(capacity), factory, new ThreadPoolExecutor.AbortPolicy()) {
                    @Override protected void terminated() { releaseDirectory(); }
                };
            }
        } catch (RuntimeException | Error failure) {
            try { lease.close(); } catch (IOException closing) { failure.addSuppressed(closing); }
            throw failure;
        }
    }

    public void execute(Runnable task) { executor.execute(Objects.requireNonNull(task)); }

    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, long initialDelay, long delay, TimeUnit unit) {
        if (!(executor instanceof ScheduledThreadPoolExecutor scheduler))
            throw new IllegalStateException("This worker does not schedule periodic tasks");
        return scheduler.scheduleWithFixedDelay(task, initialDelay, delay, unit);
    }

    public boolean isShutdown() { return executor.isShutdown(); }
    public boolean isStopped() { return executor.isTerminated() && lease.released() && releaseFailure == null; }

    /** Stop admission, drain accepted work, then cancel queued work and interrupt the running operation. */
    public ShutdownResult shutdown(Duration grace, Duration interruptGrace) {
        long graceNanos = nonnegativeNanos(grace), interruptNanos = nonnegativeNanos(interruptGrace);
        boolean interrupted = false;
        executor.shutdown();
        try {
            try { executor.awaitTermination(graceNanos, TimeUnit.NANOSECONDS); }
            catch (InterruptedException failure) { interrupted = true; }
            if (!executor.isTerminated()) {
                forced.set(true);
                cancelled.addAndGet(executor.shutdownNow().size());
                try { executor.awaitTermination(interruptNanos, TimeUnit.NANOSECONDS); }
                catch (InterruptedException failure) { interrupted = true; }
            }
            return new ShutdownResult(executor.isTerminated(), lease.released(), forced.get(),
                    cancelled.get(), interrupted, releaseFailure == null ? "" : releaseFailure.toString());
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    public ShutdownResult shutdown(Logger logger, String service) {
        ShutdownResult result = shutdown(GRACE, INTERRUPT_GRACE);
        if (!result.complete()) logger.log(Level.SEVERE, service
                + " worker is still active or its directory lease could not be released; restart is blocked: " + result);
        else if (result.forced()) logger.warning(service + " worker required interruption; cancelled queued tasks=" + result.cancelledTasks());
        return result;
    }

    @Override public void close() { shutdown(GRACE, INTERRUPT_GRACE); }

    private void releaseDirectory() {
        try { lease.close(); }
        catch (IOException failure) { releaseFailure = failure; }
    }

    private static long nonnegativeNanos(Duration duration) {
        if (Objects.requireNonNull(duration).isNegative()) throw new IllegalArgumentException("Negative shutdown duration");
        return duration.toNanos();
    }

    public record ShutdownResult(boolean terminated, boolean directoryReleased, boolean forced,
                                 int cancelledTasks, boolean interrupted, String releaseError) {
        public boolean complete() { return terminated && directoryReleased && releaseError.isEmpty(); }
    }

    private static final class DirectoryLease implements AutoCloseable {
        private final FileChannel channel;
        private final FileLock lock;
        private final String processKey;
        private final String token;
        private volatile boolean released;

        private DirectoryLease(FileChannel channel, FileLock lock, String processKey, String token) {
            this.channel = channel; this.lock = lock; this.processKey = processKey; this.token = token;
        }

        static DirectoryLease acquire(Path directory) throws IOException {
            Path root = Objects.requireNonNull(directory).toAbsolutePath().normalize();
            Files.createDirectories(root);
            if (Files.isSymbolicLink(root)) throw new IOException("Worker directory symlink rejected");
            Path file = root.toRealPath().resolve(".worker.lock");
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Worker lock is not a regular file");
            String identity = file.toString();
            if (java.io.File.separatorChar == '\\') identity = identity.toLowerCase(Locale.ROOT);
            String processKey = ("dev.stargenesis.runtime.workerLease:" + identity).intern();
            String token = UUID.randomUUID().toString();
            // Cross-plugin-classloader reservation: opening then closing another descriptor may release
            // this JVM's existing POSIX lock. Reject same-JVM contenders before they open the lock file.
            synchronized (processKey) {
                if (System.getProperties().putIfAbsent(processKey, token) != null)
                    throw new IOException("Worker directory is still owned by another runtime: " + root);
            }
            FileChannel channel = null;
            try {
                channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                FileLock lock;
                try { lock = channel.tryLock(); }
                catch (OverlappingFileLockException busy) { throw new IOException("Worker directory is still owned by another runtime: " + root, busy); }
                if (lock == null) throw new IOException("Worker directory is still owned by another runtime: " + root);
                return new DirectoryLease(channel, lock, processKey, token);
            } catch (IOException | RuntimeException | Error failure) {
                try { if (channel != null) channel.close(); }
                catch (IOException closing) { failure.addSuppressed(closing); }
                finally { System.getProperties().remove(processKey, token); }
                throw failure;
            }
        }

        boolean released() { return released; }

        @Override public void close() throws IOException {
            try { lock.release(); }
            finally {
                channel.close();
                released = true;
                System.getProperties().remove(processKey, token);
            }
            // Keep the lock file: unlinking it could let another process lock a different inode.
        }
    }
}
