package dev.stargenesis.integrations;

import java.util.Objects;

/** Durable saga cursor. Cursor counts forward effects still requiring observation or compensation. */
public record SettlementCheckpoint(SettlementPlan plan, State state, int cursor, long revision, String diagnostic) {
    public SettlementCheckpoint {
        Objects.requireNonNull(plan, "plan");
        Objects.requireNonNull(state, "state");
        diagnostic = diagnostic == null ? "" : diagnostic;
        if (cursor < 0 || cursor > plan.transfers().size() || revision < 0
                || diagnostic.length() > 240 || diagnostic.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid settlement checkpoint");
        if ((state == State.PREPARED && cursor != 0)
                || (state == State.APPLY_REVIEW && cursor == plan.transfers().size())
                || (state == State.COMPENSATION_REVIEW && cursor == 0)
                || (state == State.COMMITTED && cursor != plan.transfers().size())
                || (state == State.ABORTED && cursor != 0))
            throw new IllegalArgumentException("Settlement checkpoint state and cursor disagree");
    }

    public enum State {
        PREPARED,
        APPLYING,
        COMPENSATING,
        APPLY_REVIEW,
        COMPENSATION_REVIEW,
        COMMITTED,
        ABORTED
    }
}
