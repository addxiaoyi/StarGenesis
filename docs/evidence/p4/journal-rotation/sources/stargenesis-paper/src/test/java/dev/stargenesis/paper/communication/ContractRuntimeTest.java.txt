package dev.stargenesis.paper.communication;

import dev.stargenesis.api.design.DesignDependency;
import dev.stargenesis.api.design.DesignId;
import dev.stargenesis.api.design.DesignMetadata;
import dev.stargenesis.api.design.DesignPermissions;
import dev.stargenesis.design.Contract;
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

import static org.junit.jupiter.api.Assertions.*;

class ContractRuntimeTest {
    private static final UUID REQUESTER = new UUID(0, 61);
    private static final UUID PROVIDER = new UUID(0, 62);
    private final DesignCodec designs = new DesignCodec();
    private final ProtocolMessageCodec messages = new ProtocolMessageCodec();

    @Test void offerAcceptPartialFulfillmentAndCompletionAreDeterministic() throws Exception {
        Protocol protocol = protocol();
        Contract contract = contract(protocol, 5, 8);
        ContractRuntime first = new ContractRuntime(contract, protocol, 0, 16);
        ContractRuntime second = new ContractRuntime(contract, protocol, 0, 16);

        var offer = message(protocol, REQUESTER, PROVIDER, 0, 0, "offer", "offer_1", 5);
        assertTrue(first.receive(offer));
        assertFalse(first.receive(offer));
        assertEquals(ContractRuntime.State.OFFERED, first.state("offer_1").state());

        first.advance(1); second.receive(offer); second.advance(1);
        var accept = message(protocol, PROVIDER, REQUESTER, 1, 0, "accept", "offer_1", 5);
        assertTrue(first.receive(accept));
        second.receive(accept);
        assertEquals(ContractRuntime.State.ACCEPTED, first.state("offer_1").state());

        first.advance(2); second.advance(2);
        var partial = message(protocol, PROVIDER, REQUESTER, 2, 1, "fulfill", "offer_1", 2);
        first.receive(partial); second.receive(partial);
        assertEquals(2, first.state("offer_1").fulfilledUnits());
        assertEquals(ContractRuntime.State.ACCEPTED, first.state("offer_1").state());

        first.advance(3); second.advance(3);
        var finalDelivery = message(protocol, PROVIDER, REQUESTER, 3, 2, "fulfill", "offer_1", 3);
        first.receive(finalDelivery); second.receive(finalDelivery);
        assertEquals(ContractRuntime.State.COMPLETED, first.state("offer_1").state());
        assertEquals(ContractRuntime.State.COMPLETED, second.state("offer_1").state());
        assertEquals(first.events(), second.events());
        assertEquals(4, first.events().size());
    }

    @Test void offerExpiresAtItsLogicalDeadlineAndCannotBeAcceptedLate() throws Exception {
        Protocol protocol = protocol();
        ContractRuntime runtime = new ContractRuntime(contract(protocol, 5, 2), protocol, 10, 8);
        runtime.receive(message(protocol, REQUESTER, PROVIDER, 10, 0, "offer", "late", 1));

        assertTrue(runtime.advance(11).isEmpty());
        assertTrue(runtime.advance(12).isEmpty());
        assertTrue(runtime.receive(message(protocol, PROVIDER, REQUESTER, 11, 0, "accept", "late", 1)));
        assertEquals(ContractRuntime.State.ACCEPTED, runtime.state("late").state());
        var expiry = runtime.advance(13);
        assertEquals(1, expiry.size());
        assertEquals(ContractRuntime.State.EXPIRED, runtime.state("late").state());
        assertThrows(IllegalArgumentException.class,
                () -> runtime.receive(message(protocol, PROVIDER, REQUESTER, 12, 1, "fulfill", "late", 1)));
    }

