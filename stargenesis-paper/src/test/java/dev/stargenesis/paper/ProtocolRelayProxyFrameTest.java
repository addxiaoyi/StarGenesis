package dev.stargenesis.paper;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolRelayProxyFrameTest {
    @Test
    void customRelaySubchannelFrameRoundTripsItsPayload() {
        byte[] packet = {1, 2, 3, 4};
        assertArrayEquals(packet, ProtocolRelayProxyFrame.decode(ProtocolRelayProxyFrame.encode(packet)));
    }

    @Test
    void bungeeForwardLeavesTheCustomSubchannelFrameAfterTheProxyConsumesItsRoute() throws IOException {
        byte[] packet = {1, 2, 3, 4};
        byte[] forwarding = ProtocolRelayProxyFrame.encodeBungeeForward("hub_b", packet);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(forwarding))) {
            assertEquals("Forward", input.readUTF());
            assertEquals("hub_b", input.readUTF());
            assertEquals("BungeeCord", ProtocolRelayProxyFrame.BUNGEE_CHANNEL);
            byte[] forwarded = input.readAllBytes();
            try (DataInputStream destination = new DataInputStream(new ByteArrayInputStream(forwarded))) {
                assertEquals("sg:relay", destination.readUTF());
                assertEquals(packet.length, destination.readUnsignedShort());
                assertArrayEquals(packet, destination.readAllBytes());
            }
            assertArrayEquals(packet, ProtocolRelayProxyFrame.decode(forwarded));
        }
        assertThrows(IllegalArgumentException.class,
                () -> ProtocolRelayProxyFrame.encodeBungeeForward("invalid/server", packet));
    }

    @Test
    void wrongSubchannelTruncatedTrailingAndOversizedFramesAreRejected() throws IOException {
        assertThrows(IllegalArgumentException.class, () -> ProtocolRelayProxyFrame.decode(new byte[4]));
        ByteArrayOutputStream wrongChannel = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(wrongChannel)) {
            output.writeUTF("other:channel");
            output.writeShort(1);
            output.writeByte(1);
        }
        assertThrows(IllegalArgumentException.class,
                () -> ProtocolRelayProxyFrame.decode(wrongChannel.toByteArray()));
        byte[] valid = ProtocolRelayProxyFrame.encode(new byte[]{1, 2, 3});
        assertThrows(IllegalArgumentException.class,
                () -> ProtocolRelayProxyFrame.decode(Arrays.copyOf(valid, valid.length - 1)));
        byte[] trailing = Arrays.copyOf(valid, valid.length + 1);
        assertThrows(IllegalArgumentException.class, () -> ProtocolRelayProxyFrame.decode(trailing));
        assertThrows(IllegalArgumentException.class, () -> ProtocolRelayProxyFrame.encode(
                new byte[ProtocolRelayPacketCodec.MAX_PACKET_BYTES + 1]));
    }
}
