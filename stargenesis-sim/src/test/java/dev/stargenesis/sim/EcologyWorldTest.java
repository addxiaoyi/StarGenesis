package dev.stargenesis.sim;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EcologyWorldTest {
    @Test void ledgerEvidenceBudgetMustCoverTheDeclaredFiniteRun() {
        assertThrows(IllegalArgumentException.class,
                () -> new EcologyConfig(1_000, 86, 32, 8, 500, 512, 1_000));
        assertDoesNotThrow(EcologyConfig::defaults);
    }

    @Test void ecosystemCyclesMatterWithoutMintingAndExposesDashboardTotals() {
        EcologyConfig config = EcologyConfig.defaults();
        try (EcologyWorld world = new EcologyWorld("ecology-test", 42, config)) {
            assertEquals(config.initialMatter(), world.view().totalMatter());
            assertEquals(EcologyConfig.INITIAL_POPULATION, world.view().population());

            for (int i = 0; i < 200 && !world.view().terminal(); i++)
                world.step(EcologyInput.idle(world.tick()));

            EcologyView view = world.view();
            assertEquals(config.initialMatter(), view.totalMatter());
            assertEquals(view.totalMatter(), view.soilMatter() + view.detritusMatter() + view.organismMatter());
            assertTrue(view.processedMatter() > 0);
            assertTrue(view.births() > 0);
            assertTrue(view.population() <= config.maxOrganisms());
            assertTrue(view.maxGeneration() <= config.maxGenerations());
            assertEquals(3, view.programs().size());
        }
    }

    @Test void programUpdatesAreRecordedAndChangeTheRuleUsedByOrganisms() {
        MatterProgram producer = new MatterProgram("producer", MatterProgram.Role.PRODUCER, 1, 5, 40, 0);
        try (EcologyWorld world = new EcologyWorld("programmed-matter", 73, EcologyConfig.defaults())) {
            world.step(new EcologyInput(0, Map.of("producer", producer)));

            assertEquals(producer, world.view().programs().get("producer"));
            assertTrue(world.view().matterBySpecies().get("producer") > 0);
            assertEquals(1, world.replay().frames().getFirst().input().programUpdates().size());
            assertTrue(EcologyReplayVerifier.verify(world.replay()).passed());
        }
    }

    @Test void evolutionAndBirthBudgetsAreFiniteAndMutationsStayInsideProgramBounds() {
        EcologyConfig config = new EcologyConfig(1_000, 86, 32, 2, 50, 15, 5_000);
        Map<String, MatterProgram> updates = Map.of(
                "producer", new MatterProgram("producer", MatterProgram.Role.PRODUCER, 8, 2, 200, 500),
                "grazer", new MatterProgram("grazer", MatterProgram.Role.GRAZER, 8, 2, 200, 500),
                "decomposer", new MatterProgram("decomposer", MatterProgram.Role.DECOMPOSER, 8, 2, 200, 500));
        try (EcologyWorld world = new EcologyWorld("finite-evolution", 991, config, EcologyWorld.LEGACY_ENGINE_VERSION)) {
            world.step(new EcologyInput(0, updates));
            for (int i = 0; i < 40 && !world.view().terminal(); i++)
                world.step(EcologyInput.idle(world.tick()));

            EcologyView view = world.view();
            assertEquals(config.maxBirths(), view.births());
            assertTrue(view.population() <= config.maxOrganisms());
            assertTrue(view.maxGeneration() <= config.maxGenerations());
            assertTrue(view.evolvedOrganisms() > 0);
            view.programs().values().forEach(program -> {
                assertTrue(program.intake() <= 8);
                assertTrue(program.reproductionMass() >= 2 && program.reproductionMass() <= 32);
                assertTrue(program.lifespanSteps() >= 1 && program.lifespanSteps() <= 512);
            });
            assertEquals(config.initialMatter(), view.totalMatter());
        }
    }

    @Test void replayRestoresTheExactEcologyAndDetectsChangedStateCommitments() {
        EcologyReplay replay;
        EcologyView expected;
        try (EcologyWorld world = new EcologyWorld("replay-ecology", 1234, EcologyConfig.defaults())) {
            for (int i = 0; i < 30; i++) world.step(EcologyInput.idle(world.tick()));
            replay = world.replay();
            expected = world.view();
        }

        assertTrue(EcologyReplayVerifier.verify(replay).passed());
        try (EcologyWorld restored = EcologyWorld.restore(replay)) {
            assertEquals(expected, restored.view());
        }

        EcologyReplay.Frame first = replay.frames().getFirst();
        var changed = new EcologyReplay.Frame(first.input(), "0".repeat(64), first.eventHash());
        var tampered = new EcologyReplay(replay.schemaVersion(), replay.engineVersion(), replay.worldId(), replay.seed(),
                replay.config(), replay.initialStateHash(), java.util.stream.Stream.concat(
                java.util.stream.Stream.of(changed), replay.frames().stream().skip(1)).toList());
        assertFalse(EcologyReplayVerifier.verify(tampered).passed());
        assertThrows(IllegalArgumentException.class, () -> EcologyWorld.restore(tampered));
    }

    @Test void invalidProgramUpdateDoesNotAdvanceOrMutateTheWorld() {
        try (EcologyWorld world = new EcologyWorld("invalid-program", 9, EcologyConfig.defaults())) {
            String before = world.stateHash();
            MatterProgram invalidSpecies = new MatterProgram("unknown", MatterProgram.Role.PRODUCER, 1, 2, 5, 0);

            assertThrows(IllegalArgumentException.class,
                    () -> world.step(new EcologyInput(0, Map.of("unknown", invalidSpecies))));

            assertEquals(0, world.tick());
            assertEquals(before, world.stateHash());
        }
    }

    @Test void ageAndDurationCapsTerminateWithoutLosingMatter() {
        EcologyConfig config = new EcologyConfig(100, 10, 10, 1, 4, 4, 1_000);
        Map<String, MatterProgram> shortLived = Map.of(
                "producer", new MatterProgram("producer", MatterProgram.Role.PRODUCER, 1, 32, 1, 0),
                "grazer", new MatterProgram("grazer", MatterProgram.Role.GRAZER, 1, 32, 1, 0),
                "decomposer", new MatterProgram("decomposer", MatterProgram.Role.DECOMPOSER, 1, 32, 1, 0));
        try (EcologyWorld world = new EcologyWorld("bounded-life", 6, config)) {
            world.step(new EcologyInput(0, shortLived));
            assertEquals("EXTINCTION", world.view().reason());
            assertEquals(config.initialMatter(), world.view().totalMatter());
            assertThrows(IllegalStateException.class, () -> world.step(EcologyInput.idle(world.tick())));
        }

        try (EcologyWorld world = new EcologyWorld("bounded-duration", 7,
                new EcologyConfig(100, 10, 10, 1, 1, 0, 1_000))) {
            assertTrue(world.step(EcologyInput.idle(0)).terminal());
            assertEquals("DURATION_BUDGET", world.view().reason());
        }
    }
}
