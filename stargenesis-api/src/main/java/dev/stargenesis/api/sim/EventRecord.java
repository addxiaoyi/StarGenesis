package dev.stargenesis.api.sim;
import java.util.Map;
import java.util.Objects;
public record EventRecord(EvidenceId evidenceId, String type, Map<String, String> data) {
    public EventRecord {
        Objects.requireNonNull(evidenceId);
        if (type == null || !type.matches("[a-z][a-z0-9_.-]{0,63}")) throw new IllegalArgumentException("Invalid event type");
        data = Map.copyOf(data);
        if (data.size() > 32 || data.entrySet().stream().anyMatch(e -> e.getKey().length() > 64 || e.getValue().length() > 512))
            throw new IllegalArgumentException("Unbounded event payload");
    }
}