    @Test void partiesUnitsAndActionsAreCheckedBeforeEventsAreReserved() throws Exception {
        Protocol protocol = protocol();
        ContractRuntime runtime = new ContractRuntime(contract(protocol, 5, 4), protocol, 0, 8);
        assertThrows(IllegalArgumentException.class,
                () -> runtime.receive(message(protocol, REQUESTER, PROVIDER, 0, 0, "offer", "too_much", 6)));
        assertTrue(runtime.events().isEmpty());
        runtime.receive(message(protocol, REQUESTER, PROVIDER, 0, 0, "offer", "direction", 2));
        assertThrows(SecurityException.class,
                () -> runtime.receive(message(protocol, REQUESTER, PROVIDER, 0, 1, "accept", "direction", 2)));
        assertEquals(1, runtime.events().size());
        assertThrows(IllegalArgumentException.class,
                () -> runtime.receive(message(protocol, PROVIDER, REQUESTER, 0, 1, "cancel", "direction", 1)));
        assertEquals(1, runtime.events().size());
    }

    @Test void changedSequenceReplayIsRejectedAndExactRetryIsIdempotent() throws Exception {
        Protocol protocol = protocol();
        ContractRuntime runtime = new ContractRuntime(contract(protocol, 5, 4), protocol, 0, 8);
        var offer = message(protocol, REQUESTER, PROVIDER, 0, 8, "offer", "retry", 2);
        assertTrue(runtime.receive(offer));
        assertFalse(runtime.receive(offer));
        assertThrows(IllegalArgumentException.class,
                () -> runtime.receive(message(protocol, REQUESTER, PROVIDER, 0, 8, "offer", "retry", 3)));
        assertEquals(1, runtime.events().size());
    }

    @Test void contractMustPinTheExactProtocolVersionAndHash() throws Exception {
        Protocol protocol = protocol();
        Contract pinned = contract(protocol, 5, 4);
        Protocol replacement = protocol(List.of(
                new Protocol.Field("contract", Protocol.FieldType.TOKEN, true),
                new Protocol.Field("action", Protocol.FieldType.TOKEN, true),
                new Protocol.Field("units", Protocol.FieldType.INTEGER, true),
                new Protocol.Field("optional", Protocol.FieldType.BOOLEAN, false)), 512);
        assertNotEquals(protocol.metadata().hash(), replacement.metadata().hash());
        assertThrows(IllegalArgumentException.class, () -> new ContractRuntime(pinned, replacement, 0, 8));
    }

    private ProtocolMessageCodec.Message message(Protocol protocol, UUID sender, UUID recipient,
                                                  long tick, int sequence, String action, String id, long units) {
        String encoded = messages.encode(protocol, sender, recipient, tick, sequence,
                Map.of("contract", id, "action", action, "units", Long.toString(units)));
        return messages.decode(protocol, encoded);
    }

    private Contract contract(Protocol protocol, long maxUnits, long timeout) throws IOException {
        Contract template;
        try (var input = getClass().getResourceAsStream("/examples/contract.json")) {
            assertNotNull(input);
            template = (Contract) designs.load(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        DesignMetadata old = template.metadata();
        DesignMetadata metadata = new DesignMetadata(new DesignId("genesis:contract"), old.schemaVersion(),
                old.version(), old.author(), old.source(), List.of(new DesignDependency(protocol.metadata().id(),
                protocol.metadata().version(), protocol.metadata().hash())), "", old.permissions());
        Contract unsigned = new Contract(metadata, protocol.metadata().id(), timeout, maxUnits);
        return (Contract) designs.load(designs.seal(unsigned));
    }

    private Protocol protocol() throws IOException {
        return protocol(List.of(
                new Protocol.Field("contract", Protocol.FieldType.TOKEN, true),
                new Protocol.Field("action", Protocol.FieldType.TOKEN, true),
                new Protocol.Field("units", Protocol.FieldType.INTEGER, true)), 512);
    }

    private Protocol protocol(List<Protocol.Field> fields, int maxPayloadBytes) throws IOException {
        Protocol template;
        try (var input = getClass().getResourceAsStream("/examples/protocol.json")) {
            assertNotNull(input);
            template = (Protocol) designs.load(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        DesignMetadata old = template.metadata();
        DesignMetadata metadata = new DesignMetadata(new DesignId("genesis:service"), old.schemaVersion(),
                old.version(), old.author(), old.source(), List.of(), "", new DesignPermissions(true, true, Set.of()));
        return (Protocol) designs.load(designs.seal(new Protocol(metadata, fields, maxPayloadBytes)));
    }
}
