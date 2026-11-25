package dev.stargenesis.paper.program;

import dev.stargenesis.api.design.DesignId;
import dev.stargenesis.design.PrimitiveCatalog;
import dev.stargenesis.design.ProgramInterpreter;
import dev.stargenesis.design.ProgramValue;
import dev.stargenesis.design.ProgramWorkspace;
import dev.stargenesis.design.TypedProgramCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProgramWorkflowTest {
    @TempDir Path directory;

    @Test void editedProgramSurvivesSaveAndReloadThenProducesItsDeclaredVirtualAction() throws Exception {
        UUID owner = new UUID(0, 45);
        DesignId id = new DesignId("player:energy-signal");
        ProgramStore store = new ProgramStore(directory, new TypedProgramCodec());
        ProgramWorkspace editor = new ProgramWorkspace(64);
        editor.append("energy", PrimitiveCatalog.Operation.SENSE_INTEGER, List.of(), Map.of("key", "energy"));
        editor.append("threshold", PrimitiveCatalog.Operation.CONSTANT_INTEGER, List.of(), Map.of("value", "8"));
        editor.append("enough", PrimitiveCatalog.Operation.GREATER_THAN_INTEGER, List.of("energy", "threshold"), Map.of());
        editor.append("one", PrimitiveCatalog.Operation.CONSTANT_INTEGER, List.of(), Map.of("value", "1"));
        editor.append("zero", PrimitiveCatalog.Operation.CONSTANT_INTEGER, List.of(), Map.of("value", "0"));
        editor.append("signal_value", PrimitiveCatalog.Operation.SELECT_INTEGER, List.of("enough", "one", "zero"), Map.of());
        editor.append("signal", PrimitiveCatalog.Operation.EMIT_SIGNAL, List.of("signal_value"), Map.of("channel", "ready"));

        var saved = store.save(owner, id, editor.current(), "");
        var reopened = new ProgramWorkspace(store.load(owner, id).orElseThrow().program());
        var context = new ProgramInterpreter.ProgramContext(93, 12,
                Map.of("energy", new ProgramValue.IntegerValue(11)), Map.of(), Map.of());
        ProgramInterpreter.Execution execution = new ProgramInterpreter().execute(reopened.current(), context, 64, Set.of());

        assertEquals(saved.program(), reopened.current());
        assertEquals(ProgramInterpreter.Status.COMPLETE, execution.status());
        assertEquals(List.of(new ProgramInterpreter.SignalAction("ready", 1)), execution.actions());
        assertTrue(store.load(new UUID(0, 46), id).isEmpty());
    }

    @Test void workspaceUndoDoesNotPersistUntilExplicitSave() throws Exception {
        UUID owner = new UUID(0, 47);
        DesignId id = new DesignId("player:undo");
        ProgramStore store = new ProgramStore(directory, new TypedProgramCodec());
        ProgramWorkspace editor = new ProgramWorkspace(32);
        editor.append("wait", PrimitiveCatalog.Operation.WAIT, List.of(), Map.of());
        var original = store.save(owner, id, editor.current(), "");
        editor.append("two", PrimitiveCatalog.Operation.CONSTANT_INTEGER, List.of(), Map.of("value", "2"));
        assertTrue(editor.undo());
        assertEquals(original.program(), editor.current());
        assertEquals(original, store.load(owner, id).orElseThrow());
    }
}
