package dev.stargenesis.paper.arena;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.stargenesis.sim.ArenaAction;
import dev.stargenesis.sim.ArenaConfig;
import dev.stargenesis.sim.ArenaMatchReplayVerifier;
import dev.stargenesis.sim.ArenaMatchWorld;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ArenaMatchCompatibilityTest {
    private static final Map<UUID, String> TEAMS = Map.of(new UUID(0, 701), "cedar", new UUID(0, 702), "cedar",
            new UUID(0, 703), "ember", new UUID(0, 704), "ember");
    private static final String HASH = "b".repeat(64);

    @Test void capturedV1SessionKeepsItsOriginalHashesAndWireShapeAfterResume(@TempDir Path directory) throws Exception {
        String id = ArenaMatchWorld.matchIdFor(HASH, TEAMS);
        Path file = directory.resolve(id + ".match.json");
        try (var fixture = getClass().getResourceAsStream("/arena-match-v1.json")) {
            assertNotNull(fixture);
            Files.copy(fixture, file);
        }
        ArenaMatchPersistence persistence = new ArenaMatchPersistence(directory);
        var restored = persistence.load(id).orElseThrow();
        assertEquals(1, restored.replay().schemaVersion());
        assertNull(restored.replay().runId());
        assertEquals("4ee94ba757a217afa18a9d26f3c55345fb9b78ef889ee0ec16491133af60f6ac", restored.replay().initialStateHash());
        assertTrue(ArenaMatchReplayVerifier.verify(restored.replay()).passed());
        try (var world = ArenaMatchWorld.restore(restored.replay())) {
            assertEquals("a6f48fe08b0e997d2f806e5314f612651534c213124eff01c45abcebfbd3f18b", world.stateHash());
        }
        assertEquals(List.of(new UUID(0, 799)), restored.spectators());
        for (UUID player : List.of(new UUID(0, 701), new UUID(0, 703), new UUID(0, 704)))
            restored = ArenaMatchRound.submit(restored, player, ArenaAction.pass(player)).session();
        persistence.save(restored);
        var restarted = new ArenaMatchPersistence(directory).load(id).orElseThrow();
        assertEquals(restored, restarted);
        assertEquals(2, restarted.replay().frames().size());
        assertTrue(ArenaMatchReplayVerifier.verify(restarted.replay()).passed());
        assertFalse(new ObjectMapper().readTree(Files.readString(file)).path("replay").has("runId"));
    }

    @Test void v2RequiresRunIdAndRejectsFutureMissingDuplicateAndTrailingFields(@TempDir Path directory) throws Exception {
        var persistence = new ArenaMatchPersistence(directory);
        var session = current(1);
        persistence.save(session);
        Path file = directory.resolve(session.replay().matchId() + ".match.json");
        String valid = Files.readString(file);
        var mapper = new ObjectMapper();
        assertEquals(session, new ArenaMatchPersistence(directory).load(session.replay().matchId()).orElseThrow());
        for (String field : List.of("runId", "initialStateHash", "schemaVersion", "engineVersion")) {
            ObjectNode document = (ObjectNode) mapper.readTree(valid);
            ((ObjectNode) document.path("replay")).remove(field);
            Files.writeString(file, document.toString());
            assertThrows(IOException.class, () -> persistence.load(session.replay().matchId()), field);
        }
        for (String invalid : List.of("", "null", "[]", valid + " {}", valid.replaceFirst("\\{", "{\"schemaVersion\":1,"),
                valid.replace("\"schemaVersion\":2", "\"schemaVersion\":3"),
                valid.replace("\"runId\":\"" + session.replay().runId() + "\"", "\"runId\":null"),
                valid.replace("genesis-arena-match/2", "genesis-arena-match/1"),
                valid.replace(session.replay().runId(), new UUID(0, 999).toString()))) {
            Files.writeString(file, invalid);
            assertThrows(IOException.class, () -> persistence.load(session.replay().matchId()));
        }
    }

    @Test void writesRespectCatalogCountButAllowExistingMatchesToProgress(@TempDir Path directory) throws Exception {
        var persistence = new ArenaMatchPersistence(directory);
        for (int i = 0; i < ArenaMatchPersistence.MAX_RETAINED_MATCHES; i++) persistence.save(current(i));
        var extra = current(ArenaMatchPersistence.MAX_RETAINED_MATCHES);
        assertThrows(IOException.class, () -> persistence.save(extra));
        assertTrue(persistence.load(extra.replay().matchId()).isEmpty());
        var existing = current(0);
        UUID player = new UUID(0, 701);
        var partial = ArenaMatchRound.submit(existing, player, ArenaAction.pass(player)).session();
        persistence.save(partial);
        assertEquals(partial, persistence.load(existing.replay().matchId()).orElseThrow());
        assertEquals(ArenaMatchPersistence.MAX_RETAINED_MATCHES, persistence.catalog().size());
    }

    @Test void aggregateBytesAreCheckedBeforeCatalogDeserializationOrNewWrites(@TempDir Path directory) throws Exception {
        var persistence = new ArenaMatchPersistence(directory);
        // Sparse files exercise the preflight limit without retaining 72 MiB of JSON in the test JVM.
        for (int i = 0; i < 9; i++) {
            Path file = directory.resolve(current(i).replay().matchId() + ".match.json");
            try (var random = new java.io.RandomAccessFile(file.toFile(), "rw")) {
                random.setLength(ArenaMatchPersistence.MAX_FILE_BYTES);
            }
        }
        assertTrue(assertThrows(IOException.class, persistence::catalog).getMessage().contains("64 MiB"));
        assertTrue(assertThrows(IOException.class, () -> persistence.findForPlayer(new UUID(0, 701))).getMessage().contains("64 MiB"));
        var extra = current(99);
        assertTrue(assertThrows(IOException.class, () -> persistence.save(extra)).getMessage().contains("64 MiB"));
        assertTrue(persistence.load(extra.replay().matchId()).isEmpty());
        // The existing catalog is now exactly 64 MiB. Check the projected write size as well.
        Files.delete(directory.resolve(current(8).replay().matchId() + ".match.json"));
        assertTrue(assertThrows(IOException.class, () -> persistence.save(extra)).getMessage().contains("64 MiB"));
        var smallerReplacement = current(0);
        persistence.save(smallerReplacement);
        assertEquals(smallerReplacement, persistence.load(smallerReplacement.replay().matchId()).orElseThrow());
    }

    @Test void savingMalformedMembershipNeverOverwritesThePreviousSession(@TempDir Path directory) throws Exception {
        var persistence = new ArenaMatchPersistence(directory);
        var valid = current(0);
        persistence.save(valid);
        UUID player = new UUID(0, 701), observer = new UUID(0, 799);
        for (var invalid : List.of(
                new ArenaMatchPersistence.MatchSession(1, valid.replay(), Map.of(player, ArenaAction.pass(observer)), List.of()),
                new ArenaMatchPersistence.MatchSession(1, valid.replay(), Map.of(observer, ArenaAction.pass(observer)), List.of()),
                new ArenaMatchPersistence.MatchSession(1, valid.replay(), Map.of(), List.of(player)),
                new ArenaMatchPersistence.MatchSession(1, valid.replay(), Map.of(), List.of(observer, observer)))) {
            assertThrows(IOException.class, () -> persistence.save(invalid));
            assertEquals(valid, persistence.load(valid.replay().matchId()).orElseThrow());
        }
    }

    private ArenaMatchPersistence.MatchSession current(int run) {
        try (var world = new ArenaMatchWorld(new UUID(0, run), 88, new ArenaConfig(8, 8, 10, 4),
                "public:arena", "1.0.0", HASH, TEAMS)) {
            return ArenaMatchPersistence.MatchSession.initial(world.replay());
        }
    }
}
