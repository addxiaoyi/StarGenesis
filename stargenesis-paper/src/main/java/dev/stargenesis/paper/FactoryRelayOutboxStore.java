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

final class FactoryRelayOutboxStore {
    static final int MAX_PENDING = 4096;
    private final Path directory;

    FactoryRelayOutboxStore(Path dataDirectory) {
        directory = dataDirectory.resolve("factory-relay").resolve("outbox").toAbsolutePath().normalize();
    }

    synchronized void save(UUID requestId, byte[] packet) throws IOException {
        if (requestId == null || packet == null || packet.length < 1
                || packet.length > FactoryRelayPacketCodec.MAX_PACKET_BYTES)
            throw new IllegalArgumentException("Invalid factory relay outbox record");
        ensureDirectory();
        Path target = recordPath(requestId);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Factory relay outbox record is not a regular file");
            long size = Files.size(target);
            if (size <= FactoryRelayPacketCodec.MAX_PACKET_BYTES
                    && Arrays.equals(Files.readAllBytes(target), packet)) return;
            throw new IOException("Factory relay request identity already has different content");
        }
        if (count() >= MAX_PENDING) throw new IllegalStateException("Factory relay outbox is full");
        Path temporary = Files.createTempFile(directory, requestId + ".", ".tmp");
        try {
            writeAndForce(temporary, packet);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException failure) {
                throw new IOException("Factory relay outbox filesystem does not support atomic creation", failure);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    synchronized List<PendingRequest> loadAll() throws IOException {
        ensureDirectory();
        List<Path> paths;
        try (Stream<Path> files = Files.list(directory)) {
            paths = files.filter(path -> path.getFileName().toString().matches(
                            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.bin"))
                    .limit(MAX_PENDING + 1L)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
        }
        if (paths.size() > MAX_PENDING) throw new IOException("Factory relay outbox exceeds its record budget");
        List<PendingRequest> records = new ArrayList<>(paths.size());
        for (Path path : paths) {
            String name = path.getFileName().toString();
            UUID requestId = UUID.fromString(name.substring(0, name.length() - 4));
            records.add(readRecord(path, requestId));
        }
        return List.copyOf(records);
    }

    synchronized Optional<PendingRequest> load(UUID requestId) throws IOException {
        if (requestId == null) throw new IllegalArgumentException("Missing factory relay request identity");
        ensureDirectory();
        Path path = recordPath(requestId);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        return Optional.of(readRecord(path, requestId));
    }

    synchronized boolean remove(UUID requestId) throws IOException {
        ensureDirectory();
        return Files.deleteIfExists(recordPath(requestId));
    }

    private void ensureDirectory() throws IOException {
        Files.createDirectories(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Factory relay outbox path is not a directory");
    }

    private long count() throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.filter(path -> path.getFileName().toString().matches(
                    "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.bin")).count();
        }
    }

    private Path recordPath(UUID requestId) {
        Path path = directory.resolve(requestId + ".bin").normalize();
        if (!path.getParent().equals(directory)) throw new IllegalArgumentException("Invalid factory request identity");
        return path;
    }

    private static PendingRequest readRecord(Path path, UUID requestId) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Factory relay outbox record is not a regular file");
        long size = Files.size(path);
        if (size < 1 || size > FactoryRelayPacketCodec.MAX_PACKET_BYTES)
            throw new IOException("Factory relay outbox record size is invalid");
        return new PendingRequest(requestId, Files.readAllBytes(path));
    }

    private static void writeAndForce(Path path, byte[] data) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }

    record PendingRequest(UUID requestId, byte[] packet) {
        PendingRequest { packet = packet.clone(); }
        @Override public byte[] packet() { return packet.clone(); }
    }
}
