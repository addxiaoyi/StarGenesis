package dev.stargenesis.design;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ProgramEditorSessionTest {
    private static final UUID OWNER = new UUID(0, 31);
    private static final UUID OTHER = new UUID(0, 32);
    private final DesignCodec codec = new DesignCodec();

    @Test void typedGraphCanBeExtendedPreviewedSavedAndRun() {
        ProgramEditorSession editor = ProgramEditorSession.create(OWNER);
        assertEquals(2, editor.draft().nodes().size());
        assertEquals(Program.Operation.values().length, editor.operations().size());
        editor.append(Program.Operation.SENSE, List.of());
        Program.Node wait = editor.append(Program.Operation.WAIT, List.of());
        assertEquals(Program.ValueType.NONE, wait.inputType());
        assertEquals(4, editor.preview().instructions().size());
        String saved = editor.save(codec);
        Program verified = (Program) codec.load(saved);
        assertEquals(4, verified.nodes().size());
        assertFalse(editor.hasUnsavedChanges());
        assertFalse(editor.canUndo());
    }

    @Test void appendChecksInputCountAndTypesBeforeChangingGraph() {
        ProgramEditorSession editor = ProgramEditorSession.create(OWNER);
        int nodes = editor.draft().nodes().size();
        assertThrows(IllegalArgumentException.class, () -> editor.append(Program.Operation.MOVE, List.of("sense_resource")));
        assertThrows(IllegalArgumentException.class, () -> editor.append(Program.Operation.ADD, List.of("sense_resource", "harvest")));
        assertEquals(nodes, editor.draft().nodes().size());
        assertFalse(editor.canUndo());
    }

    @Test void removingNodeRemovesItsDependentsAndUndoRestoresGraph() {
        ProgramEditorSession editor = ProgramEditorSession.create(OWNER);
        editor.append(Program.Operation.SENSE, List.of());
        Program.Node output = editor.append(Program.Operation.WAIT, List.of());
        assertEquals(4, editor.draft().nodes().size());
        editor.remove("sense_resource");
        assertEquals(2, editor.draft().nodes().size());
        assertEquals(Program.Operation.SENSE, editor.draft().nodes().getFirst().operation());
        assertTrue(editor.undo());
        assertEquals(4, editor.draft().nodes().size());
        assertTrue(editor.draft().nodes().stream().anyMatch(node -> node.id().equals(output.id())));
    }

    @Test void checkpointRestoresDraftUndoAndCompilePreviewAndRejectsForeignActorOrTampering() {
        ProgramEditorSession editor = ProgramEditorSession.create(OWNER);
        editor.append(Program.Operation.SENSE, List.of());
        editor.preview();
        byte[] checkpoint = editor.encodeCheckpoint(codec);
        ProgramEditorSession resumed = ProgramEditorSession.resume(OWNER, checkpoint, codec);
        assertTrue(resumed.hasUnsavedChanges());
        assertTrue(resumed.canUndo());
        assertEquals(editor.draft().nodes(), resumed.draft().nodes());
        assertEquals(editor.draft().edges(), resumed.draft().edges());
        assertEquals(editor.parameterChoice(), resumed.parameterChoice());
        assertThrows(SecurityException.class, () -> ProgramEditorSession.resume(OTHER, checkpoint, codec));
        byte[] damaged = checkpoint.clone();
        damaged[damaged.length - 1] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> ProgramEditorSession.resume(OWNER, damaged, codec));
    }

    @Test void saveRequiresPreviewOfCurrentGraph() {
        ProgramEditorSession editor = ProgramEditorSession.create(OWNER);
        editor.preview();
        editor.append(Program.Operation.SENSE, List.of());
        assertThrows(IllegalStateException.class, () -> editor.save(codec));
        assertTrue(editor.preview().instructions().size() > 2);
        assertDoesNotThrow(() -> editor.save(codec));
    }

    @Test void parameterTemplatesPersistAndNodeBudgetIsEnforced() {
        ProgramEditorSession editor = ProgramEditorSession.create(OWNER);
        editor.cycleParameters();
        ProgramEditorSession resumed = ProgramEditorSession.resume(OWNER, editor.encodeCheckpoint(codec), codec);
        assertEquals(1, resumed.parameterChoice());

        for (int index = resumed.draft().nodes().size(); index < ProgramEditorSession.MAX_EDITOR_NODES; index++)
            resumed.append(Program.Operation.SENSE, List.of());
        assertEquals(ProgramEditorSession.MAX_EDITOR_NODES, resumed.draft().nodes().size());
        assertThrows(IllegalStateException.class, () -> resumed.append(Program.Operation.WAIT, List.of()));
    }
}
