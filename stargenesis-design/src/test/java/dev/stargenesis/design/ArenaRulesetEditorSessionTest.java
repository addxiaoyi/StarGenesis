package dev.stargenesis.design;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ArenaRulesetEditorSessionTest {
    private final DesignCodec codec = new DesignCodec();
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000011");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-0000-0000-000000000012");

    @Test
    void cloneEditPreviewSaveAndVersionBumpAreAuthorizedAndBounded() throws Exception {
        ArenaRuleset starter = starter();
        ArenaRulesetEditorSession editor = ArenaRulesetEditorSession.create(PLAYER, starter);
        assertEquals(PLAYER, editor.draft().metadata().author());
        assertEquals(1, editor.draft().metadata().dependencies().size());
        assertThrows(IllegalStateException.class, () -> editor.save(codec));

        editor.adjustWidth(1);
        assertTrue(editor.canUndo());
        assertTrue(editor.preview().valid());
        ArenaRuleset saved = (ArenaRuleset) codec.load(editor.save(codec));
        assertEquals(17, saved.width());
        assertEquals("1.0.0", saved.metadata().version());

        ArenaRulesetEditorSession next = ArenaRulesetEditorSession.create(PLAYER, starter, saved);
        assertEquals("1.0.1", next.draft().metadata().version());
        next.adjustWidth(1);
        next.bumpPatchVersion();
        assertEquals("1.0.2", next.draft().metadata().version());
        assertTrue(next.undo());
        assertEquals("1.0.1", next.draft().metadata().version());
    }

    @Test
    void checkpointRestoresDraftUndoAndPreviewForTheSameActor() throws Exception {
        ArenaRulesetEditorSession editor = ArenaRulesetEditorSession.create(PLAYER, starter());
        editor.adjustDuration(100);
        editor.preview();

        ArenaRulesetEditorSession resumed = ArenaRulesetEditorSession.resume(PLAYER, editor.encodeCheckpoint(codec), codec);
        assertEquals(editor.draft().width(), resumed.draft().width());
        assertEquals(editor.draft().durationSteps(), resumed.draft().durationSteps());
        assertEquals(editor.draft().metadata().version(), resumed.draft().metadata().version());
        assertTrue(resumed.canUndo());
        assertDoesNotThrow(() -> resumed.save(codec));
        assertThrows(SecurityException.class,
                () -> ArenaRulesetEditorSession.resume(STRANGER, editor.encodeCheckpoint(codec), codec));
    }

    @Test
    void privateRulesetCannotBeClonedOrEditedByStranger() throws Exception {
        ArenaRuleset playerOwned = ArenaRulesetEditorSession.create(PLAYER, starter()).draft();
        assertThrows(SecurityException.class, () -> ArenaRulesetEditorSession.create(STRANGER, playerOwned));
        assertThrows(SecurityException.class, () -> ArenaRulesetEditorSession.edit(STRANGER, playerOwned));
    }

    @Test
    void sharingRequiresTheAuthorAndPersistsWithItsOwnVersion() throws Exception {
        ArenaRulesetEditorSession editor = ArenaRulesetEditorSession.create(PLAYER, starter());
        editor.grantEditor(STRANGER);
        editor.preview();
        ArenaRuleset privateRelease = (ArenaRuleset) codec.load(editor.save(codec));
        ArenaRulesetEditorSession collaborator = ArenaRulesetEditorSession.edit(STRANGER, privateRelease);
        assertThrows(SecurityException.class, () -> collaborator.setSharing(true, true));

        editor.preview();
        editor.setSharing(true, false);
        assertThrows(IllegalStateException.class, () -> editor.save(codec));
        assertTrue(editor.undo());
        assertEquals(privateRelease, editor.draft());
        editor.setSharing(true, false);
        editor.preview();
        ArenaRulesetEditorSession resumed = ArenaRulesetEditorSession.resume(PLAYER, editor.encodeCheckpoint(codec), codec);
        ArenaRuleset shared = (ArenaRuleset) codec.load(resumed.save(codec));
        assertEquals("1.0.2", shared.metadata().version());
        assertTrue(shared.metadata().permissions().publicRead());
        assertFalse(shared.metadata().permissions().allowClone());
        assertTrue(shared.metadata().permissions().editors().contains(STRANGER));
        assertThrows(SecurityException.class, () -> ArenaRulesetEditorSession.create(STRANGER, shared));
    }

    @Test
    void legacyDraftCanUpgradeAndEditMapAndVictoryThroughTheNormalSavePath() throws Exception {
        ArenaRulesetEditorSession editor = ArenaRulesetEditorSession.create(PLAYER, starter());
        editor.upgrade(codec);
        assertEquals(2, editor.draft().metadata().schemaVersion());
        assertEquals("1.0.1", editor.draft().metadata().version());

        editor.toggleObstacle(new ArenaRuleset.Cell(3, 3));
        editor.toggleObjective(new ArenaRuleset.Cell(10, 10));
        editor.putSpawn("red", 1, new ArenaRuleset.Cell(1, 0));
        editor.setVictoryMode(ArenaRuleset.Combination.ALL);
        editor.setVictoryThreshold(ArenaRuleset.Metric.OBJECTIVE_POINTS, 25);
        assertTrue(editor.preview().valid());

        ArenaRuleset saved = (ArenaRuleset) codec.load(editor.save(codec));
        assertTrue(saved.layout().obstacles().contains(new ArenaRuleset.Cell(3, 3)));
        assertTrue(saved.layout().objectives().contains(new ArenaRuleset.Cell(10, 10)));
        assertEquals(new ArenaRuleset.Cell(1, 0), saved.layout().teams().getFirst().spawns().getFirst());
        assertEquals(ArenaRuleset.Combination.ALL, saved.victory().combination());
        assertTrue(saved.victory().conditions().stream().anyMatch(condition ->
                condition.metric() == ArenaRuleset.Metric.OBJECTIVE_POINTS && condition.threshold() == 25));

        ArenaRulesetEditorSession resumed = ArenaRulesetEditorSession.resume(PLAYER,
                ArenaRulesetEditorSession.edit(PLAYER, saved).encodeCheckpoint(codec), codec);
        assertEquals(saved, resumed.saved());
    }

    private ArenaRuleset starter() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/examples/arena_ruleset.json")) {
            assertNotNull(input);
            return (ArenaRuleset) codec.load(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
