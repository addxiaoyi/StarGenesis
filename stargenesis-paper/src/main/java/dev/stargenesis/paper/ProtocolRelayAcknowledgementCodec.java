package dev.stargenesis.paper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Set;
import java.util.UUID;

final class ProtocolRelayAcknowledgementCodec {
    private static final int MAGIC = 0x53475241;
    private static final int VERSION = 1;
    private static final int MAC_BYTES = 32;

    private ProtocolRelayAcknowledgementCodec() { }

    static boolean isAcknowledgement(byte[] encoded) {
        return encoded != null && encoded.length >= Integer.BYTES
                && ByteBuffer.wrap(encoded).getInt() == MAGIC;
    }

    static byte[] encode(ProtocolRelayAcknowledgement acknowledgement, byte[] secret) {
        requireSecret(secret);
        try {
            ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bodyBytes)) {
                output.writeInt(MAGIC);
                output.writeInt(VERSION);
                writeText(output, acknowledgement.sourceServer());
                writeText(output, acknowledgement.targetServer());
                output.writeLong(acknowledgement.messageId().getMostSignificantBits());
                output.writeLong(acknowledgement.messageId().getLeastSignificantBits());
                output.writeBoolean(acknowledgement.accepted());
                output.writeLong(acknowledgement.acknowledgedAtMillis());
            }
            byte[] body = bodyBytes.toByteArray();
            ByteArrayOutputStream encoded = new ByteArrayOutputStream(body.length + MAC_BYTES);
            encoded.write(body);
            encoded.write(sign(body, secret));
            return encoded.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("Cannot encode protocol relay acknowledgement", impossible);
        }
    }

    static ProtocolRelayAcknowledgement decode(byte[] encoded, byte[] secret, String expectedTarget,
                                                Set<String> trustedSources, long nowMillis) {
        requireSecret(secret);
        if (encoded == null || encoded.length < 70 || encoded.length > 256)
            throw new IllegalArgumentException("Protocol relay acknowledgement size is invalid");
        byte[] body = java.util.Arrays.copyOf(encoded, encoded.length - MAC_BYTES);
        byte[] signature = java.util.Arrays.copyOfRange(encoded, encoded.length - MAC_BYTES, encoded.length);
        if (!MessageDigest.isEqual(sign(body, secret), signature))
            throw new IllegalArgumentException("Protocol relay acknowledgement authentication failed");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(body))) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION)
                throw new IllegalArgumentException("Unsupported protocol relay acknowledgement format");
            ProtocolRelayAcknowledgement acknowledgement = new ProtocolRelayAcknowledgement(
                    readText(input), readText(input), new UUID(input.readLong(), input.readLong()),
                    input.readBoolean(), input.readLong());
            if (input.available() != 0) throw new IllegalArgumentException("Trailing protocol acknowledgement data");
            if (!acknowledgement.targetServer().equals(expectedTarget)
                    || !trustedSources.contains(acknowledgement.sourceServer()))
                throw new IllegalArgumentException("Protocol relay acknowledgement source is not trusted");
            long earliest = Math.max(0, nowMillis - ProtocolRelayPacketCodec.MAX_LIFETIME_MILLIS
                    - ProtocolRelayPacketCodec.MAX_CLOCK_SKEW_MILLIS);
            if (acknowledgement.acknowledgedAtMillis() < earliest
                    || acknowledgement.acknowledgedAtMillis() > Math.addExact(nowMillis,
                    ProtocolRelayPacketCodec.MAX_CLOCK_SKEW_MILLIS))
                throw new IllegalArgumentException("Protocol relay acknowledgement is outside its time window");
            return acknowledgement;
        } catch (IOException failure) {
            throw new IllegalArgumentException("Malformed protocol relay acknowledgement", failure);
        } catch (ArithmeticException failure) {
            throw new IllegalArgumentException("Invalid protocol acknowledgement time window", failure);
        }
    }

    private static void requireSecret(byte[] secret) {
        if (secret == null || secret.length < 32 || secret.length > 128)
            throw new IllegalArgumentException("Protocol relay shared secret must contain 32-128 bytes");
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

    private static void writeText(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII);
        output.writeByte(bytes.length);
        output.write(bytes);
    }

    private static String readText(DataInputStream input) throws IOException {
        int length = input.readUnsignedByte();
        if (length < 1 || length > 32 || length > input.available())
            throw new IllegalArgumentException("Invalid protocol acknowledgement server identity");
        return StandardCharsets.US_ASCII.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(input.readNBytes(length))).toString();
    }
}
