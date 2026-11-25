package dev.stargenesis.design;

import dev.stargenesis.api.design.DesignPermissions;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BlueprintWorkspaceTest {
    @Test void permissionAndVersionChangesParticipateInUndoAndClearTheContentHash() throws Exception {
        DesignCodec codec = new DesignCodec();
        Blueprint source;
        try (var input = getClass().getResourceAsStream("/examples/blueprint.json")) {
            source = (Blueprint) codec.load(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        BlueprintWorkspace workspace = new BlueprintWorkspace(source);
        workspace.permissions(new DesignPermissions(false, true, Set.of(new UUID(0, 17))));
        Blueprint licensed = workspace.current();
        assertEquals("", licensed.metadata().hash());
        workspace.version("2.1.0");
        assertEquals("2.1.0", workspace.current().metadata().version());
        assertTrue(workspace.undo());
        assertEquals(licensed, workspace.current());
        assertTrue(workspace.undo());
        assertEquals(source, workspace.current());
        workspace.permissions(source.metadata().permissions());
        workspace.version(source.metadata().version());
        assertEquals(0, workspace.undoDepth());
        assertThrows(IllegalArgumentException.class, () -> workspace.version("not-a-version"));
        assertEquals(source, workspace.current());
    }
}
