package dev.stargenesis.paper.blueprint;

import dev.stargenesis.api.design.DesignId;
import dev.stargenesis.api.design.DesignMetadata;
import dev.stargenesis.api.design.DesignPermissions;
import dev.stargenesis.design.Blueprint;
import dev.stargenesis.design.BlueprintWorkspace;
import dev.stargenesis.design.DesignCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class BlueprintCollaborationTest {
    private static final UUID AUTHOR = new UUID(0, 121);
    private static final UUID EDITOR = new UUID(0, 122);
    private static final UUID READER = new UUID(0, 123);
    private static final DesignId ID = new DesignId("player:shared_rover");
    @TempDir Path directory;

    @Test void authorizedEditorSavesInAuthorsDirectoryAndReopensAfterRestart() throws Exception {
        BlueprintStore store = store();
        Blueprint shared = share(store, false, false, Set.of(EDITOR));
        assertEquals(shared, store.loadFor(EDITOR, AUTHOR, ID).orElseThrow());
        BlueprintWorkspace editing = new BlueprintWorkspace(shared);
        editing.add(new Blueprint.Component("solar", "genesis:solar_panel", 1, 0, 0, 0), "core");
        Blueprint saved = store.save(EDITOR, editing.current(), shared.metadata().hash());
        assertEquals(AUTHOR, saved.metadata().author());
        assertFalse(Files.exists(directory.resolve(EDITOR.toString())));
        assertEquals(saved, store().loadFor(EDITOR, AUTHOR, ID).orElseThrow());
        assertEquals(List.of(saved), store().listFor(EDITOR, AUTHOR));
        assertTrue(store.listFor(READER, AUTHOR).isEmpty());
        assertThrows(SecurityException.class, () -> store.loadFor(READER, AUTHOR, ID));
    }

    @Test void publicReadingGrantsNeitherEditingNorCloning() throws Exception {
        BlueprintStore store = store();
        Blueprint published = share(store, true, false, Set.of());
        assertEquals(published, store.loadFor(READER, AUTHOR, ID).orElseThrow());
        assertEquals(List.of(published), store.listFor(READER, AUTHOR));
        assertThrows(SecurityException.class, () -> store.save(READER, published, published.metadata().hash()));
        assertThrows(SecurityException.class,
                () -> store.cloneBlueprint(READER, AUTHOR, ID, new DesignId("player:copy")));
    }

    @Test void revocationIsCheckedAgainstPersistedPermissionEvenWithACurrentHash() throws Exception {
        BlueprintStore store = store();
        Blueprint shared = share(store, false, true, Set.of(EDITOR));
        BlueprintWorkspace authorEdit = new BlueprintWorkspace(shared);
        authorEdit.permissions(new DesignPermissions(false, true, Set.of()));
        Blueprint revoked = store.save(AUTHOR, authorEdit.current(), shared.metadata().hash());
        assertThrows(IOException.class, () -> store.save(EDITOR, shared, shared.metadata().hash()));
        assertThrows(SecurityException.class, () -> store.save(EDITOR, shared, revoked.metadata().hash()));
        assertThrows(SecurityException.class, () -> store().loadFor(EDITOR, AUTHOR, ID));
        assertThrows(SecurityException.class,
                () -> store.cloneBlueprint(EDITOR, AUTHOR, ID, new DesignId("player:revoked-copy")));
        assertEquals(revoked, store.load(AUTHOR, ID).orElseThrow());
    }

    @Test void candidateCannotSelfAuthorizeOrCreateADocumentForAnotherAuthor() throws Exception {
        BlueprintStore store = store();
        Blueprint privateDesign = share(store, false, false, Set.of());
        BlueprintWorkspace forged = new BlueprintWorkspace(privateDesign);
        forged.permissions(new DesignPermissions(true, true, Set.of(EDITOR)));
        assertThrows(SecurityException.class,
                () -> store.save(EDITOR, forged.current(), privateDesign.metadata().hash()));
        Blueprint fresh = store.newBlueprint(AUTHOR, new DesignId("player:forged"));
        BlueprintWorkspace freshForged = new BlueprintWorkspace(fresh);
        freshForged.permissions(new DesignPermissions(true, true, Set.of(EDITOR)));
        assertThrows(SecurityException.class, () -> store.save(EDITOR, freshForged.current(), ""));
        assertEquals(List.of(privateDesign), store.list(AUTHOR));
    }

    @Test void editorCannotExpandPermissionsRewriteProvenanceOrChangeVersion() throws Exception {
        BlueprintStore store = store();
        Blueprint shared = share(store, false, true, Set.of(EDITOR));
        BlueprintWorkspace forged = new BlueprintWorkspace(shared);
        forged.permissions(new DesignPermissions(true, true, Set.of(EDITOR, READER)));
        assertThrows(SecurityException.class, () -> store.save(EDITOR, forged.current(), shared.metadata().hash()));
        assertTrue(forged.undo());
        forged.version("2.0.0");
        assertThrows(SecurityException.class, () -> store.save(EDITOR, forged.current(), shared.metadata().hash()));
        DesignMetadata old = shared.metadata();
        DesignMetadata altered = new DesignMetadata(old.id(), old.schemaVersion(), old.version(), old.author(),
                "player:invented-origin", old.dependencies(), "", old.permissions());
        Blueprint changed = new Blueprint(altered, shared.components(), shared.connections());
        assertThrows(SecurityException.class, () -> store.save(EDITOR, changed, shared.metadata().hash()));
        assertEquals(shared, store.load(AUTHOR, ID).orElseThrow());
    }

    @Test void licensedClonePreservesExactSourceAndIsPrivateWithNoInheritedEditors() throws Exception {
        BlueprintStore store = store();
        Blueprint shared = share(store, true, true, Set.of(EDITOR));
        DesignId target = new DesignId("player:my_rover");
        Blueprint copy = store.cloneBlueprint(READER, AUTHOR, ID, target);
        assertEquals(READER, copy.metadata().author());
        assertEquals(target, copy.metadata().id());
        assertEquals(new DesignPermissions(false, false, Set.of()), copy.metadata().permissions());
        assertEquals("clone:" + AUTHOR + ":" + ID.value() + "@1.0.0#" + shared.metadata().hash(), copy.metadata().source());
        assertEquals(shared.components(), copy.components());
        assertEquals(shared.connections(), copy.connections());
        assertEquals(copy, store().load(READER, target).orElseThrow());
        assertThrows(SecurityException.class, () -> store.loadFor(AUTHOR, READER, target));
        assertThrows(IOException.class, () -> store.cloneBlueprint(READER, AUTHOR, ID, target));
        assertEquals(shared, store.load(AUTHOR, ID).orElseThrow());
    }

    @Test void privateCloneRequiresReadAccessAndLicenseButAuthorCanDuplicateTheirOwnWork() throws Exception {
        BlueprintStore store = store();
        Blueprint shared = share(store, false, true, Set.of(EDITOR));
        assertThrows(SecurityException.class,
                () -> store.cloneBlueprint(READER, AUTHOR, ID, new DesignId("player:hidden")));
        assertEquals(EDITOR, store.cloneBlueprint(EDITOR, AUTHOR, ID, new DesignId("player:allowed")).metadata().author());
        BlueprintWorkspace workspace = new BlueprintWorkspace(shared);
        workspace.permissions(new DesignPermissions(false, false, Set.of()));
        Blueprint privateDesign = store.save(AUTHOR, workspace.current(), shared.metadata().hash());
        assertEquals(privateDesign.components(),
                store.cloneBlueprint(AUTHOR, AUTHOR, ID, new DesignId("player:backup")).components());
        assertThrows(IllegalArgumentException.class, () -> store.cloneBlueprint(AUTHOR, AUTHOR, ID, ID));
    }

    @Test void twoEditorsFromTheSameBaseCannotOverwriteEachOthersChanges() throws Exception {
        BlueprintStore store = store();
        Blueprint shared = share(store, false, false, Set.of(EDITOR, READER));
        BlueprintWorkspace a = new BlueprintWorkspace(shared);
        a.move("core", 1, 0, 0);
        BlueprintWorkspace b = new BlueprintWorkspace(shared);
        b.move("core", 2, 0, 0);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> saveRacing(store, EDITOR, a.current(), shared, ready, start));
            var second = executor.submit(() -> saveRacing(store, READER, b.current(), shared, ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertNotEquals(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS));
        } finally { start.countDown(); }
        Blueprint saved = store().load(AUTHOR, ID).orElseThrow();
        assertTrue(saved.components().getFirst().x() == 1 || saved.components().getFirst().x() == 2);
    }

    @Test void authorNamespacesAreExplicitAndMisnamedFilesAreRejectedDuringListing() throws Exception {
        BlueprintStore store = store();
        Blueprint shared = share(store, true, false, Set.of());
        Blueprint other = store.save(EDITOR, store.newBlueprint(EDITOR, ID), "");
        assertEquals(shared, store.loadFor(EDITOR, AUTHOR, ID).orElseThrow());
        assertEquals(other, store.loadFor(EDITOR, EDITOR, ID).orElseThrow());
        try (var files = Files.list(directory.resolve(AUTHOR.toString()))) {
            Path original = files.findFirst().orElseThrow();
            Files.move(original, original.resolveSibling("0".repeat(64) + ".json"));
        }
        assertThrows(IOException.class, () -> store.list(AUTHOR));
    }

    private boolean saveRacing(BlueprintStore store, UUID actor, Blueprint candidate, Blueprint initial,
                               CountDownLatch ready, CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Race did not start");
        try { store.save(actor, candidate, initial.metadata().hash()); return true; }
        catch (IOException conflict) { return false; }
    }

    private Blueprint share(BlueprintStore store, boolean publicRead, boolean allowClone, Set<UUID> editors) throws IOException {
        BlueprintWorkspace workspace = new BlueprintWorkspace(store.newBlueprint(AUTHOR, ID));
        workspace.permissions(new DesignPermissions(publicRead, allowClone, editors));
        return store.save(AUTHOR, workspace.current(), "");
    }

    private BlueprintStore store() throws IOException { return new BlueprintStore(directory, new DesignCodec()); }
}
