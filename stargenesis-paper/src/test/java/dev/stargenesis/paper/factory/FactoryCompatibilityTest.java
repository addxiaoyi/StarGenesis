package dev.stargenesis.paper.factory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.stargenesis.sim.FactoryAction;
import dev.stargenesis.sim.FactoryConfig;
import dev.stargenesis.sim.FactoryInput;
import dev.stargenesis.sim.FactoryReplay;
import dev.stargenesis.sim.FactoryReplayVerifier;
import dev.stargenesis.sim.FactoryWorld;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FactoryCompatibilityTest {
    private static final List<UUID> PLAYERS = List.of(
            new UUID(0, 3001), new UUID(0, 3002), new UUID(0, 3003), new UUID(0, 3004));

    @Test void capturedV1PendingOrderResumesCompensationWithOriginalHashesAndWireShape(@TempDir Path directory) throws Exception {
        String id = FactoryWorld.matchIdFor(PLAYERS);
        Path file = directory.resolve(id + ".factory.json");
        try (var fixture = getClass().getResourceAsStream("/factory-v1-pending-order.json")) {
            assertNotNull(fixture);
            Files.copy(fixture, file);
        }
        var persistence = new FactoryPersistence(directory);
        var replay = persistence.load(id).orElseThrow();
        assertEquals(1, replay.schemaVersion());
        assertNull(replay.runId());
        assertEquals("d1c265179f23f6c6168ffb9622192667f6e23053f82382b455d63e55a79c4aeb", replay.initialStateHash());
        assertTrue(FactoryReplayVerifier.verify(replay).passed());
        try (var world = FactoryWorld.restore(replay)) {
            assertEquals(5, world.tick());
            assertEquals("RUNNING", world.view().orders().getFirst().state());
            assertEquals("aedb894b7af80a1411b8298886f057c9e2674c0216606ae0ddef67840e28f78f", world.stateHash());
            world.step(new FactoryInput(5, FactoryAction.pass(PLAYERS.get(3))));
            assertEquals("319e5e6d4e16c0b28b83d9d306470833a97160f8246f326aa7851714983b0a71", world.stateHash());
            assertEquals("FAILED_RECOVERED", world.view().orders().getFirst().state());
            assertEquals(85, world.view().organizations().get("org_ember").credits());
            assertEquals(16, world.view().organizations().get("org_ember").ore());
            replay = world.replay();
            persistence.save(replay);
        }
        assertEquals(replay, new FactoryPersistence(directory).load(id).orElseThrow());
        assertTrue(FactoryReplayVerifier.verify(replay).passed());
        assertFalse(new ObjectMapper().readTree(Files.readString(file)).path("replay").has("runId"));
    }

    @Test void v2RejectsUnknownMissingNullDuplicateTrailingAndMismatchedIdentityFields(@TempDir Path directory) throws Exception {
        var persistence = new FactoryPersistence(directory);
        var replay = current(1);
        persistence.save(replay);
        Path file = directory.resolve(replay.matchId() + ".factory.json");
        String valid = Files.readString(file);
        var mapper = new ObjectMapper();
        assertEquals(replay, new FactoryPersistence(directory).load(replay.matchId()).orElseThrow());
        for (String field : List.of("runId", "schemaVersion", "engineVersion", "initialStateHash", "seed", "frames", "participants", "config")) {
            ObjectNode document = (ObjectNode) mapper.readTree(valid);
            ((ObjectNode) document.path("replay")).remove(field);
            Files.writeString(file, document.toString());
            assertThrows(IOException.class, () -> persistence.load(replay.matchId()), field);
            ((ObjectNode) document.path("replay")).putNull(field);
            Files.writeString(file, document.toString());
            assertThrows(IOException.class, () -> persistence.load(replay.matchId()), "null " + field);
        }
        for (String invalid : List.of("", "null", "[]", valid + " {}",
                valid.replaceFirst("\\{", "{\"schemaVersion\":1,"),
                valid.replaceFirst("\\{", "{\"future\":true,"),
                valid.replace("\"schemaVersion\":2", "\"schemaVersion\":3"),
                valid.replace("\"seed\":88", "\"seed\":88.5"),
                valid.replace("\"seed\":88", "\"seed\":\"88\""),
                valid.replace("genesis-factory/2", "genesis-factory/1"),
                valid.replace(replay.runId(), new UUID(0, 999).toString()),
                valid.replace(PLAYERS.get(0).toString(), new UUID(0, 3999).toString()))) {
            Files.writeString(file, invalid);
            assertThrows(IOException.class, () -> persistence.load(replay.matchId()), invalid);
        }
        Path wrongPath = directory.resolve(current(2).matchId() + ".factory.json");
        Files.writeString(wrongPath, valid);
        assertThrows(IOException.class, () -> persistence.load(current(2).matchId()));
    }

    @Test void v1RejectsEvenNullRunIdAndWrongRosterDerivedFileIdentity(@TempDir Path directory) throws Exception {
        var persistence = new FactoryPersistence(directory);
        String id = FactoryWorld.matchIdFor(PLAYERS);
        try (var world = new FactoryWorld(id, PLAYERS, 88, FactoryConfig.defaults())) { persistence.save(world.replay()); }
        Path file = directory.resolve(id + ".factory.json");
        String valid = Files.readString(file);
        var document = (ObjectNode) new ObjectMapper().readTree(valid);
        ((ObjectNode) document.path("replay")).putNull("runId");
        Files.writeString(file, document.toString());
        assertThrows(IOException.class, () -> persistence.load(id));
        Files.writeString(file, valid.replace(PLAYERS.get(0).toString(), new UUID(0, 3999).toString()));
        assertThrows(IOException.class, () -> persistence.load(id));
        Files.writeString(file, valid);
        assertTrue(persistence.load(id).isPresent());
    }

    @Test void countBudgetRejectsNewMatchesButAllowsExistingGamesToProgress(@TempDir Path directory) throws Exception {
        var persistence = new FactoryPersistence(directory);
        for (int i = 0; i < FactoryPersistence.MAX_RETAINED_MATCHES; i++) persistence.save(current(i));
        var extra = current(FactoryPersistence.MAX_RETAINED_MATCHES);
        assertThrows(IOException.class, () -> persistence.save(extra));
        assertTrue(persistence.load(extra.matchId()).isEmpty());
        try (var world = FactoryWorld.restore(current(0))) {
            world.step(new FactoryInput(0, FactoryAction.pass(PLAYERS.getFirst())));
            persistence.save(world.replay());
            assertEquals(world.replay(), persistence.load(world.matchId()).orElseThrow());
        }
        assertEquals(FactoryPersistence.MAX_RETAINED_MATCHES, persistence.catalog().size());
    }

    @Test void aggregateBudgetChecksExistingAndProjectedBytesBeforeDecodingOrWriting(@TempDir Path directory) throws Exception {
        var persistence = new FactoryPersistence(directory);
        for (int i = 0; i < 9; i++) {
            try (var random = new java.io.RandomAccessFile(directory.resolve(current(i).matchId() + ".factory.json").toFile(), "rw")) {
                random.setLength(FactoryPersistence.MAX_FILE_BYTES);
            }
        }
        assertTrue(assertThrows(IOException.class, persistence::catalog).getMessage().contains("64 MiB"));
        assertTrue(assertThrows(IOException.class, () -> persistence.findForPlayer(PLAYERS.getFirst())).getMessage().contains("64 MiB"));
        var extra = current(99);
        assertTrue(assertThrows(IOException.class, () -> persistence.save(extra)).getMessage().contains("64 MiB"));
        assertTrue(persistence.load(extra.matchId()).isEmpty());
        Files.delete(directory.resolve(current(8).matchId() + ".factory.json"));
        assertTrue(assertThrows(IOException.class, () -> persistence.save(extra)).getMessage().contains("64 MiB"));
        persistence.save(current(0));
        assertEquals(current(0), persistence.load(current(0).matchId()).orElseThrow());
    }

    private FactoryReplay current(int run) {
        try (var world = new FactoryWorld(new UUID(0, run), PLAYERS, 88, FactoryConfig.defaults())) { return world.replay(); }
    }
}
