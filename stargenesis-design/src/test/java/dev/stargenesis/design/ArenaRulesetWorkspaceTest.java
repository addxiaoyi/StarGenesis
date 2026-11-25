package dev.stargenesis.design;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ArenaRulesetWorkspaceTest {
    private static final UUID AUTHOR = UUID.fromString("00000000-0000-0000-0000-000000000021");

    @Test void boundedFieldEditsUndoAndReopenRetainTheCommittedVersion() throws IOException {
        ArenaRuleset starter = loadStarter();
        ArenaRulesetWorkspace workspace = new ArenaRulesetWorkspace(starter);

        workspace.resize(32, 24);
        workspace.duration(2400);
        workspace.unitBudget(8);
        assertEquals(8, workspace.current().unitBudget());
        assertEquals(3, workspace.undoDepth());
        assertTrue(workspace.undo());
        assertEquals(4, workspace.current().unitBudget());
        assertTrue(workspace.undo());
        assertEquals(1200, workspace.current().durationSteps());
        assertThrows(IllegalArgumentException.class, () -> workspace.resize(3, 24));
        assertEquals(32, workspace.current().width());
        assertTrue(workspace.undo());
        assertEquals(starter, workspace.current());
        assertFalse(workspace.undo());
    }

    @Test void permissionAndDependencyEditsInvalidateThePreviousDesignHash() throws IOException {
        ArenaRuleset starter = loadStarter();
        ArenaRulesetWorkspace workspace = new ArenaRulesetWorkspace(starter);
        workspace.publicRead(false);
        workspace.allowClone(false);
        workspace.editor(AUTHOR, true);
        assertTrue(workspace.current().metadata().hash().isEmpty());
        assertFalse(workspace.current().metadata().permissions().publicRead());
        assertFalse(workspace.current().metadata().permissions().allowClone());
        assertTrue(workspace.current().metadata().permissions().editors().contains(AUTHOR));
        assertEquals(3, workspace.undoDepth());
        assertTrue(workspace.undo());
        assertTrue(workspace.current().metadata().permissions().editors().isEmpty());
        assertFalse(workspace.current().metadata().permissions().allowClone());
        assertFalse(workspace.current().metadata().permissions().publicRead());
        assertTrue(workspace.undo());
        assertTrue(workspace.current().metadata().permissions().allowClone());
        assertFalse(workspace.current().metadata().permissions().publicRead());
        assertTrue(workspace.undo());
        assertTrue(workspace.current().metadata().permissions().publicRead());
    }

    private ArenaRuleset loadStarter() throws IOException {
        try (var input = getClass().getResourceAsStream("/examples/arena_ruleset.json")) {
            assertNotNull(input);
            return (ArenaRuleset) new DesignCodec().load(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
