package dev.stargenesis.paper;

import dev.stargenesis.integrations.SettlementCheckpoint;
import dev.stargenesis.integrations.SettlementPlan;
import dev.stargenesis.integrations.SettlementSagaStore;
import dev.stargenesis.integrations.SettlementTransfer;

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

/** Bounded checksummed journal for adapter-backed settlement sagas. */
final class SettlementSagaFileStore implements SettlementSagaStore {
    static final int MAX_RECORDS = 4_096;
    static final int MAX_FILE_BYTES = 20 * 1_024;
    private static final int MAGIC = 0x53475331;
    private static final int LEGACY_FORMAT = 1;
    private static final int FORMAT = 2;
    private static final int CHECKSUM_BYTES = 32;
    private final Path directory;

    SettlementSagaFileStore(Path dataDirectory) {
        directory = dataDirectory.resolve("settlement-sagas").toAbsolutePath().normalize();
    }

    @Override
    public synchronized Optional<SettlementCheckpoint> find(UUID id) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        requireDirectory();
        Path target = path(id);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        SettlementCheckpoint checkpoint = read(target);
        if (!checkpoint.plan().id().equals(id)) throw new IOException("Saga checkpoint identity does not match its path");
        return Optional.of(checkpoint);
    }

    @Override
    public synchronized List<SettlementCheckpoint> loadAll() throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
        requireDirectory();
        List<Path> files;
        try (Stream<Path> entries = Files.list(directory)) {
            files = entries.filter(file -> file.getFileName().toString().endsWith(".saga"))
                    .limit(MAX_RECORDS + 1L).sorted(Comparator.comparing(Path::toString)).toList();
        }
        if (files.size() > MAX_RECORDS) throw new IOException("Settlement saga journal exceeds its finite record limit");
        List<SettlementCheckpoint> checkpoints = new ArrayList<>(files.size());
        for (Path file : files) {
            SettlementCheckpoint checkpoint = read(file);
            if (!file.getFileName().toString().equals(checkpoint.plan().id() + ".saga"))
                throw new IOException("Saga checkpoint identity does not match its path");
            checkpoints.add(checkpoint);
        }
        return List.copyOf(checkpoints);
    }

    @Override
    public synchronized void save(SettlementCheckpoint checkpoint) throws IOException {
        Files.createDirectories(directory);
        requireDirectory();
        Path target = path(checkpoint.plan().id());
        boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        if (exists) {
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Settlement saga checkpoint is not a regular file");
            SettlementCheckpoint previous = read(target);
            validateNext(previous, checkpoint);
        } else {
            if (checkpoint.state() != SettlementCheckpoint.State.PREPARED || checkpoint.cursor() != 0
                    || checkpoint.revision() != 0)
                throw new IOException("A new settlement saga must start at revision zero");
            try (Stream<Path> files = Files.list(directory)) {
                long count = files.filter(file -> file.getFileName().toString().endsWith(".saga"))
                        .limit(MAX_RECORDS + 1L).count();
                if (count >= MAX_RECORDS) throw new IOException("Settlement saga journal is full");
            }
        }

        byte[] encoded;
        try {
            encoded = encode(checkpoint);
        } catch (IllegalArgumentException failure) {
            throw new IOException("Settlement saga checkpoint failed validation before save", failure);
        }
        Path temporary = Files.createTempFile(directory, "saga-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(encoded);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException failure) {
                throw new IOException("Settlement saga filesystem does not support atomic replacement", failure);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private void requireDirectory() throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Settlement saga journal path is not a directory");
    }

    private Path path(UUID id) {
        return directory.resolve(id + ".saga");
    }

    private static void validateNext(SettlementCheckpoint previous, SettlementCheckpoint next) throws IOException {
        if (!previous.plan().equals(next.plan()) || next.revision() != previous.revision() + 1)
            throw new IOException("Settlement saga identity or revision changed unexpectedly");
        int prior = previous.cursor();
        int cursor = next.cursor();
        var state = previous.state();
        var target = next.state();
        boolean valid = switch (state) {
            case PREPARED -> target == SettlementCheckpoint.State.APPLYING && cursor == 0;
            case APPLYING -> (target == SettlementCheckpoint.State.APPLYING && cursor == prior + 1)
                    || (target == SettlementCheckpoint.State.COMMITTED && prior == previous.plan().transfers().size()
                    && cursor == prior)
                    || (target == SettlementCheckpoint.State.COMPENSATING && cursor == prior)
                    || (target == SettlementCheckpoint.State.ABORTED && prior == 0 && cursor == 0)
                    || (target == SettlementCheckpoint.State.APPLY_REVIEW && cursor == prior);
            case COMPENSATING -> (target == SettlementCheckpoint.State.COMPENSATING && cursor == prior - 1)
                    || (target == SettlementCheckpoint.State.ABORTED && prior == 1 && cursor == 0)
                    || (target == SettlementCheckpoint.State.COMPENSATION_REVIEW && cursor == prior);
            case APPLY_REVIEW -> (target == SettlementCheckpoint.State.APPLYING && cursor == prior + 1)
                    || (target == SettlementCheckpoint.State.COMPENSATING && cursor == prior)
                    || (target == SettlementCheckpoint.State.ABORTED && prior == 0 && cursor == 0)
                    || (target == SettlementCheckpoint.State.APPLY_REVIEW && cursor == prior);
            case COMPENSATION_REVIEW -> (target == SettlementCheckpoint.State.COMPENSATING && cursor == prior - 1)
                    || (target == SettlementCheckpoint.State.ABORTED && prior == 1 && cursor == 0)
                    || (target == SettlementCheckpoint.State.COMPENSATING && cursor == prior)
                    || (target == SettlementCheckpoint.State.COMPENSATION_REVIEW && cursor == prior);
            case COMMITTED, ABORTED -> false;
        };
        if (!valid) throw new IOException("Invalid settlement saga state transition");
    }

    private static SettlementCheckpoint read(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Settlement saga checkpoint is not a regular file");
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            return decode(input.readNBytes(MAX_FILE_BYTES + 1));
        }
    }

    private static byte[] encode(SettlementCheckpoint checkpoint) {
        try {
            ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bodyBytes)) {
                output.writeInt(MAGIC);
                output.writeInt(FORMAT);
                writeUuid(output, checkpoint.plan().id());
                writeUuid(output, checkpoint.plan().initiator());
                output.writeUTF(checkpoint.plan().providerId());
                output.writeInt(checkpoint.plan().transfers().size());
                for (SettlementTransfer transfer : checkpoint.plan().transfers()) {
                    output.writeUTF(transfer.sourceAccount());
                    output.writeUTF(transfer.targetAccount());
                    output.writeUTF(transfer.assetId());
                    output.writeLong(transfer.amount());
                }
                output.writeByte(stateCode(checkpoint.state()));
                output.writeInt(checkpoint.cursor());
                output.writeLong(checkpoint.revision());
                output.writeUTF(checkpoint.diagnostic());
            }
            byte[] body = bodyBytes.toByteArray();
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(body);
            ByteArrayOutputStream encoded = new ByteArrayOutputStream(body.length + digest.length);
            encoded.write(body);
            encoded.write(digest);
            byte[] bytes = encoded.toByteArray();
            if (bytes.length > MAX_FILE_BYTES) throw new IllegalArgumentException("Settlement saga checkpoint exceeds its byte budget");
            return bytes;
        } catch (IOException | NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Cannot encode settlement saga checkpoint", impossible);
        }
    }

    private static SettlementCheckpoint decode(byte[] encoded) throws IOException {
        if (encoded.length < 64 || encoded.length > MAX_FILE_BYTES)
            throw new IOException("Settlement saga checkpoint size is outside its bounds");
        int bodyLength = encoded.length - CHECKSUM_BYTES;
        byte[] body = java.util.Arrays.copyOf(encoded, bodyLength);
        byte[] expected = java.util.Arrays.copyOfRange(encoded, bodyLength, encoded.length);
        try {
            if (!MessageDigest.isEqual(expected, MessageDigest.getInstance("SHA-256").digest(body)))
                throw new IOException("Settlement saga checkpoint checksum mismatch");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(body))) {
            int magic = input.readInt();
            int format = input.readInt();
            if (magic != MAGIC || (format != LEGACY_FORMAT && format != FORMAT))
                throw new IOException("Unknown settlement saga checkpoint format");
            UUID id = readUuid(input);
            UUID initiator = readUuid(input);
            String providerId = input.readUTF();
            int count = input.readInt();
            if (count < 1 || count > SettlementPlan.MAX_TRANSFERS)
                throw new IOException("Settlement saga transfer count is outside its bounds");
            List<SettlementTransfer> transfers = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                transfers.add(new SettlementTransfer(input.readUTF(), input.readUTF(), input.readUTF(), input.readLong()));
            }
            int stateCode = input.readUnsignedByte();
            SettlementCheckpoint.State state = format == LEGACY_FORMAT
                    ? legacyState(stateCode) : stateFromCode(stateCode);
            int cursor = input.readInt();
            long revision = input.readLong();
            String diagnostic = input.readUTF();
            if (input.available() != 0) throw new IOException("Settlement saga checkpoint has trailing data");
            return new SettlementCheckpoint(new SettlementPlan(id, initiator, providerId, transfers),
                    state, cursor, revision, diagnostic);
        } catch (IllegalArgumentException failure) {
            throw new IOException("Settlement saga checkpoint failed validation", failure);
        }
    }

    /** Stable wire codes are independent of enum declaration order and must never be reassigned. */
    private static int stateCode(SettlementCheckpoint.State state) {
        return switch (state) {
            case PREPARED -> 0x10;
            case APPLYING -> 0x20;
            case COMPENSATING -> 0x30;
            case APPLY_REVIEW -> 0x40;
            case COMPENSATION_REVIEW -> 0x50;
            case COMMITTED -> 0x60;
            case ABORTED -> 0x70;
        };
    }

    private static SettlementCheckpoint.State stateFromCode(int code) throws IOException {
        return switch (code) {
            case 0x10 -> SettlementCheckpoint.State.PREPARED;
            case 0x20 -> SettlementCheckpoint.State.APPLYING;
            case 0x30 -> SettlementCheckpoint.State.COMPENSATING;
            case 0x40 -> SettlementCheckpoint.State.APPLY_REVIEW;
            case 0x50 -> SettlementCheckpoint.State.COMPENSATION_REVIEW;
            case 0x60 -> SettlementCheckpoint.State.COMMITTED;
            case 0x70 -> SettlementCheckpoint.State.ABORTED;
            default -> throw new IOException("Unknown settlement saga state code");
        };
    }

    /** FORMAT=1 used these seven ordinals. Never decode it with the current enum's values(). */
    private static SettlementCheckpoint.State legacyState(int ordinal) throws IOException {
        return switch (ordinal) {
            case 0 -> SettlementCheckpoint.State.PREPARED;
            case 1 -> SettlementCheckpoint.State.APPLYING;
            case 2 -> SettlementCheckpoint.State.COMPENSATING;
            case 3 -> SettlementCheckpoint.State.APPLY_REVIEW;
            case 4 -> SettlementCheckpoint.State.COMPENSATION_REVIEW;
            case 5 -> SettlementCheckpoint.State.COMMITTED;
            case 6 -> SettlementCheckpoint.State.ABORTED;
            default -> throw new IOException("Unknown legacy settlement saga state");
        };
    }

    private static void writeUuid(DataOutputStream output, UUID id) throws IOException {
        output.writeLong(id.getMostSignificantBits());
        output.writeLong(id.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }
}
