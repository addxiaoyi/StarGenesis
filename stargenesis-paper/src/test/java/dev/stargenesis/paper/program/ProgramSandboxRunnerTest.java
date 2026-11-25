package dev.stargenesis.paper.program;

import dev.stargenesis.api.sim.AgentId;
import dev.stargenesis.design.PrimitiveCatalog;
import dev.stargenesis.design.ProgramIR;
import dev.stargenesis.design.ProgramInterpreter;
import dev.stargenesis.sim.ReplayVerifier;
import dev.stargenesis.sim.ResourceSandboxWorld;
import dev.stargenesis.sim.SandboxConfig;
import dev.stargenesis.sim.SandboxView;
import dev.stargenesis.sim.Terrain;
import dev.stargenesis.api.sim.SimulationBudget;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProgramSandboxRunnerTest {
    @Test void typedProgramDrivesMovementAndHarvestAndTheWorldReplayVerifies() {
        long seed = seedWithOpenNorthRow();
        var first = runCorridorProgram("program-world", seed);
        var second = runCorridorProgram("program-world", seed);

        assertEquals(first.stepResults(), second.stepResults());
        assertEquals(first.stateHash(), second.stateHash());
        assertEquals(first.ore(), second.ore());
        assertTrue(first.ore() > 0);
        assertTrue(first.replayPassed());
    }

    @Test void multipleWorldActionsPerTickAreRejectedWithoutAdvancingTheWorld() {
        ProgramIR program = new ProgramIR(List.of(
                instruction("wait1", PrimitiveCatalog.Operation.WAIT, List.of(), Map.of()),
                instruction("wait2", PrimitiveCatalog.Operation.WAIT, List.of(), Map.of()),
                instruction("wait3", PrimitiveCatalog.Operation.WAIT, List.of(), Map.of())), 8);
        try (var world = new ResourceSandboxWorld("program-budget", 12, SandboxConfig.defaults().withAgentCount(1))) {
            AgentId agent = world.view().agents().getFirst().id();
            var runner = new ProgramSandboxRunner(world, Map.of(agent, program));
            String before = runner.stateHash();
            var report = runner.step();
            assertEquals(ProgramSandboxRunner.Status.ACTION_BUDGET_EXCEEDED, report.status());
            assertEquals(before, runner.stateHash());
            assertEquals(0, runner.view().tick());
        }
    }

    @Test void incompatibleActionIsRejectedBeforeAnySandboxInputIsApplied() {
        ProgramIR program = new ProgramIR(List.of(
                instruction("x", PrimitiveCatalog.Operation.CONSTANT_INTEGER, List.of(), Map.of("value", "2")),
                instruction("y", PrimitiveCatalog.Operation.CONSTANT_INTEGER, List.of(), Map.of("value", "1")),
                instruction("z", PrimitiveCatalog.Operation.CONSTANT_INTEGER, List.of(), Map.of("value", "1")),
                instruction("vector", PrimitiveCatalog.Operation.MAKE_VECTOR, List.of("x", "y", "z"), Map.of()),
                instruction("move", PrimitiveCatalog.Operation.MOVE, List.of("vector"), Map.of())), 8);
        try (var world = new ResourceSandboxWorld("program-invalid", 12, SandboxConfig.defaults().withAgentCount(1))) {
            AgentId agent = world.view().agents().getFirst().id();
            var runner = new ProgramSandboxRunner(world, Map.of(agent, program));
            String before = runner.stateHash();
            var report = runner.step();
            assertEquals(ProgramSandboxRunner.Status.WORLD_REJECTED, report.status());
            assertEquals(before, runner.stateHash());
            assertEquals(0, runner.view().tick());
        }
    }

    @Test void sandboxProvidesTypedBooleanSensorsAndCarriesSignalsToTheNextTick() {
        ProgramIR program = new ProgramIR(List.of(
                instruction("near_base", PrimitiveCatalog.Operation.SENSE_BOOLEAN, List.of(), Map.of("key", "at_base")),
                instruction("one", PrimitiveCatalog.Operation.CONSTANT_INTEGER, List.of(), Map.of("value", "1")),
                instruction("zero", PrimitiveCatalog.Operation.CONSTANT_INTEGER, List.of(), Map.of("value", "0")),
                instruction("value", PrimitiveCatalog.Operation.SELECT_INTEGER, List.of("near_base", "one", "zero"), Map.of()),
                instruction("emit", PrimitiveCatalog.Operation.EMIT_SIGNAL, List.of("value"), Map.of("channel", "near_base"))), 16);
        try (var world = new ResourceSandboxWorld("program-sensor", 7, SandboxConfig.defaults().withAgentCount(1))) {
            AgentId agent = world.view().agents().getFirst().id();
            ProgramSandboxRunner runner = new ProgramSandboxRunner(world, Map.of(agent, program));

            var result = runner.step();

            assertEquals(ProgramSandboxRunner.Status.ADVANCED, result.status());
            assertEquals(1L, runner.signals().get("near_base"));
            assertEquals(1L, result.signals().get("near_base"));
        }
    }

    @Test void signalChannelsStayWithinTheInterpreterContextLimit() {
        List<ProgramIR.Instruction> instructions = new ArrayList<>();
        instructions.add(instruction("value", PrimitiveCatalog.Operation.CONSTANT_INTEGER, List.of(), Map.of("value", "1")));
        for (int index = 0; index < 65; index++)
            instructions.add(instruction("signal" + index, PrimitiveCatalog.Operation.EMIT_SIGNAL,
                    List.of("value"), Map.of("channel", "channel_" + index)));
        ProgramIR program = new ProgramIR(instructions, 128);
        SimulationBudget budget = new SimulationBudget(128, 16, 8, 3000, 16, 128);
        SandboxConfig config = SandboxConfig.defaults().withAgentCount(1).withBudget(budget);
        try (var world = new ResourceSandboxWorld("program-signals", 8, config)) {
            AgentId agent = world.view().agents().getFirst().id();
            ProgramSandboxRunner runner = new ProgramSandboxRunner(world, Map.of(agent, program));
            String before = runner.stateHash();

            var report = runner.step();

            assertEquals(ProgramSandboxRunner.Status.SIGNAL_CHANNEL_BUDGET_EXCEEDED, report.status());
            assertEquals(before, runner.stateHash());
            assertEquals(0, runner.view().tick());
            assertTrue(runner.signals().isEmpty());
        }
    }

    @Test void hostNodeBudgetStopsAnOverBudgetProgramBeforeWorldOrSignalsChange() {
        var program = new ProgramIR(List.of(
                instruction("value", PrimitiveCatalog.Operation.CONSTANT_INTEGER, List.of(), Map.of("value", "1")),
                instruction("signal", PrimitiveCatalog.Operation.EMIT_SIGNAL, List.of("value"), Map.of("channel", "ready")),
                instruction("wait", PrimitiveCatalog.Operation.WAIT, List.of(), Map.of())), 256);
        var budget = new SimulationBudget(2, 1, 0, 20, 1, 1);
        try (var world = new ResourceSandboxWorld("host-node-budget", 42, SandboxConfig.defaults().withAgentCount(1).withBudget(budget))) {
            var runner = new ProgramSandboxRunner(world, Map.of(world.view().agents().getFirst().id(), program));
            var before = runner.snapshot();
            var result = runner.step();
            assertEquals(ProgramSandboxRunner.Status.PROGRAM_FAILED, result.status());
            assertEquals(ProgramInterpreter.Status.BUDGET_EXCEEDED, result.executions().values().iterator().next().status());
            assertEquals(before, runner.snapshot());
            assertTrue(runner.signals().isEmpty());
            assertEquals(ProgramSandboxRunner.Status.PROGRAM_FAILED, runner.step().status());
            assertEquals(before, runner.snapshot());
        }
    }

    @Test void aLaterAgentFailureDoesNotPublishTheFirstAgentsActionsOrSignals() {
        var signal = new ProgramIR(List.of(
                instruction("value", PrimitiveCatalog.Operation.CONSTANT_INTEGER, List.of(), Map.of("value", "1")),
                instruction("signal", PrimitiveCatalog.Operation.EMIT_SIGNAL, List.of("value"), Map.of("channel", "ready"))), 8);
        var fail = new ProgramIR(List.of(instruction("missing", PrimitiveCatalog.Operation.SENSE_INTEGER,
                List.of(), Map.of("key", "not_available"))), 8);
        try (var world = new ResourceSandboxWorld("program-batch", 42, SandboxConfig.defaults())) {
            var agents = world.view().agents().stream().map(SandboxView.AgentView::id).sorted().toList();
            var runner = new ProgramSandboxRunner(world, Map.of(agents.getFirst(), signal, agents.getLast(), fail));
            var before = runner.snapshot();
            for (int attempt = 0; attempt < 2; attempt++) {
                var result = runner.step();
                assertEquals(ProgramSandboxRunner.Status.PROGRAM_FAILED, result.status());
                assertEquals(2, result.executions().size());
                assertTrue(result.actions().isEmpty());
                assertTrue(runner.signals().isEmpty());
                assertEquals(before, runner.snapshot());
            }
        }
    }

    @Test void allAgentsReadThePreviousTickSignalsAndFailuresDoNotConsumeMessageBudget() {
        var signal = new ProgramIR(List.of(
                instruction("previous", PrimitiveCatalog.Operation.READ_SIGNAL, List.of(), Map.of("channel", "counter")),
                instruction("one", PrimitiveCatalog.Operation.CONSTANT_INTEGER, List.of(), Map.of("value", "1")),
                instruction("value", PrimitiveCatalog.Operation.ADD_INTEGER, List.of("previous", "one"), Map.of()),
                instruction("signal", PrimitiveCatalog.Operation.EMIT_SIGNAL, List.of("value"), Map.of("channel", "counter"))), 8);
        var oneMessage = new SimulationBudget(8, 2, 0, 20, 1, 1);
        try (var world = new ResourceSandboxWorld("program-message-batch", 42, SandboxConfig.defaults().withBudget(oneMessage))) {
            var agents = world.view().agents();
            var rejected = new ProgramSandboxRunner(world, Map.of(agents.getFirst().id(), signal, agents.getLast().id(), signal));
            var before = world.snapshot();
            assertEquals(ProgramSandboxRunner.Status.MESSAGE_BUDGET_EXCEEDED, rejected.step().status());
            assertEquals(before, world.snapshot());
            assertTrue(rejected.signals().isEmpty());
            var accepted = new ProgramSandboxRunner(world, Map.of(agents.getFirst().id(), signal));
            assertEquals(ProgramSandboxRunner.Status.ADVANCED, accepted.step().status());
            assertEquals(1L, accepted.signals().get("counter"));
        }
        var twoMessages = new SimulationBudget(8, 2, 0, 20, 1, 2);
        try (var world = new ResourceSandboxWorld("program-synchronous-signals", 42, SandboxConfig.defaults().withBudget(twoMessages))) {
            var agents = world.view().agents();
            var runner = new ProgramSandboxRunner(world, Map.of(agents.getFirst().id(), signal, agents.getLast().id(), signal));
            assertEquals(ProgramSandboxRunner.Status.ADVANCED, runner.step().status());
            assertEquals(1L, runner.signals().get("counter"));
            assertEquals(ProgramSandboxRunner.Status.ADVANCED, runner.step().status());
            assertEquals(2L, runner.signals().get("counter"));
            assertTrue(ReplayVerifier.verify(runner.replayLog()).passed());
        }
    }

    private RunResult runCorridorProgram(String worldId, long seed) {
        try (var world = new ResourceSandboxWorld(worldId, seed, SandboxConfig.defaults().withAgentCount(1))) {
            AgentId agent = world.view().agents().getFirst().id();
            var runner = new ProgramSandboxRunner(world, Map.of(agent, corridorProgram()));
            List<dev.stargenesis.api.sim.StepResult> results = new ArrayList<>();
            for (int tick = 0; tick < 10 && !runner.view().terminal(); tick++) {
                var report = runner.step();
                assertEquals(ProgramSandboxRunner.Status.ADVANCED, report.status(), report.message());
                results.add(report.worldResult());
            }
            SandboxView view = runner.view();
            return new RunResult(results, runner.stateHash(), view.agents().getFirst().ore(),
                    ReplayVerifier.verify(runner.replayLog()).passed());
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
        throw new AssertionError("Expected a bounded seed with an open north corridor");
    }

    private ProgramIR corridorProgram() {
        return new ProgramIR(List.of(
                instruction("x", PrimitiveCatalog.Operation.SENSE_INTEGER, List.of(), Map.of("key", "x")),
                instruction("y", PrimitiveCatalog.Operation.SENSE_INTEGER, List.of(), Map.of("key", "y")),
                instruction("one", PrimitiveCatalog.Operation.CONSTANT_INTEGER, List.of(), Map.of("value", "1")),
                instruction("next_x", PrimitiveCatalog.Operation.ADD_INTEGER, List.of("x", "one"), Map.of()),
                instruction("edge", PrimitiveCatalog.Operation.CONSTANT_INTEGER, List.of(), Map.of("value", "7")),
                instruction("before_ore", PrimitiveCatalog.Operation.COMPARE_THRESHOLD_INTEGER, List.of("x"),
                        Map.of("operator", "lt", "value", "7")),
                instruction("move_x", PrimitiveCatalog.Operation.SELECT_INTEGER, List.of("before_ore", "next_x", "x"), Map.of()),
                instruction("zero", PrimitiveCatalog.Operation.CONSTANT_INTEGER, List.of(), Map.of("value", "0")),
                instruction("destination", PrimitiveCatalog.Operation.MAKE_VECTOR, List.of("move_x", "y", "zero"), Map.of()),
                instruction("move", PrimitiveCatalog.Operation.MOVE, List.of("destination"), Map.of()),
                instruction("ore_resource", PrimitiveCatalog.Operation.SENSE_RESOURCE, List.of(), Map.of("key", "ore_resource")),
                instruction("harvest", PrimitiveCatalog.Operation.HARVEST, List.of("ore_resource"), Map.of())), 32);
    }

    private ProgramIR.Instruction instruction(String id, PrimitiveCatalog.Operation operation,
                                              List<String> inputs, Map<String, String> parameters) {
        return new ProgramIR.Instruction(id, operation, inputs, parameters);
    }

    private record RunResult(List<dev.stargenesis.api.sim.StepResult> stepResults, String stateHash,
                             long ore, boolean replayPassed) { }
}
