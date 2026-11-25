package dev.stargenesis.paper;

import dev.stargenesis.api.design.DesignId;
import dev.stargenesis.sim.MatterReaction;
import dev.stargenesis.sim.MatterSpecies;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EcologyRecipeDraftTest {
    private static final MatterSpecies BIOMASS = new MatterSpecies(new DesignId("ecology:biomass"), 2);
    private static final MatterSpecies DETRITUS = new MatterSpecies(new DesignId("ecology:detritus"), 3);
    private static final MatterSpecies MINERAL = new MatterSpecies(new DesignId("ecology:mineral"), 1);
    private static final MatterSpecies NUTRIENT = new MatterSpecies(new DesignId("ecology:nutrient"), 1);
    private static final List<MatterSpecies> SPECIES = List.of(BIOMASS, DETRITUS, MINERAL, NUTRIENT);

    @Test
    void compilesMassBalancedRecipesWithMultipleInputsAndOutputs() {
        MatterReaction reaction = EcologyRecipeDraft.build(new DesignId("player:compound"), SPECIES,
                List.of(ingredient(BIOMASS, 1), ingredient(MINERAL, 2)),
                List.of(ingredient(DETRITUS, 1), ingredient(NUTRIENT, 1)), 12);

        assertEquals(Map.of(BIOMASS.id(), 1, MINERAL.id(), 2), reaction.inputs());
        assertEquals(Map.of(DETRITUS.id(), 1, NUTRIENT.id(), 1), reaction.outputs());
        assertEquals(12, reaction.maxFiringsPerStep());
    }

    @Test
    void combinesRepeatedSlotsBeforeCheckingMassBalance() {
        MatterReaction reaction = EcologyRecipeDraft.build(new DesignId("player:combined"), SPECIES,
                List.of(ingredient(BIOMASS, 1), ingredient(BIOMASS, 2)),
                List.of(ingredient(DETRITUS, 2)), 8);

        assertEquals(Map.of(BIOMASS.id(), 3), reaction.inputs());
        assertEquals(Map.of(DETRITUS.id(), 2), reaction.outputs());
    }

    @Test
    void rejectsEmptyUnknownUnbalancedAndOverfullDrafts() {
        DesignId reactionId = new DesignId("player:invalid");
        assertThrows(IllegalArgumentException.class,
                () -> EcologyRecipeDraft.build(reactionId, SPECIES, List.of(), List.of(ingredient(NUTRIENT, 1)), 1));
        assertThrows(IllegalArgumentException.class,
                () -> EcologyRecipeDraft.build(reactionId, SPECIES, List.of(ingredient(BIOMASS, 1)),
                        List.of(ingredient(NUTRIENT, 1)), 1));
        assertThrows(IllegalArgumentException.class,
                () -> EcologyRecipeDraft.build(reactionId, SPECIES,
                        List.of(ingredient(new MatterSpecies(new DesignId("missing:species"), 1), 1)),
                        List.of(ingredient(NUTRIENT, 2)), 1));

        List<EcologyRecipeDraft.Ingredient> overfull = java.util.Collections.nCopies(
                EcologyRecipeDraft.MAX_TERMS + 1, ingredient(BIOMASS, 1));
        assertThrows(IllegalArgumentException.class,
                () -> EcologyRecipeDraft.build(reactionId, SPECIES, overfull, List.of(ingredient(DETRITUS, 2)), 1));
    }

    private static EcologyRecipeDraft.Ingredient ingredient(MatterSpecies matter, int amount) {
        return ingredient(matter.id(), amount);
    }

    private static EcologyRecipeDraft.Ingredient ingredient(DesignId matterId, int amount) {
        return new EcologyRecipeDraft.Ingredient(matterId, amount);
    }
}
