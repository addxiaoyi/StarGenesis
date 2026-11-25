package dev.stargenesis.sim;

import dev.stargenesis.api.design.DesignId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class EcologyWorldTest {
    private static final EcologyGenome GRAZER = new EcologyGenome(3, 1, 24, 1);
    private static final EcologyGenome MOSS = new EcologyGenome(1, 1, 8, 2);

    private static EcologySettings settings(long seed) {
        return new EcologySettings("ecology-test", seed, 6, 40, 100, 2, 3, 24, 450,
                List.of(new EcologyFounder(GRAZER, 0), new EcologyFounder(MOSS, 0)));
    }

    @Test
    void equalSeedsProduceEqualStepsAndSnapshots() {
        try (EcologyWorld first = new EcologyWorld(settings(41));
             EcologyWorld second = new EcologyWorld(settings(41))) {
            for (int i = 0; i < 20; i++) {
                assertEquals(first.step(), second.step());
                assertEquals(first.snapshot(), second.snapshot());
            }
        }
    }

    @Test
    void feedingDecompositionBirthAndDeathConserveFiniteMatter() {
        try (EcologyWorld world = new EcologyWorld(settings(17))) {
            long expectedMatter = world.snapshot().statistics().conservedMatter();
            boolean observedBirth = false;
            boolean observedDecomposition = false;
            for (int i = 0; i < 40; i++) {
                EcologyStepResult result = world.step();
                observedBirth |= result.events().stream().anyMatch(event -> event.type() == EcologyEvent.Type.BORN);
                observedDecomposition |= result.events().stream()
                        .anyMatch(event -> event.type() == EcologyEvent.Type.DECOMPOSED);
                EcologySnapshot snapshot = world.snapshot();
                assertEquals(expectedMatter, snapshot.nutrient() + snapshot.waste() + snapshot.organisms().size());
                EcologyStatistics stats = snapshot.statistics();
                assertEquals(stats.conservedEnergy(), stats.availableEnergy() + stats.organismEnergy() + stats.heatReleased());
                assertEquals(result.tick() * world.checkpoint().settings().energyInputPerStep(), stats.energyInput());
                assertTrue(snapshot.organisms().size() <= 6);
                assertTrue(result.events().size() <= EcologyWorld.MAX_EVENTS_PER_STEP);
                if (result.terminal()) break;
            }
            assertTrue(observedBirth);
            assertTrue(observedDecomposition);
        }
    }

    @Test
    void checkpointRestoresExactStateAndContinuesDeterministically() {
        try (EcologyWorld world = new EcologyWorld(settings(8))) {
            world.step();
            world.step();
            EcologyCheckpoint checkpoint = world.checkpoint();
            try (EcologyWorld restored = EcologyWorld.restore(checkpoint)) {
                assertEquals(world.snapshot(), restored.snapshot());
                for (int i = 0; i < 10; i++) {
                    assertEquals(world.step(), restored.step());
                    assertEquals(world.snapshot(), restored.snapshot());
                }
            }
        }
    }

    @Test
    void habitatMovementFollowsLocalFoodAndKeepsCellLedgersBalanced() {
        EcologySettings spatial = new EcologySettings("spatial", 0, 8, 10, 64,
                0, 100, 8, 0, List.of(new EcologyFounder(GRAZER, 0)));
        try (EcologyWorld world = new EcologyWorld(spatial)) {
            EcologySnapshot initial = world.snapshot();
            assertEquals(EcologyWorld.HABITAT_CELL_COUNT, initial.habitatCells().size());
            assertEquals(8, initial.habitatCells().getFirst().nutrient());

            world.step();
            EcologySnapshot moved = world.snapshot();

            assertEquals(1, moved.organisms().getFirst().habitatCell());
            assertEquals(7, moved.habitatCells().getFirst().nutrient());
            assertEquals(1, moved.habitatCells().getFirst().waste());
            assertEquals(8, moved.habitatCells().get(1).nutrient());
            assertEquals(moved.statistics().conservedMatter(), moved.habitatCells().stream()
                    .mapToLong(EcologyHabitatCell::nutrient).sum()
                    + moved.habitatCells().stream().mapToLong(EcologyHabitatCell::waste).sum()
                    + moved.organisms().size());
        }
    }

    @Test
    void maximumLengthRunPreservesEcologyAcrossRepeatedCheckpointRecovery() {
        long maxSteps = 100_000;
        EcologyGenome longLivedGrazer = new EcologyGenome(4, 1, 10_000, 1);
        EcologySettings longRun = new EcologySettings("long-recovery", 2026, 256, maxSteps,
                10_000, 256, 2, 1_024, 0,
                List.of(new EcologyFounder(longLivedGrazer, 0), new EcologyFounder(longLivedGrazer, 0)));
        EcologyWorld world = new EcologyWorld(longRun);

        try {
            EcologyStepResult lastResult = null;
            for (long step = 1; step <= maxSteps; step++) {
                lastResult = world.step();
                if (step % 10_000 != 0) continue;

                EcologySnapshot beforeRecovery = world.snapshot();
                byte[] encoded = EcologyCheckpointCodec.encode(world.checkpoint());
                assertTrue(encoded.length <= EcologyCheckpointCodec.MAX_ENCODED_BYTES);
                EcologyCheckpoint decoded = EcologyCheckpointCodec.decode(encoded);
                assertEquals(beforeRecovery, decoded.snapshot());

                EcologyWorld restored = EcologyWorld.restore(decoded);
                assertEquals(beforeRecovery, restored.snapshot());
                world.close();
                world = restored;

                EcologyStatistics statistics = world.snapshot().statistics();
                assertEquals(step, world.snapshot().tick());
                assertEquals(256, statistics.population());
                assertEquals(statistics.conservedEnergy(), statistics.availableEnergy()
                        + statistics.organismEnergy() + statistics.heatReleased());
                assertEquals(statistics.conservedMatter(), world.snapshot().nutrient()
                        + world.snapshot().waste() + statistics.population());
            }

            EcologySnapshot finalSnapshot = world.snapshot();
            assertNotNull(lastResult);
            assertTrue(lastResult.terminal());
            assertEquals("MAX_STEPS_REACHED", lastResult.terminalReason());
            assertEquals(maxSteps, finalSnapshot.tick());
            assertEquals(256, finalSnapshot.statistics().population());
            assertThrows(IllegalStateException.class, world::step);
        } finally {
            world.close();
        }
    }

    @Test
    void massBalancedReactionsRunInEcologyAndSurviveCheckpointRestore() {
        DesignId feedstock = new DesignId("matter:feedstock");
        DesignId nutrient = new DesignId("matter:nutrient");
        MatterSpecies input = new MatterSpecies(feedstock, 1);
        MatterSpecies output = new MatterSpecies(nutrient, 1);
        MatterReaction conversion = new MatterReaction(new DesignId("reaction:convert"),
                Map.of(feedstock, 1), Map.of(nutrient, 1), 2);
        EcologySettings reactive = new EcologySettings("reactive", 19, 4, 20, 10,
                0, 100, 0, 0, List.of(new EcologyFounder(GRAZER, 10)),
                List.of(output, input), List.of(conversion), Map.of(feedstock, 4L));

        try (EcologyWorld world = new EcologyWorld(reactive)) {
            EcologyStepResult first = world.step();
            assertEquals(List.of(new MatterReactionEvent(conversion.id(), 2)), first.reactionEvents());
            assertEquals(15, first.statistics().conservedMatter());
            assertEquals(2, world.snapshot().reactionInventory().get(feedstock));
            assertEquals(2, world.snapshot().reactionInventory().get(nutrient));

            EcologyCheckpoint decoded = EcologyCheckpointCodec.decode(
                    EcologyCheckpointCodec.encode(world.checkpoint()));
            assertEquals(reactive, decoded.settings());
            try (EcologyWorld resumed = EcologyWorld.restore(decoded)) {
                assertEquals(world.step(), resumed.step());
                assertEquals(world.snapshot(), resumed.snapshot());
                assertEquals(0, resumed.snapshot().reactionInventory().get(feedstock));
                assertEquals(4, resumed.snapshot().reactionInventory().get(nutrient));
            }
        }
    }

    @Test
    void edibleReactionProductsEnterTheFoodWebWithoutChangingMatterMass() {
        DesignId biomass = new DesignId("matter:biomass");
        MatterReaction conversion = new MatterReaction(new DesignId("reaction:feed"),
                Map.of(biomass, 1), Map.of(EcologyWorld.EDIBLE_MATTER_ID, 2), 1);
        EcologySettings reactive = new EcologySettings("edible-reactive", 23, 2, 10, 10,
                0, 100, 24, 0, List.of(new EcologyFounder(GRAZER, 0)),
                List.of(new MatterSpecies(biomass, 2),
                        new MatterSpecies(EcologyWorld.EDIBLE_MATTER_ID, 1)),
                List.of(conversion), Map.of(biomass, 1L));

        try (EcologyWorld world = new EcologyWorld(reactive)) {
            long conservedMatter = world.snapshot().statistics().conservedMatter();
            EcologyStepResult result = world.step();
            EcologySnapshot snapshot = world.snapshot();

            assertEquals(List.of(new MatterReactionEvent(conversion.id(), 1)), result.reactionEvents());
            assertEquals(11, snapshot.nutrient());
            assertEquals(1, snapshot.waste());
            assertEquals(0, snapshot.reactionInventory().get(EcologyWorld.EDIBLE_MATTER_ID));
            assertEquals(conservedMatter, snapshot.nutrient() + snapshot.waste()
                    + snapshot.organisms().size() + new MatterReactionNetwork(
                    reactive.reactionSpecies(), reactive.matterReactions()).matterMass(snapshot.reactionInventory()));
        }
    }

    @Test
    void recipesCanBeAddedAndRemovedWithoutChangingTheMatterLedger() {
        DesignId biomass = new DesignId("matter:biomass");
        EcologySettings reactive = new EcologySettings("editable-reactive", 29, 2, 10, 10,
                0, 100, 24, 0, List.of(new EcologyFounder(GRAZER, 0)),
                List.of(new MatterSpecies(biomass, 2),
                        new MatterSpecies(EcologyWorld.EDIBLE_MATTER_ID, 1)), List.of(), Map.of(biomass, 1L));
        MatterReaction conversion = new MatterReaction(new DesignId("player:recipe-01"),
                Map.of(biomass, 1), Map.of(EcologyWorld.EDIBLE_MATTER_ID, 2), 4);

        try (EcologyWorld world = new EcologyWorld(reactive)) {
            long conservedMatter = world.snapshot().statistics().conservedMatter();
            world.addReaction(conversion);
            assertEquals(List.of(conversion), world.checkpoint().settings().matterReactions());
            assertEquals(conservedMatter, world.snapshot().statistics().conservedMatter());
            assertThrows(IllegalArgumentException.class, () -> world.addReaction(conversion));

            world.removeReaction(conversion.id());
            assertTrue(world.checkpoint().settings().matterReactions().isEmpty());
            assertEquals(conservedMatter, world.snapshot().statistics().conservedMatter());
        }
    }

    @Test
    void finiteGenomeEditsChangeBehaviorStateWithoutChangingMatter() {
        try (EcologyWorld world = new EcologyWorld(settings(73))) {
            EcologySnapshot before = world.snapshot();
            world.editGenome(0, 0, 1);
            EcologySnapshot edited = world.snapshot();
            assertEquals(4, edited.organisms().getFirst().genome().energyPerFood());
            assertEquals(before.statistics().conservedMatter(), edited.statistics().conservedMatter());
            assertNotEquals(before.stateHash(), edited.stateHash());
            world.editGenome(0, 0, 1);
            assertEquals(4, world.snapshot().organisms().getFirst().genome().energyPerFood());
            assertThrows(IllegalArgumentException.class, () -> world.editGenome(999, 0, 1));
            assertThrows(IllegalArgumentException.class, () -> world.editGenome(0, 4, 1));
            try (EcologyWorld restored = EcologyWorld.restore(world.checkpoint())) {
                assertEquals(world.snapshot(), restored.snapshot());
            }
        }
    }

    @Test
    void deterministicMutationCreatesAValidVariantWhenEnabled() {
        EcologySettings mutating = new EcologySettings("mutation", 29, 4, 10, 20, 0, 2, 8, 1_000,
                List.of(new EcologyFounder(GRAZER, 0)));
        try (EcologyWorld world = new EcologyWorld(mutating)) {
            EcologyStepResult result = world.step();
            assertTrue(result.events().stream().anyMatch(event -> event.type() == EcologyEvent.Type.BORN));
            EcologySnapshot snapshot = world.snapshot();
            assertNotEquals(GRAZER, snapshot.organisms().get(1).genome());
            assertEquals(2, snapshot.statistics().speciesCount());
            assertEquals(snapshot.statistics().conservedMatter(),
                    snapshot.nutrient() + snapshot.waste() + snapshot.organisms().size());
        }
    }

    @Test
    void checkpointRejectsMintedMatterAndHashTampering() {
        try (EcologyWorld world = new EcologyWorld(settings(12))) {
            EcologyCheckpoint checkpoint = world.checkpoint();
            EcologySnapshot original = checkpoint.snapshot();
            EcologySnapshot minted = new EcologySnapshot(original.engineVersion(), original.worldId(), original.seed(),
                    original.tick(), Long.MAX_VALUE, Long.MAX_VALUE, original.organisms(),
                    original.statistics(), original.stateHash());
            assertThrows(IllegalArgumentException.class,
                    () -> EcologyWorld.restore(new EcologyCheckpoint(checkpoint.settings(), minted)));

            EcologySnapshot changedHash = new EcologySnapshot(original.engineVersion(), original.worldId(),
                    original.seed(), original.tick(), original.nutrient(), original.waste(), original.organisms(),
                    original.statistics(), "0".repeat(64));
            assertThrows(IllegalArgumentException.class,
                    () -> EcologyWorld.restore(new EcologyCheckpoint(checkpoint.settings(), changedHash)));
        }
    }

    @Test
    void starvationEndsTheSimulationWithoutCreatingMatter() {
        EcologySettings settings = new EcologySettings("extinct", 2, 1, 10, 0, 0, 2, 0, 0,
                List.of(new EcologyFounder(new EcologyGenome(1, 1, 5, 1), 0)));
        try (EcologyWorld world = new EcologyWorld(settings)) {
            EcologyStepResult result = world.step();
            assertTrue(result.terminal());
            assertEquals("EXTINCT", result.terminalReason());
            assertEquals(1, result.statistics().deaths());
            assertEquals(1, world.snapshot().nutrient());
            assertThrows(IllegalStateException.class, world::step);
        }
    }

    @Test
    void populationAndStepLimitsStopFurtherWork() {
        EcologySettings limited = new EcologySettings("limited", 3, 2, 2, 30, 1, 1, 8, 1_000,
                List.of(new EcologyFounder(new EcologyGenome(4, 1, 30, 1), 8),
                        new EcologyFounder(new EcologyGenome(3, 1, 30, 1), 8)));
        try (EcologyWorld world = new EcologyWorld(limited)) {
            EcologyStepResult first = world.step();
            assertTrue(first.statistics().population() <= limited.maxPopulation());
            assertFalse(first.terminal());
            EcologyStepResult last = world.step();
            assertTrue(last.terminal());
            assertEquals("MAX_STEPS_REACHED", last.terminalReason());
            assertThrows(IllegalStateException.class, world::step);
        }
    }

    @Test
    void worldStateIsConfinedToItsConstructionThread() throws Exception {
        try (EcologyWorld world = new EcologyWorld(settings(99)); var executor = Executors.newSingleThreadExecutor()) {
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> executor.submit(world::stateHash).get());
        }
    }

    @Test
    void settingsAndGenesEnforceFiniteLimits() {
        assertThrows(IllegalArgumentException.class, () -> new EcologyGenome(5, 1, 10, 1));
        assertThrows(IllegalArgumentException.class, () -> new EcologyGenome(1, 1, 3, 3));
        assertThrows(IllegalArgumentException.class, () -> new EcologySettings("too-large", 1, 257,
                10, 1, 1, 1, 0, 0, List.of(new EcologyFounder(GRAZER, 0))));
        assertDoesNotThrow(() -> {
            EcologyGenome genome = new EcologyGenome(1, 1, 2, 1);
            for (int i = 0; i < 100; i++) genome = genome.mutate(i % 4, i % 2 == 0 ? -1 : 1);
        });
    }
}
