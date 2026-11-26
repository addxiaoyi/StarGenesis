package dev.stargenesis.sim;

import dev.stargenesis.api.sim.SimulationClock;

/** Fixed logical time. Restoring a tick never reads wall time. */
public final class FixedSimulationClock implements SimulationClock {
    private final OwnerThread owner = new OwnerThread();
    private final long stepDurationMillis;
    private long tick;

    public FixedSimulationClock(long stepDurationMillis) { this(0, stepDurationMillis); }

    public FixedSimulationClock(long initialTick, long stepDurationMillis) {
        if (initialTick < 0 || stepDurationMillis < 1 || stepDurationMillis > 60_000) {
            throw new IllegalArgumentException("Invalid logical clock bounds");
        }
        tick = initialTick;
        this.stepDurationMillis = stepDurationMillis;
    }

    @Override public long tick() { owner.check(); return tick; }
    @Override public long advance() { owner.check(); tick = Math.incrementExact(tick); return tick; }
    @Override public long stepDurationMillis() { owner.check(); return stepDurationMillis; }
}
