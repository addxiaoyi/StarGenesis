package dev.stargenesis.sim;

import dev.stargenesis.api.sim.EventOutbox;
import dev.stargenesis.api.sim.EventRecord;
import dev.stargenesis.api.sim.EvidenceId;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Bounded reference implementation, NOT durable delivery. Acknowledged evidence stays reserved.
 * Consumers must handle at-least-once deliveries idempotently. Durable adapter is a P9 dependency.
 */
public final class MemoryEventOutbox implements EventOutbox {
    private final OwnerThread owner = new OwnerThread();
    private final Map<EvidenceId, Entry> records = new TreeMap<>(Comparator.comparing(EvidenceId::world)
            .thenComparingLong(EvidenceId::tick).thenComparingInt(EvidenceId::sequence));
    private final int maxRecords;

    public MemoryEventOutbox() { this(16_384); }

    public MemoryEventOutbox(int maxRecords) {
        if (maxRecords < 1 || maxRecords > 100_000) throw new IllegalArgumentException("Invalid outbox evidence budget");
        this.maxRecords = maxRecords;
    }

    @Override public boolean append(EventRecord event) {
        owner.check(); Objects.requireNonNull(event);
        Entry old = records.get(event.evidenceId());
        if (old != null) {
            if (!old.event.equals(event)) throw new IllegalArgumentException("Evidence ID reused with a different event");
            return false;
        }
        if (records.size() >= maxRecords) throw new IllegalStateException("Outbox evidence budget exhausted");
        records.put(event.evidenceId(), new Entry(event, false));
        return true;
    }

    /** Canonical world/tick/sequence order, independent of producer insertion order. */
    @Override public List<EventRecord> pending(int limit) {
        owner.check();
        if (limit < 1 || limit > 10_000) throw new IllegalArgumentException("Invalid outbox batch limit");
        return records.values().stream().filter(entry -> !entry.acknowledged).limit(limit).map(Entry::event).toList();
    }

    @Override public void acknowledge(EvidenceId evidenceId) {
        owner.check(); Objects.requireNonNull(evidenceId);
        Entry old = records.get(evidenceId);
        if (old == null) throw new IllegalArgumentException("Cannot acknowledge unknown evidence");
        records.put(evidenceId, new Entry(old.event, true));
    }

    private record Entry(EventRecord event, boolean acknowledged) {}
}
