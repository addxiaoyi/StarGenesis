package dev.stargenesis.sim;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

/** Dynamic edits must retain lifecycle state, deterministic physics and finite-run evidence. */
class EcologyDynamicLifecycleV2Test {
    @Test void scheduledEditsResumeExactlyAcrossMaturityMutationAndDeathBoundaries() {
        var config = EcologyConfig.forDuration(160);
        var checkpoints = Set.of(20L, 21L, 32L, 64L, 80L, 81L, 85L, 89L, 97L, 105L, 113L, 129L, 143L, 144L);
        try (var world = new EcologyWorld("ecology-dynamic-checkpoints", 42, config)) {
            EcologyWorld restored = EcologyWorld.restore(world.replay());
            try {
                boolean sawMutation = false;
                int restoredCheckpoints = 0;
                while (!world.view().terminal()) {
                    var input = scheduledInput(world.tick());
                    assertEquals(world.step(input), restored.step(input));
                    assertEquals(world.view(), restored.view());
                    assertConserved(world.view(), config);
                    sawMutation |= world.view().evolvedOrganisms() > 0;
                    if (checkpoints.contains(world.tick())) {
                        restored.close();
                        restored = EcologyWorld.restore(world.replay());
                        assertEquals(world.view(), restored.view());
                        assertEquals(world.replay(), restored.replay());
                        restoredCheckpoints++;
                    }
                }
                assertTrue(sawMutation, "The schedule must include inherited mutation before later program edits");
                assertEquals(144, world.tick(), "The final lifespan-one edit ends the existing population");
                assertEquals("EXTINCTION", world.view().reason());
                assertEquals(checkpoints.size(), restoredCheckpoints);
                assertEquals(world.replay(), restored.replay());
                assertTrue(EcologyReplayVerifier.verify(world.replay()).passed());
            } finally {
                restored.close();
            }
        }
    }

    @Test void dynamicEditsPreserveWorldIndependentPhysicsAndEventMeaning() {
        var config = EcologyConfig.forDuration(160);
        try (var first = new EcologyWorld("ecology-dynamic-identity-a", 42, config);
             var second = new EcologyWorld("ecology-dynamic-identity-b", 42, config)) {
            boolean sawMutation = false;
            while (!first.view().terminal()) {
                var input = scheduledInput(first.tick());
                var firstResult = first.step(input);
                var secondResult = second.step(input);
                assertEquals(eventMeaning(firstResult), eventMeaning(secondResult));
                assertEquals(quantities(first.view()), quantities(second.view()));
                assertNotEquals(firstResult.stateHash(), secondResult.stateHash());
                assertNotEquals(firstResult.eventHash(), secondResult.eventHash());
                assertConserved(first.view(), config);
                assertConserved(second.view(), config);
                sawMutation |= first.view().evolvedOrganisms() > 0;
            }
            assertTrue(sawMutation);
            assertEquals(144, first.tick());
            assertTrue(EcologyReplayVerifier.verify(first.replay()).passed());
            assertTrue(EcologyReplayVerifier.verify(second.replay()).passed());
        }
    }

    @Test void shorteningLifespanBelowExistingAgeKillsBeforeTheNextBirth() {
        var config = EcologyConfig.forDuration(64);
        try (var world = new EcologyWorld("ecology-dynamic-shortening", 42, config)) {
            while (world.tick() < 31)
                world.step(world.tick() == 0 ? new EcologyInput(0, programs(8, 2, 64, 0)) : EcologyInput.idle(world.tick()));
            assertEquals(0, world.view().births());
            assertEquals(0, world.view().deaths());
            try (var restored = EcologyWorld.restore(world.replay())) {
                var input = new EcologyInput(world.tick(), programs(8, 2, 1, 500));
                var result = world.step(input);
                assertEquals(result, restored.step(input));
                assertEquals(7, result.events().stream().filter(event -> event.type().equals("ecology.organism_died")).count());
                assertTrue(result.events().stream().noneMatch(event -> event.type().equals("ecology.organism_born")));
                assertEquals(0, world.view().births());
                assertEquals(EcologyConfig.INITIAL_POPULATION, world.view().deaths());
                assertEquals(0, world.view().organismMatter());
                assertEquals("EXTINCTION", world.view().reason());
                assertEquals(world.view(), restored.view());
                assertConserved(world.view(), config);
                assertTrue(EcologyReplayVerifier.verify(world.replay()).passed());
            }
        }
    }

