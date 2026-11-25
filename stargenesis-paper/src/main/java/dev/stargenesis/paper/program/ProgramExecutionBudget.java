package dev.stargenesis.paper.program;

import dev.stargenesis.api.sim.SimulationBudget;
import dev.stargenesis.design.ProgramIR;

/** Execution limits do not rewrite a saved program's declared budget or content hash. */
final class ProgramExecutionBudget {
    private ProgramExecutionBudget() { }

    static ProgramIR bounded(ProgramIR program, SimulationBudget host) {
        int limit = Math.min(program.maxInstructionsPerStep(), host.nodesPerAgentStep());
        return limit == program.maxInstructionsPerStep() ? program : new ProgramIR(program.instructions(), limit);
    }
}
