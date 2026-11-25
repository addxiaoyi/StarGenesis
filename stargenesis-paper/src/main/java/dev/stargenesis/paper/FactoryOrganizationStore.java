package dev.stargenesis.paper;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/** Atomically stores the small server-owned factory roster. */
final class FactoryOrganizationStore {
    private final Path checkpointPath;

    FactoryOrganizationStore(Path dataDirectory) {
        checkpointPath = dataDirectory.resolve("factory-organization-v1.checkpoint");
    }

    Optional<FactoryOrganization> load() throws IOException {
        if (!Files.exists(checkpointPath)) return Optional.empty();
        long size = Files.size(checkpointPath);
        if (size < 44 || size > FactoryOrganizationCodec.MAX_ENCODED_BYTES) {
            throw new IOException("Organization checkpoint file size is outside the finite bounds");
        }
        try {
            return Optional.of(FactoryOrganizationCodec.decode(Files.readAllBytes(checkpointPath)));
        } catch (IllegalArgumentException failure) {
            throw new IOException("Organization checkpoint failed validation", failure);
        }
    }

    void save(FactoryOrganization organization) throws IOException {
        byte[] encoded;
        try {
            encoded = FactoryOrganizationCodec.encode(organization);
        } catch (IllegalArgumentException failure) {
            throw new IOException("Organization checkpoint failed validation before save", failure);
        }
        Files.createDirectories(checkpointPath.getParent());
        Path temporary = Files.createTempFile(checkpointPath.getParent(), "factory-organization-v1-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(encoded);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.move(temporary, checkpointPath, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException failure) {
                throw new IOException("Organization checkpoint filesystem does not support atomic replacement", failure);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
