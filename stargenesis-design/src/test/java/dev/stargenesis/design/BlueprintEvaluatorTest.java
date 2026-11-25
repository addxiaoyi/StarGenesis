package dev.stargenesis.design;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class BlueprintEvaluatorTest {
    private Blueprint starter() throws IOException {
        try (var input = getClass().getResourceAsStream("/examples/blueprint.json")) {
            assertNotNull(input);
            return (Blueprint) new DesignCodec().load(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @Test void standardModulesHaveDeterministicPhysicalCostAndCapabilities() throws IOException {
        BlueprintEvaluator evaluator = BlueprintEvaluator.standard();
        BlueprintEvaluator.Evaluation core = evaluator.evaluate(starter());
        assertTrue(core.ready(), core.issues().toString());
        assertEquals(16, core.mass());
        assertEquals(128, core.supportCapacity());
        assertEquals(32, core.materialCost());
        assertEquals(java.util.Set.of("control"), core.installedCapabilities());
        assertEquals(evaluator.availableModules(), evaluator.availableModules().stream()
                .sorted(java.util.Comparator.comparing(BlueprintEvaluator.ComponentModule::id)).toList());
    }

    @Test void unknownAndUnderpoweredModulesFailPreviewWithoutInventingCapabilities() throws IOException {
        Blueprint source = starter();
        var component = new Blueprint.Component("engine", "genesis:unknown", 1, 0, 0, 0);
        Blueprint unknown = new Blueprint(source.metadata(), List.of(source.components().getFirst(), component),
                List.of(new Blueprint.Connection("core", "engine")));
        BlueprintEvaluator.Evaluation result = BlueprintEvaluator.standard().evaluate(unknown);
        assertFalse(result.ready());
        assertTrue(result.issues().stream().anyMatch(issue -> issue.code() == BlueprintEvaluator.IssueCode.UNKNOWN_MODULE));
        assertFalse(result.installedCapabilities().contains("thrust"));

        Blueprint thruster = new Blueprint(source.metadata(), List.of(source.components().getFirst(),
                new Blueprint.Component("engine", "genesis:thruster", 1, 0, 0, 0)),
                List.of(new Blueprint.Connection("core", "engine")));
        assertTrue(BlueprintEvaluator.standard().evaluate(thruster).issues().stream()
                .anyMatch(issue -> issue.code() == BlueprintEvaluator.IssueCode.POWER_DEFICIT));
    }

    @Test void addingPoweredMobilityChangesPhysicalCostAndInstalledCapabilities() throws IOException {
        Blueprint source = starter();
        Blueprint rover = new Blueprint(source.metadata(), List.of(source.components().getFirst(),
                new Blueprint.Component("solar", "genesis:solar_panel", 1, 0, 0, 0),
                new Blueprint.Component("engine", "genesis:thruster", -1, 0, 0, 0)), List.of(
                new Blueprint.Connection("core", "solar"), new Blueprint.Connection("core", "engine")));

        BlueprintEvaluator.Evaluation result = BlueprintEvaluator.standard().evaluate(rover);

        assertTrue(result.ready(), result.issues().toString());
        assertEquals(33, result.mass());
        assertEquals(64, result.materialCost());
        assertEquals(16, result.powerGeneration());
        assertEquals(7, result.powerDemand());
        assertEquals(40, result.thrust());
        assertEquals(java.util.Set.of("control", "power", "thrust"), result.installedCapabilities());
    }

    @Test void workspaceCommitsValidatedEditsAndUndoRestoresPriorDesign() throws IOException {
        Blueprint initial = starter();
        BlueprintWorkspace workspace = new BlueprintWorkspace(initial);
        workspace.add(new Blueprint.Component("solar", "genesis:solar_panel", 1, 0, 0, 0), "core");
        Blueprint withSolar = workspace.current();
        assertEquals(2, withSolar.components().size());
        assertTrue(BlueprintEvaluator.standard().evaluate(withSolar).ready());
        workspace.move("solar", 2, 0, 0);
        assertEquals(2, workspace.current().components().stream().filter(c -> c.id().equals("solar")).findFirst().orElseThrow().x());
        assertTrue(workspace.undo());
        assertEquals(withSolar, workspace.current());
        assertTrue(workspace.undo());
        assertEquals(initial, workspace.current());
        assertFalse(workspace.undo());
    }

    @Test void invalidEditsDoNotChangeDesignOrConsumeUndoHistory() throws IOException {
        Blueprint initial = starter();
        BlueprintWorkspace workspace = new BlueprintWorkspace(initial);
        assertThrows(IllegalArgumentException.class, () -> workspace.add(
                new Blueprint.Component("bad", "genesis:frame", 0, 0, 0, 0), "core"));
        assertEquals(initial, workspace.current());
        assertEquals(0, workspace.undoDepth());
        assertThrows(IllegalArgumentException.class, () -> workspace.disconnect("core", "core"));
        assertEquals(0, workspace.undoDepth());
    }

    @Test void undoHistoryIsBounded() throws IOException {
        BlueprintWorkspace workspace = new BlueprintWorkspace(starter());
        for (int i = 0; i <= BlueprintWorkspace.MAX_UNDO_STEPS; i++) workspace.rotate("core", i % 4);
        assertEquals(BlueprintWorkspace.MAX_UNDO_STEPS, workspace.undoDepth());
    }
}
