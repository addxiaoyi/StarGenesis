package dev.stargenesis.sim;

import dev.stargenesis.api.sim.EventRecord;

import java.util.List;

public record FactoryStepResult(long tick, List<EventRecord> events, String stateHash,
                                String eventHash, boolean terminal, String reason) {
    public FactoryStepResult { events = List.copyOf(events); }
}
