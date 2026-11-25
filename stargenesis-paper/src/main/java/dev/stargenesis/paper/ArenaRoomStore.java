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
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

final class ArenaRoomStore {
    static final int MAX_ROOMS = 256;
    private static final long MAX_FILE_COUNT = MAX_ROOMS + 1L;
    private final Path directory;

    ArenaRoomStore(Path dataDirectory) {
        directory = dataDirectory.resolve("arena-matches").toAbsolutePath().normalize();
    }

    List<ArenaRoom.Data> loadAll() throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Arena match checkpoint path is not a directory");
        List<Path> paths;
        try (var files = Files.list(directory)) {
            paths = files.filter(path -> path.getFileName().toString().endsWith(".match"))
                    .limit(MAX_FILE_COUNT).sorted(Comparator.comparing(Path::toString)).toList();
        }
        if (paths.size() > MAX_ROOMS) throw new IOException("Arena match directory exceeds its finite room limit");
        return paths.stream().map(this::read).sorted(Comparator.comparing(data -> data.id().toString())).toList();
    }

    synchronized void save(ArenaRoom.Data room) throws IOException {
        Files.createDirectories(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Arena match checkpoint path is not a directory");
        Path target = path(room.id());
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            try (var files = Files.list(directory)) {
                long count = files.filter(candidate -> candidate.getFileName().toString().endsWith(".match"))
                        .limit(MAX_FILE_COUNT).count();
                if (count >= MAX_ROOMS) throw new IOException("Arena server room limit reached");
            }
        }
        byte[] encoded;
        try {
            encoded = ArenaRoomCheckpointCodec.encode(room);
        } catch (IllegalArgumentException failure) {
            throw new IOException("Arena checkpoint failed validation before save", failure);
        }
        Path temporary = Files.createTempFile(directory, "arena-v1-", ".tmp");
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
                throw new IOException("Arena checkpoint filesystem does not support atomic replacement", failure);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    synchronized void delete(UUID id) throws IOException {
        Path target = path(id);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Arena checkpoint is not a regular file");
        Files.deleteIfExists(target);
    }

    private ArenaRoom.Data read(Path path) {
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Arena checkpoint is not a regular file");
            long size = Files.size(path);
            if (size < 128 || size > ArenaRoomCheckpointCodec.MAX_ENCODED_BYTES)
                throw new IOException("Arena checkpoint size is outside the finite bounds");
            ArenaRoom.Data room = ArenaRoomCheckpointCodec.decode(Files.readAllBytes(path));
            if (!path.getFileName().toString().equals(room.id() + ".match"))
                throw new IOException("Arena checkpoint identity does not match its path");
            return room;
        } catch (IOException | IllegalArgumentException failure) {
            throw new IllegalStateException("Cannot restore arena checkpoint " + path.getFileName(), failure);
        }
    }

    private Path path(UUID id) { return directory.resolve(id + ".match"); }
}
