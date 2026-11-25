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
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

final class FactoryRelayPacketCodec {
    static final int MAX_PACKET_BYTES = 8192;
    private static final int MAGIC = 0x53474643;
    private static final int VERSION = 1;
    private static final int MAC_BYTES = 32;

    private FactoryRelayPacketCodec() { }

    static boolean isPacket(byte[] encoded) {
        return encoded != null && encoded.length >= Integer.BYTES
                && ByteBuffer.wrap(encoded).getInt() == MAGIC;
    }

    static byte[] encode(FactoryRelayPacket packet, byte[] secret) {
        requireSecret(secret);
        try {
            byte[] body = body(packet);
            byte[] signature = sign(body, secret);
            if (body.length + signature.length > MAX_PACKET_BYTES)
                throw new IllegalArgumentException("Factory relay packet exceeds its size limit");
            ByteArrayOutputStream encoded = new ByteArrayOutputStream(body.length + signature.length);
            encoded.write(body);
            encoded.write(signature);
            return encoded.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("Cannot encode factory relay packet", impossible);
        }
    }

    static FactoryRelayPacket decode(byte[] encoded, byte[] secret, String expectedTarget,
                                     Set<String> trustedSources) {
        requireSecret(secret);
        if (encoded == null || encoded.length < 64 || encoded.length > MAX_PACKET_BYTES)
            throw new IllegalArgumentException("Factory relay packet size is outside the allowed range");
        if (!ProtocolRelayPacket.validServerId(expectedTarget) || trustedSources == null
                || trustedSources.isEmpty() || trustedSources.size() > 32
                || trustedSources.stream().anyMatch(source -> !ProtocolRelayPacket.validServerId(source)))
            throw new IllegalArgumentException("Invalid factory relay trust configuration");
        byte[] body = Arrays.copyOf(encoded, encoded.length - MAC_BYTES);
        byte[] signature = Arrays.copyOfRange(encoded, encoded.length - MAC_BYTES, encoded.length);
        if (!MessageDigest.isEqual(sign(body, secret), signature))
            throw new IllegalArgumentException("Factory relay authentication failed");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(body))) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION)
                throw new IllegalArgumentException("Unsupported factory relay packet format");
            int kindValue = input.readUnsignedByte();
            if (kindValue >= FactoryRelayPacket.Kind.values().length)
                throw new IllegalArgumentException("Unknown factory relay packet kind");
            FactoryRelayPacket.Kind kind = FactoryRelayPacket.Kind.values()[kindValue];
            String source = readText(input, 32);
            String target = readText(input, 32);
            UUID requestId = readUuid(input);
            UUID actorId = readUuid(input);
            FactoryRelayPacket packet;
            if (kind == FactoryRelayPacket.Kind.REQUEST) {
                int count = input.readUnsignedByte();
                if (count < 1 || count > FactoryRelayPacket.MAX_ARGUMENTS)
                    throw new IllegalArgumentException("Invalid factory relay argument count");
                var arguments = new java.util.ArrayList<String>(count);
                for (int index = 0; index < count; index++)
                    arguments.add(readText(input, FactoryRelayPacket.MAX_ARGUMENT_BYTES));
                packet = FactoryRelayPacket.request(source, target, requestId, actorId, arguments);
            } else {
                packet = FactoryRelayPacket.response(source, target, requestId, actorId,
                        readText(input, FactoryRelayPacket.MAX_RESPONSE_BYTES));
            }
            if (input.available() != 0) throw new IllegalArgumentException("Trailing factory relay packet data");
            if (!packet.targetServer().equals(expectedTarget) || !trustedSources.contains(packet.sourceServer()))
                throw new IllegalArgumentException("Factory relay server is not trusted for this destination");
            return packet;
        } catch (IOException failure) {
            throw new IllegalArgumentException("Malformed or truncated factory relay packet", failure);
        }
    }

    static String fingerprint(FactoryRelayPacket packet) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body(packet)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        } catch (IOException impossible) {
            throw new IllegalStateException("Cannot fingerprint factory relay packet", impossible);
        }
    }

    private static byte[] body(FactoryRelayPacket packet) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            output.writeByte(packet.kind().ordinal());
            writeText(output, packet.sourceServer(), 32);
            writeText(output, packet.targetServer(), 32);
            writeUuid(output, packet.requestId());
            writeUuid(output, packet.actorId());
            if (packet.kind() == FactoryRelayPacket.Kind.REQUEST) {
                output.writeByte(packet.arguments().size());
                for (String argument : packet.arguments())
                    writeText(output, argument, FactoryRelayPacket.MAX_ARGUMENT_BYTES);
            } else {
                writeText(output, packet.response(), FactoryRelayPacket.MAX_RESPONSE_BYTES);
            }
        }
        return bytes.toByteArray();
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
            throw new IllegalArgumentException("Factory relay shared secret must contain 32-128 bytes");
    }

    private static void writeText(DataOutputStream output, String value, int maximumBytes) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maximumBytes) throw new IllegalArgumentException("Factory relay text exceeds size limit");
        output.writeShort(bytes.length);
        output.write(bytes);
    }

    private static String readText(DataInputStream input, int maximumBytes) throws IOException {
        int length = input.readUnsignedShort();
        if (length < 1 || length > maximumBytes || length > input.available())
            throw new IllegalArgumentException("Invalid factory relay text length");
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(input.readNBytes(length))).toString();
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException("Malformed factory relay UTF-8", failure);
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
