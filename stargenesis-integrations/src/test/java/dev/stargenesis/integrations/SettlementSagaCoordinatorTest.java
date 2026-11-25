package dev.stargenesis.integrations;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static dev.stargenesis.integrations.SettlementCheckpoint.State.*;
import static org.junit.jupiter.api.Assertions.*;

class SettlementSagaCoordinatorTest {
    private static final UUID SAGA = UUID.fromString("00000000-0000-0000-0000-000000000031");
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000032");

    @Test
    void orderedTransfersCommitAndRepeatedBeginKeepsTheSamePlan() throws Exception {
        MemoryStore store = new MemoryStore();
        FakeGateway gateway = new FakeGateway();
        SettlementSagaCoordinator coordinator = new SettlementSagaCoordinator(store, gateway);
        SettlementPlan plan = plan();

        assertEquals(PREPARED, coordinator.begin(plan).state());
        assertEquals(PREPARED, coordinator.begin(plan).state());
        SettlementPlan conflict = new SettlementPlan(SAGA, ACTOR, "starcore",
                List.of(new SettlementTransfer("player:a", "market", "starcore:coin", 11)));
        assertThrows(IllegalArgumentException.class, () -> coordinator.begin(conflict));

        assertEquals(APPLYING, coordinator.advance(SAGA).state());
        assertEquals(1, coordinator.advance(SAGA).cursor());
        assertEquals(2, coordinator.advance(SAGA).cursor());
        assertEquals(COMMITTED, coordinator.advance(SAGA).state());
        assertEquals(List.of(plan.applyKey(0), plan.applyKey(1)), gateway.applied);
        assertEquals(0, gateway.compensated.size());
    }

    @Test
    void recoveryObservesAnAppliedEffectBeforeRetryingItsIdempotencyKey() throws Exception {
        MemoryStore store = new MemoryStore();
        FakeGateway gateway = new FakeGateway();
        gateway.throwAfterFirstApply = true;
        SettlementSagaCoordinator coordinator = new SettlementSagaCoordinator(store, gateway);
        coordinator.begin(plan());
        coordinator.advance(SAGA);

        assertEquals(APPLY_REVIEW, coordinator.advance(SAGA).state());
        assertEquals(1, gateway.applied.size());
        assertEquals(1, coordinator.reconcile(SAGA).cursor());
        assertEquals(1, gateway.applied.size());
        assertEquals(2, coordinator.advance(SAGA).cursor());
        assertEquals(COMMITTED, coordinator.advance(SAGA).state());
    }

    @Test
    void rejectionCompensatesEarlierTransfersInReverseOrder() throws Exception {
        MemoryStore store = new MemoryStore();
        FakeGateway gateway = new FakeGateway();
        gateway.rejectSecondApply = true;
        SettlementSagaCoordinator coordinator = new SettlementSagaCoordinator(store, gateway);
        coordinator.begin(plan());
        coordinator.advance(SAGA);
        assertEquals(1, coordinator.advance(SAGA).cursor());
        assertEquals(COMPENSATING, coordinator.advance(SAGA).state());
        assertEquals(ABORTED, coordinator.advance(SAGA).state());
        assertEquals(List.of(plan().compensationKey(0)), gateway.compensated);
    }

    @Test
    void unknownEffectIsNotRepeatedUntilTheAdapterCanReconcileIt() throws Exception {
        MemoryStore store = new MemoryStore();
        FakeGateway gateway = new FakeGateway();
        gateway.unknownFirstObservation = true;
        SettlementSagaCoordinator coordinator = new SettlementSagaCoordinator(store, gateway);
        coordinator.begin(plan());
        coordinator.advance(SAGA);

        SettlementCheckpoint review = coordinator.advance(SAGA);
        assertEquals(APPLY_REVIEW, review.state());
        assertTrue(review.diagnostic().contains("cannot confirm"));
        assertTrue(gateway.applied.isEmpty());
        assertEquals(APPLY_REVIEW, coordinator.reconcile(SAGA).state());
        gateway.unknownFirstObservation = false;
        assertEquals(ABORTED, coordinator.reconcile(SAGA).state());
        assertTrue(gateway.applied.isEmpty());
    }

    @Test
    void restartRecoversTheSameKeyWhenAnAppliedEffectCouldNotBeCheckpointed() throws Exception {
        MemoryStore store = new MemoryStore();
        FakeGateway gateway = new FakeGateway();
        SettlementSagaCoordinator coordinator = new SettlementSagaCoordinator(store, gateway);
        coordinator.begin(plan());
        SettlementCheckpoint applying = coordinator.advance(SAGA);

        store.failNextSave = true;
        assertThrows(IOException.class, () -> coordinator.advance(SAGA));
        assertEquals(applying, store.find(SAGA).orElseThrow());

        SettlementSagaCoordinator restarted = new SettlementSagaCoordinator(store, gateway);
        assertEquals(1, restarted.advance(SAGA).cursor());
        assertEquals(List.of(plan().applyKey(0)), gateway.applied);
    }

