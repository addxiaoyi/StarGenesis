package dev.stargenesis.sim;

import dev.stargenesis.api.sim.AgentId;
import dev.stargenesis.api.sim.StepInput;
import dev.stargenesis.api.sim.StepResult;
import dev.stargenesis.api.sim.SimulationBudget;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

/** Behavioral contracts for the real pure-Java sandbox, without Bukkit or mocks. */
class ResourceSandboxWorldTest {
    private static ResourceSandboxWorld world() {
        return new ResourceSandboxWorld("sandbox-test", 0x51A7E5L, SandboxConfig.defaults());
    }

    private static StepResult next(ResourceSandboxWorld world) {
        return world.step(new StepInput(world.tick(), List.of()));
    }

    @Test void sameSeedAndInputsHaveIdenticalEventsAndHashesForOneThousandSteps() {
        try (var first = world(); var second = world()) {
            assertEquals(first.stateHash(), second.stateHash());
            for (int step = 0; step < 1_000; step++) {
                assertEquals(next(first), next(second), "step " + step);
                assertEquals(first.view(), second.view(), "view at step " + step);
            }
        }
    }

    @Test void pausingAnAgentChangesTheObservedSimulation() {
        try (var active = world(); var paused = world()) {
            AgentId id = paused.view().agents().getFirst().id();
            paused.step(new StepInput(0, List.of(new StepInput.Command(id, 0, "pause", Map.of()))));
            next(active);
            for (int step = 1; step < 150; step++) { next(active); next(paused); }
            assertNotEquals(active.stateHash(), paused.stateHash());
            assertTrue(paused.view().agents().getFirst().energy() >= active.view().agents().getFirst().energy());
            assertEquals(150, paused.tick());
        }
    }

    @Test void programActionsMoveGatherAndReplayWithConservedResources() {
        long seed = seedWithOpenNorthRow();
        try (var simulation = new ResourceSandboxWorld("sandbox-test", seed, SandboxConfig.defaults().withAgentCount(1))) {
            AgentId id = simulation.view().agents().getFirst().id();
            for (int tick = 0; tick < 12; tick++) {
                var agent = simulation.view().agents().getFirst();
                GridPoint destination = agent.position().x() < 7
                        ? new GridPoint(agent.position().x() + 1, agent.position().y()) : agent.position();
                long resource = ResourceType.ORE.ordinal();
                List<StepInput.Command> commands = new ArrayList<>();
                commands.add(new StepInput.Command(id, tick * 3L, "pause", Map.of()));
                commands.add(new StepInput.Command(id, tick * 3L + 1, "program_move",
                        Map.of("x", (long) destination.x(), "y", (long) destination.y(), "z", 0L)));
                if (destination.x() == simulation.view().width() - 2 && destination.y() == 1)
                    commands.add(new StepInput.Command(id, tick * 3L + 2, "program_harvest", Map.of("resource", resource)));
                simulation.step(new StepInput(simulation.tick(), commands));
                if (simulation.view().agents().getFirst().ore() > 0) break;
            }
            var view = simulation.view();
            assertTrue(view.agents().getFirst().ore() > 0);
            assertTrue(ReplayVerifier.verify(simulation.replayLog()).passed());
            assertEquals(SandboxConfig.defaults().nodeStock(), total(simulation.snapshot().ledger().balances(), "ore"));
        }
    }

    private long seedWithOpenNorthRow() {
        for (long seed = 0; seed < 100; seed++) {
            try (var candidate = new ResourceSandboxWorld("seed-probe", seed, SandboxConfig.defaults().withAgentCount(1))) {
                SandboxView view = candidate.view();
                boolean clear = true;
                for (int x = 1; x < view.width() - 1; x++)
                    clear &= view.terrain().get(view.width() + x) == Terrain.GROUND;
                if (clear) return seed;
            }
        }
        throw new AssertionError("Expected one of the bounded seeds to leave the north corridor open");
    }

