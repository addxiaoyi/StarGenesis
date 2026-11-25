package dev.stargenesis.paper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

final class ProtocolRelayProxyFrame {
    static final String CHANNEL = "sg:relay";
    static final String BUNGEE_CHANNEL = "BungeeCord";
    private static final int MAX_FRAME_BYTES = ProtocolRelayPacketCodec.MAX_PACKET_BYTES + 32;

    private ProtocolRelayProxyFrame() { }

    static byte[] encodeBungeeForward(String targetServer, byte[] payload) {
        if (!ProtocolRelayPacket.validServerId(targetServer))
            throw new IllegalArgumentException("Invalid protocol relay target server");
        byte[] proxyFrame = encode(payload);
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(proxyFrame.length + 64);
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeUTF("Forward");
                output.writeUTF(targetServer);
                output.write(proxyFrame);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("Cannot encode BungeeCord forward message", impossible);
        }
    }

    static byte[] encode(byte[] payload) {
        if (payload == null || payload.length < 1 || payload.length > ProtocolRelayPacketCodec.MAX_PACKET_BYTES)
            throw new IllegalArgumentException("Invalid protocol relay proxy payload size");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(payload.length + 16);
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeUTF(CHANNEL);
                output.writeShort(payload.length);
                output.write(payload);
            }
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("Cannot encode protocol relay proxy frame", impossible);
        }
    }

    static byte[] decode(byte[] frame) {
        if (frame == null || frame.length < 12 || frame.length > MAX_FRAME_BYTES)
            throw new IllegalArgumentException("Protocol relay proxy frame size is invalid");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(frame))) {
            if (!CHANNEL.equals(input.readUTF())) throw new IllegalArgumentException("Unexpected protocol relay proxy subchannel");
            int length = input.readUnsignedShort();
            if (length < 1 || length > ProtocolRelayPacketCodec.MAX_PACKET_BYTES || length != input.available())
                throw new IllegalArgumentException("Invalid protocol relay proxy payload length");
            return input.readNBytes(length);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Malformed protocol relay proxy frame", failure);
        }
    }
}
