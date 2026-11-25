package dev.stargenesis.integrations;

import dev.stargenesis.integrations.SettlementCheckpoint.State;
import dev.stargenesis.integrations.SettlementGateway.Observation;
import dev.stargenesis.integrations.SettlementGateway.Operation;
import dev.stargenesis.integrations.SettlementGateway.Result;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

/** Advances one external effect at a time and persists each cursor change before returning. */
public final class SettlementSagaCoordinator {
    private final SettlementSagaStore store;
    private final SettlementGateway gateway;
    private final String providerId;

    public SettlementSagaCoordinator(SettlementSagaStore store, SettlementGateway gateway) {
        this.store = Objects.requireNonNull(store, "store");
        this.gateway = Objects.requireNonNull(gateway, "gateway");
        this.providerId = Objects.requireNonNull(gateway.providerId(), "provider ID");
    }

    public synchronized SettlementCheckpoint begin(SettlementPlan plan) throws IOException {
        requireProvider(Objects.requireNonNull(plan, "plan"));
        var existing = store.find(plan.id());
        if (existing.isPresent()) {
            if (!existing.get().plan().equals(plan)) throw new IllegalArgumentException("Saga ID was reused with another plan");
            return existing.get();
        }
        SettlementCheckpoint checkpoint = new SettlementCheckpoint(plan, State.PREPARED, 0, 0, "");
        store.save(checkpoint);
        return checkpoint;
    }

    public synchronized SettlementCheckpoint advance(UUID id) throws IOException {
        SettlementCheckpoint checkpoint = require(id);
        if (terminal(checkpoint.state()) || review(checkpoint.state())) return checkpoint;
        if (checkpoint.state() == State.PREPARED) return save(checkpoint, State.APPLYING, 0, "");
        if (checkpoint.state() == State.APPLYING) return applyNext(checkpoint);
        return compensateNext(checkpoint);
    }

    /** Reconciles an ambiguous adapter result without repeating an external side effect. */
    public synchronized SettlementCheckpoint reconcile(UUID id) throws IOException {
        SettlementCheckpoint checkpoint = require(id);
        if (!review(checkpoint.state())) return checkpoint;
        boolean applying = checkpoint.state() == State.APPLY_REVIEW;
        int transferIndex = applying ? checkpoint.cursor() : checkpoint.cursor() - 1;
        String key = applying ? checkpoint.plan().applyKey(transferIndex)
                : checkpoint.plan().compensationKey(transferIndex);
        Observation observation = observe(key, checkpoint.plan().transfers().get(transferIndex),
                applying ? Operation.APPLY : Operation.COMPENSATE);
        if (observation == Observation.UNKNOWN)
            return checkpoint;
        if (applying && observation == Observation.APPLIED)
            return applied(checkpoint, checkpoint.cursor());
        if (applying) {
            State next = checkpoint.cursor() == 0 ? State.ABORTED : State.COMPENSATING;
            return save(checkpoint, next, next == State.ABORTED ? 0 : checkpoint.cursor(), "");
        }
        if (observation == Observation.APPLIED) return compensated(checkpoint, checkpoint.cursor() - 1);
        return save(checkpoint, State.COMPENSATING, checkpoint.cursor(), "");
    }

    private SettlementCheckpoint applyNext(SettlementCheckpoint checkpoint) throws IOException {
        int index = checkpoint.cursor();
        if (index == checkpoint.plan().transfers().size())
            return save(checkpoint, State.COMMITTED, index, "");
        SettlementTransfer transfer = checkpoint.plan().transfers().get(index);
        String key = checkpoint.plan().applyKey(index);
        Observation observation = observe(key, transfer, Operation.APPLY);
        if (observation == Observation.APPLIED) return applied(checkpoint, index);
        if (observation == Observation.UNKNOWN)
            return save(checkpoint, State.APPLY_REVIEW, index, "Adapter cannot confirm " + key);
        Result result;
        try {
            result = Objects.requireNonNullElse(gateway.transfer(key, transfer), Result.UNKNOWN);
        } catch (Exception failure) {
            return save(checkpoint, State.APPLY_REVIEW, index, adapterFailure("Transfer", failure));
        }
        return switch (result) {
            case APPLIED -> applied(checkpoint, index);
            case RETRY -> checkpoint;
            case REJECTED -> startCompensation(checkpoint, "Transfer rejected at step " + index);
            case UNKNOWN -> save(checkpoint, State.APPLY_REVIEW, index, "Transfer result is unknown at step " + index);
        };
    }

