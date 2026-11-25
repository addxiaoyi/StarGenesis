package dev.stargenesis.paper.factory;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.stargenesis.sim.FactoryReplay;
import dev.stargenesis.sim.FactoryWorld;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Optional;
import java.util.UUID;

/** Versioned, bounded factory match replay storage keyed by a server-derived match ID. */
public final class FactoryPersistence {
    public static final int SCHEMA_VERSION = 1;
    public static final long MAX_FILE_BYTES = 8L * 1024 * 1024;
    public static final int MAX_ORGANIZATIONS = 64;
    private static final long MAX_INDEX_SCAN_BYTES = 64L * 1024 * 1024;
    private final Path directory;
    private final Path organizationsFile;
    private final ObjectMapper mapper;

    public FactoryPersistence(Path directory) throws IOException {
        this.directory = directory.toAbsolutePath().normalize();
        Files.createDirectories(this.directory);
        organizationsFile = this.directory.resolve("organizations.json");
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

    public Optional<FactoryReplay> load(String matchId) throws IOException {
        Path file = path(matchId);
        if (!Files.exists(file)) return Optional.empty();
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file) || Files.size(file) > MAX_FILE_BYTES)
            throw new IOException("Factory replay is not a regular file or exceeds 8 MiB");
        byte[] data;
        try (var input = Files.newInputStream(file)) {
            data = input.readNBytes((int) MAX_FILE_BYTES + 1);
        }
        if (data.length > MAX_FILE_BYTES) throw new IOException("Factory replay exceeds 8 MiB");
        MatchReplay saved = mapper.readValue(data, MatchReplay.class);
        if (saved.schemaVersion() != SCHEMA_VERSION || saved.replay() == null
                || !matchId.equals(saved.replay().matchId())
                || !matchId.equals(FactoryWorld.matchIdFor(saved.replay().participants())))
            throw new IOException("Factory replay identity or schema mismatch");
        return Optional.of(saved.replay());
    }

    public List<FactoryReplay> findForPlayer(UUID player) throws IOException {
        if (player == null) throw new IllegalArgumentException("Missing factory player");
        List<Path> files;
        try (var paths = Files.list(directory)) {
            files = paths.filter(path -> path.getFileName().toString().matches("factory-[0-9a-f]{32}\\.factory\\.json"))
                    .sorted().limit(257).toList();
        }
        if (files.size() > 256) throw new IOException("Factory replay directory exceeds its 256-match budget");
        List<FactoryReplay> matches = new ArrayList<>();
        long scanBytes = 0;
        for (Path file : files) {
            scanBytes = Math.addExact(scanBytes, Files.size(file));
            if (scanBytes > MAX_INDEX_SCAN_BYTES)
                throw new IOException("Factory replay lookup exceeds its 64 MiB scan budget");
            String name = file.getFileName().toString();
            String matchId = name.substring(0, name.length() - ".factory.json".length());
            Optional<FactoryReplay> replay = load(matchId);
            replay.filter(value -> value.participants().contains(player)).ifPresent(matches::add);
        }
        if (matches.size() > 1) throw new IOException("Player is present in multiple factory matches");
        return List.copyOf(matches);
    }

    public Map<UUID, UUID> loadOrganizations() throws IOException {
        if (!Files.exists(organizationsFile)) return Map.of();
        if (Files.isSymbolicLink(organizationsFile) || !Files.isRegularFile(organizationsFile)
                || Files.size(organizationsFile) > 256 * 1024)
            throw new IOException("Factory organization file is not regular or exceeds 256 KiB");
        byte[] data;
        try (var input = Files.newInputStream(organizationsFile)) { data = input.readNBytes(256 * 1024 + 1); }
        if (data.length > 256 * 1024) throw new IOException("Factory organization file exceeds 256 KiB");
        Organizations saved = mapper.readValue(data, Organizations.class);
        if (saved.schemaVersion() != SCHEMA_VERSION || saved.pairs().size() > MAX_ORGANIZATIONS)
            throw new IOException("Factory organization schema or count is invalid");
        Map<UUID, UUID> result = new HashMap<>();
        for (OrganizationPair pair : saved.pairs()) {
            if (pair.first() == null || pair.second() == null || pair.first().equals(pair.second())
                    || result.containsKey(pair.first()) || result.containsKey(pair.second()))
                throw new IOException("Factory organization contains invalid or overlapping members");
            result.put(pair.first(), pair.second());
            result.put(pair.second(), pair.first());
        }
        return Map.copyOf(result);
    }

    public void saveOrganizations(Map<UUID, UUID> partners) throws IOException {
        if (partners == null || partners.size() % 2 != 0 || partners.size() / 2 > MAX_ORGANIZATIONS)
            throw new IOException("Factory organization count exceeds its budget");
        Set<UUID> written = new HashSet<>();
        List<OrganizationPair> pairs = new ArrayList<>();
        for (Map.Entry<UUID, UUID> entry : partners.entrySet().stream()
                .sorted(Comparator.comparing(value -> value.getKey().toString())).toList()) {
            UUID first = entry.getKey();
            UUID second = entry.getValue();
            if (first == null || second == null || first.equals(second) || !first.equals(partners.get(second)))
                throw new IllegalArgumentException("Factory organization roster is not symmetric");
            if (!written.add(first)) continue;
            if (!written.add(second)) throw new IllegalArgumentException("Factory organization member is repeated");
            pairs.add(first.toString().compareTo(second.toString()) < 0
                    ? new OrganizationPair(first, second) : new OrganizationPair(second, first));
        }
        if (written.size() != partners.size()) throw new IOException("Factory organization roster is incomplete");
        byte[] data = mapper.writeValueAsBytes(new Organizations(SCHEMA_VERSION, List.copyOf(pairs)));
        if (data.length > 256 * 1024) throw new IOException("Factory organization file exceeds 256 KiB");
        atomicWrite(organizationsFile, data, "Factory organization");
    }

    public void save(FactoryReplay replay) throws IOException {
        Path file = path(replay.matchId());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        byte[] data = mapper.writeValueAsBytes(new MatchReplay(SCHEMA_VERSION, replay));
        if (data.length > MAX_FILE_BYTES) throw new IOException("Factory replay exceeds 8 MiB");
        atomicWrite(file, data, "Factory replay");
    }

    private void atomicWrite(Path file, byte[] data, String label) throws IOException {
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        if (Files.isSymbolicLink(file) || Files.isSymbolicLink(temporary))
            throw new IOException(label + " symlink path rejected");
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
            throw new IOException("Atomic " + label.toLowerCase() + " persistence unavailable; previous file preserved", failure);
        }
    }

    public boolean delete(String matchId) throws IOException {
        Path file = path(matchId);
        if (Files.isSymbolicLink(file)) throw new IOException("Factory replay symlink path rejected");
        return Files.deleteIfExists(file);
    }

    private Path path(String matchId) {
        if (matchId == null || !matchId.matches("factory-[0-9a-f]{32}"))
            throw new IllegalArgumentException("Invalid factory match ID");
        return directory.resolve(matchId + ".factory.json");
    }

    public record MatchReplay(int schemaVersion, FactoryReplay replay) { }
    public record OrganizationPair(UUID first, UUID second) { }
    public record Organizations(int schemaVersion, List<OrganizationPair> pairs) {
        public Organizations { pairs = List.copyOf(pairs); }
    }
}
