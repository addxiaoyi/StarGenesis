package dev.stargenesis.paper;

import dev.stargenesis.design.ArenaRuleset;
import dev.stargenesis.design.DesignCodec;
import dev.stargenesis.sim.ArenaMatchWorld.Action;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ArenaRoomStoreTest {
    @Test
    void atomicRoomCheckpointsRestoreRosterTurnsAndCanBeDeleted(@TempDir Path directory) throws Exception {
        ArenaRuleset ruleset = starter();
        UUID host = new UUID(0, 1);
        ArenaRoom room = new ArenaRoom(new UUID(0, 2), host, ruleset);
        room.join(host, "red:1");
        room.join(new UUID(0, 3), "red:2");
        room.join(new UUID(0, 4), "blue:1");
        room.join(new UUID(0, 5), "blue:2");
        room.start(host, 10);
        room.submit(host, 0, Action.WAIT, 10);

        ArenaRoomStore firstProcess = new ArenaRoomStore(directory);
        firstProcess.save(room.checkpoint());
        ArenaRoom.Data loaded = new ArenaRoomStore(directory).loadAll().getFirst();
        var pending = new ArenaMatchService.PendingRoom(loaded, ruleset);
        ArenaRoom restored = ArenaMatchService.restoreRooms(List.of(pending), 20).get(room.id());

        assertEquals(room.snapshot(), restored.snapshot());
        assertEquals(1, restored.submittedCount());
        ArenaRoom conflictingLobby = new ArenaRoom(new UUID(0, 6), host, ruleset);
        var conflicting = new ArenaMatchService.PendingRoom(conflictingLobby.checkpoint(), ruleset);
        assertThrows(java.io.IOException.class, () -> ArenaMatchService.restoreRooms(List.of(pending, conflicting), 20));
        firstProcess.delete(room.id());
        assertTrue(firstProcess.loadAll().isEmpty());
    }

    @Test
    void symlinkedCheckpointDirectoryIsRejected(@TempDir Path directory) throws Exception {
        Path outside = Files.createDirectory(directory.resolve("outside"));
        Path matches = directory.resolve("plugin-data").resolve("arena-matches");
        Files.createDirectories(matches.getParent());
        Files.createSymbolicLink(matches, outside);

        ArenaRoom room = new ArenaRoom(new UUID(0, 2), new UUID(0, 1), starter());
        assertThrows(java.io.IOException.class,
                () -> new ArenaRoomStore(matches.getParent()).save(room.checkpoint()));
    }

    private ArenaRuleset starter() throws Exception {
        try (var input = getClass().getResourceAsStream("/examples/arena_ruleset-v2.json")) {
            assertNotNull(input);
            return (ArenaRuleset) new DesignCodec().load(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
