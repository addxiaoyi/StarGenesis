package dev.stargenesis.api.sim;
/** Single-owner worker; adapters may consume immutable results only. No Bukkit types. */
public interface SimulationWorld extends AutoCloseable {
    String ENGINE_VERSION = "genesis-sim/1";
    String worldId();
    long seed();
    long tick();
    StepResult step(StepInput input);
    String stateHash();
    @Override void close();
}
