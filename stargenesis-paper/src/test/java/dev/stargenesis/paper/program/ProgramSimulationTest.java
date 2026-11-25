package dev.stargenesis.paper.program;

import dev.stargenesis.api.sim.SimulationBudget;
import dev.stargenesis.design.PrimitiveCatalog.Operation;
import dev.stargenesis.design.ProgramIR;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProgramSimulationTest {
    @Test void commandSimulationUsesHostDurationAndProducesAReplayWithTheSameConfig() {
        var budget = new SimulationBudget(8, 1, 0, 3, 1, 0);
        var wait = new ProgramIR(List.of(new ProgramIR.Instruction("wait", Operation.WAIT, List.of(), Map.of())), 256);
        var result = ProgramSimulation.run("program-duration", 42, wait, 20, budget);
        assertEquals(3, result.advanced());
        assertEquals(3, result.view().tick());
        assertTrue(result.view().terminal());
        assertEquals(budget, result.replay().config().budget());
        assertTrue(result.verification().passed());
        assertEquals(result.view().stateHash(), result.verification().actualHash());
    }

    @Test void commandSimulationDoesNotFallBackToDefaultMessageBudget() {
        var budget = new SimulationBudget(8, 1, 0, 20, 1, 0);
        var program = signalProgram();
        var result = ProgramSimulation.run("program-no-messages", 42, program, 20, budget);
        assertEquals(ProgramSandboxRunner.Status.MESSAGE_BUDGET_EXCEEDED, result.last().status());
        assertEquals(0, result.advanced());
        assertEquals(0, result.view().tick());
        assertTrue(result.last().signals().isEmpty());
        assertTrue(result.replay().frames().isEmpty());
    }

    @Test void eachSimulationStartsWithIsolatedSignalsAndSameInputReproducesItsEvidence() {
        var first = ProgramSimulation.run("program-repeat", 42, signalProgram(), 3, SimulationBudget.defaults());
        var second = ProgramSimulation.run("program-repeat", 42, signalProgram(), 3, SimulationBudget.defaults());
        assertEquals(first.replay(), second.replay());
        assertEquals(3L, first.last().signals().get("counter"));
        assertEquals(first.last().signals(), second.last().signals());
        assertTrue(first.verification().passed());
        assertTrue(second.verification().passed());
    }

    @Test void commandSimulationRejectsUnboundedStepsBeforeCreatingAWorld() {
        assertThrows(IllegalArgumentException.class, () -> ProgramSimulation.run("unused", 1, signalProgram(), 0, SimulationBudget.defaults()));
        assertThrows(IllegalArgumentException.class, () -> ProgramSimulation.run("unused", 1, signalProgram(), 21, SimulationBudget.defaults()));
    }

    private ProgramIR signalProgram() {
        return new ProgramIR(List.of(
                new ProgramIR.Instruction("previous", Operation.READ_SIGNAL, List.of(), Map.of("channel", "counter")),
                new ProgramIR.Instruction("one", Operation.CONSTANT_INTEGER, List.of(), Map.of("value", "1")),
                new ProgramIR.Instruction("value", Operation.ADD_INTEGER, List.of("previous", "one"), Map.of()),
                new ProgramIR.Instruction("signal", Operation.EMIT_SIGNAL, List.of("value"), Map.of("channel", "counter"))), 256);
    }
}
