package dev.stargenesis.paper.communication;

import dev.stargenesis.design.ProtocolMessageCodec;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ContractRecoveryTest {
    private static final UUID ALICE = new UUID(0, 81), BOB = new UUID(0, 82);

    @Test void restoredStateMustFollowTheActualReceivedMessagesAndEvents() {
        var runtime = runtime();
        runtime.receive(message(ALICE, BOB, 0, 1, "offer", "order", 3));
        var snapshot = runtime.snapshot();
        var offered = snapshot.states().getFirst();
        var forged = new ContractRuntime.ContractState(offered.id(), ALICE, BOB, 3, 3,
                offered.createdAt(), offered.expiresAt(), ContractRuntime.State.COMPLETED);
        var changed = new ContractRuntime.Snapshot(0, List.of(forged), snapshot.received(), snapshot.events());
        assertThrows(IllegalArgumentException.class, () -> restore(changed));
    }

    @Test void validMixedHistoryAndSimultaneousExpirationsResumeExactly() {
        var runtime = runtime();
        runtime.receive(message(ALICE, BOB, 0, 1, "offer", "order_a", 3));
        runtime.receive(message(ALICE, BOB, 0, 2, "offer", "order_b", 2));
        runtime.receive(message(ALICE, BOB, 0, 3, "offer", "order_c", 1));
        runtime.advance(1);
        runtime.receive(message(BOB, ALICE, 1, 1, "accept", "order_a", 3));
        runtime.receive(message(BOB, ALICE, 1, 2, "fulfill", "order_a", 1));
        var restored = restore(runtime.snapshot());
        assertEquals(runtime.snapshot(), restored.snapshot());
        for (int tick = 2; tick <= 201; tick++) assertEquals(runtime.advance(tick), restored.advance(tick));
        assertEquals(3, runtime.events().stream().filter(event -> event.type().equals("contract.expire")).count());
        assertEquals(runtime.snapshot(), restore(runtime.snapshot()).snapshot());
    }

    @Test void missingDuplicatedReorderedOrForgedHistoryIsRejected() {
        var runtime = runtime();
        runtime.receive(message(ALICE, BOB, 0, 1, "offer", "order", 3));
        runtime.advance(1);
        runtime.receive(message(BOB, ALICE, 1, 1, "accept", "order", 3));
        runtime.advance(2);
        runtime.receive(message(BOB, ALICE, 2, 2, "fulfill", "order", 3));
        var saved = runtime.snapshot();
        var duplicated = new ArrayList<>(saved.events());
        duplicated.add(saved.events().getLast());
        var reordered = new ArrayList<>(saved.events());
        java.util.Collections.reverse(reordered);
        for (var events : List.of(saved.events().subList(1, 3), saved.events().subList(0, 2), duplicated, reordered))
            assertThrows(IllegalArgumentException.class,
                    () -> restore(new ContractRuntime.Snapshot(2, saved.states(), saved.received(), events)));
        assertThrows(IllegalArgumentException.class, () -> restore(new ContractRuntime.Snapshot(2,
                saved.states(), saved.received().subList(0, 2), saved.events())));
        var extra = new ArrayList<>(saved.received());
        extra.add(message(ALICE, BOB, 2, 2, "offer", "unaccounted", 1));
        assertThrows(IllegalArgumentException.class, () -> restore(new ContractRuntime.Snapshot(2,
                saved.states(), extra, saved.events())));
        var last = saved.events().getLast();
        var forged = new ContractRuntime.ContractEvent(last.tick(), last.type(), last.contractId(), last.actor(),
                last.peer(), 2, last.state(), last.sequence());
        assertThrows(IllegalArgumentException.class, () -> restore(new ContractRuntime.Snapshot(2, saved.states(),
                saved.received(), List.of(saved.events().get(0), saved.events().get(1), forged))));
    }

    @Test void expirationEvidenceAndDeadlineMustMatchAndHugeIdleClockRestoresWithoutTickIteration() {
        var runtime = runtime();
        runtime.receive(message(ALICE, BOB, 0, 1, "offer", "order", 1));
        for (int tick = 1; tick <= 201; tick++) runtime.advance(tick);
        var saved = runtime.snapshot();
        assertEquals(saved, restore(saved).snapshot());
        assertThrows(IllegalArgumentException.class, () -> restore(new ContractRuntime.Snapshot(201,
                saved.states(), saved.received(), saved.events().subList(0, 1))));
        var expiration = saved.events().getLast();
        var early = new ContractRuntime.ContractEvent(200, expiration.type(), expiration.contractId(),
                expiration.actor(), expiration.peer(), 0, expiration.state(), -1);
        assertThrows(IllegalArgumentException.class, () -> restore(new ContractRuntime.Snapshot(201,
                saved.states(), saved.received(), List.of(saved.events().getFirst(), early))));
        var idle = new ContractRuntime.Snapshot(Long.MAX_VALUE - 1, saved.states(), saved.received(), saved.events());
        assertEquals(idle, restore(idle).snapshot());
    }

    private static ContractRuntime runtime() {
        return new ContractRuntime(ServiceContractDefinition.contract(), ServiceContractDefinition.protocol(), 0, 256);
    }

    private static ContractRuntime restore(ContractRuntime.Snapshot snapshot) {
        return ContractRuntime.restore(ServiceContractDefinition.contract(), ServiceContractDefinition.protocol(), 256, snapshot);
    }

    private static ProtocolMessageCodec.Message message(UUID sender, UUID recipient, long tick, int sequence,
                                                        String action, String id, int units) {
        var protocol = ServiceContractDefinition.protocol();
        var codec = new ProtocolMessageCodec();
        return codec.decode(protocol, codec.encode(protocol, sender, recipient, tick, sequence,
                Map.of("contract", id, "action", action, "units", Integer.toString(units))));
    }
}
