package dev.stargenesis.paper.program;

import dev.stargenesis.api.design.DesignId;
import dev.stargenesis.api.sim.SimulationBudget;
import dev.stargenesis.design.PrimitiveCatalog.Operation;
import dev.stargenesis.design.ProgramInterpreter;
import dev.stargenesis.design.ProgramValue;
import dev.stargenesis.design.ProgramWorkspace;
import dev.stargenesis.design.TypedProgramCodec;
import dev.stargenesis.design.TypedProgramDocument;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProgramSessionTest {
    private static final UUID OWNER = new UUID(0, 103);
    private static final DesignId ID = new DesignId("player:session");
    @TempDir Path directory;
    private ProgramStore store;

    @BeforeEach void setup() throws Exception { store = new ProgramStore(directory, new TypedProgramCodec()); }

    @Test void failedSaveKeepsEditedWorkspaceUndoDebuggerAndBaseHashForRetry() throws Exception {
        var session = savedSession(SimulationBudget.defaults());
        Path file = onlyProgram(); byte[] before = Files.readAllBytes(file);
        String originalHash = session.baseHash;
        session.append("two", Operation.CONSTANT_INTEGER, List.of(), Map.of("value", "2"));
        var execution = session.execute(1, false);
        int undoDepth = session.workspace.undoDepth();
        Path blocker = Files.createDirectory(file.resolveSibling(file.getFileName() + ".tmp"));
        assertThrows(IOException.class, () -> session.save(store));
        assertTrue(session.dirty);
        assertEquals(originalHash, session.baseHash);
        assertSame(execution, session.execution);
        assertEquals(undoDepth, session.workspace.undoDepth());
        assertArrayEquals(before, Files.readAllBytes(file));
        Files.delete(blocker);
        session.save(store);
        assertFalse(session.dirty);
        assertNull(session.execution);
        assertEquals(0, session.workspace.undoDepth());
        assertNotEquals(originalHash, session.baseHash);
        assertEquals(session.workspace.current(), new ProgramStore(directory, new TypedProgramCodec()).load(OWNER, ID).orElseThrow().program());
    }

    @Test void staleSessionCannotOverwriteACommittedEditAndRetainsItsOwnDraft() throws Exception {
        var first = savedSession(SimulationBudget.defaults());
        var second = open(store.load(OWNER, ID).orElseThrow(), SimulationBudget.defaults());
        first.replace("value", Operation.CONSTANT_INTEGER, List.of(), Map.of("value", "2"));
        first.save(store);
        byte[] committed = Files.readAllBytes(onlyProgram());
        second.replace("value", Operation.CONSTANT_INTEGER, List.of(), Map.of("value", "3"));
        var draft = second.workspace.current();
        assertThrows(IOException.class, () -> second.save(store));
        assertTrue(second.dirty);
        assertEquals(draft, second.workspace.current());
        assertArrayEquals(committed, Files.readAllBytes(onlyProgram()));
    }

    @Test void editingAndUndoInvalidateDebugStateAndRemoveOrphanedBreakpoints() throws Exception {
        var session = savedSession(SimulationBudget.defaults());
        session.append("sum", Operation.ADD_INTEGER, List.of("value", "value"), Map.of());
        session.breakpoint("sum", true);
        assertEquals(ProgramInterpreter.Status.BREAKPOINT, session.execute(8, false).status());
        session.replace("value", Operation.CONSTANT_INTEGER, List.of(), Map.of("value", "4"));
        assertNull(session.execution);
        assertEquals(ProgramInterpreter.Status.BREAKPOINT, session.execute(8, true).status());
        var completed = session.execute(8, true);
        assertEquals(new ProgramValue.IntegerValue(8), completed.values().get("sum"));
        assertTrue(session.undo());
        assertNull(session.execution);
        assertTrue(session.undo());
        assertTrue(session.breakpoints.isEmpty());
        assertFalse(session.dirty);
    }

    @Test void invalidEditKeepsTheCurrentExecutionAndDoesNotSpendUndoHistory() throws Exception {
        var session = savedSession(SimulationBudget.defaults());
        session.append("sum", Operation.ADD_INTEGER, List.of("value", "value"), Map.of());
        var execution = session.execute(1, false);
        var program = session.workspace.current(); int undo = session.workspace.undoDepth();
        assertThrows(IllegalArgumentException.class, () -> session.remove("value"));
        assertThrows(IllegalArgumentException.class, () -> session.replace("value", Operation.CONSTANT_BOOLEAN,
                List.of(), Map.of("value", "true")));
        assertSame(execution, session.execution);
        assertEquals(program, session.workspace.current());
        assertEquals(undo, session.workspace.undoDepth());
    }

    @Test void changingSensorsSignalsResourcesOrClockRestartsExecutionFromTheBeginning() throws Exception {
        var session = savedSession(SimulationBudget.defaults());
        session.replace("value", Operation.SENSE_INTEGER, List.of(), Map.of("key", "energy"));
        session.append("previous", Operation.READ_SIGNAL, List.of(), Map.of("channel", "ready"));
        session.setSensor("energy", new ProgramValue.IntegerValue(3));
        var yielded = session.execute(1, false);
        assertEquals(new ProgramValue.IntegerValue(3), yielded.values().get("value"));
        session.setSensor("energy", new ProgramValue.IntegerValue(4));
        assertNull(session.execution);
        assertEquals(new ProgramValue.IntegerValue(4), session.execute(1, true).values().get("value"));
        session.setSignal("ready", 7);
        assertNull(session.execution);
        assertEquals(new ProgramValue.IntegerValue(7), session.execute(8, true).values().get("previous"));
        session.setResource("ore", 5); assertNull(session.execution);
        session.execute(1, false);
        session.setClock(123, 10); assertNull(session.execution);
        assertEquals(10, session.context().tick());
        assertEquals(new ProgramValue.IntegerValue(3), yielded.values().get("value"));
    }

    @Test void invalidOrOverCapacityContextUpdateIsAtomicAndPlayerContextsAreIsolated() throws Exception {
        var session = savedSession(SimulationBudget.defaults());
        for (int i = 0; i < 64; i++) session.setSignal("channel" + i, i);
        var context = session.context(); var execution = session.execute(1, false);
        assertThrows(IllegalArgumentException.class, () -> session.setSignal("overflow", 1));
        assertThrows(IllegalArgumentException.class, () -> session.setResource("ore", -1));
        assertThrows(IllegalArgumentException.class, () -> session.setSensor("energy", ProgramValue.NoneValue.INSTANCE));
        assertThrows(IllegalArgumentException.class, () -> session.setClock(1, -1));
        assertEquals(context, session.context());
        assertSame(execution, session.execution);
        session.setSignal("channel0", 100);
        assertEquals(100L, session.signals.get("channel0"));
        var other = new ProgramSession(new UUID(0, 104), ID, new ProgramWorkspace(session.workspace.current()), null, SimulationBudget.defaults());
        assertTrue(other.signals.isEmpty());
        assertTrue(other.sensors.isEmpty());
    }

    @Test void debuggerBudgetAppliesAcrossStepAndContinueWithoutChangingSavedDeclaration() throws Exception {
        var host = new SimulationBudget(2, 1, 0, 20, 1, 0);
        var session = savedSession(host);
        session.append("two", Operation.CONSTANT_INTEGER, List.of(), Map.of("value", "2"));
        session.append("sum", Operation.ADD_INTEGER, List.of("value", "two"), Map.of());
        session.save(store);
        byte[] before = Files.readAllBytes(onlyProgram());
        assertEquals(ProgramInterpreter.Status.YIELDED, session.execute(1, false).status());
        assertEquals(ProgramInterpreter.Status.YIELDED, session.execute(1, true).status());
        var exhausted = session.execute(8, true);
        assertEquals(ProgramInterpreter.Status.BUDGET_EXCEEDED, exhausted.status());
        assertEquals(2, exhausted.executedInstructions());
        assertEquals("sum", exhausted.nextInstructionId());
        assertThrows(IllegalArgumentException.class, () -> session.execute(8, true));
        assertArrayEquals(before, Files.readAllBytes(onlyProgram()));
        assertEquals(256, store.load(OWNER, ID).orElseThrow().program().maxInstructionsPerStep());
    }

    @Test void legacyV1ProgramKeepsItsHashAndCanRunUnderATighterHostLimit() throws Exception {
        String json;
        try (var input = getClass().getResourceAsStream("/program-v1-host-budget.json")) {
            assertNotNull(input); json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        var fixture = new TypedProgramCodec().load(json);
        assertEquals("075a71198ada2b5855431e826d412bee40152f242d6e13213c50305f5edc64af", fixture.hash());
        var saved = store.save(OWNER, fixture.id(), fixture.program(), "");
        assertEquals(fixture, saved);
        var session = open(saved, new SimulationBudget(3, 1, 0, 20, 1, 1));
        assertEquals(ProgramInterpreter.Status.COMPLETE, session.execute(8, false).status());
        assertEquals(256, session.workspace.current().maxInstructionsPerStep());
        session.save(store);
        assertEquals(fixture.hash(), session.baseHash);
        assertThrows(IllegalArgumentException.class, () -> new ProgramSession(new UUID(0, 999), fixture.id(),
                new ProgramWorkspace(fixture.program()), fixture, SimulationBudget.defaults()));
    }

    private ProgramSession savedSession(SimulationBudget budget) throws Exception {
        var workspace = new ProgramWorkspace(256);
        workspace.append("value", Operation.CONSTANT_INTEGER, List.of(), Map.of("value", "1"));
        var session = new ProgramSession(OWNER, ID, workspace, null, budget);
        session.save(store);
        return session;
    }

    private ProgramSession open(TypedProgramDocument saved, SimulationBudget budget) {
        return new ProgramSession(OWNER, saved.id(), new ProgramWorkspace(saved.program()), saved, budget);
    }

    private Path onlyProgram() throws IOException {
        try (var files = Files.list(directory.resolve(OWNER.toString()))) { return files.findFirst().orElseThrow(); }
    }
}
