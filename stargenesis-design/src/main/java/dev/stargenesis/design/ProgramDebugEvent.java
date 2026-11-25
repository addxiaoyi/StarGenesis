package dev.stargenesis.design;

import dev.stargenesis.api.sim.StepInput;

import java.util.List;

public record ProgramDebugEvent(int instructionIndex, String nodeId, String operation, String output,
                                List<StepInput.Command> actions, String status) {
    public ProgramDebugEvent {
        actions = List.copyOf(actions);
    }
}
