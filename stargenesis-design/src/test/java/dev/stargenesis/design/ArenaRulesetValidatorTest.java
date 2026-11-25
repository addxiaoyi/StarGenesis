package dev.stargenesis.design;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class ArenaRulesetValidatorTest {
    @Test
    void starterHasFullyReachableGridAndFiniteWorkEstimate() throws Exception {
        ArenaRuleset ruleset = starter();
        ArenaRulesetValidator.Analysis analysis = new ArenaRulesetValidator().requireValid(ruleset);
        assertEquals(256, analysis.cellCount());
        assertEquals(256, analysis.reachableCellCount());
        assertEquals(1_228_800, analysis.estimatedWorkUnits());
        assertTrue(analysis.valid());
    }

    @Test
    void validModelBoundsCanStillExceedThePublicationWorkBudget() throws Exception {
        ArenaRuleset source = starter();
        ArenaRuleset expensive = new ArenaRuleset(source.metadata(), 128, 128, 72_000, 64);
        ArenaRulesetValidator.Analysis analysis = new ArenaRulesetValidator().analyze(expensive);
        assertFalse(analysis.valid());
        assertTrue(analysis.estimatedWorkUnits() > ArenaRulesetValidator.MAX_WORK_UNITS);
        assertThrows(IllegalArgumentException.class, () -> new ArenaRulesetValidator().requireValid(expensive));
    }

    private static ArenaRuleset starter() throws Exception {
        try (InputStream input = ArenaRulesetValidatorTest.class.getResourceAsStream("/examples/arena_ruleset.json")) {
            assertNotNull(input);
            return (ArenaRuleset) new DesignCodec().load(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
