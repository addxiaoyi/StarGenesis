package dev.stargenesis.paper;

import dev.stargenesis.sim.EcologyWorld;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EcologyGroupStoreTest {
    @TempDir Path temporaryDirectory;

    @Test
    void groupCheckpointAndRosterSurviveStoreReload() throws IOException {
        UUID groupId = UUID.fromString("00000000-0000-0000-0000-000000000300");
        UUID owner = UUID.fromString("00000000-0000-0000-0000-000000000301");
        UUID member = UUID.fromString("00000000-0000-0000-0000-000000000302");
        EcologyGroupRecord group;
        try (var world = new EcologyWorld(EcologySession.sharedSettings(groupId))) {
            world.step();
            group = new EcologyGroupRecord(groupId, owner, List.of(owner, member), world.checkpoint());
        }
        EcologyGroupStore store = new EcologyGroupStore(temporaryDirectory);

        store.save(group);

        assertEquals(List.of(group), new EcologyGroupStore(temporaryDirectory).loadAll());
    }

    @Test
    void corruptedGroupFileIsRejectedOnReload() throws IOException {
        UUID groupId = UUID.fromString("00000000-0000-0000-0000-000000000310");
        UUID owner = UUID.fromString("00000000-0000-0000-0000-000000000311");
        EcologyGroupRecord group;
        try (var world = new EcologyWorld(EcologySession.sharedSettings(groupId))) {
            group = new EcologyGroupRecord(groupId, owner, List.of(owner), world.checkpoint());
        }
        EcologyGroupStore store = new EcologyGroupStore(temporaryDirectory);
        store.save(group);
        Path saved = temporaryDirectory.resolve("ecology-groups").resolve(groupId + ".eco");
        byte[] damaged = Files.readAllBytes(saved);
        damaged[damaged.length - 1] ^= 1;
        Files.write(saved, damaged);

        assertThrows(IllegalStateException.class, () -> new EcologyGroupStore(temporaryDirectory).loadAll());
    }

    @Test
    void deletingGroupCheckpointReleasesItsPersistentRecord() throws IOException {
        UUID groupId = UUID.fromString("00000000-0000-0000-0000-000000000320");
        UUID owner = UUID.fromString("00000000-0000-0000-0000-000000000321");
        EcologyGroupRecord group;
        try (var world = new EcologyWorld(EcologySession.sharedSettings(groupId))) {
            group = new EcologyGroupRecord(groupId, owner, List.of(owner), world.checkpoint());
        }
        EcologyGroupStore store = new EcologyGroupStore(temporaryDirectory);
        store.save(group);

        store.delete(groupId);

        assertTrue(store.loadAll().isEmpty());
        assertTrue(store.load(groupId).isEmpty());
        store.delete(groupId);
    }
}
