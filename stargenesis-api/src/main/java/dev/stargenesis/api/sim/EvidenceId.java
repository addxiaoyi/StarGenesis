package dev.stargenesis.api.sim;
/** world:tick:sequence is the stable simulation evidence key, separate from real transactions. */
public record EvidenceId(String world, long tick, int sequence) {
    public EvidenceId {
        if (world == null || !world.matches("[a-z0-9][a-z0-9_-]{0,63}") || tick < 0 || sequence < 0)
            throw new IllegalArgumentException("Invalid evidence ID");
    }
    @Override public String toString() { return "sg:v1:" + world + ":" + tick + ":" + sequence; }
}
