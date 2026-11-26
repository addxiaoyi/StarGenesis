package dev.stargenesis.paper.runtime;

import java.lang.management.ManagementFactory;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Coordinate lifecycle races by observing the actual store/service monitor, without server mocks. */
public final class WorkspaceRace {
    private WorkspaceRace() { }

    public static void exactlyOneAdmission(Callable<?> first, Callable<?> second) throws Exception {
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> admit(first, ready, start));
            var two = executor.submit(() -> admit(second, ready, start));
            try {
                assertTrue(ready.await(5, TimeUnit.SECONDS));
            } finally { start.countDown(); }
            assertNotEquals(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS));
        }
    }

    private static boolean admit(Callable<?> operation, CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Admission did not start");
        try { operation.call(); return true; }
        catch (IllegalArgumentException denied) { return false; }
    }

    public static <T> T duringStoreCall(Object store, Callable<T> operation, Object service, Runnable retirement)
            throws Exception {
        var operating = new AtomicReference<Thread>();
        var retiring = new AtomicReference<Thread>();
        try (var executor = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Future<T> result;
            java.util.concurrent.Future<?> retired;
            synchronized (store) {
                result = executor.submit(() -> { operating.set(Thread.currentThread()); return operation.call(); });
                awaitBlockedOn(operating, store);
                retired = executor.submit(() -> { retiring.set(Thread.currentThread()); retirement.run(); });
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (!retired.isDone() && !blockedOn(retiring.get(), service) && System.nanoTime() < deadline)
                    Thread.sleep(5);
                assertTrue(retired.isDone() || blockedOn(retiring.get(), service), "Retirement did not reach lifecycle boundary");
            }
            T value = result.get(10, TimeUnit.SECONDS);
            retired.get(10, TimeUnit.SECONDS);
            return value;
        }
    }

    private static void awaitBlockedOn(AtomicReference<Thread> reference, Object monitor) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (blockedOn(reference.get(), monitor)) return;
            Thread.sleep(5);
        }
        fail("Operation did not reach the expected store monitor");
    }

    private static boolean blockedOn(Thread thread, Object monitor) {
        if (thread == null) return false;
        var info = ManagementFactory.getThreadMXBean().getThreadInfo(thread.threadId());
        return info != null && info.getThreadState() == Thread.State.BLOCKED && info.getLockInfo() != null
                && info.getLockInfo().getIdentityHashCode() == System.identityHashCode(monitor);
    }
}