    @Test void lengtheningLifespanBeforeDeathKeepsAccumulatedAgeAcrossRestore() {
        var config = new EcologyConfig(1000, 86, 32, 0, 80, 0, 5120);
        try (var world = new EcologyWorld("ecology-dynamic-lengthening", 42, config)) {
            while (world.tick() < 7)
                world.step(world.tick() == 0 ? new EcologyInput(0, programs(8, 2, 8, 0)) : EcologyInput.idle(world.tick()));
            world.step(new EcologyInput(world.tick(), programs(8, 2, 64, 0)));
            assertEquals(0, world.view().deaths(), "The old lifespan no longer governs this step");
            try (var restored = EcologyWorld.restore(world.replay())) {
                while (world.tick() < 63) {
                    var input = EcologyInput.idle(world.tick());
                    assertEquals(world.step(input), restored.step(input));
                    assertEquals(0, world.view().deaths());
                    assertConserved(world.view(), config);
                }
                assertEquals(world.step(EcologyInput.idle(63)), restored.step(EcologyInput.idle(63)));
                assertEquals(64, world.tick(), "Editing the program must not reset the original age");
                assertEquals("EXTINCTION", world.view().reason());
                assertEquals(EcologyConfig.INITIAL_POPULATION, world.view().deaths());
                assertEquals(0, world.view().births());
                assertEquals(world.replay(), restored.replay());
                assertConserved(world.view(), config);
                assertTrue(EcologyReplayVerifier.verify(world.replay()).passed());
            }
        }
    }

    @Test void changingCurrentLifespanRecalculatesMaturityAndBirthRecoveryWithoutResettingAge() {
        var config = EcologyConfig.forDuration(80);
        try (var world = new EcologyWorld("ecology-dynamic-birth-recovery", 42, config)) {
            Set<String> firstDecomposerParents = new HashSet<>();
            while (world.tick() < 32) {
                var result = world.step(world.tick() == 0 ? new EcologyInput(0, programs(8, 2, 64, 0)) : EcologyInput.idle(world.tick()));
                result.events().stream().filter(event -> event.type().equals("ecology.organism_born")
                                && event.data().get("species").equals("decomposer"))
                        .forEach(event -> firstDecomposerParents.add(event.data().get("parent")));
            }
            assertEquals(2, firstDecomposerParents.size());
            world.step(new EcologyInput(world.tick(), programs(8, 2, 128, 0)));
            try (var restored = EcologyWorld.restore(world.replay())) {
                while (world.tick() < 63) {
                    var input = EcologyInput.idle(world.tick());
                    var result = world.step(input);
                    assertEquals(result, restored.step(input));
                    assertTrue(result.events().stream().noneMatch(event -> event.type().equals("ecology.organism_born")
                            && firstDecomposerParents.contains(event.data().get("parent"))));
                    assertConserved(world.view(), config);
                }
                var result = world.step(EcologyInput.idle(world.tick()));
                assertEquals(result, restored.step(EcologyInput.idle(restored.tick())));
                var secondParents = new HashSet<String>();
                result.events().stream().filter(event -> event.type().equals("ecology.organism_born"))
                        .forEach(event -> secondParents.add(event.data().get("parent")));
                assertTrue(secondParents.containsAll(firstDecomposerParents),
                        "At age 64 the new maturity and 32-step birth interval are both satisfied");
                assertEquals(64, world.tick());
                assertEquals(world.view(), restored.view());
                assertTrue(EcologyReplayVerifier.verify(world.replay()).passed());
            }
        }
    }

    @Test void validResourcePopulationAndEvidenceBoundariesConserveMatterAndRestoreTerminalReplays() {
        var configs = List.of(
                config(14, 0, 7, 0, 64, 0),
                config(14, 0, 32, 8, 64, 2048),
                config(100_000, 0, 7, 8, 64, 2048),
                config(100_000, 99_986, 32, 8, 64, 2048),
                config(100_000, 99_986, 7, 0, 64, 0),
                config(100_000, 0, 32, 0, 64, 0),
                config(1000, 86, 32, 8, EcologyConfig.MAX_TICKS, 2048));
        for (int index = 0; index < configs.size(); index++) {
            var config = configs.get(index);
            assertEquals(2 * config.maxTicks() * config.maxOrganisms(), config.maxLedgerAttempts());
            try (var world = new EcologyWorld("ecology-dynamic-budgets-" + index, Long.MIN_VALUE + index, config)) {
                while (!world.view().terminal()) {
                    var result = world.step(world.tick() == 0 ? new EcologyInput(0, programs(8, 2, 512, 500))
                            : EcologyInput.idle(world.tick()));
                    assertTrue(result.events().size() <= EcologyWorld.MAX_EVENTS_PER_STEP);
                    assertConserved(world.view(), config);
                }
                assertTrue(world.tick() <= config.maxTicks());
                if (config.maxGenerations() == 0 || config.maxBirths() == 0) assertEquals(0, world.view().births());
                try (var restored = EcologyWorld.restore(world.replay())) {
                    assertEquals(world.view(), restored.view());
                    assertEquals(world.replay(), restored.replay());
                    assertThrows(IllegalStateException.class, () -> restored.step(EcologyInput.idle(restored.tick())));
                }
                assertTrue(EcologyReplayVerifier.verify(world.replay()).passed());
            }
        }
    }

