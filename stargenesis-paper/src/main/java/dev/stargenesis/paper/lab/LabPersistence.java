package dev.stargenesis.paper.lab;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.stargenesis.sim.ReplayLog;
import dev.stargenesis.sim.WorldSnapshot;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;
import java.util.UUID;

/** Host-only bounded persistence. No player-supplied paths or polymorphic types are accepted. */
public final class LabPersistence {
    public static final long MAX_FILE_BYTES = 4L * 1024 * 1024;
    private final Path directory;
    private final ObjectMapper mapper;

    public LabPersistence(Path directory) throws IOException {
        this.directory = directory.toAbsolutePath().normalize();
        Files.createDirectories(this.directory);
        mapper = new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(40)
                        .maxStringLength(4096).maxNumberLength(20).build()).build());
        mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS);
        mapper.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        mapper.disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    }

    public void saveSnapshot(UUID player, WorldSnapshot snapshot) throws IOException {
        save(path(player, "snapshot"), snapshot);
    }
    public Optional<WorldSnapshot> loadSnapshot(UUID player) throws IOException {
        return load(path(player, "snapshot"), WorldSnapshot.class);
    }
    public void saveReplay(UUID player, ReplayLog replay) throws IOException { save(path(player, "replay"), replay); }
    public Optional<ReplayLog> loadReplay(UUID player) throws IOException { return load(path(player, "replay"), ReplayLog.class); }

    private Path path(UUID player, String kind) {
        if (player == null) throw new IllegalArgumentException("Missing player ID");
        return directory.resolve(player + "." + kind + ".json");
    }
    private <T> Optional<T> load(Path file, Class<T> type) throws IOException {
        if (!Files.exists(file)) return Optional.empty();
        if (!Files.isRegularFile(file) || Files.isSymbolicLink(file) || Files.size(file) > MAX_FILE_BYTES)
            throw new IOException("Lab evidence file is not regular or exceeds 4 MiB");
        // Bound the actual read as well as the initial size check (defends replacement/growth races).
        byte[] data;
        try (var input = Files.newInputStream(file)) { data = input.readNBytes((int) MAX_FILE_BYTES + 1); }
        if (data.length > MAX_FILE_BYTES) throw new IOException("Lab evidence file exceeds 4 MiB");
        return Optional.of(mapper.readValue(data, type));
    }
    private void save(Path file, Object value) throws IOException {
        byte[] data = mapper.writeValueAsBytes(value);
        if (data.length > MAX_FILE_BYTES) throw new IOException("Lab evidence exceeds 4 MiB; session stopped");
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        if (Files.isSymbolicLink(file) || Files.isSymbolicLink(temporary)) throw new IOException("Symlink evidence path rejected");
        Files.write(temporary, data);
        try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException failure) {
            Files.deleteIfExists(temporary);
            throw new IOException("Atomic evidence persistence unavailable; previous checkpoint preserved", failure);
        }
    }
}
