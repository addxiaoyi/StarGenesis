package dev.stargenesis.api.sim;
import java.util.List;
/** At-least-once delivery; same evidence with different data must fail closed. */
public interface EventOutbox {
    boolean append(EventRecord event);
    List<EventRecord> pending(int limit);
    void acknowledge(EvidenceId evidenceId);
}
