package dev.stargenesis.sim;

import java.util.Objects;

/** Re-executes the full arena match and verifies actions, events and state commitments. */
public final class ArenaMatchReplayVerifier {
    private ArenaMatchReplayVerifier() { }

    public static Verification verify(ArenaMatchReplay replay) {
        Objects.requireNonNull(replay, "replay");
        if (!ArenaMatchWorld.ENGINE_VERSION.equals(replay.engineVersion())
                && !ArenaMatchWorld.LEGACY_ENGINE_VERSION.equals(replay.engineVersion()))
            return new Verification(false, -1, "UNSUPPORTED_ENGINE");
        long currentTick = 0;
        try (ArenaMatchWorld world = ArenaMatchWorld.initialWorld(replay)) {
            if (!world.stateHash().equals(replay.initialStateHash()))
                return new Verification(false, -1, "INITIAL_STATE_MISMATCH");
            for (ArenaMatchReplay.Frame frame : replay.frames()) {
                currentTick = frame.input().tick();
                ArenaMatchStepResult result = world.step(frame.input());
                if (!result.events().equals(frame.events()))
                    return new Verification(false, currentTick, "EVENTS_MISMATCH");
                if (!result.stateHash().equals(frame.stateHash()))
                    return new Verification(false, currentTick, "STATE_HASH_MISMATCH");
                if (!result.eventHash().equals(frame.eventHash()))
                    return new Verification(false, currentTick, "EVENT_HASH_MISMATCH");
            }
            return new Verification(true, -1, "MATCH");
        } catch (RuntimeException invalid) {
            return new Verification(false, currentTick, "INVALID_REPLAY:" + invalid.getMessage());
        }
    }

    public record Verification(boolean passed, long firstDivergentTick, String reason) { }
}
