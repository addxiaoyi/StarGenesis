package dev.stargenesis.sim;

import dev.stargenesis.api.sim.StepInput;

import java.util.List;
import java.util.Objects;

/** Durable inputs and state bounds for replaying one player's grid experiment. */
public record GridWorldReplayCheckpoint(GridWorldCheckpoint baseline, GridWorldCheckpoint current,
                                        List<StepInput> inputs) {
    public static final int MAX_REPLAY_COMMANDS = 4_096;

    public GridWorldReplayCheckpoint {
        Objects.requireNonNull(baseline, "baseline");
        Objects.requireNonNull(current, "current");
        inputs = List.copyOf(inputs);
        if (!baseline.settings().equals(current.settings()))
            throw new IllegalArgumentException("Replay checkpoint settings do not match");
        long expectedTick = baseline.snapshot().tick();
        int commandCount = 0;
        for (StepInput input : inputs) {
            Objects.requireNonNull(input, "input");
            if (input.tick() != expectedTick) throw new IllegalArgumentException("Replay inputs must use consecutive ticks");
            commandCount = Math.addExact(commandCount, input.commands().size());
            if (commandCount > MAX_REPLAY_COMMANDS) throw new IllegalArgumentException("Player replay command budget exceeded");
            expectedTick++;
        }
        if (expectedTick != current.snapshot().tick())
            throw new IllegalArgumentException("Replay checkpoint tick does not match its inputs");
        if (expectedTick > baseline.settings().budget().maxSteps())
            throw new IllegalArgumentException("Replay checkpoint exceeds the simulation step budget");
    }
}
