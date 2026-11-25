package dev.stargenesis.design;

import dev.stargenesis.api.sim.StepInput;

import java.util.List;

public record ProgramExecutionResult(List<StepInput.Command> commands, List<ProgramDebugEvent> trace,
                                     int instructionsExecuted, boolean complete, String status) {
    public ProgramExecutionResult {
        commands = List.copyOf(commands);
        trace = List.copyOf(trace);
        if (instructionsExecuted < 0 || status == null || status.length() > 512)
            throw new IllegalArgumentException("Invalid program execution result");
    }
}
