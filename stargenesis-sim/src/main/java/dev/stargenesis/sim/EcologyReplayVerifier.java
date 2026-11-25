package dev.stargenesis.sim;

import java.util.Objects;

/** Re-executes bounded ecology inputs and checks both state and event commitments. */
public final class EcologyReplayVerifier {
    private EcologyReplayVerifier() { }

    public static Verification verify(EcologyReplay replay) {
        Objects.requireNonNull(replay, "replay");
        if (!EcologyWorld.supportsEngine(replay.engineVersion()))
            return new Verification(false, -1, "UNSUPPORTED_ENGINE");
        long currentTick = 0;
        try (EcologyWorld world = new EcologyWorld(replay.worldId(), replay.seed(), replay.config(), replay.engineVersion())) {
            if (!world.stateHash().equals(replay.initialStateHash()))
                return new Verification(false, -1, "INITIAL_STATE_MISMATCH");
            for (EcologyReplay.Frame frame : replay.frames()) {
                currentTick = frame.input().tick();
                EcologyStepResult actual = world.step(frame.input());
                if (!actual.stateHash().equals(frame.stateHash()))
                    return new Verification(false, currentTick, "STATE_HASH_MISMATCH");
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
