package dev.stargenesis.paper.blueprint;

import dev.stargenesis.api.design.DesignId;
import dev.stargenesis.api.design.DesignPermissions;
import dev.stargenesis.design.DesignCodec;
import dev.stargenesis.paper.runtime.WorkspaceRace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BlueprintSessionsTest {
    private static final UUID OWNER = new UUID(0, 810);
    private static final DesignId ID = new DesignId("player:blueprint-lifecycle");
    @TempDir Path directory;

    @Test void closedServiceCannotCreateAnotherPersistentBlueprint() throws Exception {
        var store = new BlueprintStore(directory, new DesignCodec());
        var sessions = new BlueprintSessions(store);
        sessions.close();
        sessions.close();
        assertTrue(sessions.isClosed());
        assertThrows(IllegalArgumentException.class, () -> sessions.create(OWNER, ID));
        assertThrows(IllegalArgumentException.class, () -> sessions.open(OWNER, OWNER, ID));
        assertThrows(IllegalArgumentException.class, () -> sessions.cloneBlueprint(OWNER, OWNER, ID, new DesignId("player:closed-copy")));
        assertThrows(IllegalArgumentException.class, () -> sessions.save(OWNER));
        assertTrue(store.list(OWNER).isEmpty());
    }

    @Test void admittedOpenFinishesBeforeCloseAndCannotRestoreARetiredWorkspace() throws Exception {
        var store = new BlueprintStore(directory, new DesignCodec());
        var sessions = new BlueprintSessions(store);
        var saved = sessions.create(OWNER, ID);
        sessions.closeSession(OWNER, false);
        assertEquals(saved, WorkspaceRace.duringStoreCall(store, () -> sessions.open(OWNER, OWNER, ID), sessions, sessions::close));
        assertEquals(0, sessions.size());
        assertEquals(saved, store.load(OWNER, ID).orElseThrow());
    }

    @Test void disconnectReopensOnlySavedStateAndDoesNotRemoveAnotherPlayersWorkspace() throws Exception {
        var store = new BlueprintStore(directory, new DesignCodec());
        var sessions = new BlueprintSessions(store);
        var saved = sessions.create(OWNER, ID);
        var old = sessions.require(OWNER);
        move(old, 2);
        UUID other = new UUID(0, 811);
        sessions.create(other, ID);
        var untouched = sessions.require(other);
        byte[] bytes = Files.readAllBytes(file(OWNER, ID));
        sessions.disconnected(OWNER);
        assertEquals(1, sessions.size());
        assertSame(untouched, sessions.require(other));
        assertEquals(saved, sessions.open(OWNER, OWNER, ID));
        assertNotSame(old, sessions.require(OWNER));
        assertFalse(sessions.require(OWNER).dirty);
        assertEquals(0, sessions.require(OWNER).workspace.undoDepth());
        assertArrayEquals(bytes, Files.readAllBytes(file(OWNER, ID)));
    }

    @Test void createAndCloneWriteFailuresReleaseAdmissionAndRetryKeepsProvenance() throws Exception {
        var store = new BlueprintStore(directory, new DesignCodec());
        var sessions = new BlueprintSessions(store);
        Path original = file(OWNER, ID);
        Files.createDirectories(original.getParent());
        Path blocker = Files.createDirectory(temporary(original));
        assertThrows(IOException.class, () -> sessions.create(OWNER, ID));
        assertEquals(0, sessions.size());
        assertFalse(Files.exists(original));
        Files.delete(blocker);
        var saved = sessions.create(OWNER, ID);
        sessions.closeSession(OWNER, false);
        DesignId copyId = new DesignId("player:copy");
        blocker = Files.createDirectory(temporary(file(OWNER, copyId)));
        assertThrows(IOException.class, () -> sessions.cloneBlueprint(OWNER, OWNER, ID, copyId));
        assertEquals(0, sessions.size());
        assertFalse(Files.exists(file(OWNER, copyId)));
        Files.delete(blocker);
        var copy = sessions.cloneBlueprint(OWNER, OWNER, ID, copyId);
        assertTrue(copy.metadata().source().endsWith("#" + saved.metadata().hash()));
        assertFalse(copy.metadata().permissions().publicRead());
        assertFalse(copy.metadata().permissions().allowClone());
        assertTrue(copy.metadata().permissions().editors().isEmpty());
        assertEquals(saved, store.load(OWNER, ID).orElseThrow());
    }

    @Test void saveFailurePreservesWorkspaceUndoAndHashUntilRetryCommits() throws Exception {
        var store = new BlueprintStore(directory, new DesignCodec());
        var sessions = new BlueprintSessions(store);
        var saved = sessions.create(OWNER, ID);
        var editing = sessions.require(OWNER);
        move(editing, 2);
        var candidate = editing.workspace.current();
        Path file = file(OWNER, ID);
        byte[] bytes = Files.readAllBytes(file);
        Path blocker = Files.createDirectory(temporary(file));
        assertThrows(IOException.class, () -> sessions.save(OWNER));
        assertSame(editing, sessions.require(OWNER));
        assertEquals(candidate, editing.workspace.current());
        assertEquals(saved.metadata().hash(), editing.baseHash);
        assertEquals(1, editing.workspace.undoDepth());
        assertTrue(editing.dirty);
        assertArrayEquals(bytes, Files.readAllBytes(file));
        Files.delete(blocker);
        var committed = sessions.save(OWNER);
        assertFalse(editing.dirty);
        assertEquals(0, editing.workspace.undoDepth());
        assertEquals(committed, new BlueprintStore(directory, new DesignCodec()).load(OWNER, ID).orElseThrow());
    }

    @Test void publicReadIsNotEditPermissionAndReopenRechecksRevokedAccess() throws Exception {
        var store = new BlueprintStore(directory, new DesignCodec());
        var sessions = new BlueprintSessions(store);
        sessions.create(OWNER, ID);
        var author = sessions.require(OWNER);
        author.workspace.permissions(new DesignPermissions(true, false, Set.of()));
        var published = sessions.save(OWNER);
        UUID reader = new UUID(0, 812);
        assertEquals(published, sessions.open(reader, OWNER, ID));
        assertThrows(SecurityException.class, () -> sessions.requireEditable(reader));
        assertThrows(SecurityException.class, () -> sessions.save(reader));
        author.workspace.permissions(new DesignPermissions(false, false, Set.of()));
        var privateAgain = sessions.save(OWNER);
        sessions.disconnected(reader);
        assertThrows(SecurityException.class, () -> sessions.open(reader, OWNER, ID));
        assertEquals(1, sessions.size());
        assertThrows(SecurityException.class, () -> sessions.cloneBlueprint(reader, OWNER, ID, new DesignId("player:denied")));
        assertTrue(store.list(reader).isEmpty());
        assertEquals(privateAgain, store.load(OWNER, ID).orElseThrow());
    }

    @Test void collaboratorConflictPreservesDraftAndRevocationPreventsReopening() throws Exception {
        var store = new BlueprintStore(directory, new DesignCodec());
        var sessions = new BlueprintSessions(store);
        sessions.create(OWNER, ID);
        UUID editor = new UUID(0, 813);
        var author = sessions.require(OWNER);
        author.workspace.permissions(new DesignPermissions(false, false, Set.of(editor)));
        sessions.save(OWNER);
        sessions.open(editor, OWNER, ID);
        var editing = sessions.requireEditable(editor);
        move(editing, 4);
        move(author, 8);
        sessions.save(OWNER);
        byte[] committed = Files.readAllBytes(file(OWNER, ID));
        assertThrows(IOException.class, () -> sessions.save(editor));
        assertSame(editing, sessions.require(editor));
        assertTrue(editing.dirty);
        assertEquals(1, editing.workspace.undoDepth());
        assertArrayEquals(committed, Files.readAllBytes(file(OWNER, ID)));
        author.workspace.permissions(new DesignPermissions(false, false, Set.of()));
        sessions.save(OWNER);
        assertThrows(IOException.class, () -> sessions.save(editor));
        sessions.disconnected(editor);
        assertThrows(SecurityException.class, () -> sessions.open(editor, OWNER, ID));
    }

    @Test void dirtyCloseRequiresDiscardAndUndoAllowsNormalClose() throws Exception {
        var store = new BlueprintStore(directory, new DesignCodec());
        var sessions = new BlueprintSessions(store);
        var saved = sessions.create(OWNER, ID);
        var editing = sessions.require(OWNER);
        move(editing, 4);
        assertThrows(IllegalArgumentException.class, () -> sessions.closeSession(OWNER, false));
        assertTrue(editing.workspace.undo());
        editing.dirty = !editing.workspace.current().equals(editing.saved);
        assertFalse(sessions.closeSession(OWNER, false));
        sessions.open(OWNER, OWNER, ID);
        move(sessions.require(OWNER), 6);
        assertTrue(sessions.closeSession(OWNER, true));
        assertEquals(saved, sessions.open(OWNER, OWNER, ID));
    }

    @Test void capacityIncludesReadersAndLastSlotRaceDoesNotCreateOrphanClones() throws Exception {
        var store = new BlueprintStore(directory, new DesignCodec());
        var sessions = new BlueprintSessions(store);
        sessions.create(OWNER, ID);
        sessions.require(OWNER).workspace.permissions(new DesignPermissions(true, true, Set.of()));
        sessions.save(OWNER);
        for (int i = 0; i < 63; i++) sessions.open(new UUID(1, i), OWNER, ID);
        UUID first = new UUID(1, 64), second = new UUID(1, 65);
        DesignId copy = new DesignId("player:capacity-copy");
        assertThrows(IllegalArgumentException.class, () -> sessions.cloneBlueprint(first, OWNER, ID, copy));
        assertThrows(IllegalArgumentException.class, () -> sessions.create(first, ID));
        assertFalse(Files.exists(directory.resolve(first.toString())));
        sessions.disconnected(new UUID(1, 0));
        WorkspaceRace.exactlyOneAdmission(() -> sessions.cloneBlueprint(first, OWNER, ID, copy),
                () -> sessions.cloneBlueprint(second, OWNER, ID, copy));
        assertEquals(64, sessions.size());
        assertEquals(1, store.list(first).size() + store.list(second).size());
    }

    @Test void concurrentSamePlayerCreationKeepsExactlyOneDocumentAndSession() throws Exception {
        var store = new BlueprintStore(directory, new DesignCodec());
        var sessions = new BlueprintSessions(store);
        WorkspaceRace.exactlyOneAdmission(() -> sessions.create(OWNER, ID),
                () -> sessions.create(OWNER, new DesignId("player:other")));
        assertEquals(1, sessions.size());
        assertEquals(java.util.List.of(sessions.require(OWNER).saved), store.list(OWNER));
    }

    @Test void closeWaitsForSaveAndDisconnectWaitsForCloneBeforeRetiringWorkspaces() throws Exception {
        var store = new BlueprintStore(directory, new DesignCodec());
        var sessions = new BlueprintSessions(store);
        sessions.create(OWNER, ID);
        move(sessions.require(OWNER), 4);
        var saved = WorkspaceRace.duringStoreCall(store, () -> sessions.save(OWNER), sessions, sessions::close);
        assertTrue(sessions.isClosed());
        assertEquals(0, sessions.size());
        assertThrows(IllegalArgumentException.class, () -> sessions.save(OWNER));
        assertEquals(saved, store.load(OWNER, ID).orElseThrow());
        var restarted = new BlueprintSessions(new BlueprintStore(directory, new DesignCodec()));
        assertEquals(saved, restarted.open(OWNER, OWNER, ID));
        restarted.closeSession(OWNER, false);
        var clonedSessions = new BlueprintSessions(store);
        DesignId copy = new DesignId("player:retired-copy");
        var cloned = WorkspaceRace.duringStoreCall(store, () -> clonedSessions.cloneBlueprint(OWNER, OWNER, ID, copy),
                clonedSessions, () -> clonedSessions.disconnected(OWNER));
        assertEquals(0, clonedSessions.size());
        assertEquals(cloned, clonedSessions.open(OWNER, OWNER, copy));
    }

    private static void move(BlueprintSessions.EditSession session, int x) {
        session.workspace.move("core", x, 0, 0);
        session.dirty = !session.workspace.current().equals(session.saved);
    }

    private Path file(UUID owner, DesignId id) throws Exception {
        String key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(id.value().getBytes(StandardCharsets.UTF_8)));
        return directory.resolve(owner.toString()).resolve(key + ".json");
    }

    private static Path temporary(Path file) { return file.resolveSibling(file.getFileName() + ".tmp"); }
}
