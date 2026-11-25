package dev.stargenesis.sim;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EcologyLongRunTest {
    private static final Map<String, MatterProgram> CANDIDATE = Map.of(
            "producer", new MatterProgram("producer", MatterProgram.Role.PRODUCER, 6, 2, 512, 0),
            "grazer", new MatterProgram("grazer", MatterProgram.Role.GRAZER, 1, 12, 512, 0),
            "decomposer", new MatterProgram("decomposer", MatterProgram.Role.DECOMPOSER, 1, 12, 512, 0));

    @Test void extendedDurationReservesEnoughEvidenceWithoutChangingDefaultOrLedgerLimits() {
        assertEquals(EcologyConfig.defaults(), EcologyConfig.forDuration(500));
        assertEquals(98_304, EcologyConfig.forDuration(1_536).maxLedgerAttempts());
        assertEquals(512, EcologyConfig.forDuration(1_536).maxBirths());
        for (int ticks : new int[]{0, -1, 1_537, Integer.MAX_VALUE})
            assertThrows(IllegalArgumentException.class, () -> EcologyConfig.forDuration(ticks));
        assertThrows(IllegalArgumentException.class, () -> new EcologyConfig(1_000, 86, 32, 8, 1_536, 512, 98_303));
        assertThrows(IllegalArgumentException.class, () -> new EcologyConfig(1_000, 86, 32, 8, 1_536, 512, 100_001));
    }

    @Test void nonReproducingOrganismsCompleteTheirFullLifespanAndReturnAllMatter() {
        var config = new EcologyConfig(1_000, 86, 32, 0, 1_536, 0, 98_304);
        try (var world = new EcologyWorld("ecology-lifespan", 42, config)) {
            world.step(new EcologyInput(0, CANDIDATE));
            while (world.tick() < 511) {
                world.step(EcologyInput.idle(world.tick()));
                assertConserved(world.view(), config);
            }
            assertEquals(7, world.view().population());
            assertEquals(0, world.view().deaths());
            world.step(EcologyInput.idle(world.tick()));
            assertEquals(512, world.tick());
            assertEquals("EXTINCTION", world.view().reason());
            assertEquals(0, world.view().population());
            assertEquals(7, world.view().deaths());
            assertEquals(0, world.view().births());
            assertConserved(world.view(), config);
            assertTrue(EcologyReplayVerifier.verify(world.replay()).passed());
        }
    }

    @Test void threeLifespanRunConservesMatterAndResumesIdenticallyAfterTheOldHorizon() {
        var config = EcologyConfig.forDuration(1_536);
        try (var world = new EcologyWorld("ecology-long-study-0", 52_008, config)) {
            while (world.tick() < 768) {
                world.step(candidateInput(world.tick()));
                assertConserved(world.view(), config);
                if (world.tick() == 500) assertEquals(3, world.view().populationBySpecies().size());
                if (world.tick() == 512) {
                    assertFalse(world.view().populationBySpecies().containsKey("grazer"));
                    assertEquals(7, world.view().deaths());
                }
            }
            try (var restarted = EcologyWorld.restore(world.replay())) {
                while (!world.view().terminal()) {
                    var input = EcologyInput.idle(world.tick());
                    assertEquals(world.step(input), restarted.step(input));
                    assertConserved(restarted.view(), config);
                }
                assertEquals(1_536, world.tick());
                assertEquals("DURATION_BUDGET", world.view().reason());
                assertTrue(world.view().deaths() > EcologyConfig.INITIAL_POPULATION);
                assertTrue(world.view().births() > 25);
                assertEquals(world.replay(), restarted.replay());
            }
        }
    }

    @Test void tamperingBeyondFiveHundredTicksIsDetectedAtTheChangedFrame() {
        try (var world = new EcologyWorld("ecology-long-study-0", 52_008, EcologyConfig.forDuration(520))) {
            while (!world.view().terminal()) world.step(candidateInput(world.tick()));
            var replay = world.replay();
            assertEquals(520, replay.frames().size());
            var frames = new ArrayList<>(replay.frames());
            var original = frames.get(510);
            frames.set(510, new EcologyReplay.Frame(original.input(), "0".repeat(64), original.eventHash()));
            var tampered = new EcologyReplay(1, replay.engineVersion(), replay.worldId(), replay.seed(), replay.config(),
                    replay.initialStateHash(), frames);
            assertEquals(510, EcologyReplayVerifier.verify(tampered).firstDivergentTick());
            assertFalse(EcologyReplayVerifier.verify(tampered).passed());
            assertThrows(IllegalArgumentException.class, () -> EcologyWorld.restore(tampered));
        }
    }

    @Test void longRunsRespectBirthAndGenerationCapsWithoutExhaustingLedgerAccounts() {
        var config = new EcologyConfig(1_000, 86, 32, 2, 1_536, 15, 98_304);
        try (var world = new EcologyWorld("ecology-finite-long", 42, config)) {
            while (!world.view().terminal()) {
                world.step(candidateInput(world.tick()));
                assertConserved(world.view(), config);
            }
            assertTrue(world.tick() > 500);
            assertEquals("EXTINCTION", world.view().reason());
            assertEquals(config.maxBirths(), world.view().births());
            assertEquals(world.view().births() + EcologyConfig.INITIAL_POPULATION, world.view().deaths());
        }
    }

    @Test void longCycleCandidatePreservesThreeSpeciesAcrossTheOriginalLifespanAndRestoreBoundary() {
        var programs = Map.of(
                "producer", new MatterProgram("producer", MatterProgram.Role.PRODUCER, 6, 4, 320, 0),
                "grazer", new MatterProgram("grazer", MatterProgram.Role.GRAZER, 4, 24, 512, 0),
                "decomposer", new MatterProgram("decomposer", MatterProgram.Role.DECOMPOSER, 3, 4, 320, 0));
        var config = EcologyConfig.forDuration(1536);
        for (int index = 0; index < 2; index++) {
            try (var world = new EcologyWorld("ecology-long-study-" + index, 52008, config)) {
                while (world.tick() < 768) {
                    long tick = world.tick();
                    String species = tick == 0 ? "producer" : tick == 1 ? "grazer" : tick == 2 ? "decomposer" : null;
                    world.step(species == null ? EcologyInput.idle(tick) : new EcologyInput(tick, Map.of(species, programs.get(species))));
                    assertConserved(world.view(), config);
                    assertEquals(3, world.view().populationBySpecies().size());
                }
                try (var restored = EcologyWorld.restore(world.replay())) {
                    while (!world.view().terminal()) {
                        var input = EcologyInput.idle(world.tick());
                        assertEquals(world.step(input), restored.step(input));
                        assertConserved(world.view(), config);
                        assertEquals(3, world.view().populationBySpecies().size());
                    }
                    assertEquals(1536, world.tick());
                    assertEquals("DURATION_BUDGET", world.view().reason());
                    assertTrue(world.view().births() > 100);
                    assertTrue(world.view().deaths() > 100);
                    assertEquals(world.replay(), restored.replay());
                }
            }
        }
    }

    @Test void expandedWorldSampleExposesProducerExtinctionDespiteConservedMatter() {
        var programs = Map.of(
                "producer", new MatterProgram("producer", MatterProgram.Role.PRODUCER, 6, 4, 320, 0),
                "grazer", new MatterProgram("grazer", MatterProgram.Role.GRAZER, 4, 24, 512, 0),
                "decomposer", new MatterProgram("decomposer", MatterProgram.Role.DECOMPOSER, 3, 4, 320, 0));
        var config = EcologyConfig.forDuration(1536);
        long firstProducerExtinction = -1;
        long lastProducerBirthTick = -1;
        var producerGenerations = new java.util.TreeMap<Integer, Integer>(Map.of(0, 3));
        try (var world = new EcologyWorld("ecology-long-study-2", 52008, config)) {
            while (!world.view().terminal()) {
                long tick = world.tick();
                String species = tick == 0 ? "producer" : tick == 1 ? "grazer" : tick == 2 ? "decomposer" : null;
                var result = world.step(species == null ? EcologyInput.idle(tick) : new EcologyInput(tick, Map.of(species, programs.get(species))));
                for (var event : result.events()) {
                    if (!"producer".equals(event.data().get("species"))) continue;
                    int change = event.type().equals("ecology.organism_born") ? 1 : event.type().equals("ecology.organism_died") ? -1 : 0;
                    if (change == 0) continue;
                    producerGenerations.merge(Integer.parseInt(event.data().get("generation")), change, Integer::sum);
                    if (change > 0) lastProducerBirthTick = tick;
                }
                assertConserved(world.view(), config);
                if (world.tick() == 1280) {
                    assertEquals(4, producerGenerations.get(config.maxGenerations()));
                    assertEquals(4, producerGenerations.values().stream().mapToInt(Integer::intValue).sum());
                    assertEquals(962, lastProducerBirthTick);
                    assertTrue(world.view().births() < config.maxBirths());
                }
                if (firstProducerExtinction < 0 && !world.view().populationBySpecies().containsKey("producer"))
                    firstProducerExtinction = world.tick();
            }
            assertEquals(1283, firstProducerExtinction);
            assertEquals(962, lastProducerBirthTick);
            assertEquals(1536, world.tick());
            assertEquals(2, world.view().populationBySpecies().size());
            assertFalse(world.view().populationBySpecies().containsKey("producer"));
            assertTrue(EcologyReplayVerifier.verify(world.replay()).passed());
        }
    }

    private EcologyInput candidateInput(long tick) {
        String species = tick == 0 ? "producer" : tick == 1 ? "grazer" : tick == 2 ? "decomposer" : null;
        return species == null ? EcologyInput.idle(tick) : new EcologyInput(tick, Map.of(species, CANDIDATE.get(species)));
    }

    private void assertConserved(EcologyView view, EcologyConfig config) {
        assertEquals(config.initialMatter(), view.totalMatter());
        assertEquals(view.totalMatter(), view.soilMatter() + view.detritusMatter() + view.organismMatter());
        assertEquals(EcologyConfig.INITIAL_POPULATION + view.births() - view.deaths(), view.population());
        assertTrue(view.population() <= config.maxOrganisms());
        assertTrue(view.births() <= config.maxBirths());
        assertTrue(view.maxGeneration() <= config.maxGenerations());
    }
}
