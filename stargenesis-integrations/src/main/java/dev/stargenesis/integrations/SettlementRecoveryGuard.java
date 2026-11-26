package dev.stargenesis.integrations;

import java.util.Objects;
import java.util.UUID;

/** Rejects a withdrawn or replaced capability before consuming provider results or writing state. */
final class SettlementRecoveryGuard {
    private final IdempotentSettlementPort port;
    private final IdempotentSettlementPort.RecoveryDescriptor expected;

    SettlementRecoveryGuard(IdempotentSettlementPort port, IdempotentSettlementPort.RecoveryDescriptor expected) {
        this.port = Objects.requireNonNull(port, "port");
        this.expected = Objects.requireNonNull(expected, "expected");
    }

    static IdempotentSettlementPort.RecoveryDescriptor available(IdempotentSettlementPort port) {
        IdempotentSettlementPort.RecoveryDescriptor current;
        try {
            current = port.recoveryDescriptor();
        } catch (RuntimeException | LinkageError unavailable) {
            throw new IllegalStateException("Settlement provider recovery capability unavailable", unavailable);
        }
        if (current == null || !current.capability().supportsRecovery())
            throw new IllegalStateException("Settlement provider recovery capability unavailable");
        return current;
    }

    void requireCurrent() {
        if (!expected.equals(available(port)))
            throw new IllegalStateException("Settlement provider recovery evidence changed");
    }

    SettlementAuthorityBinding authorityBinding() {
        return expected.authorityBinding();
    }

    /** Never infer a legacy saga's authority from whichever provider happens to be online now. */
    void requireBinding(SettlementSagaRepository repository, UUID sagaId) {
        requireCurrent();
        var stored = repository.authorityBinding(sagaId)
                .orElseThrow(() -> new IllegalStateException("Settlement has no persisted asset-authority binding"));
        if (!expected.authorityBinding().equals(stored))
            throw new IllegalStateException("Settlement asset authority or idempotency history changed");
    }

    IdempotentSettlementPort.Observation inspect(SettlementOperation operation) {
        requireCurrent();
        IdempotentSettlementPort.Observation observation;
        try {
            observation = Objects.requireNonNull(port.inspect(operation), "settlement observation");
        } catch (RuntimeException | LinkageError unavailable) {
            observation = IdempotentSettlementPort.Observation.UNKNOWN;
        }
        // Deliberately outside the catch: an invalid capability cannot authorize a state change.
        requireCurrent();
        return observation;
    }

    IdempotentSettlementPort.Outcome apply(SettlementOperation operation) {
        requireCurrent();
        IdempotentSettlementPort.Outcome outcome;
        try {
            outcome = Objects.requireNonNull(port.apply(operation), "settlement outcome");
        } catch (RuntimeException | LinkageError uncertain) {
            outcome = IdempotentSettlementPort.Outcome.INDETERMINATE;
        }
        // A committed external effect may precede withdrawal; leave local state for later lookup.
        requireCurrent();
        return outcome;
    }
}
