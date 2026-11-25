package dev.stargenesis.design;

import dev.stargenesis.api.design.DesignId;
import dev.stargenesis.api.design.DesignMetadata;
import dev.stargenesis.api.design.DesignPermissions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BlueprintEditorTest {
    private static final UUID AUTHOR = new UUID(0, 1);
    private static final UUID EDITOR = new UUID(0, 2);
    private static final UUID STRANGER = new UUID(0, 3);
    private final ModuleCatalog catalog = ModuleCatalog.standard();
    private final BlueprintBudget budget = BlueprintBudget.standard();

    @Test
    void analyzerCalculatesCostsCapabilitiesAndStableShapeHash() {
        Blueprint blueprint = blueprint(new DesignPermissions(true, true, Set.of()), List.of(
                component("core", ModuleCatalog.CORE_TYPE, 0, 0),
                component("collector", "genesis:collector", 1, 0)), List.of(connection("core", "collector")));
        BlueprintAnalysis analysis = new BlueprintAnalyzer(catalog, budget).analyze(blueprint);

        assertTrue(analysis.valid(), analysis.issues().toString());
        assertEquals(14, analysis.totalMass());
        assertEquals(7, analysis.totalEnergy());
        assertEquals(160, analysis.maximumDurability());
        assertEquals(1, analysis.capabilityCounts().get(ModuleCapability.GATHER));
        assertEquals(analysis.shapeHash(), new BlueprintAnalyzer(catalog, budget).analyze(blueprint).shapeHash());
    }

    @Test
    void analyzerReportsBudgetAndPhysicalConnectionFailures() {
        BlueprintBudget small = new BlueprintBudget(4, 4, 10, 10);
        Blueprint blueprint = blueprint(new DesignPermissions(true, true, Set.of()), List.of(
                component("core", ModuleCatalog.CORE_TYPE, 0, 0),
                component("collector", "genesis:collector", 6, 0)), List.of(connection("core", "collector")));

        BlueprintAnalysis analysis = new BlueprintAnalyzer(catalog, small).analyze(blueprint);

        assertTrue(analysis.issues().contains("Mass budget exceeded"));
        assertTrue(analysis.issues().stream().anyMatch(issue -> issue.startsWith("Connection range exceeded")));
    }

    @Test
    void editSessionPreviewsShapeChangesUndoAndRequiresFreshPreviewBeforeSave() {
        BlueprintEditorSession editor = BlueprintEditorSession.cloneFor(EDITOR,
                blueprint(new DesignPermissions(true, true, Set.of()), List.of(component("core", ModuleCatalog.CORE_TYPE, 0, 0)), List.of()),
                catalog, budget);
        Blueprint.Component placed = editor.place("genesis:collector", 1, 0, 0);

        BlueprintPreview preview = editor.preview();
        assertTrue(preview.canSave(), preview.issues().toString());
        assertEquals(List.of(placed.id()), preview.difference().addedComponents());
        editor.place("genesis:frame", 0, 1, 0);
        assertThrows(IllegalStateException.class, () -> editor.save(new DesignCodec()));
        assertTrue(editor.undo());
        assertTrue(editor.preview().canSave());
        editor.save(new DesignCodec());
        assertFalse(editor.hasUnsavedChanges());
        assertFalse(editor.undo());
    }

    @Test
    void checkpointRestoresDraftUndoHistoryAndInvalidatesPreviewAfterFurtherEdits() {
        DesignCodec codec = new DesignCodec();
        BlueprintEditorSession editor = BlueprintEditorSession.cloneFor(EDITOR,
                blueprint(new DesignPermissions(true, true, Set.of()), List.of(component("core", ModuleCatalog.CORE_TYPE, 0, 0)), List.of()),
                catalog, budget);
        editor.place("genesis:collector", 1, 0, 0);
        editor.preview();

        BlueprintEditorSession resumed = BlueprintEditorSession.restoreCheckpoint(EDITOR,
                editor.encodeCheckpoint(codec), codec, catalog, budget);
        assertTrue(resumed.hasUnsavedChanges());
        assertTrue(resumed.canUndo());
        assertFalse(resumed.preview().difference().addedComponents().isEmpty());
        assertTrue(resumed.undo());
        assertFalse(resumed.hasUnsavedChanges());
        assertThrows(IllegalStateException.class, () -> resumed.save(codec));
    }

    @Test
    void checkpointRejectsAnotherActorAndCorruptedBytes() {
        DesignCodec codec = new DesignCodec();
        BlueprintEditorSession editor = BlueprintEditorSession.cloneFor(EDITOR,
                blueprint(new DesignPermissions(true, true, Set.of()), List.of(component("core", ModuleCatalog.CORE_TYPE, 0, 0)), List.of()),
                catalog, budget);
        byte[] checkpoint = editor.encodeCheckpoint(codec);

        assertThrows(SecurityException.class, () -> BlueprintEditorSession.restoreCheckpoint(STRANGER,
                checkpoint, codec, catalog, budget));
        assertThrows(IllegalArgumentException.class, () -> BlueprintEditorSession.restoreCheckpoint(EDITOR,
                new byte[] {1, 2, 3}, codec, catalog, budget));
    }

    @Test
    void connectionChangesProduceShapeDifferencesAndCanBeUndone() {
        Blueprint source = blueprint(new DesignPermissions(true, true, Set.of(EDITOR)), List.of(
                component("core", ModuleCatalog.CORE_TYPE, 0, 0),
                component("collector", "genesis:collector", 1, 0),
                component("frame", "genesis:frame", 1, 1)), List.of(
                connection("core", "collector"), connection("collector", "frame"), connection("core", "frame")));
        BlueprintEditorSession editor = BlueprintEditorSession.edit(EDITOR, source, catalog, budget);

        editor.disconnect("collector", "frame");
        assertEquals(List.of("collector->frame"), editor.preview().difference().removedConnections());
        assertTrue(editor.undo());
        assertTrue(editor.preview().difference().removedConnections().isEmpty());
    }

    @Test
    void unchangedActionsDoNotCreateUndoEntries() {
        BlueprintEditorSession editor = BlueprintEditorSession.edit(EDITOR,
                blueprint(new DesignPermissions(true, true, Set.of(EDITOR)), List.of(component("core", ModuleCatalog.CORE_TYPE, 0, 0)), List.of()),
                catalog, budget);

        editor.move("core", 0, 0, 0);
        assertFalse(editor.canUndo());
        assertThrows(IllegalArgumentException.class, () -> editor.connect("core", "core"));
        assertFalse(editor.canUndo());
    }

    @Test
    void saveIsAuthorizedSignedAndRetainsPinnedSource() {
        Blueprint source = blueprint(new DesignPermissions(true, true, Set.of(EDITOR)),
                List.of(component("core", ModuleCatalog.CORE_TYPE, 0, 0)), List.of());
        BlueprintEditorSession editor = BlueprintEditorSession.cloneFor(EDITOR, source, catalog, budget);
        editor.place("genesis:collector", 1, 0, 0);
        editor.preview();
        String encoded = editor.save(new DesignCodec());
        Blueprint stored = (Blueprint) new DesignCodec().load(encoded);

        assertEquals(EDITOR, stored.metadata().author());
        assertEquals(source.metadata().id(), stored.metadata().dependencies().getFirst().id());
        assertFalse(editor.hasUnsavedChanges());
        assertFalse(stored.metadata().permissions().allowClone());
        assertThrows(SecurityException.class, () -> new DesignCodec().writeEdited(source, STRANGER));
    }

    @Test
    void cloneAndEditAuthorizationAreCheckedBeforeOpeningSession() {
        Blueprint privateBlueprint = blueprint(new DesignPermissions(false, true, Set.of()),
                List.of(component("core", ModuleCatalog.CORE_TYPE, 0, 0)), List.of());
        Blueprint noClone = blueprint(new DesignPermissions(true, false, Set.of()),
                List.of(component("core", ModuleCatalog.CORE_TYPE, 0, 0)), List.of());

        assertThrows(SecurityException.class, () -> BlueprintEditorSession.cloneFor(STRANGER, privateBlueprint, catalog, budget));
        assertThrows(SecurityException.class, () -> BlueprintEditorSession.cloneFor(EDITOR, noClone, catalog, budget));
        assertThrows(SecurityException.class, () -> BlueprintEditorSession.edit(STRANGER, privateBlueprint, catalog, budget));
    }

    @Test
    void moduleCatalogRejectsDuplicateTypesAndCoreSubstitution() {
        ModuleDefinition core = catalog.require(ModuleCatalog.CORE_TYPE);
        assertThrows(IllegalArgumentException.class, () -> new ModuleCatalog(List.of(core, core)));
        assertThrows(IllegalArgumentException.class, () -> new ModuleDefinition("other:core",
                Set.of(ModuleCapability.CORE_CONTROL), 1, 1, 10, 1));
    }

    private static Blueprint blueprint(DesignPermissions permissions, List<Blueprint.Component> components,
                                       List<Blueprint.Connection> connections) {
        return new Blueprint(new DesignMetadata(new DesignId("genesis:test"), 1, "1.0.0", AUTHOR,
                "test:fixture", List.of(), "0".repeat(64), permissions), components, connections);
    }

    private static Blueprint.Component component(String id, String type, int x, int y) {
        return new Blueprint.Component(id, type, x, y, 0, 0);
    }

    private static Blueprint.Connection connection(String from, String to) {
        return new Blueprint.Connection(from, to);
    }
}
