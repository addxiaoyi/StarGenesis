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
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

final class EcologyGroupStore {
    private final Path directory;

    EcologyGroupStore(Path dataDirectory) {
        directory = dataDirectory.resolve("ecology-groups").toAbsolutePath().normalize();
    }

    synchronized List<EcologyGroupRecord> loadAll() throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Ecology group checkpoint path is not a directory");
        List<Path> paths;
        try (Stream<Path> files = Files.list(directory)) {
            paths = files.filter(path -> path.getFileName().toString().matches(
                            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.eco"))
                    .limit(EcologyGroupCodec.MAX_GROUPS + 1L)
                    .sorted(Comparator.comparing(path -> path.getFileName().toString())).toList();
        }
        if (paths.size() > EcologyGroupCodec.MAX_GROUPS)
            throw new IOException("Ecology group directory exceeds its finite group limit");
        return paths.stream().map(this::read).toList();
    }

    synchronized void save(EcologyGroupRecord group) throws IOException {
        Files.createDirectories(directory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Ecology group checkpoint path is not a directory");
        Path target = path(group.id());
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            try (Stream<Path> files = Files.list(directory)) {
                long count = files.filter(candidate -> candidate.getFileName().toString().endsWith(".eco"))
                        .limit(EcologyGroupCodec.MAX_GROUPS + 1L).count();
                if (count >= EcologyGroupCodec.MAX_GROUPS)
                    throw new IOException("Ecology server group limit reached");
            }
        }
        byte[] encoded;
        try {
            encoded = EcologyGroupCodec.encode(group);
        } catch (IllegalArgumentException failure) {
            throw new IOException("Ecology group checkpoint failed validation before save", failure);
        }
        Path temporary = Files.createTempFile(directory, "ecology-group-v1-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(encoded);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException failure) {
                throw new IOException("Ecology group filesystem does not support atomic replacement", failure);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    synchronized void delete(UUID id) throws IOException {
        Path target = path(id);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return;
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Ecology group checkpoint is not a regular file");
        Files.delete(target);
    }

    Optional<EcologyGroupRecord> load(UUID id) throws IOException {
        Path target = path(id);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        return Optional.of(read(target));
    }

    private EcologyGroupRecord read(Path path) {
        try {
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Ecology group checkpoint is not a regular file");
            long size = Files.size(path);
            if (size < 73 || size > EcologyGroupCodec.MAX_ENCODED_BYTES)
                throw new IOException("Ecology group checkpoint size is outside the finite bounds");
            EcologyGroupRecord group = EcologyGroupCodec.decode(Files.readAllBytes(path));
            if (!path.getFileName().toString().equals(group.id() + ".eco"))
                throw new IOException("Ecology group checkpoint identity does not match its path");
            return group;
        } catch (IOException | IllegalArgumentException failure) {
            throw new IllegalStateException("Cannot restore ecology group checkpoint " + path.getFileName(), failure);
        }
    }

    private Path path(UUID id) {
        return directory.resolve(id + ".eco").normalize();
    }
}
