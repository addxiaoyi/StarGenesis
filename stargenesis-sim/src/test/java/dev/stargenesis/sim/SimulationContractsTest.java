package dev.stargenesis.sim;

import dev.stargenesis.api.sim.AgentId;
import dev.stargenesis.api.sim.EventRecord;
import dev.stargenesis.api.sim.EvidenceId;
import dev.stargenesis.api.sim.ResourceLedger.TransferResult;
import dev.stargenesis.api.sim.StepInput;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class SimulationContractsTest {
    private static EvidenceId key(int sequence) { return new EvidenceId("test-world", 0, sequence); }
    private static ConservingResourceLedger ledger() {
        return new ConservingResourceLedger(Map.of("mine", Map.of("ore:iron", 100L), "base", Map.of()));
    }
    private static EventRecord event(int sequence) {
        return new EventRecord(key(sequence), "resource.transferred", Map.of("amount", "3"));
    }

    @Test void clockAdvancesFromRestoredLogicalTick() {
        var clock = new FixedSimulationClock(71, 50);
        assertEquals(71, clock.tick());
        assertEquals(72, clock.advance());
        assertEquals(50, clock.stepDurationMillis());
    }

    @Test void clockBoundsAndOverflowFailWithoutAdvancing() {
        assertThrows(IllegalArgumentException.class, () -> new FixedSimulationClock(-1, 50));
        assertThrows(IllegalArgumentException.class, () -> new FixedSimulationClock(0));
        assertThrows(IllegalArgumentException.class, () -> new FixedSimulationClock(60_001));
        var clock = new FixedSimulationClock(Long.MAX_VALUE, 50);
        assertThrows(ArithmeticException.class, clock::advance);
        assertEquals(Long.MAX_VALUE, clock.tick());
    }

    @Test void transferConservesAndRepeatedEvidenceDoesNotPayTwice() {
        var ledger = ledger();
        assertEquals(TransferResult.APPLIED, ledger.transfer(key(0), "mine", "base", "ore:iron", 30));
        assertEquals(TransferResult.ALREADY_APPLIED, ledger.transfer(key(0), "mine", "base", "ore:iron", 30));
        assertEquals(70, ledger.balance("mine", "ore:iron"));
        assertEquals(30, ledger.balance("base", "ore:iron"));
        assertEquals(0, ledger.balance("base", "ore:copper"));
    }

    @Test void sameEvidenceWithDifferentArgumentsFailsClosed() {
        var ledger = ledger();
        ledger.transfer(key(1), "mine", "base", "ore:iron", 1);
        var before = ledger.snapshot();
        assertThrows(IllegalArgumentException.class, () -> ledger.transfer(key(1), "mine", "base", "ore:iron", 2));
        assertThrows(IllegalArgumentException.class, () -> ledger.transfer(key(1), "mine", "other", "ore:iron", 1));
        assertEquals(before, ledger.snapshot());
    }

    @Test void insufficientFundsReservesFailureAndNewEvidenceMayRetry() {
        var ledger = ledger();
        assertEquals(TransferResult.INSUFFICIENT_FUNDS, ledger.transfer(key(0), "base", "mine", "ore:iron", 10));
        ledger.transfer(key(1), "mine", "base", "ore:iron", 20);
        assertEquals(TransferResult.INSUFFICIENT_FUNDS, ledger.transfer(key(0), "base", "mine", "ore:iron", 10));
        assertEquals(20, ledger.balance("base", "ore:iron"));
        assertEquals(TransferResult.APPLIED, ledger.transfer(key(2), "base", "mine", "ore:iron", 10));
    }

    @Test void overflowChangesNeitherBalancesNorHistory() {
        var ledger = new ConservingResourceLedger(Map.of("source", Map.of("ore", 5L), "target", Map.of("ore", Long.MAX_VALUE)));
        var before = ledger.checkpoint();
        assertThrows(ArithmeticException.class, () -> ledger.transfer(key(0), "source", "target", "ore", 1));
        assertEquals(before, ledger.checkpoint());
    }

    @Test void invalidRequestsCannotMintOrMutate() {
        var ledger = ledger();
        var before = ledger.checkpoint();
        for (long amount : new long[] {0, -1, Long.MIN_VALUE}) {
            assertThrows(IllegalArgumentException.class, () -> ledger.transfer(key(0), "mine", "base", "ore:iron", amount));
        }
        assertThrows(IllegalArgumentException.class, () -> ledger.transfer(key(0), "mine", "mine", "ore:iron", 1));
        assertThrows(IllegalArgumentException.class, () -> ledger.transfer(key(0), "../ bad", "base", "ore:iron", 1));
        assertEquals(before, ledger.checkpoint());
    }

    @Test void initializationAndSnapshotsAreDeeplyCopiedAndImmutable() {
        var resources = new HashMap<>(Map.of("ore", 10L));
        var initial = new HashMap<String, Map<String, Long>>(); initial.put("mine", resources);
        var ledger = new ConservingResourceLedger(initial);
        resources.put("ore", 900L); initial.clear();
        var snapshot = ledger.snapshot();
        assertEquals(10, snapshot.get("mine").get("ore"));
        assertThrows(UnsupportedOperationException.class, () -> snapshot.clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.get("mine").put("ore", 7L));
        ledger.transfer(key(1), "mine", "base", "ore", 1);
        assertEquals(10, snapshot.get("mine").get("ore"));
    }

    @Test void invalidInitialBalancesAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ConservingResourceLedger(Map.of("mine", Map.of("ore", -1L))));
        assertThrows(IllegalArgumentException.class, () -> new ConservingResourceLedger(Map.of("bad account", Map.of())));
        assertThrows(IllegalArgumentException.class, () -> new ConservingResourceLedger(Map.of(), 0));
        assertThrows(IllegalArgumentException.class, () -> new ConservingResourceLedger(Map.of(), 100_001));
    }

    @Test void evidenceBudgetStopsBeforeApplyingNewTransfer() {
        var ledger = new ConservingResourceLedger(Map.of("mine", Map.of("ore", 10L)), 1);
        ledger.transfer(key(0), "mine", "base", "ore", 1);
        assertThrows(IllegalStateException.class, () -> ledger.transfer(key(1), "mine", "base", "ore", 1));
        assertEquals(TransferResult.ALREADY_APPLIED, ledger.transfer(key(0), "mine", "base", "ore", 1));
        assertEquals(9, ledger.balance("mine", "ore"));
    }

    @Test void accountBudgetRejectsBeforeDebitOrEvidenceReservation() {
        var initial = new HashMap<String, Map<String, Long>>();
        initial.put("mine", Map.of("ore", 10L));
        for (int i = 1; i < 4096; i++) initial.put("empty-" + i, Map.of());
        var ledger = new ConservingResourceLedger(initial);
        var before = ledger.checkpoint();
        assertThrows(IllegalStateException.class, () -> ledger.transfer(key(0), "mine", "new-account", "ore", 1));
        assertEquals(before, ledger.checkpoint());
        assertEquals(TransferResult.APPLIED, ledger.transfer(key(0), "mine", "empty-1", "ore", 1));
        assertEquals(9, ledger.balance("mine", "ore"));
    }

    @Test void resourceEntryBudgetRejectsBeforeDebitOrEvidenceReservation() {
        var full = new HashMap<String, Long>();
        for (int i = 0; i < 16_381; i++) full.put("ore-" + i, 0L);
        full.put("shared", 0L);
        var ledger = new ConservingResourceLedger(Map.of("mine", Map.of("ore", 10L, "shared", 2L), "full", full));
        var before = ledger.checkpoint();
        assertThrows(IllegalStateException.class, () -> ledger.transfer(key(0), "mine", "full", "ore", 1));
        assertEquals(before, ledger.checkpoint());
        assertEquals(TransferResult.APPLIED, ledger.transfer(key(0), "mine", "full", "shared", 1));
        assertEquals(1, ledger.balance("full", "shared"));
    }

    @Test void checkpointRestoreRetainsSuccessfulAndFailedEvidence() {
        var original = ledger();
        original.transfer(key(0), "mine", "base", "ore:iron", 10);
        original.transfer(key(1), "mine", "base", "ore:iron", 1_000);
        var restored = ConservingResourceLedger.restore(original.checkpoint());
        assertEquals(original.checkpoint(), restored.checkpoint());
        assertEquals(TransferResult.ALREADY_APPLIED, restored.transfer(key(0), "mine", "base", "ore:iron", 10));
        assertEquals(TransferResult.INSUFFICIENT_FUNDS, restored.transfer(key(1), "mine", "base", "ore:iron", 1_000));
        assertThrows(IllegalArgumentException.class, () -> restored.transfer(key(0), "mine", "base", "ore:iron", 11));
    }

    @Test void duplicateCheckpointHistoryIsRejected() {
        var attempt = new ConservingResourceLedger.Attempt(key(0), "mine", "base", "ore", 1, TransferResult.APPLIED);
        assertThrows(IllegalArgumentException.class, () -> new ConservingResourceLedger.Checkpoint(Map.of(), List.of(attempt, attempt), 10));
    }

    @Test void checkpointRejectsSyntheticAlreadyAppliedOutcomesAndOverBudgetHistory() {
        assertThrows(IllegalArgumentException.class, () -> new ConservingResourceLedger.Attempt(
                key(0), "mine", "base", "ore", 1, TransferResult.ALREADY_APPLIED));
        var first = new ConservingResourceLedger.Attempt(key(0), "mine", "base", "ore", 1, TransferResult.APPLIED);
        var second = new ConservingResourceLedger.Attempt(key(1), "mine", "base", "ore", 1, TransferResult.INSUFFICIENT_FUNDS);
        assertThrows(IllegalArgumentException.class, () -> new ConservingResourceLedger.Checkpoint(Map.of(), List.of(first, second), 1));
    }

    @Test void thousandDeterministicTransfersConserveIntegerResource() {
        var first = ledger(); var second = ledger(); var random = new Random(42);
        for (int i = 0; i < 1000; i++) {
            String source = random.nextBoolean() ? "mine" : "base";
            String target = source.equals("mine") ? "base" : "mine";
            long amount = random.nextInt(16) + 1;
            assertEquals(first.transfer(key(i), source, target, "ore:iron", amount), second.transfer(key(i), source, target, "ore:iron", amount));
            assertEquals(first.checkpoint(), second.checkpoint());
            BigInteger total = first.snapshot().values().stream().flatMap(m -> m.values().stream())
                    .map(BigInteger::valueOf).reduce(BigInteger.ZERO, BigInteger::add);
            assertEquals(BigInteger.valueOf(100), total);
        }
    }

    @Test void outboxDeliversAtLeastOnceInCanonicalOrder() {
        var outbox = new MemoryEventOutbox();
        assertTrue(outbox.append(event(2))); assertTrue(outbox.append(event(0))); assertTrue(outbox.append(event(1)));
        assertEquals(List.of(event(0), event(1)), outbox.pending(2));
        assertEquals(List.of(event(0), event(1)), outbox.pending(2));
        assertThrows(UnsupportedOperationException.class, () -> outbox.pending(1).clear());
    }

    @Test void acknowledgedEventsRemainReservedAndDuplicateAckIsSafe() {
        var outbox = new MemoryEventOutbox(); outbox.append(event(0));
        outbox.acknowledge(key(0)); outbox.acknowledge(key(0));
        assertFalse(outbox.append(event(0))); assertTrue(outbox.pending(5).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> outbox.append(new EventRecord(key(0), "changed", Map.of())));
    }

    @Test void outboxOrdersAcrossWorldsAndTicksAndAckDoesNotFreeEvidenceCapacity() {
        var outbox = new MemoryEventOutbox(3);
        var laterWorld = new EventRecord(new EvidenceId("world-b", 0, 0), "event", Map.of());
        var laterTick = new EventRecord(new EvidenceId("world-a", 10, 0), "event", Map.of());
        var earlierTick = new EventRecord(new EvidenceId("world-a", 2, 9), "event", Map.of());
        outbox.append(laterWorld); outbox.append(laterTick); outbox.append(earlierTick);
        assertEquals(List.of(earlierTick, laterTick, laterWorld), outbox.pending(3));
        outbox.acknowledge(earlierTick.evidenceId());
        assertEquals(List.of(laterTick, laterWorld), outbox.pending(3));
        assertThrows(IllegalStateException.class, () -> outbox.append(event(99)));
        assertFalse(outbox.append(earlierTick));
    }

    @Test void outboxRejectsUnknownAcksAndInvalidBudgets() {
        var outbox = new MemoryEventOutbox(1); outbox.append(event(0));
        assertThrows(IllegalArgumentException.class, () -> outbox.acknowledge(key(1)));
        assertThrows(IllegalArgumentException.class, () -> outbox.pending(0));
        assertThrows(IllegalArgumentException.class, () -> outbox.pending(10_001));
        assertThrows(IllegalArgumentException.class, () -> new MemoryEventOutbox(0));
        assertThrows(IllegalStateException.class, () -> outbox.append(event(1)));
        assertEquals(List.of(event(0)), outbox.pending(1));
    }

    @Test void eventPayloadIsBoundedAndDefensivelyCopied() {
        var data = new HashMap<>(Map.of("amount", "3"));
        var event = new EventRecord(key(0), "transferred", data); data.put("amount", "7");
        assertEquals("3", event.data().get("amount"));
        assertThrows(UnsupportedOperationException.class, () -> event.data().clear());
        assertThrows(IllegalArgumentException.class, () -> new EventRecord(key(0), "invalid command", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> new EventRecord(key(0), "event", Map.of("a", "x".repeat(513))));
    }

    @Test void allMutableImplementationsRejectCrossThreadAccess() throws Exception {
        var clock = new FixedSimulationClock(50); var ledger = ledger(); var outbox = new MemoryEventOutbox();
        var readsAndWrites = List.<Runnable>of(clock::tick, clock::advance, clock::stepDurationMillis,
                () -> ledger.balance("mine", "ore:iron"), ledger::snapshot, ledger::checkpoint,
                () -> ledger.transfer(key(0), "mine", "base", "ore:iron", 1),
                () -> outbox.append(event(0)), () -> outbox.pending(1), () -> outbox.acknowledge(key(0)));
        try (var executor = Executors.newSingleThreadExecutor()) {
            for (Runnable operation : readsAndWrites) executor.submit(() -> assertThrows(IllegalStateException.class, operation::run)).get();
        }
        assertEquals(0, clock.tick()); assertEquals(100, ledger.balance("mine", "ore:iron"));
    }

    @Test void immutableCheckpointCanMoveToNewOwnerThread() throws Exception {
        var checkpoint = ledger().checkpoint();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var restoredSnapshot = executor.submit(() -> {
                var restored = ConservingResourceLedger.restore(checkpoint);
                restored.transfer(key(0), "mine", "base", "ore:iron", 1);
                return restored.snapshot();
            }).get();
            assertEquals(1, restoredSnapshot.get("base").get("ore:iron"));
        }
    }

    @Test void stepInputDefinesCanonicalOrderAndRejectsDuplicateSequence() {
        var a = new AgentId(new UUID(0, 1)); var b = new AgentId(new UUID(0, 2));
        var commands = new ArrayList<>(List.of(new StepInput.Command(b, 0, "move", Map.of()),
                new StepInput.Command(a, 2, "move", Map.of()), new StepInput.Command(a, 1, "move", Map.of())));
        var input = new StepInput(0, commands); commands.clear();
        assertEquals(List.of(a, a, b), input.commands().stream().map(StepInput.Command::agent).toList());
        assertEquals(List.of(1L, 2L, 0L), input.commands().stream().map(StepInput.Command::sequence).toList());
        assertThrows(IllegalArgumentException.class, () -> new StepInput(0, List.of(input.commands().get(0), input.commands().get(0))));
        assertThrows(UnsupportedOperationException.class, () -> input.commands().clear());
    }
}
