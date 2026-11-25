package dev.stargenesis.sim;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class EcologyLifecycleV2Test {
    @Test void defaultLongRunKeepsThreeSpeciesAndRestoresMutationAndBirthRecoveryAt768() {
        var config = EcologyConfig.forDuration(1536);
        try (var world = new EcologyWorld("ecology-v2-validation-1", 42, config)) {
            while (world.tick() < 768) {
                world.step(EcologyInput.idle(world.tick()));
                assertConserved(world.view(), config);
                assertEquals(3, world.view().populationBySpecies().size());
            }
            assertTrue(world.view().evolvedOrganisms() > 0);
            try (var restored = EcologyWorld.restore(world.replay())) {
                while (!world.view().terminal()) {
                    var input = EcologyInput.idle(world.tick());
                    assertEquals(world.step(input), restored.step(input));
                    assertConserved(restored.view(), config);
                    assertEquals(3, restored.view().populationBySpecies().size());
                    assertEquals(world.view(), restored.view());
                }
                assertEquals(1536, world.tick());
                assertTrue(world.view().births() > 50);
                assertTrue(world.view().deaths() > 50);
                assertEquals("DURATION_BUDGET", world.view().reason());
                assertEquals("7c3dc98ebc3625d6d749b311d7e211c6125a2c4388f9a871fb2292c02d0620ff", world.stateHash());
                assertEquals(world.replay(), restored.replay());
            }
        }
    }

    @Test void aShortLifetimeControlStillEndsInExtinctionWithoutResettingGenerationsOrInjectingMatter() {
        var config = EcologyConfig.forDuration(1536);
        try (var world = new EcologyWorld("ecology-v2-extinction-control", 42, config)) {
            while (!world.view().terminal()) {
                world.step(world.tick() == 0 ? new EcologyInput(0, programs(8, 0)) : EcologyInput.idle(world.tick()));
                assertConserved(world.view(), config);
            }
            assertTrue(world.tick() < 1536);
            assertEquals("EXTINCTION", world.view().reason());
            assertEquals(0, world.view().population());
            assertEquals(world.view().births() + EcologyConfig.INITIAL_POPULATION, world.view().deaths());
            assertTrue(EcologyReplayVerifier.verify(world.replay()).passed());
        }
    }

    @Test void maximumMutationCounterexampleLosesDecomposersWithoutBreakingConservationOrReplay() {
        var config = EcologyConfig.forDuration(1536);
        try (var world = new EcologyWorld("ecology-v2-validation-10", -6448665863545292081L, config)) {
            var programs = new HashMap<>(world.view().programs());
            programs.replaceAll((species, program) -> program.withParameters(program.intake(), program.reproductionMass(),
                    program.lifespanSteps(), 500));
            long firstExtinction = -1;
            while (!world.view().terminal()) {
                world.step(world.tick() == 0 ? new EcologyInput(0, programs) : EcologyInput.idle(world.tick()));
                assertConserved(world.view(), config);
                if (firstExtinction < 0 && !world.view().populationBySpecies().containsKey("decomposer"))
                    firstExtinction = world.tick();
            }
            assertEquals(1200, firstExtinction);
            assertEquals(Map.of("producer", 11, "grazer", 21), world.view().populationBySpecies());
            assertEquals(1536, world.tick());
            assertTrue(EcologyReplayVerifier.verify(world.replay()).passed());
        }
    }

    @Test void immatureOrganismsStopFeedingAtTheirBiomassCapacityInsteadOfLockingAllMatter() {
        var config = new EcologyConfig(1000, 86, 32, 8, 160, 512, 50_000);
        var programs = new HashMap<>(programs(320, 0));
        programs.replaceAll((species, program) -> program.withParameters(8, 4, 320, 0));
        try (var world = new EcologyWorld("ecology-reserves", 42, config)) {
            while (world.tick() < 159) {
                world.step(world.tick() == 0 ? new EcologyInput(0, programs) : EcologyInput.idle(world.tick()));
                assertEquals(0, world.view().births());
                assertTrue(world.view().organismMatter() <= 7 * 4);
                assertTrue(world.view().soilMatter() > 850);
                assertConserved(world.view(), config);
            }
            assertEquals(28, world.view().organismMatter());
            var before = world.view();
            world.step(EcologyInput.idle(world.tick()));
            assertEquals(7, world.view().births());
            assertEquals(before.organismMatter(), world.view().organismMatter(), "Birth redistributes existing biomass");
            assertTrue(EcologyReplayVerifier.verify(world.replay()).passed());
        }
    }

    @Test void loweringBiomassCapacityStopsFeedingWithoutDeletingTheExistingReserve() {
        var config = new EcologyConfig(1000, 86, 32, 0, 40, 0, 50_000);
        var programs = new HashMap<>(programs(64, 0));
        programs.replaceAll((species, program) -> program.withParameters(8, 32, 64, 0));
        try (var world = new EcologyWorld("ecology-capacity-edit", 42, config)) {
            while (world.tick() < 20) world.step(world.tick() == 0 ? new EcologyInput(0, programs) : EcologyInput.idle(world.tick()));
            var before = world.view();
            assertTrue(before.organismMatter() > 14);
            world.step(new EcologyInput(world.tick(), programs(64, 0)));
            assertEquals(before.organismMatter(), world.view().organismMatter());
            assertEquals(before.processedMatter(), world.view().processedMatter());
            assertConserved(world.view(), config);
        }
    }

    @Test void parentsAndDescendantsWaitForMaturityAndRecoverBetweenBirths() {
        var birthSteps = new HashMap<String, Long>();
        var lastBirth = new HashMap<String, Long>();
        boolean repeated = false, descendant = false;
        long firstBirth = -1;
        try (var world = new EcologyWorld("ecology-maturity", 42, EcologyConfig.forDuration(100))) {
            while (!world.view().terminal()) {
                var input = world.tick() == 0 ? new EcologyInput(0, programs(13, 0)) : EcologyInput.idle(world.tick());
                var result = world.step(input);
                for (var event : result.events()) {
                    if (!event.type().equals("ecology.organism_born")) continue;
                    if (firstBirth < 0) firstBirth = world.tick();
                    String parent = event.data().get("parent");
                    long age = world.tick() - birthSteps.getOrDefault(parent, 0L);
                    assertTrue(age >= 7, "A parent must reach ceil(lifespan/2), including descendants");
                    if (lastBirth.containsKey(parent)) {
                        assertTrue(world.tick() - lastBirth.get(parent) >= 4, "Successful births need ceil(lifespan/4) recovery");
                        repeated = true;
                    }
                    descendant |= birthSteps.containsKey(parent);
                    lastBirth.put(parent, world.tick());
                    birthSteps.put(event.data().get("organism"), world.tick());
                }
                assertTrue(world.view().maxGeneration() <= world.tick() / 7);
                assertConserved(world.view(), world.config());
            }
            assertEquals(7, firstBirth);
            assertTrue(repeated);
            assertTrue(descendant);
            assertTrue(EcologyReplayVerifier.verify(world.replay()).passed());
        }
    }

    @Test void aOneTickLifespanCannotReproduceAndOddMaturityRoundsUp() {
        for (int life : new int[]{1, 2, 3}) {
            try (var world = new EcologyWorld("ecology-short-life", 42, EcologyConfig.forDuration(6))) {
                long first = -1;
                while (!world.view().terminal()) {
                    var result = world.step(world.tick() == 0 ? new EcologyInput(0, programs(life, 0)) : EcologyInput.idle(world.tick()));
                    if (first < 0 && result.events().stream().anyMatch(event -> event.type().equals("ecology.organism_born"))) first = world.tick();
                    assertConserved(world.view(), world.config());
                }
                assertEquals(life == 1 ? -1 : (life + 1) / 2, first);
                if (life == 1) assertEquals(0, world.view().births());
            }
        }
    }

    @Test void programUpdatesAndRestoreDoNotResetAnExistingParentsRecoveryInterval() {
        Set<String> firstParents = new HashSet<>();
        try (var world = new EcologyWorld("ecology-cooldown-restart", 42, EcologyConfig.forDuration(80))) {
            while (world.tick() < 32) {
                var result = world.step(world.tick() == 0 ? new EcologyInput(0, programs(64, 0)) : EcologyInput.idle(world.tick()));
                result.events().stream().filter(event -> event.type().equals("ecology.organism_born"))
                        .forEach(event -> firstParents.add(event.data().get("parent")));
            }
            assertFalse(firstParents.isEmpty());
            world.step(new EcologyInput(world.tick(), programs(64, 0)));
            try (var restored = EcologyWorld.restore(world.replay())) {
                while (!world.view().terminal()) {
                    var input = EcologyInput.idle(world.tick());
                    var result = world.step(input);
                    assertEquals(result, restored.step(input));
                    if (world.tick() < 48) assertTrue(result.events().stream().noneMatch(event ->
                            event.type().equals("ecology.organism_born") && firstParents.contains(event.data().get("parent"))));
                    assertEquals(world.view(), restored.view());
                }
                assertEquals(world.replay(), restored.replay());
                assertEquals(EcologyWorld.ENGINE_VERSION, restored.engineVersion());
            }
        }
    }

    @Test void allDeathsReleasePopulationSlotsBeforeSurvivingParentsReproduce() {
        var config = new EcologyConfig(1000, 86, 7, 8, 20, 512, 50_000);
        var programs = new HashMap<>(programs(20, 0));
        programs.put("producer", programs.get("producer").withParameters(8, 2, 10, 0));
        try (var world = new EcologyWorld("ecology-population-slots", 42, config)) {
            while (world.tick() < 9) world.step(world.tick() == 0 ? new EcologyInput(0, programs) : EcologyInput.idle(world.tick()));
            assertEquals(0, world.view().births());
            var result = world.step(EcologyInput.idle(world.tick()));
            assertEquals(3, world.view().deaths());
            assertEquals(3, world.view().births());
            assertEquals(7, world.view().population());
            boolean seenBirth = false;
            for (var event : result.events()) {
                if (event.type().equals("ecology.organism_born")) seenBirth = true;
                if (event.type().equals("ecology.organism_died")) assertFalse(seenBirth);
            }
            assertConserved(world.view(), config);
        }
    }

    @Test void changingWorldIdentityDoesNotChangeFeedingBirthsDeathsOrMutation() {
        for (long seed : new long[]{0, 42, Long.MIN_VALUE, Long.MAX_VALUE}) {
            var config = EcologyConfig.forDuration(80);
            try (var first = new EcologyWorld("ecology-identity-a", seed, config);
                 var second = new EcologyWorld("ecology-identity-b", seed, config)) {
                boolean evolved = false;
                while (!first.view().terminal()) {
                    var input = first.tick() == 0 ? new EcologyInput(0, programs(40, 500)) : EcologyInput.idle(first.tick());
                    first.step(input);
                    second.step(input);
                    assertEquals(quantities(first.view()), quantities(second.view()));
                    assertNotEquals(first.stateHash(), second.stateHash(), "Evidence commitments must still bind the world identity");
                    assertConserved(first.view(), config);
                    evolved |= first.view().evolvedOrganisms() > 0;
                }
                assertTrue(evolved, "The comparison must exercise inherited mutation as well as feeding");
                assertTrue(EcologyReplayVerifier.verify(first.replay()).passed());
                assertTrue(EcologyReplayVerifier.verify(second.replay()).passed());
            }
        }
    }

    @Test void distinctSeedsStillProduceDistinctPhysicalOutcomes() {
        try (var first = new EcologyWorld("ecology-seed", 1, EcologyConfig.forDuration(80));
             var second = new EcologyWorld("ecology-seed", 2, EcologyConfig.forDuration(80))) {
            boolean diverged = false;
            while (!first.view().terminal() && !second.view().terminal()) {
                var input = first.tick() == 0 ? new EcologyInput(0, programs(40, 500)) : EcologyInput.idle(first.tick());
                first.step(input);
                second.step(input);
                diverged |= !quantities(first.view()).equals(quantities(second.view()));
            }
            assertTrue(diverged);
        }
    }

    @Test void bothEnginesRestoreWithoutMigrationAndRejectRelabelledOrUnknownVersions() {
        for (String version : List.of(EcologyWorld.LEGACY_ENGINE_VERSION, EcologyWorld.ENGINE_VERSION)) {
            try (var world = new EcologyWorld("ecology-version", 42, EcologyConfig.defaults(), version)) {
                for (int tick = 0; tick < 24; tick++) world.step(EcologyInput.idle(tick));
                var replay = world.replay();
                assertEquals(version, replay.engineVersion());
                try (var restored = EcologyWorld.restore(replay)) {
                    assertEquals(version, restored.engineVersion());
                    assertEquals(world.step(EcologyInput.idle(world.tick())), restored.step(EcologyInput.idle(restored.tick())));
                    assertEquals(world.replay(), restored.replay());
                }
                String other = version.equals(EcologyWorld.ENGINE_VERSION) ? EcologyWorld.LEGACY_ENGINE_VERSION : EcologyWorld.ENGINE_VERSION;
                for (String replacement : List.of(other, "genesis-ecology/99")) {
                    var relabelled = new EcologyReplay(1, replacement, replay.worldId(), replay.seed(), replay.config(), replay.initialStateHash(), replay.frames());
                    assertFalse(EcologyReplayVerifier.verify(relabelled).passed());
                    assertThrows(IllegalArgumentException.class, () -> EcologyWorld.restore(relabelled));
                }
            }
        }
        assertThrows(IllegalArgumentException.class, () -> new EcologyWorld("unsupported", 1, EcologyConfig.defaults(), "genesis-ecology/99"));
    }

    @Test void lifecycleRulesPreserveBirthGenerationAndLedgerBudgets() {
        for (int generations : new int[]{0, 2}) for (int births : new int[]{0, 15}) {
            var config = new EcologyConfig(100_000, 10_000, 32, generations, 160, births, 50_000);
            try (var world = new EcologyWorld("ecology-v2-limits", 991, config)) {
                while (!world.view().terminal()) {
                    var result = world.step(world.tick() == 0 ? new EcologyInput(0, programs(64, 500)) : EcologyInput.idle(world.tick()));
                    assertTrue(result.events().size() <= EcologyWorld.MAX_EVENTS_PER_STEP);
                    assertConserved(world.view(), config);
                }
                assertEquals(generations == 0 ? 0 : births, world.view().births());
                assertTrue(EcologyReplayVerifier.verify(world.replay()).passed());
            }
        }
    }

    private static Map<String, MatterProgram> programs(int life, int mutation) {
        return Map.of(
                "producer", new MatterProgram("producer", MatterProgram.Role.PRODUCER, 8, 2, life, mutation),
                "grazer", new MatterProgram("grazer", MatterProgram.Role.GRAZER, 8, 2, life, mutation),
                "decomposer", new MatterProgram("decomposer", MatterProgram.Role.DECOMPOSER, 8, 2, life, mutation));
    }

    private static List<Object> quantities(EcologyView view) {
        return List.of(view.tick(), view.totalMatter(), view.soilMatter(), view.detritusMatter(), view.organismMatter(),
                view.population(), view.populationBySpecies(), view.matterBySpecies(), view.maxGeneration(),
                view.births(), view.deaths(), view.processedMatter(), view.evolvedOrganisms(), view.programs(), view.terminal(), view.reason());
    }

    private static void assertConserved(EcologyView view, EcologyConfig config) {
        assertEquals(config.initialMatter(), view.totalMatter());
        assertEquals(view.totalMatter(), view.soilMatter() + view.detritusMatter() + view.organismMatter());
        assertEquals(EcologyConfig.INITIAL_POPULATION + view.births() - view.deaths(), view.population());
        assertTrue(view.population() <= config.maxOrganisms());
        assertTrue(view.births() <= config.maxBirths());
        assertTrue(view.maxGeneration() <= config.maxGenerations());
    }
}
