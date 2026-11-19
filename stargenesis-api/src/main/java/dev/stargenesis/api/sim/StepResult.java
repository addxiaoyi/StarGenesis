package dev.stargenesis.api.sim;
import java.util.List;
public record StepResult(long tick, List<EventRecord> events, String stateHash, boolean terminal, String reason) {
    public StepResult {
        events = List.copyOf(events);
        if (tick < 0 || stateHash == null || !stateHash.matches("[0-9a-f]{64}") || reason == null || reason.length() > 512)
            throw new IllegalArgumentException("Invalid step result");
        if (events.size() > 16384) throw new IllegalArgumentException("Too many events");
    }
}
