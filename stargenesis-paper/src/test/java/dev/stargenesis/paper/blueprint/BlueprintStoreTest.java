package dev.stargenesis.paper.blueprint;

import dev.stargenesis.api.design.DesignId;
import dev.stargenesis.design.Blueprint;
import dev.stargenesis.design.BlueprintWorkspace;
import dev.stargenesis.design.DesignCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BlueprintStoreTest {
    @TempDir Path directory;

    @Test void savesAndReloadsEditedBlueprintWithOptimisticConflictCheck() throws Exception {
        UUID owner = new UUID(0, 11);
        DesignId id = new DesignId("player:starter");
        BlueprintStore store = new BlueprintStore(directory, new DesignCodec());
        Blueprint initial = store.save(owner, store.newBlueprint(owner, id), "");
        BlueprintWorkspace workspace = new BlueprintWorkspace(initial);
        workspace.add(new Blueprint.Component("solar", "genesis:solar_panel", 1, 0, 0, 0), "core");
        Blueprint updated = store.save(owner, workspace.current(), initial.metadata().hash());

        assertNotEquals(initial.metadata().hash(), updated.metadata().hash());
        assertEquals(updated, store.load(owner, id).orElseThrow());
        assertEquals(1, store.list(owner).size());
        assertThrows(IOException.class, () -> store.save(owner, initial, initial.metadata().hash()));
    }

    @Test void rejectsOtherOwnersAndTamperedFiles() throws Exception {
        UUID owner = new UUID(0, 12);
        UUID other = new UUID(0, 13);
        DesignId id = new DesignId("player:private");
        BlueprintStore store = new BlueprintStore(directory, new DesignCodec());
        Blueprint saved = store.save(owner, store.newBlueprint(owner, id), "");
        assertThrows(SecurityException.class, () -> store.save(other, saved, saved.metadata().hash()));
        assertTrue(store.load(other, id).isEmpty());

        Path file;
        try (var files = Files.walk(directory.resolve(owner.toString()))) {
            file = files.filter(Files::isRegularFile).findFirst().orElseThrow();
        }
        Files.writeString(file, "{}");
        assertThrows(IOException.class, () -> store.load(owner, id));
    }

    @Test void storedFilesUseBoundedHashedNames() throws Exception {
        UUID owner = new UUID(0, 14);
        DesignId id = new DesignId("player:path/nested");
        BlueprintStore store = new BlueprintStore(directory, new DesignCodec());
        store.save(owner, store.newBlueprint(owner, id), "");
        try (var files = Files.list(directory.resolve(owner.toString()))) {
            Path file = files.findFirst().orElseThrow();
            assertTrue(file.getFileName().toString().matches("[0-9a-f]{64}\\.json"));
            assertTrue(Files.size(file) < DesignCodec.MAX_DOCUMENT_BYTES);
        }
    }

    @Test void enforcesThePerPlayerDocumentLimit() throws Exception {
        UUID owner = new UUID(0, 15);
        BlueprintStore store = new BlueprintStore(directory, new DesignCodec());
        for (int index = 0; index < BlueprintStore.MAX_BLUEPRINTS_PER_PLAYER; index++) {
            DesignId id = new DesignId("player:design-" + index);
            store.save(owner, store.newBlueprint(owner, id), "");
        }
        DesignId overflow = new DesignId("player:design-overflow");
        assertThrows(IOException.class, () -> store.save(owner, store.newBlueprint(owner, overflow), ""));
        assertEquals(BlueprintStore.MAX_BLUEPRINTS_PER_PLAYER, store.list(owner).size());
    }
}
