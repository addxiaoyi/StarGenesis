package dev.stargenesis.sim;

import java.util.List;
import java.util.Objects;

public record EcologyStepResult(long tick, List<EcologyEvent> events, EcologyStatistics statistics,
                                List<MatterReactionEvent> reactionEvents,
                                String stateHash, boolean terminal, String terminalReason) {
    public EcologyStepResult(long tick, List<EcologyEvent> events, EcologyStatistics statistics,
                             String stateHash, boolean terminal, String terminalReason) {
        this(tick, events, statistics, List.of(), stateHash, terminal, terminalReason);
    }

    public EcologyStepResult {
        if (tick < 1 || stateHash == null || !stateHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid ecology step result");
        }
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        reactionEvents = List.copyOf(Objects.requireNonNull(reactionEvents, "reactionEvents"));
        if (events.size() > EcologyWorld.MAX_EVENTS_PER_STEP) {
            throw new IllegalArgumentException("Ecology event budget exceeded");
        }
        if (reactionEvents.size() > MatterReactionNetwork.MAX_REACTIONS
                || reactionEvents.stream().mapToInt(MatterReactionEvent::firings).sum()
                > MatterReactionNetwork.MAX_FIRINGS_PER_STEP) {
            throw new IllegalArgumentException("Ecology reaction budget exceeded");
        }
        Objects.requireNonNull(statistics, "statistics");
        terminalReason = Objects.requireNonNull(terminalReason, "terminalReason");
        if (terminal != !terminalReason.isEmpty()) throw new IllegalArgumentException("Terminal reason mismatch");
    }
}