    @Test void programActionValidationRejectsBeforeAnyMutation() {
        try (var simulation = world()) {
            AgentId id = simulation.view().agents().getFirst().id();
            String before = simulation.stateHash();
            assertThrows(IllegalArgumentException.class, () -> simulation.step(new StepInput(0, List.of(
                    new StepInput.Command(id, 0, "pause", Map.of()),
                    new StepInput.Command(id, 1, "program_move", Map.of("x", 2L, "y", 1L))))));
            assertEquals(before, simulation.stateHash());
            assertThrows(IllegalArgumentException.class, () -> simulation.step(new StepInput(0, List.of(
                    new StepInput.Command(id, 0, "pause", Map.of()),
                    new StepInput.Command(id, 1, "program_move", Map.of("x", 4L, "y", 1L, "z", 0L))))));
            assertEquals(before, simulation.stateHash());
        }
    }

    @Test void programNodeBudgetAndStopCommandValidateBeforeApplyingInputs() {
        SimulationBudget budget = new SimulationBudget(1, 16, 8, 3000, 16, 64);
        try (var simulation = new ResourceSandboxWorld("sandbox-test", 42L,
                SandboxConfig.defaults().withAgentCount(1).withBudget(budget))) {
            AgentId id = simulation.view().agents().getFirst().id();
            String before = simulation.stateHash();
            assertThrows(IllegalArgumentException.class, () -> simulation.step(new StepInput(0, List.of(
                    new StepInput.Command(id, 0, "pause", Map.of()),
                    new StepInput.Command(id, 1, "program_wait", Map.of()),
                    new StepInput.Command(id, 2, "program_wait", Map.of())))));
            assertEquals(before, simulation.stateHash());
            assertThrows(IllegalArgumentException.class, () -> simulation.step(new StepInput(0, List.of(
                    new StepInput.Command(id, 0, "stop", Map.of()),
                    new StepInput.Command(id, 1, "pause", Map.of())))));
            assertEquals(before, simulation.stateHash());
        }
    }

    @Test void checkpointRestoreMatchesTheUninterruptedRun() {
        try (var original = world()) {
            for (int step = 0; step < 500; step++) next(original);
            try (var restored = ResourceSandboxWorld.restore(original.snapshot())) {
                assertEquals(original.stateHash(), restored.stateHash());
                for (int step = 500; step < 1_000; step++) {
                    assertEquals(next(original), next(restored), "restored step " + step);
                }
                assertEquals(original.view(), restored.view());
            }
        }
    }

    @Test void twoToFourAgentsStayInBoundsAndDoNotPassThroughWallsOrEachOther() {
        for (int count = 2; count <= 4; count++) {
            try (var simulation = new ResourceSandboxWorld("sandbox-test", 42L, SandboxConfig.defaults().withAgentCount(count))) {
                for (int step = 0; step < 1_000; step++) {
                    next(simulation);
                    SandboxView view = simulation.view();
                    assertEquals(count, view.agents().size());
                    Set<GridPoint> occupied = new HashSet<>();
                    for (var agent : view.agents()) {
                        GridPoint point = agent.position();
                        assertTrue(point.x() >= 0 && point.x() < view.width());
                        assertTrue(point.y() >= 0 && point.y() < view.height());
                        assertNotEquals(Terrain.BLOCKED, view.terrain().get(point.y() * view.width() + point.x()));
                        assertTrue(occupied.add(point), "Agent collision at " + point);
                        assertTrue(agent.energy() >= 0);
                        assertTrue(agent.ore() >= 0 && agent.biomass() >= 0);
                        assertTrue(agent.ore() + agent.biomass() <= agent.capacity());
                    }
                    if (step % 25 == 0) {
                        Map<String, Map<String, Long>> balances = simulation.snapshot().ledger().balances();
                        assertEquals(SandboxConfig.defaults().nodeStock(), total(balances, "ore"));
                        assertEquals(SandboxConfig.defaults().nodeStock(), total(balances, "biomass"));
                        assertEquals(SandboxConfig.defaults().energyPerAgent() * count, total(balances, "energy"));
                    }
                }
                assertTrue(simulation.view().score() > 0, "The " + count + "-agent experiment must actually deliver material");
            }
        }
    }

