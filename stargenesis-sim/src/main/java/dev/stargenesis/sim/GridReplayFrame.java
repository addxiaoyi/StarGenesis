package dev.stargenesis.sim;

import dev.stargenesis.api.sim.StepInput;
import dev.stargenesis.api.sim.StepResult;

import java.util.Objects;

public record GridReplayFrame(StepInput input, StepResult result) {
    public GridReplayFrame {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(result, "result");
    }
}