    private SettlementCheckpoint compensateNext(SettlementCheckpoint checkpoint) throws IOException {
        int index = checkpoint.cursor() - 1;
        if (index < 0) return save(checkpoint, State.ABORTED, 0, "");
        SettlementTransfer transfer = checkpoint.plan().transfers().get(index);
        String key = checkpoint.plan().compensationKey(index);
        Observation observation = observe(key, transfer, Operation.COMPENSATE);
        if (observation == Observation.APPLIED) return compensated(checkpoint, index);
        if (observation == Observation.UNKNOWN)
            return save(checkpoint, State.COMPENSATION_REVIEW, checkpoint.cursor(), "Adapter cannot confirm " + key);
        Result result;
        try {
            result = Objects.requireNonNullElse(gateway.compensate(key, transfer), Result.UNKNOWN);
        } catch (Exception failure) {
            return save(checkpoint, State.COMPENSATION_REVIEW, checkpoint.cursor(), adapterFailure("Compensation", failure));
        }
        return switch (result) {
            case APPLIED -> compensated(checkpoint, index);
            case RETRY -> checkpoint;
            case REJECTED -> save(checkpoint, State.COMPENSATION_REVIEW, checkpoint.cursor(),
                    "Compensation rejected at step " + index);
            case UNKNOWN -> save(checkpoint, State.COMPENSATION_REVIEW, checkpoint.cursor(),
                    "Compensation result is unknown at step " + index);
        };
    }

    private Observation observe(String key, SettlementTransfer transfer, Operation operation) {
        try {
            return Objects.requireNonNullElse(gateway.observe(key, transfer, operation), Observation.UNKNOWN);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            return Observation.UNKNOWN;
        }
    }

    private SettlementCheckpoint startCompensation(SettlementCheckpoint checkpoint, String reason) throws IOException {
        if (checkpoint.cursor() == 0) return save(checkpoint, State.ABORTED, 0, reason);
        return save(checkpoint, State.COMPENSATING, checkpoint.cursor(), reason);
    }

    private SettlementCheckpoint applied(SettlementCheckpoint checkpoint, int index) throws IOException {
        int next = index + 1;
        return save(checkpoint, State.APPLYING, next, "");
    }

    private SettlementCheckpoint compensated(SettlementCheckpoint checkpoint, int index) throws IOException {
        int next = index;
        return save(checkpoint, next == 0 ? State.ABORTED : State.COMPENSATING, next, "");
    }

    private SettlementCheckpoint save(SettlementCheckpoint previous, State state, int cursor, String diagnostic)
            throws IOException {
        SettlementCheckpoint next = new SettlementCheckpoint(previous.plan(), state, cursor,
                Math.addExact(previous.revision(), 1), diagnostic);
        store.save(next);
        return next;
    }

    private SettlementCheckpoint require(UUID id) throws IOException {
        SettlementCheckpoint checkpoint = store.find(Objects.requireNonNull(id, "saga ID"))
                .orElseThrow(() -> new IllegalArgumentException("Unknown settlement saga"));
        requireProvider(checkpoint.plan());
        return checkpoint;
    }

    private void requireProvider(SettlementPlan plan) {
        if (!plan.providerId().equals(providerId))
            throw new IllegalArgumentException("Settlement plan belongs to another provider");
    }

    private static boolean terminal(State state) {
        return state == State.COMMITTED || state == State.ABORTED;
    }

    private static boolean review(State state) {
        return state == State.APPLY_REVIEW || state == State.COMPENSATION_REVIEW;
    }

    private static String adapterFailure(String operation, Exception failure) {
        if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
        return operation + " adapter did not confirm the result";
    }
}
