package dev.stargenesis.paper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** Strict bounded codec for the server-owned pre-factory roster. */
final class FactoryOrganizationCodec {
    static final int MAX_ENCODED_BYTES = 512;
    private static final int MAGIC = 0x53474F52;
    private static final int VERSION = 1;
    private static final int DIGEST_BYTES = 32;

    private FactoryOrganizationCodec() { }

    static byte[] encode(FactoryOrganization organization) {
        try {
            ByteArrayOutputStream payloadBytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(payloadBytes)) {
                output.writeInt(MAGIC);
                output.writeInt(VERSION);
                writeText(output, organization.name());
                writeUuid(output, organization.owner());
                output.writeInt(organization.members().size());
                for (UUID member : organization.members()) writeUuid(output, member);
            }
            byte[] payload = payloadBytes.toByteArray();
            byte[] digest = digest(payload);
            ByteArrayOutputStream encoded = new ByteArrayOutputStream(payload.length + digest.length);
            encoded.write(payload);
            encoded.write(digest);
            byte[] result = encoded.toByteArray();
            if (result.length > MAX_ENCODED_BYTES) throw new IllegalArgumentException("Organization checkpoint is too large");
            return result;
        } catch (IOException impossible) {
            throw new IllegalStateException("Cannot encode organization checkpoint", impossible);
        }
    }

    static FactoryOrganization decode(byte[] encoded) {
        if (encoded == null || encoded.length < 44 || encoded.length > MAX_ENCODED_BYTES) {
            throw new IllegalArgumentException("Invalid organization checkpoint size");
        }
        int payloadLength = encoded.length - DIGEST_BYTES;
        byte[] payload = Arrays.copyOf(encoded, payloadLength);
        byte[] storedDigest = Arrays.copyOfRange(encoded, payloadLength, encoded.length);
        if (!MessageDigest.isEqual(storedDigest, digest(payload))) {
            throw new IllegalArgumentException("Organization checkpoint checksum mismatch");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION) {
                throw new IllegalArgumentException("Unsupported organization checkpoint format");
            }
            String name = readText(input);
            UUID owner = readUuid(input);
            int memberCount = input.readInt();
            if (memberCount < 1 || memberCount > FactoryOrganization.MAX_MEMBERS) {
                throw new IllegalArgumentException("Invalid organization member count");
            }
            List<UUID> members = new ArrayList<>(memberCount);
            for (int index = 0; index < memberCount; index++) members.add(readUuid(input));
            if (input.available() != 0) throw new IllegalArgumentException("Organization checkpoint has trailing data");
            return new FactoryOrganization(name, owner, members);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot decode organization checkpoint", failure);
        }
    }

    private static byte[] digest(byte[] payload) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(payload);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void writeText(DataOutputStream output, String value) throws IOException {
        byte[] encoded = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        output.writeInt(encoded.length);
        output.write(encoded);
    }

    private static String readText(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 1 || length > 32) throw new IllegalArgumentException("Invalid organization name size");
        byte[] encoded = input.readNBytes(length);
        if (encoded.length != length) throw new IllegalArgumentException("Truncated organization name");
        String value = new String(encoded, java.nio.charset.StandardCharsets.UTF_8);
        if (!Arrays.equals(encoded, value.getBytes(java.nio.charset.StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("Invalid organization name encoding");
        }
        return value;
    }

    private static void writeUuid(DataOutputStream output, UUID value) throws IOException {
        output.writeLong(value.getMostSignificantBits());
        output.writeLong(value.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }
}
