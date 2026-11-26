package dev.stargenesis.paper.program;

import dev.stargenesis.api.design.DesignId;
import dev.stargenesis.api.sim.SimulationBudget;
import dev.stargenesis.design.PrimitiveCatalog.Operation;
import dev.stargenesis.design.ProgramValue;
import dev.stargenesis.design.TypedProgramCodec;
import dev.stargenesis.paper.runtime.WorkspaceRace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProgramSessionsTest {
    private static final UUID OWNER = new UUID(0, 820);
    private static final DesignId ID = new DesignId("player:program-lifecycle");
    @TempDir Path directory;

    @Test void closedServiceCannotCreateAnotherPersistentProgram() throws Exception {
        var store = new ProgramStore(directory, new TypedProgramCodec());
        var sessions = new ProgramSessions(store, SimulationBudget.defaults());
        sessions.close();
        sessions.close();
        assertTrue(sessions.isClosed());
        assertThrows(IllegalArgumentException.class, () -> sessions.create(OWNER, ID));
        assertThrows(IllegalArgumentException.class, () -> sessions.open(OWNER, ID));
        assertThrows(IllegalArgumentException.class, () -> sessions.save(OWNER));
        assertTrue(store.list(OWNER).isEmpty());
    }

    @Test void admittedOpenFinishesBeforeCloseAndCannotRestoreARetiredWorkspace() throws Exception {
        var store = new ProgramStore(directory, new TypedProgramCodec());
        var sessions = new ProgramSessions(store, SimulationBudget.defaults());
        var saved = sessions.create(OWNER, ID).workspace.current();
        sessions.closeSession(OWNER, false);
        assertEquals(saved, WorkspaceRace.duringStoreCall(store, () -> sessions.open(OWNER, ID), sessions, sessions::close).workspace.current());
        assertEquals(0, sessions.size());
        assertEquals(saved, store.load(OWNER, ID).orElseThrow().program());
    }

    @Test void disconnectDropsUnsavedInstructionsAndAllDebuggerContextButKeepsTheSavedFile() throws Exception {
        var store = new ProgramStore(directory, new TypedProgramCodec());
        var sessions = new ProgramSessions(store, SimulationBudget.defaults());
        var old = sessions.create(OWNER, ID);
        byte[] bytes = Files.readAllBytes(file(OWNER, ID));
        old.append("value", Operation.CONSTANT_INTEGER, List.of(), Map.of("value", "4"));
        old.setSensor("energy", new ProgramValue.IntegerValue(9));
        old.setResource("ore", 8);
        old.setSignal("ready", 1);
        old.setClock(123, 40);
        old.breakpoint("value", true);
        old.execute(8, false);
        UUID other = new UUID(0, 821);
        var untouched = sessions.create(other, ID);
        sessions.disconnected(OWNER);
        assertEquals(1, sessions.size());
        assertSame(untouched, sessions.require(other));
        var reopened = sessions.open(OWNER, ID);
        assertNotSame(old, reopened);
        assertFalse(reopened.dirty);
        assertEquals(1, reopened.workspace.instructions().size());
        assertEquals(0, reopened.workspace.undoDepth());
        assertTrue(reopened.sensors.isEmpty());
        assertTrue(reopened.resources.isEmpty());
        assertTrue(reopened.signals.isEmpty());
        assertTrue(reopened.breakpoints.isEmpty());
        assertNull(reopened.execution);
        assertEquals(0, reopened.seed);
        assertEquals(0, reopened.tick);
        assertArrayEquals(bytes, Files.readAllBytes(file(OWNER, ID)));
    }

    @Test void failedCreationAndMissingOpenReleaseAdmissionForRetry() throws Exception {
        var store = new ProgramStore(directory, new TypedProgramCodec());
        var sessions = new ProgramSessions(store, SimulationBudget.defaults());
        assertThrows(IllegalArgumentException.class, () -> sessions.open(OWNER, ID));
        Path file = file(OWNER, ID);
        Files.createDirectories(file.getParent());
        Path blocker = Files.createDirectory(temporary(file));
        assertThrows(IOException.class, () -> sessions.create(OWNER, ID));
        assertEquals(0, sessions.size());
        assertFalse(Files.exists(file));
        Files.delete(blocker);
        var created = sessions.create(OWNER, ID);
        assertFalse(created.dirty);
        assertEquals(created.workspace.current(), store.load(OWNER, ID).orElseThrow().program());
    }

    @Test void failedSaveRetainsUndoExecutionAndHashThenRetriesTheSameDraft() throws Exception {
        var store = new ProgramStore(directory, new TypedProgramCodec());
        var sessions = new ProgramSessions(store, SimulationBudget.defaults());
        var editing = sessions.create(OWNER, ID);
        String originalHash = editing.baseHash;
        editing.append("value", Operation.CONSTANT_INTEGER, List.of(), Map.of("value", "4"));
        var execution = editing.execute(1, false);
        var candidate = editing.workspace.current();
        Path file = file(OWNER, ID);
        byte[] bytes = Files.readAllBytes(file);
        Path blocker = Files.createDirectory(temporary(file));
        assertThrows(IOException.class, () -> sessions.save(OWNER));
        assertSame(editing, sessions.require(OWNER));
        assertEquals(candidate, editing.workspace.current());
        assertSame(execution, editing.execution);
        assertEquals(originalHash, editing.baseHash);
        assertEquals(1, editing.workspace.undoDepth());
        assertTrue(editing.dirty);
        assertArrayEquals(bytes, Files.readAllBytes(file));
        Files.delete(blocker);
        assertSame(editing, sessions.save(OWNER));
        assertFalse(editing.dirty);
        assertNull(editing.execution);
        assertEquals(0, editing.workspace.undoDepth());
        assertEquals(candidate, new ProgramStore(directory, new TypedProgramCodec()).load(OWNER, ID).orElseThrow().program());
    }

    @Test void dirtyCloseRequiresDiscardAndUndoAllowsNormalClose() throws Exception {
        var store = new ProgramStore(directory, new TypedProgramCodec());
        var sessions = new ProgramSessions(store, SimulationBudget.defaults());
        var editing = sessions.create(OWNER, ID);
        var saved = editing.workspace.current();
        editing.append("value", Operation.CONSTANT_INTEGER, List.of(), Map.of("value", "4"));
        assertThrows(IllegalArgumentException.class, () -> sessions.closeSession(OWNER, false));
        assertTrue(editing.undo());
        assertFalse(sessions.closeSession(OWNER, false));
        sessions.open(OWNER, ID).append("value", Operation.CONSTANT_INTEGER, List.of(), Map.of("value", "5"));
        assertTrue(sessions.closeSession(OWNER, true));
        assertEquals(saved, sessions.open(OWNER, ID).workspace.current());
    }

    @Test void capacityRejectsBeforeFilesAndConcurrentLastSlotCreatesOneProgram() throws Exception {
        var store = new ProgramStore(directory, new TypedProgramCodec());
        var sessions = new ProgramSessions(store, SimulationBudget.defaults());
        for (int i = 0; i < 64; i++) sessions.create(new UUID(1, i), ID);
        UUID first = new UUID(1, 64), second = new UUID(1, 65);
        assertThrows(IllegalArgumentException.class, () -> sessions.create(first, ID));
        assertFalse(Files.exists(directory.resolve(first.toString())));
        sessions.disconnected(new UUID(1, 0));
        WorkspaceRace.exactlyOneAdmission(() -> sessions.create(first, ID), () -> sessions.create(second, ID));
        assertEquals(64, sessions.size());
        assertEquals(1, store.list(first).size() + store.list(second).size());
    }

    @Test void concurrentSamePlayerCreationKeepsExactlyOneDocumentAndSession() throws Exception {
        var store = new ProgramStore(directory, new TypedProgramCodec());
        var sessions = new ProgramSessions(store, SimulationBudget.defaults());
        WorkspaceRace.exactlyOneAdmission(() -> sessions.create(OWNER, ID),
                () -> sessions.create(OWNER, new DesignId("player:other")));
        assertEquals(1, sessions.size());
        var saved = store.list(OWNER);
        assertEquals(1, saved.size());
        assertEquals(saved.getFirst().id(), sessions.require(OWNER).id);
        assertEquals(saved.getFirst().program(), sessions.require(OWNER).workspace.current());
    }

    @Test void closeWaitsForAdmittedSaveAndRestartLoadsItsCommittedDocument() throws Exception {
        var store = new ProgramStore(directory, new TypedProgramCodec());
        var sessions = new ProgramSessions(store, SimulationBudget.defaults());
        var editing = sessions.create(OWNER, ID);
        editing.append("value", Operation.CONSTANT_INTEGER, List.of(), Map.of("value", "4"));
        var candidate = editing.workspace.current();
        WorkspaceRace.duringStoreCall(store, () -> sessions.save(OWNER), sessions, sessions::close);
        assertTrue(sessions.isClosed());
        assertEquals(0, sessions.size());
        assertThrows(IllegalArgumentException.class, () -> sessions.save(OWNER));
        var restarted = new ProgramSessions(new ProgramStore(directory, new TypedProgramCodec()), SimulationBudget.defaults());
        assertEquals(candidate, restarted.open(OWNER, ID).workspace.current());
    }

    @Test void disconnectWaitsForAdmittedCreateAndRequiresAnExplicitReopen() throws Exception {
        var store = new ProgramStore(directory, new TypedProgramCodec());
        var sessions = new ProgramSessions(store, SimulationBudget.defaults());
        var created = WorkspaceRace.duringStoreCall(store, () -> sessions.create(OWNER, ID), sessions,
                () -> sessions.disconnected(OWNER));
        assertEquals(0, sessions.size());
        assertThrows(IllegalArgumentException.class, () -> sessions.require(OWNER));
        assertEquals(created.workspace.current(), sessions.open(OWNER, ID).workspace.current());
    }

    private Path file(UUID owner, DesignId id) throws Exception {
        String key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(id.value().getBytes(StandardCharsets.UTF_8)));
        return directory.resolve(owner.toString()).resolve(key + ".json");
    }

    private static Path temporary(Path file) { return file.resolveSibling(file.getFileName() + ".tmp"); }
}
