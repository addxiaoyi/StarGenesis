package dev.stargenesis.paper;

import dev.stargenesis.api.design.DesignDependency;
import dev.stargenesis.api.design.DesignId;
import dev.stargenesis.design.ArenaRuleset.Cell;
import dev.stargenesis.sim.ArenaMatchWorld.Action;
import dev.stargenesis.sim.ArenaMatchWorld.Checkpoint;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

final class ArenaRoomCheckpointCodec {
    static final int MAX_ENCODED_BYTES = 32_768;
    private static final int FORMAT = 1;
    private static final int HASH_BYTES = 32;

    private ArenaRoomCheckpointCodec() { }

    static byte[] encode(ArenaRoom.Data room) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(FORMAT);
                writeUuid(output, room.id());
                writeUuid(output, room.host());
                output.writeUTF(room.release().id().value());
                output.writeUTF(room.release().version());
                writeHash(output, room.release().hash());
                output.writeBoolean(room.started());
                writeCount(output, room.seats().size(), 64);
                for (var seat : new TreeMap<>(room.seats()).entrySet()) {
                    output.writeUTF(seat.getKey());
                    writeUuid(output, seat.getValue());
                }
                writeCount(output, room.pending().size(), 64);
                for (var action : new TreeMap<>(room.pending()).entrySet()) {
                    output.writeUTF(action.getKey());
                    output.writeByte(action.getValue().ordinal());
                }
                output.writeLong(room.world().tick());
                writeHash(output, room.world().stateHash());
                writeCount(output, room.world().positions().size(), 64);
                for (var position : new TreeMap<>(room.world().positions()).entrySet()) {
                    output.writeUTF(position.getKey());
                    output.writeInt(position.getValue().x());
                    output.writeInt(position.getValue().y());
                }
                writeCount(output, room.world().teamPoints().size(), 8);
                for (var team : new TreeMap<>(room.world().teamPoints()).entrySet()) {
                    output.writeUTF(team.getKey());
                    output.writeLong(team.getValue());
                }
            }
            byte[] payload = bytes.toByteArray();
            if (payload.length + HASH_BYTES > MAX_ENCODED_BYTES)
                throw new IllegalArgumentException("Arena checkpoint exceeds its finite byte budget");
            ByteArrayOutputStream envelope = new ByteArrayOutputStream(payload.length + HASH_BYTES);
            envelope.write(payload);
            envelope.write(MessageDigest.getInstance("SHA-256").digest(payload));
            return envelope.toByteArray();
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalStateException("Cannot encode arena room", failure);
        }
    }

    static ArenaRoom.Data decode(byte[] encoded) {
        if (encoded == null || encoded.length < 128 || encoded.length > MAX_ENCODED_BYTES)
            throw new IllegalArgumentException("Arena checkpoint size is outside the finite bounds");
        try {
            int payloadLength = encoded.length - HASH_BYTES;
            byte[] payload = java.util.Arrays.copyOf(encoded, payloadLength);
            byte[] actualHash = java.util.Arrays.copyOfRange(encoded, payloadLength, encoded.length);
            byte[] expectedHash = MessageDigest.getInstance("SHA-256").digest(payload);
            if (!MessageDigest.isEqual(actualHash, expectedHash))
                throw new IllegalArgumentException("Arena checkpoint checksum mismatch");

            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
                if (input.readInt() != FORMAT) throw new IllegalArgumentException("Unsupported arena checkpoint format");
                UUID id = readUuid(input);
                UUID host = readUuid(input);
                DesignDependency release = new DesignDependency(new DesignId(input.readUTF()), input.readUTF(), readHash(input));
                boolean started = input.readBoolean();
                Map<String, UUID> seats = new TreeMap<>();
                int seatCount = readCount(input, 64);
                for (int index = 0; index < seatCount; index++)
                    if (seats.putIfAbsent(input.readUTF(), readUuid(input)) != null)
                        throw new IllegalArgumentException("Duplicate arena seat");
                Map<String, Action> pending = new TreeMap<>();
                int pendingCount = readCount(input, 64);
                for (int index = 0; index < pendingCount; index++) {
                    String unit = input.readUTF();
                    int action = input.readUnsignedByte();
                    if (action >= Action.values().length || pending.putIfAbsent(unit, Action.values()[action]) != null)
                        throw new IllegalArgumentException("Invalid or duplicate arena action");
                }
                long tick = input.readLong();
                String stateHash = readHash(input);
                Map<String, Cell> positions = new TreeMap<>();
                int positionCount = readCount(input, 64);
                for (int index = 0; index < positionCount; index++) {
                    String unit = input.readUTF();
                    Cell position = new Cell(input.readInt(), input.readInt());
                    if (positions.putIfAbsent(unit, position) != null)
                        throw new IllegalArgumentException("Duplicate arena unit position");
                }
                Map<String, Long> teamPoints = new TreeMap<>();
                int teamCount = readCount(input, 8);
                for (int index = 0; index < teamCount; index++)
                    if (teamPoints.putIfAbsent(input.readUTF(), input.readLong()) != null)
                        throw new IllegalArgumentException("Duplicate arena team score");
                if (input.available() != 0) throw new IllegalArgumentException("Trailing bytes in arena checkpoint");
                return new ArenaRoom.Data(FORMAT, id, host, release, seats, pending, started,
                        new Checkpoint(tick, positions, teamPoints, stateHash));
            }
        } catch (IOException | NoSuchAlgorithmException failure) {
            throw new IllegalArgumentException("Malformed arena checkpoint", failure);
        }
    }

    private static void writeUuid(DataOutputStream output, UUID id) throws IOException {
        output.writeLong(id.getMostSignificantBits());
        output.writeLong(id.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }

    private static void writeHash(DataOutputStream output, String hash) throws IOException {
        if (hash == null || !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid arena hash");
        output.write(HexFormat.of().parseHex(hash));
    }

    private static String readHash(DataInputStream input) throws IOException {
        byte[] hash = input.readNBytes(HASH_BYTES);
        if (hash.length != HASH_BYTES) throw new IOException("Truncated arena hash");
        return HexFormat.of().formatHex(hash);
    }

    private static void writeCount(DataOutputStream output, int count, int max) throws IOException {
        if (count < 0 || count > max) throw new IllegalArgumentException("Arena checkpoint count exceeds its finite bounds");
        output.writeByte(count);
    }

    private static int readCount(DataInputStream input, int max) throws IOException {
        int count = input.readUnsignedByte();
        if (count > max) throw new IllegalArgumentException("Arena checkpoint count exceeds its finite bounds");
        return count;
    }
}
