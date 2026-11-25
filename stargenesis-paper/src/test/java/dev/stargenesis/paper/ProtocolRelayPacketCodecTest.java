package dev.stargenesis.paper;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolRelayPacketCodecTest {
    private static final byte[] SECRET = new byte[32];
    private static final long NOW = 1_800_000_000_000L;

    @Test
    void authenticatedPacketRoundTripsOnlyForItsConfiguredPeerAndTarget() {
        ProtocolRelayPacket packet = packet();
        byte[] encoded = ProtocolRelayPacketCodec.encode(packet, SECRET);
        assertEquals(packet, ProtocolRelayPacketCodec.decode(encoded, SECRET, "hub_b", Set.of("hub_a"), NOW));
        assertThrows(IllegalArgumentException.class,
                () -> ProtocolRelayPacketCodec.decode(encoded, SECRET, "hub_c", Set.of("hub_a"), NOW));
        assertThrows(IllegalArgumentException.class,
                () -> ProtocolRelayPacketCodec.decode(encoded, SECRET, "hub_b", Set.of("hub_c"), NOW));
    }

    @Test
    void packetTamperingExpiryAndClockWindowAreRejected() {
        byte[] encoded = ProtocolRelayPacketCodec.encode(packet(), SECRET);
        byte[] changed = Arrays.copyOf(encoded, encoded.length);
        changed[20] ^= 1;
        assertThrows(IllegalArgumentException.class,
                () -> ProtocolRelayPacketCodec.decode(changed, SECRET, "hub_b", Set.of("hub_a"), NOW));
        assertThrows(IllegalArgumentException.class,
                () -> ProtocolRelayPacketCodec.decode(encoded, SECRET, "hub_b", Set.of("hub_a"), NOW + 60_001));
        assertThrows(IllegalArgumentException.class,
                () -> ProtocolRelayPacketCodec.decode(encoded, SECRET, "hub_b", Set.of("hub_a"), NOW - 30_001));
    }

    @Test
    void retriesRemainValidUntilExpiryAfterTheClockSkewWindowPasses() {
        ProtocolRelayPacket original = packet();
        byte[] encoded = ProtocolRelayPacketCodec.encode(original, SECRET);
        assertEquals(original, ProtocolRelayPacketCodec.decode(
                encoded, SECRET, "hub_b", Set.of("hub_a"), NOW + 30_001));
        assertEquals(original, ProtocolRelayPacketCodec.decode(
                encoded, SECRET, "hub_b", Set.of("hub_a"), original.expiresAtMillis() - 1));
        assertThrows(IllegalArgumentException.class, () -> ProtocolRelayPacketCodec.decode(
                encoded, SECRET, "hub_b", Set.of("hub_a"), original.expiresAtMillis()));
    }

    @Test
    void acknowledgementAuthenticatesSourceTargetStatusAndMessageIdentity() {
        UUID messageId = packet().messageId();
        ProtocolRelayAcknowledgement acknowledgement = new ProtocolRelayAcknowledgement(
                "hub_b", "hub_a", messageId, true, NOW);
        byte[] encoded = ProtocolRelayAcknowledgementCodec.encode(acknowledgement, SECRET);
        assertEquals(acknowledgement, ProtocolRelayAcknowledgementCodec.decode(
                encoded, SECRET, "hub_a", Set.of("hub_b"), NOW));
        encoded[10] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> ProtocolRelayAcknowledgementCodec.decode(
                encoded, SECRET, "hub_a", Set.of("hub_b"), NOW));
    }

    @Test
    void secretPacketSizeAndPacketFieldsAreBounded() {
        assertThrows(IllegalArgumentException.class, () -> ProtocolRelayPacketCodec.encode(packet(), new byte[31]));
        assertThrows(IllegalArgumentException.class, () -> ProtocolRelayPacketCodec.decode(
                new byte[ProtocolRelayPacketCodec.MAX_PACKET_BYTES + 1], SECRET, "hub_b", Set.of("hub_a"), NOW));
        assertThrows(IllegalArgumentException.class, () -> new ProtocolRelayPacket(
                "hub_a", "hub_a", UUID.randomUUID(), UUID.randomUUID(), "sender", UUID.randomUUID(),
                "genesis:protocol", "payload", NOW, NOW + 1000));
        assertThrows(IllegalArgumentException.class, () -> new ProtocolRelayPacket(
                "hub_a", "hub_b", UUID.randomUUID(), UUID.randomUUID(), "sender", UUID.randomUUID(),
                "genesis:protocol", "payload", NOW, NOW + ProtocolRelayPacketCodec.MAX_LIFETIME_MILLIS + 1));
    }

    private static ProtocolRelayPacket packet() {
        return new ProtocolRelayPacket("hub_a", "hub_b", UUID.fromString("00000000-0000-0000-0000-000000000001"),
                UUID.fromString("00000000-0000-0000-0000-000000000002"), "sender",
                UUID.fromString("00000000-0000-0000-0000-000000000003"), "genesis:protocol",
                "8:sequence=1:7", NOW, NOW + 60_000);
    }
}
