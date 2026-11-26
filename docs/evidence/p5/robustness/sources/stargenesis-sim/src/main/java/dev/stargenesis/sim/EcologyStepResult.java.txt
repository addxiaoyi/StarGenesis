package dev.stargenesis.sim;

import dev.stargenesis.api.sim.EventRecord;

import java.util.List;

public record EcologyStepResult(long tick, List<EventRecord> events, String stateHash,
                                String eventHash, boolean terminal, String reason) {
    public EcologyStepResult {
        events = List.copyOf(events);
    }
}
