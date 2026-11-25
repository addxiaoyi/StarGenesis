package dev.stargenesis.design;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import dev.stargenesis.design.ArenaRuleset.Cell;
import dev.stargenesis.design.ArenaRuleset.Combination;
import dev.stargenesis.design.ArenaRuleset.Condition;
import dev.stargenesis.design.ArenaRuleset.Layout;
import dev.stargenesis.design.ArenaRuleset.Metric;
import dev.stargenesis.design.ArenaRuleset.Team;
import dev.stargenesis.design.ArenaRuleset.Victory;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ArenaRulesetV2Test {
    private static final UUID AUTHOR = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final DesignCodec codec = new DesignCodec();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void schemaTwoExampleRoundTripsAndValidatesAgainstItsDedicatedSchema() throws Exception {
        ArenaRuleset ruleset = v2Starter();
        assertEquals(ArenaRuleset.SCHEMA_VERSION, ruleset.metadata().schemaVersion());
        assertEquals(ruleset, codec.load(codec.write(ruleset)));
        var schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(json.readTree(resource("/schemas/arena_ruleset-v2.schema.json")));
        assertTrue(schema.validate(json.readTree(resource("/examples/arena_ruleset-v2.json"))).isEmpty());
    }

    @Test
    void v1MigrationVerifiesOriginalHashPreservesIdentityAndCreatesPlayableDefaults() throws Exception {
        String legacy = resource("/examples/arena_ruleset.json");
        ArenaRuleset old = (ArenaRuleset) codec.load(legacy);
        DesignCodec.MigrationResult result = codec.migrateArenaV1(legacy);
        ArenaRuleset upgraded = (ArenaRuleset) result.document();

        assertEquals(old.metadata().hash(), result.previousHash());
        assertEquals(old.metadata().id(), upgraded.metadata().id());
        assertEquals(old.metadata().author(), upgraded.metadata().author());
        assertEquals(old.metadata().permissions(), upgraded.metadata().permissions());
        assertEquals(old.metadata().dependencies(), upgraded.metadata().dependencies());
        assertEquals("1.0.1", upgraded.metadata().version());
        assertEquals(2, upgraded.metadata().schemaVersion());
        assertTrue(new ArenaRulesetValidator().requireValid(upgraded).valid());
        assertEquals(result, codec.migrateArenaV1(legacy));

        ObjectNode changed = (ObjectNode) json.readTree(legacy);
        ((ObjectNode) changed.path("metadata")).put("hash", "0".repeat(64));
        assertThrows(DesignValidationException.class, () -> codec.migrateArenaV1(changed.toString()));
    }

    @Test
    void migrationRaisesShortRoundsAndAddsTheMinimumTwoUnitBudget() throws Exception {
        ObjectNode legacy = (ObjectNode) json.readTree(resource("/examples/arena_ruleset.json"));
        legacy.put("durationSteps", 1);
        legacy.put("unitBudget", 1);
        ((ObjectNode) legacy.path("metadata")).put("hash", "");
        ArenaRuleset upgraded = (ArenaRuleset) codec.migrateArenaV1(codec.seal(legacy.toString())).document();

        assertEquals(2, upgraded.unitBudget());
        assertTrue(upgraded.durationSteps() > 1);
        assertTrue(new ArenaRulesetValidator().requireValid(upgraded).valid());
    }

    @Test
    void schemaOneStillRejectsVersionTwoOnlyFields() throws Exception {
        ObjectNode legacy = (ObjectNode) json.readTree(resource("/examples/arena_ruleset.json"));
        legacy.set("layout", json.createObjectNode());
        ((ObjectNode) legacy.path("metadata")).put("hash", "");
        assertThrows(DesignValidationException.class, () -> codec.seal(legacy.toString()));
    }

    @Test
    void pathsMustRemainConnectedAndEveryTeamMustHaveAReachableWinningRoute() throws Exception {
        ArenaRuleset source = v2Starter();
        List<Team> teams = List.of(
                new Team("red", List.of(new Cell(0, 0))),
                new Team("blue", List.of(new Cell(15, 15))));
        Layout separated = new Layout(List.of(new Cell(1, 0), new Cell(0, 1), new Cell(2, 1), new Cell(1, 2)),
                teams, List.of(new Cell(1, 1)));
        ArenaRuleset unreachable = replace(source, 4, separated, source.victory());
        ArenaRulesetValidator.Analysis analysis = new ArenaRulesetValidator().analyze(unreachable);
        assertFalse(analysis.valid());
        assertTrue(analysis.issues().stream().anyMatch(issue -> issue.contains("unreachable")));
        assertThrows(IllegalArgumentException.class, () -> new ArenaRulesetValidator().requireValid(unreachable));
    }

    @Test
    void layoutBoundsSpawnBudgetAndVictoryFeasibilityAreChecked() throws Exception {
        ArenaRuleset source = v2Starter();
        Layout oneObjective = new Layout(List.of(), source.layout().teams(), List.of(new Cell(8, 7)));
        Victory tooManyPoints = new Victory(Combination.ALL,
                List.of(new Condition(Metric.OBJECTIVE_POINTS, Math.toIntExact(source.durationSteps() + 1))));
        assertFalse(new ArenaRulesetValidator().analyze(replace(source, 4, oneObjective, tooManyPoints)).valid());

        ArenaRuleset lowBudget = replace(source, 2, source.layout(), source.victory());
        assertTrue(new ArenaRulesetValidator().analyze(lowBudget).issues().stream()
                .anyMatch(issue -> issue.contains("spawn count")));

        Layout outside = new Layout(List.of(new Cell(16, 2)), source.layout().teams(), source.layout().objectives());
        assertTrue(new ArenaRulesetValidator().analyze(replace(source, 4, outside, source.victory())).issues().stream()
                .anyMatch(issue -> issue.contains("outside")));
    }

    @Test
    void victoryConditionCombinationsAreDeterministic() throws Exception {
        ArenaRuleset source = v2Starter();
        var conditions = List.of(new Condition(Metric.CONTROLLED_OBJECTIVES, 2),
                new Condition(Metric.OBJECTIVE_POINTS, 25));
        assertTrue(new Victory(Combination.ANY, conditions).met(0, 25));
        assertFalse(new Victory(Combination.ALL, conditions).met(0, 25));
        assertTrue(new Victory(Combination.ALL, conditions).met(2, 25));
        assertEquals(source.width(), 16);
    }

    private ArenaRuleset replace(ArenaRuleset source, int units, Layout layout, Victory victory) {
        return new ArenaRuleset(source.metadata(), source.width(), source.height(), source.durationSteps(), units,
                layout, victory);
    }

    private ArenaRuleset v2Starter() throws Exception {
        return (ArenaRuleset) codec.load(resource("/examples/arena_ruleset-v2.json"));
    }

    private String resource(String name) throws Exception {
        try (InputStream input = getClass().getResourceAsStream(name)) {
            assertNotNull(input, name);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
