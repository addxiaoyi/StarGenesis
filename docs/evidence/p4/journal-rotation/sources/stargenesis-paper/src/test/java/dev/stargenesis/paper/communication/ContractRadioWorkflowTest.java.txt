package dev.stargenesis.paper.communication;

import dev.stargenesis.design.Contract;
import dev.stargenesis.design.Protocol;
import dev.stargenesis.design.ProtocolMessageCodec;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ContractRadioWorkflowTest {
    private static final UUID REQUESTER = new UUID(0, 71);
    private static final UUID PROVIDER = new UUID(0, 72);
    private final Protocol protocol = ServiceContractDefinition.protocol();
    private final Contract contract = ServiceContractDefinition.contract();
    private final ProtocolMessageCodec codec = new ProtocolMessageCodec();

    @Test void twoInstalledEndpointsOfferAcceptAndCompleteAContractThroughDelayedMailboxDelivery() {
        VirtualMessageBus bus = new VirtualMessageBus(0, 8);
        bus.register(REQUESTER, endpoint(0), Set.of(RadioProtocol.key(), ServiceContractDefinition.key()));
        bus.register(PROVIDER, endpoint(1), Set.of(RadioProtocol.key(), ServiceContractDefinition.key()));
        ContractRuntime runtime = new ContractRuntime(contract, protocol, 0, 16);

        var offer = bus.sendNow(REQUESTER, protocol, PROVIDER, 1, payload(1, "offer", "service_01", 3));
        assertTrue(offer.queued());
        assertTrue(bus.poll(PROVIDER, 8).isEmpty());
        bus.advance(1);
        var deliveredOffer = bus.poll(PROVIDER, 8).getFirst();
        assertEquals(codec.decode(protocol, offer.encodedEnvelope()), deliveredOffer);
        assertTrue(runtime.receive(deliveredOffer));
        assertTrue(bus.acknowledge(PROVIDER, deliveredOffer));
        assertEquals(ContractRuntime.State.OFFERED, runtime.state("service_01").state());

        runtime.advance(1);
        assertTrue(bus.sendNow(PROVIDER, protocol, REQUESTER, 1, payload(1, "accept", "service_01", 3)).queued());
        bus.advance(2);
        var deliveredAccept = bus.poll(REQUESTER, 8).getFirst();
        assertTrue(runtime.receive(deliveredAccept));
        assertTrue(bus.acknowledge(REQUESTER, deliveredAccept));
        assertEquals(ContractRuntime.State.ACCEPTED, runtime.state("service_01").state());

        runtime.advance(2);
        assertTrue(bus.sendNow(PROVIDER, protocol, REQUESTER, 2, payload(2, "fulfill", "service_01", 3)).queued());
        bus.advance(3);
        var deliveredFulfillment = bus.poll(REQUESTER, 8).getFirst();
        assertTrue(runtime.receive(deliveredFulfillment));
        assertTrue(bus.acknowledge(REQUESTER, deliveredFulfillment));
        assertEquals(ContractRuntime.State.COMPLETED, runtime.state("service_01").state());
        assertEquals(3, runtime.events().size());
    }

    @Test void uninstalledServiceChipCannotSendContractMessages() {
        VirtualMessageBus bus = new VirtualMessageBus(0, 8);
        bus.register(REQUESTER, endpoint(0), Set.of(RadioProtocol.key()));
        bus.register(PROVIDER, endpoint(1), Set.of(RadioProtocol.key()));

        assertThrows(SecurityException.class, () -> bus.sendNow(REQUESTER, protocol, PROVIDER, 1,
                payload(1, "offer", "service_02", 1)));
    }

    private VirtualMessageBus.Endpoint endpoint(int x) {
        return new VirtualMessageBus.Endpoint("contract_network", x, 0, 0, 16);
    }

    private Map<String, String> payload(int sequence, String action, String id, int units) {
        return Map.of("contract", id, "action", action, "units", Integer.toString(units));
    }

}