    private static long total(Map<String, Map<String, Long>> balances, String resource) {
        return balances.values().stream().mapToLong(resources -> resources.getOrDefault(resource, 0L)).sum();
    }

    @Test void invalidInputsAreRejectedBeforeStateMutation() {
        try (var simulation = world()) {
            AgentId id = simulation.view().agents().getFirst().id();
            String before = simulation.stateHash();
            assertThrows(IllegalArgumentException.class,
                    () -> simulation.step(new StepInput(1, List.of())));
            assertEquals(before, simulation.stateHash());
            assertThrows(IllegalArgumentException.class,
                    () -> simulation.step(new StepInput(0, List.of(new StepInput.Command(id, 0, "run_java", Map.of())))));
            assertEquals(before, simulation.stateHash());
            assertThrows(IllegalArgumentException.class,
                    () -> simulation.step(new StepInput(0, List.of(new StepInput.Command(new AgentId(new UUID(999, 999)), 0, "pause", Map.of())))));
            assertEquals(before, simulation.stateHash());
            assertThrows(IllegalArgumentException.class,
                    () -> simulation.step(new StepInput(0, List.of(new StepInput.Command(id, 0, "pause", Map.of("unexpected", 1L))))));
            assertEquals(before, simulation.stateHash());
        }
    }

    @Test void oldInputSequencesCannotBeReusedForAnotherAction() {
        try (var simulation = world()) {
            AgentId id = simulation.view().agents().getFirst().id();
            simulation.step(new StepInput(0, List.of(new StepInput.Command(id, 7, "pause", Map.of()))));
            String before = simulation.stateHash();
            assertThrows(IllegalArgumentException.class,
                    () -> simulation.step(new StepInput(1, List.of(new StepInput.Command(id, 7, "resume", Map.of())))));
            assertEquals(before, simulation.stateHash());
        }
    }

    @Test void replayVerifiesTheActualOnlineStateHash() {
        try (var simulation = world()) {
            for (int step = 0; step < 1_000; step++) next(simulation);
            var verification = ReplayVerifier.verify(simulation.replayLog());
            assertTrue(verification.passed(), verification::toString);
        }
    }

    @Test void replayReportsTheFirstTamperedFrame() {
        try (var simulation = world()) {
            for (int step = 0; step < 30; step++) next(simulation);
            ReplayLog log = simulation.replayLog();
            var frames = new ArrayList<>(log.frames());
            ReplayLog.Frame frame = frames.get(11);
            frames.set(11, new ReplayLog.Frame(frame.input(), "0".repeat(64), frame.events()));
            ReplayLog corrupted = new ReplayLog(log.engineVersion(), log.schemaVersion(), log.worldId(), log.seed(), log.config(), frames);
            var verification = ReplayVerifier.verify(corrupted);
            assertFalse(verification.passed());
            assertEquals(11, verification.firstDivergentTick());
        }
    }

    @Test void replayLocatesInvalidCommandAtTheActualFaultFrame() {
        try (var simulation = world()) {
            for (int step = 0; step < 30; step++) next(simulation);
            ReplayLog log = simulation.replayLog();
            var frames = new ArrayList<>(log.frames());
            ReplayLog.Frame frame = frames.get(11);
            AgentId id = simulation.view().agents().getFirst().id();
            StepInput invalid = new StepInput(11, List.of(new StepInput.Command(id, 5, "run_java", Map.of())));
            frames.set(11, new ReplayLog.Frame(invalid, frame.stateHash(), frame.events()));
            var verification = ReplayVerifier.verify(new ReplayLog(log.engineVersion(), log.schemaVersion(), log.worldId(), log.seed(), log.config(), frames));
            assertFalse(verification.passed());
            assertEquals(11, verification.firstDivergentTick());
        }
    }

