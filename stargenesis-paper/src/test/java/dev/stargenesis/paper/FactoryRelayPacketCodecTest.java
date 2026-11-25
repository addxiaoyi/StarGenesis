package dev.stargenesis.paper;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FactoryRelayPacketCodecTest {
    private static final byte[] SECRET = new byte[32];

    @Test
    void requestAndResponsePacketsAuthenticateTheirPeerAndDestination() {
        FactoryRelayPacket request = FactoryRelayPacket.request("hub_a", "hub_b", UUID.randomUUID(),
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                List.of("bid", "factory:frame", "4", "12"));
        byte[] encodedRequest = FactoryRelayPacketCodec.encode(request, SECRET);
        assertTrue(FactoryRelayPacketCodec.isPacket(encodedRequest));
        assertArrayEquals(encodedRequest, ProtocolRelayProxyFrame.decode(ProtocolRelayProxyFrame.encode(encodedRequest)));
        assertEquals(request, FactoryRelayPacketCodec.decode(encodedRequest, SECRET, "hub_b", Set.of("hub_a")));
        byte[] wrongSecret = new byte[32];
        wrongSecret[0] = 1;
        assertThrows(IllegalArgumentException.class,
                () -> FactoryRelayPacketCodec.decode(encodedRequest, wrongSecret, "hub_b", Set.of("hub_a")));
        assertThrows(IllegalArgumentException.class,
                () -> FactoryRelayPacketCodec.decode(encodedRequest, SECRET, "hub_c", Set.of("hub_a")));

        FactoryRelayPacket response = FactoryRelayPacket.response("hub_b", "hub_a", request.requestId(),
                request.actorId(), "竞价已记录 factory:frame，当前竞标数 2/4，状态 OPEN.");
        assertEquals(response, FactoryRelayPacketCodec.decode(FactoryRelayPacketCodec.encode(response, SECRET),
                SECRET, "hub_a", Set.of("hub_b")));
    }

    @Test
    void tamperingAndReusingAnIdentityWithDifferentCommandContentAreDetectable() {
        FactoryRelayPacket original = FactoryRelayPacket.request("hub_a", "hub_b", UUID.randomUUID(),
                UUID.randomUUID(), List.of("status"));
        byte[] encoded = FactoryRelayPacketCodec.encode(original, SECRET);
        byte[] tampered = Arrays.copyOf(encoded, encoded.length);
        tampered[24] ^= 1;
        assertThrows(IllegalArgumentException.class,
                () -> FactoryRelayPacketCodec.decode(tampered, SECRET, "hub_b", Set.of("hub_a")));

        FactoryRelayPacket changed = FactoryRelayPacket.request(original.sourceServer(), original.targetServer(),
                original.requestId(), original.actorId(), List.of("orders"));
        assertNotEquals(FactoryRelayPacketCodec.fingerprint(original), FactoryRelayPacketCodec.fingerprint(changed));
        assertEquals(FactoryRelayPacketCodec.fingerprint(original), FactoryRelayPacketCodec.fingerprint(
                FactoryRelayPacketCodec.decode(encoded, SECRET, "hub_b", Set.of("hub_a"))));
    }

    @Test
    void packetFieldsAndPayloadSizesAreBounded() {
        assertThrows(IllegalArgumentException.class, () -> FactoryRelayPacketCodec.encode(
                FactoryRelayPacket.request("hub_a", "hub_b", UUID.randomUUID(), UUID.randomUUID(),
                        List.of("x".repeat(FactoryRelayPacket.MAX_ARGUMENT_BYTES + 1))), SECRET));
        assertThrows(IllegalArgumentException.class, () -> FactoryRelayPacket.response(
                "hub_a", "hub_b", UUID.randomUUID(), UUID.randomUUID(), "\u0001"));
        assertThrows(IllegalArgumentException.class,
                () -> FactoryRelayPacketCodec.encode(FactoryRelayPacket.request(
                        "hub_a", "hub_b", UUID.randomUUID(), UUID.randomUUID(), List.of("status")), new byte[31]));
    }
}
