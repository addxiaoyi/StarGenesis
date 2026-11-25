package dev.stargenesis.integrations;

import java.util.Objects;

/** Immutable persisted state for a currency-for-item exchange. */
public record SettlementSaga(int schemaVersion, SettlementRequest request, SettlementPermit permit,
                             State state, long revision, String reason) {
    public SettlementSaga {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(permit, "permit");
        Objects.requireNonNull(state, "state");
        reason = reason == null ? "" : reason;
        if (schemaVersion != 1 || revision < 0 || reason.length() > 256
                || !permit.actor().equals(request.actor()) || !permit.buyer().equals(request.buyer())
                || !permit.seller().equals(request.seller()) || !permit.requestHash().equals(SettlementRequestCodec.hash(request)))
            throw new IllegalArgumentException("Invalid settlement saga snapshot");
    }

    public enum State { PREPARED, PAYMENT_APPLIED, COMPENSATION_REQUIRED, COMPLETED, COMPENSATED, FAILED, MANUAL_REVIEW }
    public boolean terminal() { return state == State.COMPLETED || state == State.COMPENSATED
            || state == State.FAILED || state == State.MANUAL_REVIEW; }
}
