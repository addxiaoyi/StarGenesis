package dev.stargenesis.paper.lab;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.stargenesis.sim.ReplayLog;
import dev.stargenesis.sim.WorldSnapshot;
import dev.stargenesis.sim.ResourceSandboxWorld;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.UUID;

/** Host-only bounded persistence. No player-supplied paths or polymorphic types are accepted. */
public final class LabPersistence {
    public static final long MAX_FILE_BYTES = 4L * 1024 * 1024;
    public static final int EVIDENCE_VERSION = 1;
    private final Path directory;
    private final ObjectMapper mapper;

    public LabPersistence(Path directory) throws IOException {
        this.directory = directory.toAbsolutePath().normalize();
        Files.createDirectories(this.directory);
        if (Files.isSymbolicLink(this.directory)) throw new IOException("Lab directory symlink rejected");
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

    /** Legacy standalone writers retained for existing host consumers. New sessions use saveEvidence. */
    public synchronized void saveSnapshot(UUID player, WorldSnapshot snapshot) throws IOException {
        save(path(player, "snapshot"), snapshot);
    }
    public synchronized Optional<WorldSnapshot> loadSnapshot(UUID player) throws IOException {
        var evidence = loadEvidence(player);
        if (evidence.isPresent()) return Optional.of(evidence.get().snapshot());
        return load(path(player, "snapshot"), WorldSnapshot.class);
    }
    public synchronized void saveReplay(UUID player, ReplayLog replay) throws IOException { save(path(player, "replay"), replay); }
    public synchronized Optional<ReplayLog> loadReplay(UUID player) throws IOException {
        var evidence = loadEvidence(player);
        if (evidence.isPresent()) {
            WorldSnapshot snapshot = evidence.get().snapshot();
            return Optional.of(new ReplayLog(snapshot.engineVersion(), snapshot.schemaVersion(), snapshot.worldId(),
                    snapshot.seed(), snapshot.config(), snapshot.history()));
        }
        return load(path(player, "replay"), ReplayLog.class);
    }

    /** Snapshot already contains its complete replay: one replacement publishes both views together. */
    public synchronized void saveEvidence(UUID player, WorldSnapshot snapshot) throws IOException {
        if (snapshot == null) throw new IOException("Missing lab checkpoint");
        save(path(player, "lab"), new Evidence(EVIDENCE_VERSION, player, snapshot));
    }

    public synchronized Optional<Evidence> loadEvidence(UUID player) throws IOException {
        var loaded = load(path(player, "lab"), Evidence.class);
        if (loaded.isEmpty()) return loaded;
        var evidence = loaded.get();
        if (evidence.schemaVersion() != EVIDENCE_VERSION || !player.equals(evidence.owner()) || evidence.snapshot() == null)
            throw new IOException("Lab evidence owner or schema mismatch");
        try (var restored = ResourceSandboxWorld.restore(evidence.snapshot())) { restored.view(); }
        catch (RuntimeException invalid) { throw new IOException("Lab evidence cannot reproduce its checkpoint", invalid); }
        return loaded;
    }

    public record Evidence(int schemaVersion, UUID owner, WorldSnapshot snapshot) { }

    private Path path(UUID player, String kind) {
        if (player == null) throw new IllegalArgumentException("Missing player ID");
        return directory.resolve(player + "." + kind + ".json");
    }
    private <T> Optional<T> load(Path file, Class<T> type) throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        if (!Files.isRegularFile(file) || Files.isSymbolicLink(file) || Files.size(file) > MAX_FILE_BYTES)
            throw new IOException("Lab evidence file is not regular or exceeds 4 MiB");
        // Bound the actual read as well as the initial size check (defends replacement/growth races).
        byte[] data;
        try (var input = Files.newInputStream(file)) { data = input.readNBytes((int) MAX_FILE_BYTES + 1); }
        if (data.length > MAX_FILE_BYTES) throw new IOException("Lab evidence file exceeds 4 MiB");
        T value = mapper.readValue(data, type);
        if (value == null) throw new IOException("Missing lab evidence document");
        return Optional.of(value);
    }
    private void save(Path file, Object value) throws IOException {
        if (value == null) throw new IOException("Missing lab evidence");
        byte[] data = mapper.writeValueAsBytes(value);
        if (data.length > MAX_FILE_BYTES) throw new IOException("Lab evidence exceeds 4 MiB; session stopped");
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        if (Files.isSymbolicLink(file) || Files.isSymbolicLink(temporary)) throw new IOException("Symlink evidence path rejected");
        try (var channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            var buffer = java.nio.ByteBuffer.wrap(data);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException failure) {
            Files.deleteIfExists(temporary);
            throw new IOException("Atomic evidence persistence unavailable; previous checkpoint preserved", failure);
        }
    }
}
