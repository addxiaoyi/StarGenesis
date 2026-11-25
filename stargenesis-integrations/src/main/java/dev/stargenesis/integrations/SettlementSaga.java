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

    public void requireInitial() {
        if (state != State.PREPARED || revision != 0 || !reason.isEmpty())
            throw new IllegalArgumentException("New settlement sagas must start prepared at revision zero");
    }

    /** Repositories must preserve the original operation identity and permit on every replacement. */
    public void requireSuccessorOf(SettlementSaga previous) {
        Objects.requireNonNull(previous, "previous");
        if (schemaVersion != previous.schemaVersion || !request.equals(previous.request)
                || !permit.equals(previous.permit) || revision != Math.addExact(previous.revision, 1))
            throw new IllegalArgumentException("Settlement replacement changes its immutable request, permit or revision");
        boolean allowed = switch (previous.state) {
            case PREPARED -> state == State.PAYMENT_APPLIED || state == State.FAILED || state == State.MANUAL_REVIEW;
            case PAYMENT_APPLIED -> state == State.COMPLETED || state == State.COMPENSATION_REQUIRED || state == State.MANUAL_REVIEW;
            case COMPENSATION_REQUIRED -> state == State.COMPENSATED || state == State.MANUAL_REVIEW;
            case COMPLETED, COMPENSATED, FAILED, MANUAL_REVIEW -> false;
        };
        if (!allowed) throw new IllegalArgumentException("Invalid settlement transition from " + previous.state + " to " + state);
    }
}
