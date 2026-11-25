package dev.stargenesis.paper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

final class ProtocolRelayOutboxStore {
    static final int MAX_PENDING = 4096;
    private final Path directory;

    ProtocolRelayOutboxStore(Path dataDirectory) {
        directory = dataDirectory.resolve("protocol-relay").resolve("outbox").toAbsolutePath().normalize();
    }

    synchronized void save(UUID messageId, byte[] packet) throws IOException {
        if (messageId == null || packet == null || packet.length < 1
                || packet.length > ProtocolRelayPacketCodec.MAX_PACKET_BYTES)
            throw new IllegalArgumentException("Invalid protocol relay outbox record");
        ensureDirectory();
        Path target = recordPath(messageId);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Protocol relay outbox record is not a regular file");
            long size = Files.size(target);
            if (size <= ProtocolRelayPacketCodec.MAX_PACKET_BYTES
                    && Arrays.equals(Files.readAllBytes(target), packet)) return;
            throw new IOException("Protocol relay message identity already has different content");
        }
        if (count() >= MAX_PENDING)
            throw new IllegalStateException("Protocol relay outbox is full");
        Path temporary = Files.createTempFile(directory, messageId + ".", ".tmp");
        try {
            writeAndForce(temporary, packet);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException failure) {
                throw new IOException("Protocol relay outbox filesystem does not support atomic creation", failure);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    synchronized List<PendingRelay> loadAll() throws IOException {
        ensureDirectory();
        List<Path> paths;
        try (Stream<Path> files = Files.list(directory)) {
            paths = files.filter(path -> path.getFileName().toString().matches(
                            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.bin"))
                    .limit(MAX_PENDING + 1L)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
        }
        if (paths.size() > MAX_PENDING) throw new IOException("Protocol relay outbox exceeds its record budget");
        List<PendingRelay> records = new ArrayList<>(paths.size());
        for (Path path : paths) {
            String name = path.getFileName().toString();
            UUID id = UUID.fromString(name.substring(0, name.length() - 4));
            records.add(readRecord(path, id));
        }
        return List.copyOf(records);
    }

    synchronized Optional<PendingRelay> load(UUID messageId) throws IOException {
        if (messageId == null) throw new IllegalArgumentException("Missing relay message identity");
        ensureDirectory();
        Path path = recordPath(messageId);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        return Optional.of(readRecord(path, messageId));
    }

    synchronized boolean remove(UUID messageId) throws IOException {
        ensureDirectory();
        return Files.deleteIfExists(recordPath(messageId));
    }

    private void ensureDirectory() throws IOException {
        Files.createDirectories(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Protocol relay outbox path is not a directory");
    }

    private long count() throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().matches(
                    "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.bin")).count();
        }
    }

    private Path recordPath(UUID id) {
        Path path = directory.resolve(id + ".bin").normalize();
        if (!path.getParent().equals(directory)) throw new IllegalArgumentException("Invalid relay message identity");
        return path;
    }

    private static PendingRelay readRecord(Path path, UUID messageId) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Invalid protocol relay outbox record: " + path.getFileName());
        long size = Files.size(path);
        if (size < 1 || size > ProtocolRelayPacketCodec.MAX_PACKET_BYTES)
            throw new IOException("Invalid protocol relay outbox record: " + path.getFileName());
        return new PendingRelay(messageId, Files.readAllBytes(path));
    }

    private static void writeAndForce(Path path, byte[] data) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }

    record PendingRelay(UUID messageId, byte[] packet) {
        PendingRelay { packet = packet.clone(); }
        @Override public byte[] packet() { return packet.clone(); }
    }
}
