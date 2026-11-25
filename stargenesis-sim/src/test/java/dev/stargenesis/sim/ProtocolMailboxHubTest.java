package dev.stargenesis.sim;

import dev.stargenesis.api.sim.AgentId;
import org.junit.jupiter.api.Test;
import java.util.Set;
import java.util.UUID;
import java.util.Map;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ProtocolMailboxHubTest {
    private static final AgentId A = id(1);
    private static final AgentId B = id(2);
    private static final AgentId C = id(3);

    @Test void authorizedMessagesArePrivateAndDeliveredInDeterministicSenderOrder() {
        ProtocolMailboxHub hub = hub(Set.of(A, B, C));
        hub.send(C, B, "genesis:trade", "offer-c", 8);
        hub.send(A, B, "genesis:trade", "offer-a", 8);
        var deliveries = hub.drain(B);
        assertEquals(2, deliveries.size());
        assertEquals(A, deliveries.get(0).message().sender());
        assertEquals(C, deliveries.get(1).message().sender());
        assertTrue(hub.drain(A).isEmpty());
    }

    @Test void unauthorizedAndOverCapacityMessagesAreRejectedWithoutMailboxMutation() {
        ProtocolMailboxHub hub = hub(Set.of(A, B));
        ProtocolMailboxHub unauthorized = new ProtocolMailboxHub(Set.of(A, B));
        assertEquals(ProtocolDelivery.Status.REJECTED, unauthorized.send(A, B, "genesis:trade", "x", 2).status());
        for (int index = 0; index < ProtocolMailboxHub.MAX_PENDING_PER_AGENT; index++)
            {
                if (index > 0 && index % ProtocolMailboxHub.MAX_MESSAGES_PER_STEP == 0) hub.advance();
                assertEquals(ProtocolDelivery.Status.QUEUED, hub.send(A, B, "genesis:trade", "x", 2048).status());
            }
        hub.advance();
        assertEquals("MAILBOX_FULL", hub.send(A, B, "genesis:trade", "x", 4).reason());
        assertEquals(ProtocolMailboxHub.MAX_PENDING_PER_AGENT, hub.drain(B).size());
    }

    @Test void stepBudgetAndMessageLifetimeAreBounded() {
        ProtocolMailboxHub hub = hub(Set.of(A, B));
        for (int index = 0; index < ProtocolMailboxHub.MAX_MESSAGES_PER_STEP; index++)
            hub.send(A, B, "genesis:trade", "x", 1);
        assertThrows(IllegalStateException.class, () -> hub.send(A, B, "genesis:trade", "x", 1));
        var expired = hub.advance();
        assertEquals(ProtocolMailboxHub.MAX_MESSAGES_PER_STEP, expired.size());
        assertTrue(expired.stream().allMatch(delivery -> delivery.status() == ProtocolDelivery.Status.EXPIRED));
        assertEquals(ProtocolDelivery.Status.QUEUED, hub.send(A, B, "genesis:trade", "replacement", 1).status());
        assertThrows(IllegalArgumentException.class, () -> hub.send(A, B, "genesis:trade", "x", 0));
    }

    @Test void malformedMessagesAndUnknownParticipantsAreRejected() {
        ProtocolMailboxHub hub = hub(Set.of(A, B));
        assertThrows(IllegalArgumentException.class, () -> hub.send(A, B, "../secret", "x", 1));
        assertThrows(IllegalArgumentException.class, () -> hub.send(A, B, "genesis:trade", " ", 1));
        assertThrows(IllegalArgumentException.class, () -> hub.send(A, C, "genesis:trade", "x", 1));
        assertThrows(IllegalArgumentException.class, () -> hub.send(A, B, "genesis:trade", "é".repeat(2049), 1));
        assertThrows(IllegalArgumentException.class, () -> new ProtocolMailboxHub(Set.of()));
    }

    @Test void simulationStateRejectsCrossThreadAccess() throws InterruptedException {
        ProtocolMailboxHub hub = hub(Set.of(A, B));
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        Thread other = new Thread(() -> {
            try { hub.drain(B); } catch (Throwable thrown) { failure.set(thrown); }
        });
        other.start();
        other.join();
        assertInstanceOf(IllegalStateException.class, failure.get());
    }

    @Test void totalMailboxBudgetBoundsMemoryAcrossAllParticipants() {
        java.util.Set<AgentId> participants = new java.util.HashSet<>();
        participants.add(A);
        for (int index = 1; index <= 32; index++) participants.add(id(index + 10));
        ProtocolMailboxHub hub = new ProtocolMailboxHub(participants, participants.stream().collect(
                java.util.stream.Collectors.toMap(agent -> agent, ignored -> Set.of("genesis:trade"))));
        AgentId sender = A;
        AgentId recipient = id(11);
        int sent = 0;
        while (sent < ProtocolMailboxHub.MAX_PENDING_TOTAL) {
            if (sent > 0 && sent % ProtocolMailboxHub.MAX_MESSAGES_PER_STEP == 0) hub.advance();
            if (sent > 0 && sent % ProtocolMailboxHub.MAX_PENDING_PER_AGENT == 0) {
                int nextRecipient = 11 + sent / ProtocolMailboxHub.MAX_PENDING_PER_AGENT;
                recipient = id(nextRecipient);
            }
            assertEquals(ProtocolDelivery.Status.QUEUED, hub.send(sender, recipient, "genesis:trade", "x",
                    ProtocolMailboxHub.MAX_TTL_STEPS).status());
            sent++;
        }
        hub.advance();
        assertEquals("MAILBOX_FULL", hub.send(sender, recipient, "genesis:trade", "x", 1).reason());
    }

    @Test void dynamicParticipantsCanPersistAndAcknowledgeQueuedMessages() {
        ProtocolMailboxHub hub = new ProtocolMailboxHub();
        hub.register(A, Set.of("genesis:trade"));
        hub.register(B, Set.of("genesis:trade"));
        ProtocolMessage message = hub.send(A, B, "genesis:trade", "offer", 10).message();

        assertEquals(List.of(message), hub.pendingMessages(B));
        assertThrows(IllegalArgumentException.class, () -> hub.acknowledgePersisted(B,
                List.of(new ProtocolMessage(1, 0, 5, A, B, "genesis:trade", "unknown"))));
        hub.acknowledgePersisted(B, List.of(message));
        assertTrue(hub.pendingMessages(B).isEmpty());
        assertFalse(hub.retireIfIdle(A));
        assertTrue(hub.retireIfIdle(B));
        hub.advance();
        assertTrue(hub.retireIfIdle(A));
        assertTrue(hub.participants().isEmpty());
    }

    @Test void dynamicRegistrationPreservesGrantedProtocolPermissions() {
        ProtocolMailboxHub hub = new ProtocolMailboxHub();
        hub.register(A, Set.of("genesis:trade"));
        hub.register(A, Set.of());
        hub.register(B, Set.of("genesis:trade"));
        assertEquals(ProtocolDelivery.Status.QUEUED,
                hub.send(A, B, "genesis:trade", "offer", 2).status());
    }

    private static ProtocolMailboxHub hub(Set<AgentId> agents) {
        return new ProtocolMailboxHub(agents, agents.stream().collect(java.util.stream.Collectors.toMap(
                agent -> agent, ignored -> Set.of("genesis:trade"))));
    }

    private static AgentId id(long leastBits) { return new AgentId(new UUID(0, leastBits)); }
}
