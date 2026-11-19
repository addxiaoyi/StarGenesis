package dev.stargenesis.api.sim;
/** Owner-thread only. Logical ticks, never wall time, determine simulation results. */
public interface SimulationClock {
    long tick();
    long advance();
    long stepDurationMillis();
}
