package dev.stargenesis.sim;

import java.util.List;
import java.util.Objects;

public record GridWorldReplay(GridWorldCheckpoint baseline, List<GridReplayFrame> frames) {
    public static final int MAX_REPLAY_COMMANDS = 65_536;

    public GridWorldReplay {
        Objects.requireNonNull(baseline, "baseline");
        frames = List.copyOf(frames);
        long expectedTick = baseline.snapshot().tick();
        int commandCount = 0;
        for (GridReplayFrame frame : frames) {
            Objects.requireNonNull(frame, "frame");
            if (frame.input().tick() != expectedTick || frame.result().tick() != expectedTick + 1) {
                throw new IllegalArgumentException("Replay frames must use consecutive logical ticks");
            }
            if (frame.result().events().size() != frame.input().commands().size()) {
                throw new IllegalArgumentException("Replay result does not cover every input command");
            }
            commandCount = Math.addExact(commandCount, frame.input().commands().size());
            if (commandCount > MAX_REPLAY_COMMANDS) throw new IllegalArgumentException("Replay command budget exceeded");
            expectedTick++;
        }
        if (expectedTick > baseline.settings().budget().maxSteps()) {
            throw new IllegalArgumentException("Replay exceeds the configured step budget");
        }
    }
}
