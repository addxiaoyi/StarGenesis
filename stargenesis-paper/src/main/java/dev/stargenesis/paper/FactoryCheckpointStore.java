package dev.stargenesis.paper;

import dev.stargenesis.sim.FactoryCheckpoint;
import dev.stargenesis.sim.FactoryCheckpointCodec;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/** Bounded server checkpoint storage that only exposes complete atomic replacements. */
final class FactoryCheckpointStore {
    private final Path checkpointPath;
    private final FileSizeReader fileSizeReader;
    private final AtomicFileReplacer fileReplacer;

    FactoryCheckpointStore(Path dataDirectory) {
        this(dataDirectory, Files::size, FactoryCheckpointStore::replaceAtomically);
    }

    FactoryCheckpointStore(Path dataDirectory, AtomicFileReplacer fileReplacer) {
        this(dataDirectory, Files::size, fileReplacer);
    }

    FactoryCheckpointStore(Path dataDirectory, FileSizeReader fileSizeReader) {
        this(dataDirectory, fileSizeReader, FactoryCheckpointStore::replaceAtomically);
    }

    FactoryCheckpointStore(Path dataDirectory, FileSizeReader fileSizeReader, AtomicFileReplacer fileReplacer) {
        checkpointPath = dataDirectory.resolve("factory-v1.checkpoint");
        this.fileSizeReader = java.util.Objects.requireNonNull(fileSizeReader, "fileSizeReader");
        this.fileReplacer = java.util.Objects.requireNonNull(fileReplacer, "fileReplacer");
    }

    Optional<FactoryCheckpoint> load() throws IOException {
        if (!Files.exists(checkpointPath)) return Optional.empty();
        long size = fileSizeReader.size(checkpointPath);
        if (size < 8 || size > FactoryCheckpointCodec.MAX_ENCODED_BYTES) {
            throw new IOException("Factory checkpoint file size is outside the finite bounds");
        }
        try {
            return Optional.of(FactoryCheckpointCodec.decode(Files.readAllBytes(checkpointPath)));
        } catch (IllegalArgumentException failure) {
            throw new IOException("Factory checkpoint failed validation", failure);
        }
    }

    void save(FactoryCheckpoint checkpoint) throws IOException {
        byte[] encoded;
        try {
            encoded = FactoryCheckpointCodec.encode(checkpoint);
        } catch (IllegalArgumentException failure) {
            throw new IOException("Factory checkpoint failed validation before save", failure);
        }
        Files.createDirectories(checkpointPath.getParent());
        Path temporary = Files.createTempFile(checkpointPath.getParent(), "factory-v1-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(encoded);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            fileReplacer.replace(temporary, checkpointPath);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void replaceAtomically(Path temporary, Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException failure) {
            throw new IOException("Factory checkpoint filesystem does not support atomic replacement", failure);
        }
    }

    @FunctionalInterface
    interface AtomicFileReplacer {
        void replace(Path temporary, Path target) throws IOException;
    }

    @FunctionalInterface
    interface FileSizeReader {
        long size(Path path) throws IOException;
    }
}
