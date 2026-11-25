package dev.stargenesis.sim;

import dev.stargenesis.api.sim.AgentId;
import dev.stargenesis.api.sim.SimulationBudget;
import dev.stargenesis.api.sim.StepInput;
import dev.stargenesis.api.sim.StepResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.*;

class GridSimulationWorldTest {
    private static final AgentId AGENT = new AgentId(new UUID(0, 1));

    private static GridWorldSettings settings(long seed) {
        return new GridWorldSettings("test-world", seed, 8, 8, 100, SimulationBudget.defaults(),
                List.of(new GridAgentStart(AGENT, 2, 2)));
    }

    private static StepResult step(GridSimulationWorld world, String action, Map<String, Long> arguments) {
        StepInput.Command command = new StepInput.Command(AGENT, world.tick(), action, arguments);
        return world.step(new StepInput(world.tick(), List.of(command)));
    }

    @Test
    void sameSeedAndInputsProduceTheSameStateAndEvents() {
        try (var first = new GridSimulationWorld(settings(91)); var second = new GridSimulationWorld(settings(91))) {
            assertEquals(first.snapshot(), second.snapshot());
            assertEquals(step(first, "gather", Map.of()), step(second, "gather", Map.of()));
            assertEquals(step(first, "wait", Map.of()), step(second, "wait", Map.of()));
            assertEquals(first.stateHash(), second.stateHash());
        }
    }

    @Test
    void gatherAndDepositConserveScenarioResources() {
        try (var world = new GridSimulationWorld(settings(3))) {
            long initialTotal = totalResources(world.snapshot());
            StepResult gather = step(world, "gather", Map.of());
            assertEquals("action.applied", gather.events().getFirst().type());
            assertEquals(1, world.snapshot().agents().getFirst().inventory());

            StepResult deposit = step(world, "deposit", Map.of("amount", 1L));
            assertEquals("action.applied", deposit.events().getFirst().type());
            assertEquals(1, world.snapshot().sharedStock());
            assertEquals(0, world.snapshot().agents().getFirst().inventory());
            assertEquals(initialTotal, totalResources(world.snapshot()));
        }
    }

    @Test
    void invalidActionsAreRecordedWithoutChangingTheirTargetState() {
        try (var world = new GridSimulationWorld(new GridWorldSettings("edge", 7, 4, 4, 100,
                SimulationBudget.defaults(), List.of(new GridAgentStart(AGENT, 0, 0))))) {
            StepResult outside = step(world, "move", Map.of("dx", -1L, "dy", 0L));
            assertEquals("action.rejected", outside.events().getFirst().type());
            assertEquals("out_of_bounds", outside.events().getFirst().data().get("reason"));
            assertEquals(0, world.snapshot().agents().getFirst().x());

            StepResult invalidDirection = step(world, "move", Map.of("dx", Long.MIN_VALUE, "dy", 0L));
            assertEquals("invalid_direction", invalidDirection.events().getFirst().data().get("reason"));
            assertEquals("unknown_action", step(world, "teleport", Map.of()).events().getFirst().data().get("reason"));
        }
    }

    @Test
    void canonicalAgentOrderResolvesSameCellMovesDeterministically() {
        AgentId firstId = new AgentId(new UUID(0, 1));
        AgentId secondId = new AgentId(new UUID(0, 2));
        boolean tested = false;
        for (long seed = 0; seed < 100 && !tested; seed++) {
            GridWorldSettings settings = new GridWorldSettings("collision", seed, 5, 5, 100,
                    SimulationBudget.defaults(), List.of(new GridAgentStart(secondId, 2, 1), new GridAgentStart(firstId, 0, 1)));
            try (var world = new GridSimulationWorld(settings)) {
                GridWorldSnapshot.Cell middle = world.snapshot().cells().get(settings.width() + 1);
                if (middle.blocked()) continue;
                StepInput input = new StepInput(0, List.of(
                        new StepInput.Command(secondId, 0, "move", Map.of("dx", -1L, "dy", 0L)),
                        new StepInput.Command(firstId, 0, "move", Map.of("dx", 1L, "dy", 0L))));

                StepResult result = world.step(input);
                assertEquals(firstId.value().toString(), result.events().get(0).data().get("agent"));
                assertEquals("applied", result.events().get(0).data().get("result"));
                assertEquals(secondId.value().toString(), result.events().get(1).data().get("agent"));
                assertEquals("occupied", result.events().get(1).data().get("reason"));
                tested = true;
            }
        }
        assertTrue(tested, "Expected at least one open collision target among the deterministic fixtures");
    }

