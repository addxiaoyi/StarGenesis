package dev.stargenesis.paper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Set;
import java.util.UUID;

final class ProtocolRelayPacketCodec {
    static final int MAX_PACKET_BYTES = 8192;
    static final long MAX_LIFETIME_MILLIS = 120_000;
    static final long MAX_CLOCK_SKEW_MILLIS = 30_000;
    private static final int MAGIC = 0x53475250;
    private static final int VERSION = 1;
    private static final int MAC_BYTES = 32;

    private ProtocolRelayPacketCodec() { }

    static boolean isPacket(byte[] encoded) {
        return encoded != null && encoded.length >= Integer.BYTES
                && ByteBuffer.wrap(encoded).getInt() == MAGIC;
    }

    static byte[] encode(ProtocolRelayPacket packet, byte[] secret) {
        requireSecret(secret);
        try {
            ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bodyBytes)) {
                output.writeInt(MAGIC);
                output.writeInt(VERSION);
                writeText(output, packet.sourceServer(), 32);
                writeText(output, packet.targetServer(), 32);
                writeUuid(output, packet.messageId());
                writeUuid(output, packet.senderId());
                writeText(output, packet.senderName(), 16);
                writeUuid(output, packet.recipientId());
                writeText(output, packet.protocolId(), 127);
                writeText(output, packet.payload(), ProtocolRelayPacket.MAX_PAYLOAD_BYTES);
                output.writeLong(packet.createdAtMillis());
                output.writeLong(packet.expiresAtMillis());
            }
            byte[] body = bodyBytes.toByteArray();
            byte[] signature = sign(body, secret);
            if (body.length + signature.length > MAX_PACKET_BYTES)
                throw new IllegalArgumentException("Protocol relay packet exceeds size limit");
            ByteArrayOutputStream encoded = new ByteArrayOutputStream(body.length + signature.length);
            encoded.write(body);
            encoded.write(signature);
            return encoded.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("Cannot encode protocol relay packet", impossible);
        }
    }

    static ProtocolRelayPacket decode(byte[] encoded, byte[] secret, String expectedTarget,
                                      Set<String> trustedSources, long nowMillis) {
        requireSecret(secret);
        if (encoded == null || encoded.length < 100 || encoded.length > MAX_PACKET_BYTES)
            throw new IllegalArgumentException("Protocol relay packet size is outside the allowed range");
        if (!ProtocolRelayPacket.validServerId(expectedTarget) || trustedSources == null
                || trustedSources.size() > 32 || trustedSources.stream().anyMatch(source -> !ProtocolRelayPacket.validServerId(source)))
            throw new IllegalArgumentException("Invalid protocol relay trust configuration");
        byte[] body = java.util.Arrays.copyOf(encoded, encoded.length - MAC_BYTES);
        byte[] signature = java.util.Arrays.copyOfRange(encoded, encoded.length - MAC_BYTES, encoded.length);
        if (!MessageDigest.isEqual(sign(body, secret), signature))
            throw new IllegalArgumentException("Protocol relay authentication failed");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(body))) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION)
                throw new IllegalArgumentException("Unsupported protocol relay packet format");
            ProtocolRelayPacket packet = new ProtocolRelayPacket(readText(input, 32), readText(input, 32),
                    readUuid(input), readUuid(input), readText(input, 16), readUuid(input),
                    readText(input, 127), readText(input, ProtocolRelayPacket.MAX_PAYLOAD_BYTES),
                    input.readLong(), input.readLong());
            if (input.available() != 0) throw new IllegalArgumentException("Trailing protocol relay packet data");
            if (!packet.targetServer().equals(expectedTarget) || !trustedSources.contains(packet.sourceServer()))
                throw new IllegalArgumentException("Protocol relay server is not trusted for this destination");
            long latest = Math.addExact(nowMillis, MAX_CLOCK_SKEW_MILLIS);
            if (nowMillis < 0 || packet.createdAtMillis() > latest
                    || packet.expiresAtMillis() <= nowMillis
                    || packet.expiresAtMillis() - packet.createdAtMillis() > MAX_LIFETIME_MILLIS)
                throw new IllegalArgumentException("Protocol relay packet is expired or outside its lifetime");
            return packet;
        } catch (IOException failure) {
            throw new IllegalArgumentException("Malformed or truncated protocol relay packet", failure);
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("Invalid protocol relay time window", failure);
        }
    }

    private static byte[] sign(byte[] body, byte[] secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(body);
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", failure);
        }
    }

    private static void requireSecret(byte[] secret) {
        if (secret == null || secret.length < 32 || secret.length > 128)
            throw new IllegalArgumentException("Protocol relay shared secret must contain 32-128 bytes");
    }

    private static void writeText(DataOutputStream output, String value, int maximumBytes) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maximumBytes) throw new IllegalArgumentException("Protocol relay text exceeds size limit");
        output.writeShort(bytes.length);
        output.write(bytes);
    }

    private static String readText(DataInputStream input, int maximumBytes) throws IOException {
        int length = input.readUnsignedShort();
        if (length == 0 || length > maximumBytes || length > input.available())
            throw new IllegalArgumentException("Invalid protocol relay text length");
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(input.readNBytes(length))).toString();
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException("Malformed protocol relay UTF-8", failure);
        }
    }

    private static void writeUuid(DataOutputStream output, UUID value) throws IOException {
        output.writeLong(value.getMostSignificantBits());
        output.writeLong(value.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }
}
