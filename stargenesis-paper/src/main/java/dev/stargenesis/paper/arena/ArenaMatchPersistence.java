package dev.stargenesis.paper.arena;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.stargenesis.api.design.DesignId;
import dev.stargenesis.sim.ArenaAction;
import dev.stargenesis.sim.ArenaMatchReplay;

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
import java.util.Optional;
import java.util.UUID;

/** Bounded match replay and pending simultaneous-round action persistence. */
public final class ArenaMatchPersistence {
    public static final int SCHEMA_VERSION = 1;
    public static final long MAX_FILE_BYTES = 8L * 1024 * 1024;
    public static final int MAX_RETAINED_MATCHES = 128;
    private final Path directory;
    private final Path rosterFile;
    private final ObjectMapper mapper;

    public ArenaMatchPersistence(Path directory) throws IOException {
        this.directory = directory.toAbsolutePath().normalize();
        Files.createDirectories(this.directory);
        if (Files.isSymbolicLink(this.directory)) throw new IOException("Arena match directory symlink rejected");
        rosterFile = this.directory.resolve("teams.json");
        mapper = new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32)
                        .maxStringLength(8192).maxNumberLength(20).build()).build());
        mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS);
        mapper.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        mapper.disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    }

    public synchronized Optional<MatchSession> load(String matchId) throws IOException {
        Path file = path(matchId);
        if (!Files.exists(file)) return Optional.empty();
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file) || Files.size(file) > MAX_FILE_BYTES)
            throw new IOException("Arena match is not regular or exceeds 8 MiB");
        byte[] data;
        try (var input = Files.newInputStream(file)) { data = input.readNBytes((int) MAX_FILE_BYTES + 1); }
        if (data.length > MAX_FILE_BYTES) throw new IOException("Arena match exceeds 8 MiB");
        MatchSession session = mapper.readValue(data, MatchSession.class);
        if (session.schemaVersion() != SCHEMA_VERSION || session.replay() == null
                || !matchId.equals(session.replay().matchId())
                || !matchId.equals(dev.stargenesis.sim.ArenaMatchWorld.matchIdFor(
                session.replay().rulesetHash(), session.replay().teams())))
            throw new IOException("Arena match identity or schema mismatch");
        if (session.pending().size() > session.replay().teams().size()
                || session.pending().entrySet().stream().anyMatch(entry -> !session.replay().teams().containsKey(entry.getKey())
                || !entry.getKey().equals(entry.getValue().actor()))
                || session.spectators().size() > 128
                || session.spectators().stream().anyMatch(session.replay().teams()::containsKey)
                || session.spectators().stream().distinct().count() != session.spectators().size())
            throw new IOException("Arena pending action roster mismatch");
        return Optional.of(session);
    }

    public synchronized void save(MatchSession session) throws IOException {
        if (session.schemaVersion() != SCHEMA_VERSION || session.replay() == null
                || session.pending().size() > session.replay().teams().size())
            throw new IOException("Invalid Arena match session");
        Path target = path(session.replay().matchId());
        byte[] data = mapper.writeValueAsBytes(session);
        if (data.length > MAX_FILE_BYTES) throw new IOException("Arena match exceeds 8 MiB");
        atomicWrite(target, data);
    }

    public synchronized List<MatchSession> findForPlayer(UUID player) throws IOException {
        if (player == null) throw new IllegalArgumentException("Missing arena player");
        List<Path> files;
        try (var stream = Files.list(directory)) {
            files = stream.filter(file -> file.getFileName().toString().matches("arena-[0-9a-f]{32}\\.match\\.json"))
                    .sorted().limit(MAX_RETAINED_MATCHES + 1).toList();
        }
        if (files.size() > MAX_RETAINED_MATCHES) throw new IOException("Arena match catalog exceeds its budget");
        long totalBytes = 0;
        List<MatchSession> matches = new ArrayList<>();
        for (Path file : files) {
            totalBytes = Math.addExact(totalBytes, Files.size(file));
            if (totalBytes > 64L * 1024 * 1024) throw new IOException("Arena match scan exceeds 64 MiB");
            String name = file.getFileName().toString();
            String matchId = name.substring(0, name.length() - ".match.json".length());
            load(matchId).filter(session -> session.replay().teams().containsKey(player)
                    || session.spectators().contains(player)).ifPresent(matches::add);
        }
        return List.copyOf(matches);
    }

    public synchronized List<MatchSession> catalog() throws IOException {
        List<Path> files;
        try (var stream = Files.list(directory)) {
            files = stream.filter(file -> file.getFileName().toString().matches("arena-[0-9a-f]{32}\\.match\\.json"))
                    .sorted().limit(MAX_RETAINED_MATCHES + 1).toList();
        }
        if (files.size() > MAX_RETAINED_MATCHES) throw new IOException("Arena match catalog exceeds its budget");
        List<MatchSession> result = new ArrayList<>();
        for (Path file : files) result.add(load(file.getFileName().toString().substring(0,
                file.getFileName().toString().length() - ".match.json".length())).orElseThrow());
        return List.copyOf(result);
    }

    public synchronized boolean delete(String matchId) throws IOException {
        Path file = path(matchId);
        if (Files.isSymbolicLink(file)) throw new IOException("Arena match symlink path rejected");
        return Files.deleteIfExists(file);
    }

    public synchronized Map<UUID, String> loadTeamRoster() throws IOException {
        if (!Files.exists(rosterFile)) return Map.of();
        if (Files.isSymbolicLink(rosterFile) || !Files.isRegularFile(rosterFile) || Files.size(rosterFile) > 64 * 1024)
            throw new IOException("Arena team roster exceeds its regular-file budget");
        TeamRoster saved;
        byte[] data;
        try (var input = Files.newInputStream(rosterFile)) { data = input.readNBytes(64 * 1024 + 1); }
        if (data.length > 64 * 1024) throw new IOException("Arena team roster exceeds 64 KiB");
        saved = mapper.readValue(data, TeamRoster.class);
        if (saved.schemaVersion() != SCHEMA_VERSION || saved.teams().size() > 8
                || saved.teams().keySet().stream().anyMatch(java.util.Objects::isNull)
                || saved.teams().values().stream().anyMatch(team -> team == null || !team.matches("[a-z][a-z0-9_-]{0,31}"))
                || saved.teams().values().stream().collect(java.util.stream.Collectors.groupingBy(team -> team,
                java.util.stream.Collectors.counting())).values().stream().anyMatch(count -> count > 4))
            throw new IOException("Arena team roster schema or bounds are invalid");
        return Map.copyOf(saved.teams());
    }

    public synchronized void saveTeamRoster(Map<UUID, String> roster) throws IOException {
        if (roster == null || roster.size() > 8
                || roster.entrySet().stream().anyMatch(entry -> entry.getKey() == null || entry.getValue() == null
                || !entry.getValue().matches("[a-z][a-z0-9_-]{0,31}"))
                || roster.values().stream().collect(java.util.stream.Collectors.groupingBy(team -> team,
                java.util.stream.Collectors.counting())).values().stream().anyMatch(count -> count > 4))
            throw new IOException("Arena team roster exceeds its bounded player/team budget");
        byte[] data = mapper.writeValueAsBytes(new TeamRoster(SCHEMA_VERSION, roster));
        if (data.length > 64 * 1024) throw new IOException("Arena team roster exceeds 64 KiB");
        atomicWrite(rosterFile, data);
    }

    private Path path(String matchId) {
        if (matchId == null || !matchId.matches("arena-[0-9a-f]{32}"))
            throw new IllegalArgumentException("Invalid arena match ID");
        return directory.resolve(matchId + ".match.json");
    }

    private void atomicWrite(Path target, byte[] data) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        if (Files.isSymbolicLink(target) || Files.isSymbolicLink(temporary))
            throw new IOException("Arena match symlink path rejected");
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            var buffer = java.nio.ByteBuffer.wrap(data);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException failure) {
            Files.deleteIfExists(temporary);
            throw new IOException("Atomic Arena match persistence unavailable; previous match preserved", failure);
        }
    }

    public record MatchSession(int schemaVersion, ArenaMatchReplay replay, Map<UUID, ArenaAction> pending,
                               List<UUID> spectators) {
        public MatchSession {
            pending = Map.copyOf(pending);
            spectators = spectators.stream().distinct().sorted(Comparator.comparing(UUID::toString)).toList();
        }
        public static MatchSession initial(ArenaMatchReplay replay) { return new MatchSession(SCHEMA_VERSION, replay, Map.of(), List.of()); }
    }
    public record TeamRoster(int schemaVersion, Map<UUID, String> teams) {
        public TeamRoster { teams = Map.copyOf(teams); }
    }
}
