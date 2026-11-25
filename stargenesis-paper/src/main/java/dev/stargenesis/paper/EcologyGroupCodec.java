package dev.stargenesis.paper;

import dev.stargenesis.sim.EcologyCheckpoint;
import dev.stargenesis.sim.EcologyCheckpointCodec;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

final class EcologyGroupCodec {
    static final int MAX_GROUPS = 128;
    static final int MAX_ENCODED_BYTES = EcologyCheckpointCodec.MAX_ENCODED_BYTES + 256;
    private static final int MAGIC = 0x53474547;
    private static final int VERSION = 1;
    private static final int HASH_BYTES = 32;

    private EcologyGroupCodec() { }

    static byte[] encode(EcologyGroupRecord group) {
        if (group == null) throw new IllegalArgumentException("Ecology group is required");
        try {
            byte[] checkpoint = EcologyCheckpointCodec.encode(group.checkpoint());
            ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream(checkpoint.length + 160);
            try (DataOutputStream output = new DataOutputStream(bodyBytes)) {
                output.writeInt(MAGIC);
                output.writeInt(VERSION);
                writeUuid(output, group.id());
                writeUuid(output, group.ownerId());
                output.writeByte(group.members().size());
                for (UUID member : group.members()) writeUuid(output, member);
                output.writeInt(checkpoint.length);
                output.write(checkpoint);
            }
            byte[] body = bodyBytes.toByteArray();
            byte[] encoded = ByteBuffer.allocate(body.length + HASH_BYTES).put(body).put(sha256(body)).array();
            if (encoded.length > MAX_ENCODED_BYTES)
                throw new IllegalArgumentException("Ecology group checkpoint exceeds its encoded size limit");
            return encoded;
        } catch (IOException impossible) {
            throw new IllegalStateException("Cannot encode ecology group", impossible);
        }
    }

    static EcologyGroupRecord decode(byte[] encoded) {
        if (encoded == null || encoded.length < 4 + 4 + 16 + 16 + 1 + 4 + HASH_BYTES
                || encoded.length > MAX_ENCODED_BYTES)
            throw new IllegalArgumentException("Ecology group record size is outside the allowed range");
        byte[] body = java.util.Arrays.copyOf(encoded, encoded.length - HASH_BYTES);
        byte[] hash = java.util.Arrays.copyOfRange(encoded, encoded.length - HASH_BYTES, encoded.length);
        if (!MessageDigest.isEqual(sha256(body), hash))
            throw new IllegalArgumentException("Ecology group record checksum does not match");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(body))) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION)
                throw new IllegalArgumentException("Unsupported ecology group format");
            UUID id = readUuid(input);
            UUID ownerId = readUuid(input);
            int memberCount = input.readUnsignedByte();
            if (memberCount < 1 || memberCount > EcologyGroupRecord.MAX_MEMBERS)
                throw new IllegalArgumentException("Ecology group member count is outside the allowed range");
            List<UUID> members = new ArrayList<>(memberCount);
            for (int index = 0; index < memberCount; index++) members.add(readUuid(input));
            int checkpointLength = input.readInt();
            if (checkpointLength < 8 || checkpointLength > EcologyCheckpointCodec.MAX_ENCODED_BYTES
                    || checkpointLength != input.available())
                throw new IllegalArgumentException("Ecology group checkpoint length is invalid");
            EcologyCheckpoint checkpoint = EcologyCheckpointCodec.decode(input.readNBytes(checkpointLength));
            if (input.available() != 0) throw new IllegalArgumentException("Trailing ecology group data");
            return new EcologyGroupRecord(id, ownerId, members, checkpoint);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Malformed ecology group record", failure);
        }
    }

    private static byte[] sha256(byte[] bytes) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(bytes);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
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
