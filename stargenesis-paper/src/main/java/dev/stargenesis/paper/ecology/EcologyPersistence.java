package dev.stargenesis.paper.ecology;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.stargenesis.sim.EcologyReplay;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.UUID;

/** Bounded per-player ecology replay storage. Filesystem paths are derived only from player UUIDs. */
public final class EcologyPersistence {
    public static final int SCHEMA_VERSION = 1;
    public static final long MAX_FILE_BYTES = 4L * 1024 * 1024;
    private final Path directory;
    private final ObjectMapper mapper;

    public EcologyPersistence(Path directory) throws IOException {
        this.directory = directory.toAbsolutePath().normalize();
        Files.createDirectories(this.directory);
        if (Files.isSymbolicLink(this.directory)) throw new IOException("Ecology directory symlink rejected");
        mapper = new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32)
                        .maxStringLength(4096).maxNumberLength(20).build()).build());
        mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS);
        mapper.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        mapper.disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    }

    public synchronized Optional<EcologyReplay> load(UUID owner) throws IOException {
        Path file = path(owner);
        if (!Files.exists(file)) return Optional.empty();
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file) || Files.size(file) > MAX_FILE_BYTES)
            throw new IOException("Ecology replay is not a regular file or exceeds 4 MiB");
        byte[] data;
        try (var input = Files.newInputStream(file)) {
            data = input.readNBytes((int) MAX_FILE_BYTES + 1);
        }
        if (data.length > MAX_FILE_BYTES) throw new IOException("Ecology replay exceeds 4 MiB");
        PlayerReplay saved = mapper.readValue(data, PlayerReplay.class);
        if (saved == null || saved.schemaVersion() != SCHEMA_VERSION || !owner.equals(saved.owner()) || saved.replay() == null)
            throw new IOException("Ecology replay owner or schema mismatch");
        requireOwner(owner, saved.replay());
        return Optional.of(saved.replay());
    }

    public synchronized void save(UUID owner, EcologyReplay replay) throws IOException {
        requireOwner(owner, replay);
        Path file = path(owner);
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        byte[] data = mapper.writeValueAsBytes(new PlayerReplay(SCHEMA_VERSION, owner, replay));
        if (data.length > MAX_FILE_BYTES) throw new IOException("Ecology replay exceeds 4 MiB");
        if (Files.isSymbolicLink(file) || Files.isSymbolicLink(temporary))
            throw new IOException("Ecology replay symlink path rejected");
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            var buffer = java.nio.ByteBuffer.wrap(data);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException failure) {
            Files.deleteIfExists(temporary);
            throw new IOException("Atomic ecology persistence unavailable; previous replay preserved", failure);
        }
    }

    public synchronized boolean delete(UUID owner) throws IOException {
        Path file = path(owner);
        if (Files.isSymbolicLink(file)) throw new IOException("Ecology replay symlink path rejected");
        return Files.deleteIfExists(file);
    }

    private Path path(UUID owner) {
        if (owner == null) throw new IllegalArgumentException("Missing ecology owner");
        return directory.resolve(owner + ".ecology.json");
    }

    private void requireOwner(UUID owner, EcologyReplay replay) throws IOException {
        if (owner == null || replay == null || !("ecology-" + owner.toString().replace("-", "")).equals(replay.worldId()))
            throw new IOException("Ecology replay world does not belong to its owner");
    }

    public record PlayerReplay(int schemaVersion, UUID owner, EcologyReplay replay) { }
}