    @Test
    void restartObservesAnAppliedCompensationBeforeAdvancingItsCursor() throws Exception {
        MemoryStore store = new MemoryStore();
        FakeGateway gateway = new FakeGateway();
        gateway.rejectSecondApply = true;
        SettlementSagaCoordinator coordinator = new SettlementSagaCoordinator(store, gateway);
        coordinator.begin(plan());
        coordinator.advance(SAGA);
        coordinator.advance(SAGA);
        assertEquals(COMPENSATING, coordinator.advance(SAGA).state());
        SettlementCheckpoint compensating = store.find(SAGA).orElseThrow();

        store.failNextSave = true;
        assertThrows(IOException.class, () -> coordinator.advance(SAGA));
        assertEquals(compensating, store.find(SAGA).orElseThrow());
        assertEquals(List.of(plan().compensationKey(0)), gateway.compensated);

        SettlementSagaCoordinator restarted = new SettlementSagaCoordinator(store, gateway);
        assertEquals(ABORTED, restarted.advance(SAGA).state());
        assertEquals(List.of(plan().compensationKey(0)), gateway.compensated);
    }

    @Test
    void sagaCannotBeReplayedThroughAnotherProvider() throws Exception {
        MemoryStore store = new MemoryStore();
        FakeGateway gateway = new FakeGateway();
        SettlementSagaCoordinator coordinator = new SettlementSagaCoordinator(store, gateway);
        SettlementPlan otherProvider = new SettlementPlan(SAGA, ACTOR, "other", plan().transfers());

        assertThrows(IllegalArgumentException.class, () -> coordinator.begin(otherProvider));
        store.save(new SettlementCheckpoint(otherProvider, PREPARED, 0, 0, ""));
        assertThrows(IllegalArgumentException.class, () -> coordinator.advance(SAGA));
        assertThrows(IllegalArgumentException.class, () -> coordinator.reconcile(SAGA));
        assertTrue(gateway.applied.isEmpty());
    }

    private static SettlementPlan plan() {
        return new SettlementPlan(SAGA, ACTOR, "starcore", List.of(
                new SettlementTransfer("player:a", "escrow", "starcore:coin", 10),
                new SettlementTransfer("escrow", "market", "starcore:coin", 10)));
    }

    private static final class MemoryStore implements SettlementSagaStore {
        private final Map<UUID, SettlementCheckpoint> records = new HashMap<>();
        private boolean failNextSave;

        @Override public Optional<SettlementCheckpoint> find(UUID id) { return Optional.ofNullable(records.get(id)); }

        @Override public List<SettlementCheckpoint> loadAll() {
            return records.values().stream().sorted((a, b) -> a.plan().id().compareTo(b.plan().id())).toList();
        }

        @Override public void save(SettlementCheckpoint checkpoint) throws IOException {
            if (failNextSave) {
                failNextSave = false;
                throw new IOException("Injected checkpoint write failure");
            }
            SettlementCheckpoint previous = records.get(checkpoint.plan().id());
            if (previous != null && (!previous.plan().equals(checkpoint.plan())
                    || checkpoint.revision() != previous.revision() + 1))
                throw new IOException("Invalid saga revision");
            records.put(checkpoint.plan().id(), checkpoint);
        }
    }

    private static final class FakeGateway implements SettlementGateway {
        private final Map<String, Observation> observations = new HashMap<>();
        private final List<String> applied = new ArrayList<>();
        private final List<String> compensated = new ArrayList<>();
        private boolean throwAfterFirstApply;
        private boolean rejectSecondApply;
        private boolean unknownFirstObservation;

        @Override public String providerId() { return "starcore"; }

        @Override public Observation observe(String key, SettlementTransfer transfer, Operation operation) {
            if (unknownFirstObservation && applied.isEmpty()) return Observation.UNKNOWN;
            return observations.getOrDefault(key, Observation.NOT_APPLIED);
        }

        @Override public Result transfer(String key, SettlementTransfer transfer) throws Exception {
            if (rejectSecondApply && key.endsWith("apply:1")) return Result.REJECTED;
            if (observations.get(key) == Observation.APPLIED) return Result.APPLIED;
            observations.put(key, Observation.APPLIED);
            applied.add(key);
            if (throwAfterFirstApply && applied.size() == 1) throw new IOException("connection closed after response");
            return Result.APPLIED;
        }

        @Override public Result compensate(String key, SettlementTransfer transfer) {
            if (observations.get(key) == Observation.APPLIED) return Result.APPLIED;
            observations.put(key, Observation.APPLIED);
            compensated.add(key);
            return Result.APPLIED;
        }
    }
}