    @Test void replayChecksEventEvidenceAsWellAsStateHash() {
        try (var simulation = world()) {
            for (int step = 0; step < 10; step++) next(simulation);
            ReplayLog log = simulation.replayLog();
            var frames = new ArrayList<>(log.frames());
            ReplayLog.Frame frame = frames.getFirst();
            assertFalse(frame.events().isEmpty());
            frames.set(0, new ReplayLog.Frame(frame.input(), frame.stateHash(), List.of()));
            var verification = ReplayVerifier.verify(new ReplayLog(log.engineVersion(), log.schemaVersion(), log.worldId(), log.seed(), log.config(), frames));
            assertFalse(verification.passed());
            assertEquals(0, verification.firstDivergentTick());
            assertEquals("EVENT_LOG_MISMATCH", verification.reason());
        }
    }

    @Test void nodeAndPathBudgetExhaustionTerminatesWithAnExplanation() {
        List<SimulationBudget> limits = List.of(
                new SimulationBudget(1, 16, 8, 3000, 16, 64),
                new SimulationBudget(128, 16, 0, 3000, 16, 64));
        for (SimulationBudget budget : limits) {
            try (var simulation = new ResourceSandboxWorld("sandbox-test", 42L, SandboxConfig.defaults().withBudget(budget))) {
                StepResult result = next(simulation);
                assertTrue(result.terminal(), "Over-budget work must stop in its own step");
                assertTrue(result.reason().contains("BUDGET"), result::reason);
                assertThrows(IllegalStateException.class, () -> next(simulation));
            }
        }
    }

    @Test void runtimeBudgetStopsTheScenarioEvenWhenConfiguredDurationIsLonger() {
        SimulationBudget budget = new SimulationBudget(128, 16, 8, 3, 16, 64);
        try (var simulation = new ResourceSandboxWorld("sandbox-test", 42L, SandboxConfig.defaults().withBudget(budget))) {
            assertFalse(next(simulation).terminal());
            assertFalse(next(simulation).terminal());
            StepResult last = next(simulation);
            assertTrue(last.terminal());
            assertTrue(last.reason().contains("BUDGET"));
            assertEquals(3, simulation.tick());
        }
    }

    @Test void manualStopEndsOnceAndIsReplayable() {
        try (var simulation = world()) {
            AgentId id = simulation.view().agents().getFirst().id();
            StepResult result = simulation.step(new StepInput(0, List.of(new StepInput.Command(id, 0, "stop", Map.of()))));
            assertTrue(result.terminal());
            assertEquals("MANUAL_STOP", result.reason());
            assertTrue(ReplayVerifier.verify(simulation.replayLog()).passed());
            assertThrows(IllegalStateException.class, () -> next(simulation));
        }
    }

    @Test void snapshotWithUnmatchedStateHashIsRejected() {
        try (var simulation = world()) {
            for (int step = 0; step < 10; step++) next(simulation);
            WorldSnapshot value = simulation.snapshot();
            WorldSnapshot invalid = new WorldSnapshot(value.engineVersion(), value.schemaVersion(), value.worldId(), value.seed(),
                    value.config(), value.tick(), value.terrain(), value.base(), value.nodes(), value.agents(), value.ledger(),
                    value.ledgerHistoryHash(), value.eventHistoryHash(), value.terminal(), value.reason(), value.history(), "0".repeat(64));
            assertThrows(IllegalArgumentException.class, () -> ResourceSandboxWorld.restore(invalid));
        }
    }

    @Test void publishedViewCollectionsAreImmutable() {
        try (var simulation = world()) {
            SandboxView view = simulation.view();
            assertThrows(UnsupportedOperationException.class, () -> view.agents().clear());
            assertThrows(UnsupportedOperationException.class, () -> view.nodes().clear());
            assertThrows(UnsupportedOperationException.class, () -> view.terrain().clear());
        }
    }

    @Test void simulationStateCannotBeReadFromAnotherThread() throws Exception {
        try (var simulation = world(); var executor = Executors.newSingleThreadExecutor()) {
            executor.submit(() -> assertThrows(IllegalStateException.class, simulation::view)).get();
            executor.submit(() -> assertThrows(IllegalStateException.class, simulation::snapshot)).get();
            executor.submit(() -> assertThrows(IllegalStateException.class, () -> simulation.step(new StepInput(0, List.of())))).get();
        }
    }
}