    private static EcologyConfig config(long matter, long detritus, int population, int generations, int ticks, int births) {
        return new EcologyConfig(matter, detritus, population, generations, ticks, births, 2 * ticks * population);
    }

    private static EcologyInput scheduledInput(long tick) {
        if (tick == 0) return new EcologyInput(tick, programs(8, 2, 40, 500));
        if (tick == 80 || tick == 84 || tick == 88) {
            String species = tick == 80 ? "producer" : tick == 84 ? "grazer" : "decomposer";
            var role = species.equals("producer") ? MatterProgram.Role.PRODUCER
                    : species.equals("grazer") ? MatterProgram.Role.GRAZER : MatterProgram.Role.DECOMPOSER;
            return new EcologyInput(tick, Map.of(species, new MatterProgram(species, role, 1, 32, 512, 0)));
        }
        if (tick == 96 || tick == 104 || tick == 112) {
            String species = tick == 96 ? "producer" : tick == 104 ? "grazer" : "decomposer";
            return new EcologyInput(tick, Map.of(species, programs(8, 2, 64, 500).get(species)));
        }
        if (tick == 128) return new EcologyInput(tick, programs(8, 32, 512, 500));
        if (tick == 143) return new EcologyInput(tick, programs(1, 2, 1, 0));
        return EcologyInput.idle(tick);
    }

    private static Map<String, MatterProgram> programs(int intake, int reserve, int life, int mutation) {
        return Map.of(
                "producer", new MatterProgram("producer", MatterProgram.Role.PRODUCER, intake, reserve, life, mutation),
                "grazer", new MatterProgram("grazer", MatterProgram.Role.GRAZER, intake, reserve, life, mutation),
                "decomposer", new MatterProgram("decomposer", MatterProgram.Role.DECOMPOSER, intake, reserve, life, mutation));
    }

    private static List<EventMeaning> eventMeaning(EcologyStepResult result) {
        return result.events().stream().map(event -> {
            var data = new TreeMap<>(event.data());
            data.remove("parent");
            data.remove("organism");
            return new EventMeaning(event.type(), Map.copyOf(data));
        }).toList();
    }

    private static List<Object> quantities(EcologyView view) {
        return List.of(view.seed(), view.tick(), view.totalMatter(), view.soilMatter(), view.detritusMatter(), view.organismMatter(),
                view.population(), view.populationBySpecies(), view.matterBySpecies(), view.maxGeneration(), view.births(),
                view.deaths(), view.processedMatter(), view.evolvedOrganisms(), view.programs(), view.terminal(), view.reason());
    }

    private static void assertConserved(EcologyView view, EcologyConfig config) {
        assertEquals(config.initialMatter(), view.totalMatter());
        assertEquals(view.totalMatter(), view.soilMatter() + view.detritusMatter() + view.organismMatter());
        assertTrue(view.soilMatter() >= 0 && view.detritusMatter() >= 0 && view.organismMatter() >= 0);
        assertEquals(EcologyConfig.INITIAL_POPULATION + view.births() - view.deaths(), view.population());
        assertEquals(view.population(), view.populationBySpecies().values().stream().mapToInt(Integer::intValue).sum());
        assertEquals(view.organismMatter(), view.matterBySpecies().values().stream().mapToLong(Long::longValue).sum());
        assertTrue(view.population() <= config.maxOrganisms());
        assertTrue(view.births() <= config.maxBirths());
        assertTrue(view.maxGeneration() <= config.maxGenerations());
    }

    private record EventMeaning(String type, Map<String, String> data) { }
}