    @Test
    void checkpointRestoresStateAndReplayVerifiesEveryStep() {
        try (var world = new GridSimulationWorld(settings(29))) {
            step(world, "gather", Map.of());
            step(world, "wait", Map.of());
            GridWorldCheckpoint checkpoint = world.checkpoint();
            List<GridWorldSnapshot.Cell> changedCells = new ArrayList<>(checkpoint.snapshot().cells());
            int spawnIndex = 2 * checkpoint.snapshot().width() + 2;
            GridWorldSnapshot.Cell spawn = changedCells.get(spawnIndex);
            changedCells.set(spawnIndex, new GridWorldSnapshot.Cell(spawn.x(), spawn.y(), false,
                    spawn.resourceUnits() + 1));
            GridWorldSnapshot changedSnapshot = new GridWorldSnapshot(checkpoint.snapshot().engineVersion(),
                    checkpoint.snapshot().worldId(), checkpoint.snapshot().seed(), checkpoint.snapshot().width(),
                    checkpoint.snapshot().height(), checkpoint.snapshot().tick(), checkpoint.snapshot().sharedStock(),
                    changedCells, checkpoint.snapshot().agents(), checkpoint.snapshot().stateHash());
            assertThrows(IllegalArgumentException.class,
                    () -> GridSimulationWorld.restore(new GridWorldCheckpoint(checkpoint.settings(), changedSnapshot)));
            try (var restored = GridSimulationWorld.restore(checkpoint)) {
                assertEquals(world.snapshot(), restored.snapshot());
                assertEquals(step(world, "deposit", Map.of("amount", 1L)),
                        step(restored, "deposit", Map.of("amount", 1L)));
                assertEquals(world.snapshot(), restored.snapshot());
            }

            GridWorldReplay replay = world.replayLog();
            try (var replayed = GridSimulationWorld.replay(replay)) {
                assertEquals(world.snapshot(), replayed.snapshot());
            }
        }
    }

    @Test
    void immutableSnapshotsAndWorldOwnershipAreEnforced() throws Exception {
        try (var world = new GridSimulationWorld(settings(42))) {
            GridWorldSnapshot snapshot = world.snapshot();
            assertThrows(UnsupportedOperationException.class, () -> snapshot.cells().clear());
            assertThrows(UnsupportedOperationException.class, () -> snapshot.agents().clear());
            try (var executor = Executors.newSingleThreadExecutor()) {
                assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> executor.submit(world::stateHash).get());
            }
        }
    }

    @Test
    void terminalStepAndInvalidSettingsRespectHardLimits() {
        SimulationBudget oneStep = new SimulationBudget(1, 1, 0, 1, 1, 0);
        GridWorldSettings oneStepSettings = new GridWorldSettings("terminal", 1, 2, 2, 100,
                oneStep, List.of(new GridAgentStart(AGENT, 0, 0)));
        try (var world = new GridSimulationWorld(oneStepSettings)) {
            assertTrue(step(world, "wait", Map.of()).terminal());
            assertThrows(IllegalStateException.class, () -> step(world, "wait", Map.of()));
        }
        assertThrows(IllegalArgumentException.class, () -> new GridWorldSettings("too-large", 1, 65, 65, 100,
                SimulationBudget.defaults(), List.of(new GridAgentStart(AGENT, 0, 0))));
        assertThrows(IllegalArgumentException.class, () -> new GridWorldSettings("overlap", 1, 4, 4, 100,
                SimulationBudget.defaults(), List.of(new GridAgentStart(AGENT, 0, 0),
                new GridAgentStart(new AgentId(new UUID(0, 2)), 0, 0))));
    }

    @Test
    void maximumGridAndAgentLoadReplaysDeterministicallyAtScale() {
        SimulationBudget budget = new SimulationBudget(128, 128, 64, 256, 16, 256);
        List<GridAgentStart> agents = java.util.stream.IntStream.range(0, 128)
                .mapToObj(index -> new GridAgentStart(new AgentId(new UUID(0, index + 1)), index % 64, index / 64))
                .toList();
        GridWorldSettings settings = new GridWorldSettings("scale-test", 937, 64, 64, 50, budget, agents);
        try (var source = new GridSimulationWorld(settings)) {
            for (int tick = 0; tick < 256; tick++) {
                long logicalTick = tick;
                List<StepInput.Command> commands = agents.stream()
                        .map(agent -> new StepInput.Command(agent.id(), logicalTick, "wait", Map.of()))
                        .toList();
                source.step(new StepInput(logicalTick, commands));
            }

            GridWorldReplay replay = source.replayLog();
            assertEquals(32_768, replay.frames().stream().mapToInt(frame -> frame.input().commands().size()).sum());
            try (var restored = GridSimulationWorld.replay(replay)) {
                assertEquals(source.stateHash(), restored.stateHash());
                assertEquals(source.snapshot(), restored.snapshot());
            }
        }
    }

    private static long totalResources(GridWorldSnapshot snapshot) {
        return snapshot.cells().stream().mapToLong(GridWorldSnapshot.Cell::resourceUnits).sum()
                + snapshot.agents().stream().mapToLong(GridWorldSnapshot.Agent::inventory).sum()
                + snapshot.sharedStock();
    }
}
