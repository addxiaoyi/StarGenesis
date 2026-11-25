package dev.stargenesis.paper.arena;

import dev.stargenesis.api.design.DesignId;
import dev.stargenesis.api.design.DesignMetadata;
import dev.stargenesis.design.ArenaRuleset;
import dev.stargenesis.design.ArenaRulesetWorkspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ArenaDraftAuthorizationTest {
    private static final UUID AUTHOR = new UUID(0, 141);
    private static final UUID EDITOR = new UUID(0, 142);
    private static final DesignId ID = new DesignId("player:collaborative_arena");
    @TempDir Path directory;

    @Test void privateDraftCannotBeOverwrittenBySelfAuthorizingInTheCandidate() throws Exception {
        ArenaRulesetStore store = new ArenaRulesetStore(directory);
        ArenaRuleset draft = store.create(AUTHOR, ID);
        ArenaRulesetWorkspace forged = new ArenaRulesetWorkspace(draft);
        forged.editor(EDITOR, true);
        assertThrows(SecurityException.class, () -> store.saveDraft(EDITOR, forged.current(), draft.metadata().hash()));
        assertEquals(draft, store.loadDraft(AUTHOR, ID).orElseThrow());
    }

    @Test void revokedEditorCannotRestoreOldPermissionUsingTheNewHash() throws Exception {
        ArenaRulesetStore store = new ArenaRulesetStore(directory);
        ArenaRuleset shared = shared(store);
        ArenaRulesetWorkspace revoke = new ArenaRulesetWorkspace(shared);
        revoke.editor(EDITOR, false);
        ArenaRuleset revoked = store.saveDraft(AUTHOR, revoke.current(), shared.metadata().hash());
        assertThrows(SecurityException.class, () -> store.saveDraft(EDITOR, shared, revoked.metadata().hash()));
        assertThrows(SecurityException.class, () -> new ArenaRulesetStore(directory).loadDraft(EDITOR, ID));
        assertEquals(revoked, store.loadDraft(AUTHOR, ID).orElseThrow());
    }

    @Test void legitimateEditorCanChangeRulesButCannotGrantPermissionsOrRewriteOrigin() throws Exception {
        ArenaRulesetStore store = new ArenaRulesetStore(directory);
        ArenaRuleset shared = shared(store);
        ArenaRulesetWorkspace edit = new ArenaRulesetWorkspace(shared);
        edit.resize(24, 24);
        ArenaRuleset changed = store.saveDraft(EDITOR, edit.current(), shared.metadata().hash());
        assertEquals(24, changed.width());
        ArenaRulesetWorkspace permissions = new ArenaRulesetWorkspace(changed);
        permissions.publicRead(true);
        assertThrows(SecurityException.class, () -> store.saveDraft(EDITOR, permissions.current(), changed.metadata().hash()));
        assertTrue(permissions.undo());
        permissions.version("2.0.0");
        assertThrows(SecurityException.class, () -> store.saveDraft(EDITOR, permissions.current(), changed.metadata().hash()));
        DesignMetadata old = changed.metadata();
        DesignMetadata metadata = new DesignMetadata(old.id(), old.schemaVersion(), old.version(), old.author(),
                "player:forged-source", old.dependencies(), "", old.permissions());
        ArenaRuleset forged = new ArenaRuleset(metadata, changed.width(), changed.height(), changed.durationSteps(), changed.unitBudget());
        assertThrows(SecurityException.class, () -> store.saveDraft(EDITOR, forged, changed.metadata().hash()));
        assertEquals(changed, store.loadDraft(AUTHOR, ID).orElseThrow());
    }

    @Test void editorCannotCreateADraftForAnotherAuthorByChangingItsId() throws Exception {
        ArenaRulesetStore store = new ArenaRulesetStore(directory);
        ArenaRuleset shared = shared(store);
        DesignMetadata old = shared.metadata();
        DesignId otherId = new DesignId("player:forged_draft");
        DesignMetadata metadata = new DesignMetadata(otherId, old.schemaVersion(), old.version(), old.author(),
                old.source(), old.dependencies(), "", old.permissions());
        ArenaRuleset forged = new ArenaRuleset(metadata, shared.width(), shared.height(), shared.durationSteps(), shared.unitBudget());
        assertThrows(SecurityException.class, () -> store.saveDraft(EDITOR, forged, ""));
        assertTrue(store.loadDraft(AUTHOR, otherId).isEmpty());
    }

    private ArenaRuleset shared(ArenaRulesetStore store) throws Exception {
        ArenaRuleset draft = store.create(AUTHOR, ID);
        ArenaRulesetWorkspace workspace = new ArenaRulesetWorkspace(draft);
        workspace.editor(EDITOR, true);
        return store.saveDraft(AUTHOR, workspace.current(), draft.metadata().hash());
    }
}
