package dev.stargenesis.api.sim;
import java.util.Objects;
import java.util.UUID;
public record AgentId(UUID value) implements Comparable<AgentId> {
    public AgentId { Objects.requireNonNull(value); }
    @Override public int compareTo(AgentId other) { return value.toString().compareTo(other.value.toString()); }
}
