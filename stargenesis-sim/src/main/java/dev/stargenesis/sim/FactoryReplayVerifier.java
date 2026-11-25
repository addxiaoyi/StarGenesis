package dev.stargenesis.sim;

import java.util.Objects;

/** Replays four-seat actions and verifies each state and event commitment. */
public final class FactoryReplayVerifier {
    private FactoryReplayVerifier() { }

    public static Verification verify(FactoryReplay replay) {
        Objects.requireNonNull(replay, "replay");
        if (!FactoryWorld.ENGINE_VERSION.equals(replay.engineVersion()))
            return new Verification(false, -1, "UNSUPPORTED_ENGINE");
        long currentTick = 0;
        try (FactoryWorld world = new FactoryWorld(replay.matchId(), replay.participants(), replay.seed(), replay.config())) {
            if (!world.stateHash().equals(replay.initialStateHash()))
                return new Verification(false, -1, "INITIAL_STATE_MISMATCH");
            for (FactoryReplay.Frame frame : replay.frames()) {
                currentTick = frame.input().tick();
                FactoryStepResult actual = world.step(frame.input());
                if (!actual.stateHash().equals(frame.stateHash()))
                    return new Verification(false, currentTick, "STATE_HASH_MISMATCH");
                if (!actual.events().equals(frame.events()))
                    return new Verification(false, currentTick, "EVENTS_MISMATCH");
                if (!actual.eventHash().equals(frame.eventHash()))
                    return new Verification(false, currentTick, "EVENT_HASH_MISMATCH");
            }
            return new Verification(true, -1, "MATCH");
        } catch (RuntimeException invalid) {
            return new Verification(false, currentTick, "INVALID_REPLAY:" + invalid.getMessage());
        }
    }

    public record Verification(boolean passed, long firstDivergentTick, String reason) { }
}
