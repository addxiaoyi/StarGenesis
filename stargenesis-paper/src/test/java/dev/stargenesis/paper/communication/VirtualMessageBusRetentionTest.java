package dev.stargenesis.paper.communication;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class VirtualMessageBusRetentionTest {
    private static final UUID SENDER = new UUID(0, 91), RECIPIENT = new UUID(0, 92);
    private static final VirtualMessageBus.Endpoint ENDPOINT = new VirtualMessageBus.Endpoint("retention", 0, 0, 0, 16);
    private static final Set<VirtualMessageBus.ProtocolKey> INSTALLED = Set.of(RadioProtocol.key());

    @Test void idleParticipantChurnDoesNotRetainMailboxesOrChangeDurableState() {
        var bus = new VirtualMessageBus(0, 8);
        var before = bus.snapshot();
        for (int index = 0; index < 10_000; index++) {
            UUID player = new UUID(1, index);
            bus.register(player, ENDPOINT, INSTALLED);
            assertTrue(bus.poll(player, 1).isEmpty());
            assertEquals(0, bus.pendingCount(player));
            if (index % 2 == 0) bus.unregister(player);
            else assertTrue(bus.unregisterIfRegistered(player));
        }
        assertEquals(before, bus.snapshot());
        assertEquals(0, bus.participantCount());
        assertEquals(0, bus.retainedMailboxCount(), "Empty mailboxes must not accumulate across player identities");
    }

    @Test void lastAcknowledgementReleasesMailboxWithoutReleasingSequenceEvidence() {
        var bus = bus(8);
        assertTrue(bus.sendNow(SENDER, RadioProtocol.protocol(), RECIPIENT, 1, payload(1)).queued());
        bus.advance(1);
        var message = bus.poll(RECIPIENT, 1).getFirst();
        assertTrue(bus.acknowledge(RECIPIENT, message));
        assertFalse(bus.acknowledge(RECIPIENT, message));
        assertFalse(bus.sendNow(SENDER, RadioProtocol.protocol(), RECIPIENT, 1, payload(1)).queued());
        assertEquals(1, bus.retainedEvidenceCount());
        assertEquals(2, bus.events().size());
        assertTrue(bus.poll(RECIPIENT, 1).isEmpty());
        assertEquals(0, bus.pendingCount(RECIPIENT));
        assertEquals(0, bus.retainedMailboxCount(), "Acknowledged deliveries must not leave empty recipient state");
    }

    @Test void candidatesAndRestoredBusesRetainOfflineDeliveriesWithoutSharingMailboxLists() {
        var original = bus(8);
        original.sendNow(SENDER, RadioProtocol.protocol(), RECIPIENT, 1, payload(1));
        original.sendNow(SENDER, RadioProtocol.protocol(), RECIPIENT, 2, payload(2));
        original.advance(1);
        var message = original.poll(RECIPIENT, 1).getFirst();
        var candidate = original.copy();
        assertTrue(candidate.acknowledge(RECIPIENT, message));
        candidate.unregister(RECIPIENT);
        assertEquals(1, candidate.pendingCount(RECIPIENT));
        assertEquals(2, original.pendingCount(RECIPIENT));
        assertEquals(message, original.poll(RECIPIENT, 1).getFirst());
        assertEquals(1, original.retainedMailboxCount());

        var recovered = restore(candidate.snapshot());
        assertEquals(0, recovered.participantCount());
        assertEquals(1, recovered.retainedMailboxCount());
        assertThrows(IllegalArgumentException.class, () -> recovered.poll(RECIPIENT, 1));
        recovered.register(RECIPIENT, ENDPOINT, INSTALLED);
        assertFalse(recovered.acknowledge(RECIPIENT, message));
        assertTrue(recovered.acknowledge(RECIPIENT, recovered.poll(RECIPIENT, 1).getFirst()));
        assertEquals(0, recovered.retainedMailboxCount());
        assertEquals(1, candidate.retainedMailboxCount());
        assertEquals(2, original.pendingCount(RECIPIENT));
        assertEquals(0, restore(recovered.snapshot()).retainedMailboxCount());
    }

    @Test void offlineMailboxesAreIndependentOfOnlineParticipantAdmission() {
        var bus = new VirtualMessageBus(0, 128);
        bus.register(SENDER, ENDPOINT, INSTALLED);
        for (int index = 0; index < 65; index++) {
            UUID recipient = new UUID(2, index);
            bus.register(recipient, ENDPOINT, INSTALLED);
            bus.sendNow(SENDER, RadioProtocol.protocol(), recipient, index, payload(index));
            bus.unregister(recipient);
        }
        assertEquals(1, bus.participantCount());
        assertEquals(65, bus.retainedMailboxCount());
        for (int index = 0; index < 63; index++) bus.register(new UUID(3, index), ENDPOINT, INSTALLED);
        var before = bus.snapshot();
        assertThrows(IllegalStateException.class, () -> bus.register(RECIPIENT, ENDPOINT, INSTALLED));
        assertEquals(before, bus.snapshot());
        assertEquals(65, bus.retainedMailboxCount());
        bus.unregister(new UUID(3, 0));
        UUID offline = new UUID(2, 0);
        bus.register(offline, ENDPOINT, INSTALLED);
        bus.advance(1);
        assertEquals(0, bus.poll(offline, 1).getFirst().sequence());
        assertTrue(bus.acknowledge(offline, bus.poll(offline, 1).getFirst()));
        assertEquals(64, bus.retainedMailboxCount());
        assertEquals(64, bus.participantCount());
    }

    @Test void mailboxOverflowAndForgedAcknowledgementPreserveCapacityAndRetry() {
        var bus = bus(128);
        for (int sequence = 0; sequence < 64; sequence++)
            bus.sendNow(SENDER, RadioProtocol.protocol(), RECIPIENT, sequence, payload(sequence));
        var before = bus.snapshot();
        assertThrows(IllegalStateException.class,
                () -> bus.sendNow(SENDER, RadioProtocol.protocol(), RECIPIENT, 64, payload(64)));
        assertEquals(before, bus.snapshot());
        assertEquals(1, bus.retainedMailboxCount());
        bus.advance(1);
        var first = bus.poll(RECIPIENT, 1).getFirst();
        assertThrows(IllegalArgumentException.class, () -> bus.acknowledge(SENDER, first));
        assertEquals(64, bus.pendingCount(RECIPIENT));
        assertTrue(bus.acknowledge(RECIPIENT, first));
        assertTrue(bus.sendNow(SENDER, RadioProtocol.protocol(), RECIPIENT, 64, payload(64)).queued());
        bus.advance(2);
        for (var message : bus.poll(RECIPIENT, 64)) assertTrue(bus.acknowledge(RECIPIENT, message));
        assertEquals(0, bus.retainedMailboxCount());
        assertTrue(bus.sendNow(SENDER, RadioProtocol.protocol(), RECIPIENT, 65, payload(65)).queued());
        assertEquals(1, bus.retainedMailboxCount());
    }

    @Test void rejoiningWithAnotherProtocolRetainsUnacknowledgedEvidenceWithoutExposingIt() {
        var bus = bus(8);
        bus.sendNow(SENDER, RadioProtocol.protocol(), RECIPIENT, 1, payload(1));
        bus.advance(1);
        var message = bus.poll(RECIPIENT, 1).getFirst();
        bus.unregisterIfRegistered(RECIPIENT);
        bus.register(RECIPIENT, ENDPOINT, Set.of(ServiceContractDefinition.key()));
        assertTrue(bus.poll(RECIPIENT, 1).isEmpty());
        assertThrows(SecurityException.class, () -> bus.acknowledge(RECIPIENT, message));
        assertEquals(1, bus.retainedMailboxCount());
        assertEquals(1, bus.pendingCount(RECIPIENT));
        bus.register(RECIPIENT, ENDPOINT, INSTALLED);
        assertEquals(message, bus.poll(RECIPIENT, 1).getFirst());
        assertTrue(bus.acknowledge(RECIPIENT, message));
        assertEquals(0, bus.retainedMailboxCount());
    }

    private VirtualMessageBus restore(VirtualMessageBus.Snapshot snapshot) {
        var bus = new VirtualMessageBus(snapshot.tick(), 128);
        bus.restore(snapshot, Map.of(RadioProtocol.key(), RadioProtocol.protocol()));
        return bus;
    }

    private VirtualMessageBus bus(int budget) {
        var bus = new VirtualMessageBus(0, budget);
        bus.register(SENDER, ENDPOINT, INSTALLED);
        bus.register(RECIPIENT, ENDPOINT, INSTALLED);
        return bus;
    }

    private Map<String, String> payload(int sequence) {
        return Map.of("sequence", Integer.toString(sequence), "channel", "test", "value", "ready");
    }
}
