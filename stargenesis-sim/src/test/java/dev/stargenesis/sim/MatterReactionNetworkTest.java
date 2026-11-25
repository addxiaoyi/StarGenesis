package dev.stargenesis.sim;

import dev.stargenesis.api.design.DesignId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class MatterReactionNetworkTest {
    private static final MatterSpecies RAW = new MatterSpecies(new DesignId("matter:raw"), 1);
    private static final MatterSpecies INTERMEDIATE = new MatterSpecies(new DesignId("matter:intermediate"), 2);
    private static final MatterSpecies PRODUCT = new MatterSpecies(new DesignId("matter:product"), 4);

    @Test
    void appliesMassBalancedReactionsInStableIdOrder() {
        MatterReaction rawToIntermediate = new MatterReaction(new DesignId("reaction:a-raw"),
                Map.of(RAW.id(), 2), Map.of(INTERMEDIATE.id(), 1), 8);
        MatterReaction intermediateToProduct = new MatterReaction(new DesignId("reaction:b-product"),
                Map.of(INTERMEDIATE.id(), 2), Map.of(PRODUCT.id(), 1), 8);
        MatterReactionNetwork forward = new MatterReactionNetwork(List.of(RAW, INTERMEDIATE, PRODUCT),
                List.of(rawToIntermediate, intermediateToProduct));
        MatterReactionNetwork reversed = new MatterReactionNetwork(List.of(PRODUCT, INTERMEDIATE, RAW),
                List.of(intermediateToProduct, rawToIntermediate));

        MatterReactionStep result = forward.applyStep(Map.of(RAW.id(), 8L));
        assertEquals(result, reversed.applyStep(Map.of(RAW.id(), 8L)));
        assertEquals(0, result.inventory().get(INTERMEDIATE.id()));
        assertEquals(2, result.inventory().get(PRODUCT.id()));
        assertEquals(8, result.conservedMatterMass());
        assertEquals(List.of(new MatterReactionEvent(rawToIntermediate.id(), 4),
                new MatterReactionEvent(intermediateToProduct.id(), 2)), result.events());
    }

    @Test
    void firingAndInventoryLimitsAreEnforcedWithoutChangingTheInput() {
        MatterReaction reaction = new MatterReaction(new DesignId("reaction:convert"),
                Map.of(RAW.id(), 2), Map.of(INTERMEDIATE.id(), 1), 2);
        MatterReactionNetwork network = new MatterReactionNetwork(List.of(RAW, INTERMEDIATE), List.of(reaction));
        Map<DesignId, Long> input = Map.of(RAW.id(), 9L);

        MatterReactionStep result = network.applyStep(input);
        assertEquals(5, result.inventory().get(RAW.id()));
        assertEquals(2, result.inventory().get(INTERMEDIATE.id()));
        assertEquals(9, input.get(RAW.id()));
        assertThrows(UnsupportedOperationException.class, () -> result.inventory().put(RAW.id(), 0L));
        assertThrows(IllegalArgumentException.class, () -> network.applyStep(Map.of(RAW.id(), -1L)));
        assertThrows(IllegalArgumentException.class,
                () -> network.applyStep(Map.of(RAW.id(), MatterReactionNetwork.MAX_UNITS_PER_SPECIES + 1L)));
        assertThrows(IllegalArgumentException.class,
                () -> network.applyStep(Map.of(new DesignId("matter:unknown"), 1L)));
    }

    @Test
    void networkLimitsTotalFiringsAcrossAllReactions() {
        MatterSpecies sourceA = new MatterSpecies(new DesignId("matter:source-a"), 1);
        MatterSpecies sourceB = new MatterSpecies(new DesignId("matter:source-b"), 1);
        MatterSpecies outputA = new MatterSpecies(new DesignId("matter:output-a"), 1);
        MatterSpecies outputB = new MatterSpecies(new DesignId("matter:output-b"), 1);
        MatterReaction first = new MatterReaction(new DesignId("reaction:a"),
                Map.of(sourceA.id(), 1), Map.of(outputA.id(), 1), MatterReaction.MAX_FIRINGS_PER_REACTION);
        MatterReaction second = new MatterReaction(new DesignId("reaction:b"),
                Map.of(sourceB.id(), 1), Map.of(outputB.id(), 1), MatterReaction.MAX_FIRINGS_PER_REACTION);
        MatterReactionNetwork network = new MatterReactionNetwork(
                List.of(sourceA, sourceB, outputA, outputB), List.of(first, second));

        MatterReactionStep result = network.applyStep(Map.of(sourceA.id(), 1_024L, sourceB.id(), 1_024L));
        assertEquals(MatterReactionNetwork.MAX_FIRINGS_PER_STEP,
                result.events().stream().mapToInt(MatterReactionEvent::firings).sum());
        assertEquals(1_024, result.inventory().get(sourceB.id()));
        assertEquals(0, result.inventory().get(outputB.id()));
    }

    @Test
    void availableProductCapacityLimitsOtherwiseValidReactionFirings() {
        MatterSpecies heavy = new MatterSpecies(new DesignId("matter:heavy"), 2);
        MatterSpecies light = new MatterSpecies(new DesignId("matter:light"), 1);
        MatterReaction split = new MatterReaction(new DesignId("reaction:split"),
                Map.of(heavy.id(), 1), Map.of(light.id(), 2), 10);
        MatterReactionNetwork network = new MatterReactionNetwork(List.of(heavy, light), List.of(split));

        MatterReactionStep result = network.applyStep(Map.of(heavy.id(), 1L,
                light.id(), MatterReactionNetwork.MAX_UNITS_PER_SPECIES - 1L));
        assertEquals(1, result.inventory().get(heavy.id()));
        assertEquals(MatterReactionNetwork.MAX_UNITS_PER_SPECIES - 1L, result.inventory().get(light.id()));
        assertTrue(result.events().isEmpty());
        assertEquals(2 + MatterReactionNetwork.MAX_UNITS_PER_SPECIES - 1L, result.conservedMatterMass());
    }

    @Test
    void rejectsUnbalancedUnknownAndDuplicateReactionDefinitions() {
        MatterReaction unbalanced = new MatterReaction(new DesignId("reaction:unbalanced"),
                Map.of(RAW.id(), 1), Map.of(INTERMEDIATE.id(), 1), 1);
        assertThrows(IllegalArgumentException.class,
                () -> new MatterReactionNetwork(List.of(RAW, INTERMEDIATE), List.of(unbalanced)));

        MatterReaction unknown = new MatterReaction(new DesignId("reaction:unknown"),
                Map.of(new DesignId("matter:missing"), 1), Map.of(RAW.id(), 1), 1);
        assertThrows(IllegalArgumentException.class,
                () -> new MatterReactionNetwork(List.of(RAW), List.of(unknown)));

        MatterReaction duplicate = new MatterReaction(new DesignId("reaction:duplicate"),
                Map.of(RAW.id(), 1), Map.of(RAW.id(), 1), 1);
        assertThrows(IllegalArgumentException.class,
                () -> new MatterReactionNetwork(List.of(RAW), List.of(duplicate, duplicate)));
        assertThrows(IllegalArgumentException.class,
                () -> new MatterReactionNetwork(List.of(RAW, RAW), List.of()));
    }

    @Test
    void rejectsReactionDefinitionsOutsideFiniteLimits() {
        assertThrows(IllegalArgumentException.class,
                () -> new MatterReaction(new DesignId("reaction:zero"), Map.of(RAW.id(), 1), Map.of(PRODUCT.id(), 1), 0));
        assertThrows(IllegalArgumentException.class,
                () -> new MatterReaction(new DesignId("reaction:empty"), Map.of(), Map.of(RAW.id(), 1), 1));
        MatterReactionStep empty = new MatterReactionNetwork(List.of(), List.of()).applyStep(Map.of());
        assertTrue(empty.inventory().isEmpty());
        assertEquals(0, empty.conservedMatterMass());
    }
}
