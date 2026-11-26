package dev.stargenesis.paper.communication;

import dev.stargenesis.api.design.DesignId;
import dev.stargenesis.design.DesignCodec;
import dev.stargenesis.design.Protocol;
import dev.stargenesis.design.ProtocolMessageCodec;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class VirtualMessageBusTest {
    private final UUID sender = new UUID(0, 51);
    private final UUID recipient = new UUID(0, 52);
    private final ProtocolMessageCodec codec = new ProtocolMessageCodec();

    @Test void messagesWaitOneLogicalTickAndRemainUntilIdempotentAcknowledgement() throws Exception {
        Protocol protocol = protocol();
        VirtualMessageBus.ProtocolKey key = key(protocol);
        VirtualMessageBus bus = new VirtualMessageBus(4, 8);
        bus.register(sender, endpoint("world", 1, 2, 3, 12), Set.of(key));
        bus.register(recipient, endpoint("world", 5, 2, 3, 12), Set.of(key));
        String encoded = codec.encode(protocol, sender, recipient, 4, 0, Map.of("sequence", "9"));
        ProtocolMessageCodec.Message message = codec.decode(protocol, encoded);

        assertTrue(bus.send(sender, protocol, encoded));
        assertFalse(bus.send(sender, protocol, encoded));
        assertEquals(1, bus.events().size());
        assertTrue(bus.poll(recipient, 4).isEmpty());
        bus.advance(5);
        String delayedRetry = codec.encode(protocol, sender, recipient, 5, 0, Map.of("sequence", "9"));
        assertFalse(bus.send(sender, protocol, delayedRetry));
        String conflictingRetry = codec.encode(protocol, sender, recipient, 5, 0, Map.of("sequence", "10"));
        assertThrows(IllegalArgumentException.class, () -> bus.send(sender, protocol, conflictingRetry));
        assertEquals(List.of(message), bus.poll(recipient, 4));
        assertEquals(List.of(message), bus.poll(recipient, 4));
        assertTrue(bus.acknowledge(recipient, message));
        assertFalse(bus.acknowledge(recipient, message));
        assertTrue(bus.poll(recipient, 4).isEmpty());
        assertEquals(1, bus.retainedEvidenceCount());
        assertEquals(List.of("radio.sent", "radio.acknowledged"), bus.events().stream().map(VirtualMessageBus.RadioEvent::type).toList());
        assertThrows(UnsupportedOperationException.class, () -> bus.events().clear());
    }

    @Test void senderRecipientMustInstallThePinnedProtocolAndBeInRadioRange() throws Exception {
        Protocol protocol = protocol();
        VirtualMessageBus.ProtocolKey key = key(protocol);
        String encoded = codec.encode(protocol, sender, recipient, 0, 0, Map.of("sequence", "1"));

        VirtualMessageBus missingProtocol = new VirtualMessageBus(0, 8);
        missingProtocol.register(sender, endpoint("world", 0, 0, 0, 10), Set.of(key));
        missingProtocol.register(recipient, endpoint("world", 1, 0, 0, 10), Set.of(new VirtualMessageBus.ProtocolKey(
                new DesignId("genesis:other"), "a".repeat(64))));
        assertThrows(SecurityException.class, () -> missingProtocol.send(sender, protocol, encoded));

        VirtualMessageBus outOfRange = new VirtualMessageBus(0, 8);
        outOfRange.register(sender, endpoint("world", 0, 0, 0, 2), Set.of(key));
        outOfRange.register(recipient, endpoint("world", 3, 0, 0, 2), Set.of(key));
        assertThrows(IllegalArgumentException.class, () -> outOfRange.send(sender, protocol, encoded));

        VirtualMessageBus otherWorld = new VirtualMessageBus(0, 8);
        otherWorld.register(sender, endpoint("world_a", 0, 0, 0, 10), Set.of(key));
        otherWorld.register(recipient, endpoint("world_b", 0, 0, 0, 10), Set.of(key));
        assertThrows(IllegalArgumentException.class, () -> otherWorld.send(sender, protocol, encoded));
    }

    @Test void tickMessageAndMailboxBudgetsFailBeforeReservingEvidence() throws Exception {
        Protocol protocol = protocol();
        VirtualMessageBus.ProtocolKey key = key(protocol);
        VirtualMessageBus bus = new VirtualMessageBus(2, 1);
        bus.register(sender, endpoint("world", 0, 0, 0, 10), Set.of(key));
        bus.register(recipient, endpoint("world", 1, 0, 0, 10), Set.of(key));
        assertThrows(IllegalArgumentException.class,
                () -> bus.send(sender, protocol, codec.encode(protocol, sender, recipient, 1, 0, Map.of("sequence", "1"))));

        String first = codec.encode(protocol, sender, recipient, 2, 0, Map.of("sequence", "1"));
        String second = codec.encode(protocol, sender, recipient, 2, 1, Map.of("sequence", "2"));
        assertTrue(bus.send(sender, protocol, first));
        assertThrows(IllegalStateException.class, () -> bus.send(sender, protocol, second));
        assertEquals(1, bus.retainedEvidenceCount());
        assertEquals(1, bus.pendingCount(recipient));
        assertThrows(IllegalArgumentException.class, () -> bus.advance(4));
        assertEquals(2, bus.tick());
    }

    @Test void busCanSafelyAdvanceFromItsServerTickThread() throws Exception {
        VirtualMessageBus bus = new VirtualMessageBus(0, 2);
        try (var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> bus.advance(1)).get();
        }
        assertEquals(1, bus.tick());
    }

    @Test void senderIdentityMustMatchTheAuthenticatedPlayer() throws Exception {
        Protocol protocol = protocol();
        VirtualMessageBus.ProtocolKey key = key(protocol);
        VirtualMessageBus bus = new VirtualMessageBus(0, 8);
        bus.register(sender, endpoint("world", 0, 0, 0, 10), Set.of(key));
        bus.register(recipient, endpoint("world", 1, 0, 0, 10), Set.of(key));
        String forgedSender = codec.encode(protocol, recipient, sender, 0, 0, Map.of("sequence", "1"));
        assertThrows(SecurityException.class, () -> bus.send(sender, protocol, forgedSender));
        assertEquals(0, bus.retainedEvidenceCount());
    }

    @Test void unregisterAndRejoinKeepTheUnacknowledgedMailbox() throws Exception {
        Protocol protocol = protocol();
        VirtualMessageBus.ProtocolKey key = key(protocol);
        VirtualMessageBus bus = new VirtualMessageBus(0, 8);
        bus.register(sender, endpoint("world", 0, 0, 0, 10), Set.of(key));
        bus.register(recipient, endpoint("world", 1, 0, 0, 10), Set.of(key));
        var message = codec.decode(protocol, codec.encode(protocol, sender, recipient, 0, 0, Map.of("sequence", "1")));
        assertTrue(bus.send(sender, protocol, codec.encode(protocol, sender, recipient, 0, 0, Map.of("sequence", "1"))));
        bus.unregister(recipient);
        bus.advance(1);
        assertThrows(IllegalArgumentException.class, () -> bus.poll(recipient, 4));
        assertThrows(IllegalArgumentException.class, () -> bus.send(sender, protocol,
                codec.encode(protocol, sender, recipient, 1, 1, Map.of("sequence", "2"))));
        bus.register(recipient, endpoint("world", 1, 0, 0, 10), Set.of(
                new VirtualMessageBus.ProtocolKey(new DesignId("genesis:other"), "a".repeat(64))));
        assertTrue(bus.poll(recipient, 4).isEmpty());
        bus.register(recipient, endpoint("world", 1, 0, 0, 10), Set.of(key));
        assertEquals(List.of(message), bus.poll(recipient, 4));
    }

    @Test void sendNowCapturesTheCurrentTickAtomicallyAndUsesTheSharedWireCodec() throws Exception {
        Protocol protocol = protocol();
        VirtualMessageBus.ProtocolKey key = key(protocol);
        VirtualMessageBus bus = new VirtualMessageBus(19, 8);
        bus.register(sender, endpoint("network", 0, 0, 0, 8), Set.of(key));
        bus.register(recipient, endpoint("network", 1, 0, 0, 8), Set.of(key));

        VirtualMessageBus.Submission submission = bus.sendNow(sender, protocol, recipient, 12,
                Map.of("sequence", "12"));

        assertTrue(submission.queued());
        assertEquals(19, codec.decode(protocol, submission.encodedEnvelope()).tick());
        assertTrue(bus.poll(recipient, 4).isEmpty());
        bus.advance(20);
        assertEquals(1, bus.poll(recipient, 4).size());
    }

    private VirtualMessageBus.Endpoint endpoint(String world, int x, int y, int z, int range) {
        return new VirtualMessageBus.Endpoint(world, x, y, z, range);
    }

    private VirtualMessageBus.ProtocolKey key(Protocol protocol) {
        return new VirtualMessageBus.ProtocolKey(protocol.metadata().id(), protocol.metadata().hash());
    }

    private Protocol protocol() throws IOException {
        try (var input = getClass().getResourceAsStream("/examples/protocol.json")) {
            assertNotNull(input);
            return (Protocol) new DesignCodec().load(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
