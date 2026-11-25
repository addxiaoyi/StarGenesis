package dev.stargenesis.integrations;

import java.util.Objects;
import java.util.UUID;

/** Fail-closed currency/item saga. It never repeats an external operation without querying its key. */
public final class SettlementSagaCoordinator {
    private final SettlementAuthorizer authorizer;
    private final IdempotentSettlementPort port;
    private final SettlementSagaRepository repository;

    public SettlementSagaCoordinator(SettlementAuthorizer authorizer, IdempotentSettlementPort port,
                                     SettlementSagaRepository repository) {
        this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
        this.port = Objects.requireNonNull(port, "port");
        this.repository = Objects.requireNonNull(repository, "repository");
        IdempotentSettlementPort.Capability capability = Objects.requireNonNull(port.capability(), "port capability");
        if (!capability.supportsRecovery())
            throw new IllegalArgumentException("Settlement port must provide durable idempotency and lookup evidence");
    }

    public SettlementSaga begin(SettlementRequest request, long logicalTick) {
        Objects.requireNonNull(request, "request");
        if (logicalTick < 0) throw new IllegalArgumentException("Negative settlement logical tick");
        return repository.withExclusiveSaga(request.sagaId(), () -> {
            var existing = repository.load(request.sagaId());
            if (existing.isPresent()) {
                if (!existing.get().request().equals(request)) throw new IllegalArgumentException("Saga ID reused with a different request");
                return existing.get();
            }
            if (logicalTick >= request.expiresAtTick())
                throw new IllegalArgumentException("Settlement authorization deadline has passed");
            SettlementPermit permit = Objects.requireNonNull(authorizer.authorize(request, logicalTick), "permit");
            if (!authorizer.verify(request, permit, logicalTick))
                throw new SecurityException("Settlement permit did not authorize the request");
            return repository.create(new SettlementSaga(1, request, permit, SettlementSaga.State.PREPARED, 0, ""));
        });
    }

    public SettlementSaga advance(UUID sagaId, long logicalTick) {
        if (logicalTick < 0) throw new IllegalArgumentException("Negative settlement logical tick");
        return repository.withExclusiveSaga(Objects.requireNonNull(sagaId, "sagaId"), () -> {
            SettlementSaga saga = repository.load(sagaId)
                    .orElseThrow(() -> new IllegalArgumentException("Settlement saga does not exist"));
            if (saga.terminal()) return saga;
            if (saga.state() == SettlementSaga.State.PREPARED) return applyPayment(saga, logicalTick);
            if (saga.state() == SettlementSaga.State.PAYMENT_APPLIED) return applyDelivery(saga, logicalTick);
            if (saga.state() == SettlementSaga.State.COMPENSATION_REQUIRED) return compensate(saga);
            throw new IllegalStateException("Unsupported settlement saga state " + saga.state());
        });
    }

    private SettlementSaga applyPayment(SettlementSaga saga, long logicalTick) {
        SettlementOperation payment = SettlementOperation.forPhase(saga.request(), SettlementOperation.Phase.PAYMENT);
        return execute(saga, payment, logicalTick, SettlementSaga.State.PAYMENT_APPLIED,
                SettlementSaga.State.FAILED, "PAYMENT_REJECTED", "AUTHORIZATION_EXPIRED");
    }

    private SettlementSaga applyDelivery(SettlementSaga saga, long logicalTick) {
        SettlementOperation delivery = SettlementOperation.forPhase(saga.request(), SettlementOperation.Phase.DELIVERY);
        return execute(saga, delivery, logicalTick, SettlementSaga.State.COMPLETED,
                SettlementSaga.State.COMPENSATION_REQUIRED, "DELIVERY_REJECTED", "AUTHORIZATION_EXPIRED_AFTER_PAYMENT");
    }

    private SettlementSaga compensate(SettlementSaga saga) {
        SettlementOperation refund = SettlementOperation.forPhase(saga.request(), SettlementOperation.Phase.COMPENSATION);
        IdempotentSettlementPort.Observation observation = inspect(refund);
        if (observation == IdempotentSettlementPort.Observation.APPLIED)
            return replace(saga, SettlementSaga.State.COMPENSATED, "CURRENCY_REFUNDED");
        if (observation == IdempotentSettlementPort.Observation.UNKNOWN)
            return replace(saga, SettlementSaga.State.MANUAL_REVIEW, "COMPENSATION_OUTCOME_UNKNOWN");
        return switch (safeApply(refund)) {
            case APPLIED -> replace(saga, SettlementSaga.State.COMPENSATED, "CURRENCY_REFUNDED");
            case NO_EFFECT -> replace(saga, SettlementSaga.State.MANUAL_REVIEW, "COMPENSATION_REJECTED");
            case INDETERMINATE -> saga;
        };
    }

    private SettlementSaga execute(SettlementSaga saga, SettlementOperation operation, long logicalTick,
                                   SettlementSaga.State applied, SettlementSaga.State noEffect,
                                   String rejectedReason, String expiredReason) {
        IdempotentSettlementPort.Observation observation = inspect(operation);
        if (observation == IdempotentSettlementPort.Observation.APPLIED)
            return replace(saga, applied, "RECOVERED_BY_IDEMPOTENCY_LOOKUP");
        if (observation == IdempotentSettlementPort.Observation.UNKNOWN)
            return replace(saga, SettlementSaga.State.MANUAL_REVIEW, "EXTERNAL_OUTCOME_UNKNOWN");
        // Expiry prevents new side effects, but cannot erase an operation applied before a crash.
        if (logicalTick >= saga.request().expiresAtTick()
                || !authorizer.verify(saga.request(), saga.permit(), logicalTick))
            return replace(saga, noEffect, expiredReason);
        return switch (safeApply(operation)) {
            case APPLIED -> replace(saga, applied, "");
            case NO_EFFECT -> replace(saga, noEffect, rejectedReason);
            case INDETERMINATE -> saga;
        };
    }

    private IdempotentSettlementPort.Observation inspect(SettlementOperation operation) {
        try {
            return Objects.requireNonNull(port.inspect(operation), "settlement observation");
        } catch (RuntimeException | LinkageError unavailable) {
            return IdempotentSettlementPort.Observation.UNKNOWN;
        }
    }

    private IdempotentSettlementPort.Outcome safeApply(SettlementOperation operation) {
        try {
            return Objects.requireNonNull(port.apply(operation), "settlement outcome");
        } catch (RuntimeException | LinkageError uncertain) {
            return IdempotentSettlementPort.Outcome.INDETERMINATE;
        }
    }

    private SettlementSaga replace(SettlementSaga previous, SettlementSaga.State state, String reason) {
        SettlementSaga updated = new SettlementSaga(previous.schemaVersion(), previous.request(), previous.permit(),
                state, Math.addExact(previous.revision(), 1), reason);
        return repository.replace(updated, previous.revision());
    }

}
