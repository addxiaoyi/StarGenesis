package dev.stargenesis.sim;

/** Construct an instance on its simulation worker; immutable snapshots may leave that worker. */
final class OwnerThread {
    private final Thread owner = Thread.currentThread();

    void check() {
        if (Thread.currentThread() != owner) {
            throw new IllegalStateException("Simulation state accessed outside its owner thread");
        }
    }
}
