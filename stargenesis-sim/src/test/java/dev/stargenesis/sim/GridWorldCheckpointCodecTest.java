package dev.stargenesis.sim;

import dev.stargenesis.api.sim.AgentId;
import dev.stargenesis.api.sim.SimulationBudget;
import dev.stargenesis.api.sim.StepInput;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class GridWorldCheckpointCodecTest {
    private static GridWorldSettings settings() {
        return new GridWorldSettings("codec-world", 71, 16, 8, 100, SimulationBudget.defaults(),
                List.of(new GridAgentStart(new AgentId(new UUID(1, 2)), 8, 4)));
    }

    @Test
    void checkpointRoundTripPreservesStateAndCanResume() {
        try (var world = new GridSimulationWorld(settings())) {
            world.step(new StepInput(0, List.of(
                    new StepInput.Command(new AgentId(new UUID(1, 2)), 0, "gather", Map.of()))));
            GridWorldCheckpoint checkpoint = world.checkpoint();
            byte[] encoded = GridWorldCheckpointCodec.encode(checkpoint);
            GridWorldCheckpoint decoded = GridWorldCheckpointCodec.decode(encoded);
            assertEquals(checkpoint, decoded);
            try (var restored = GridSimulationWorld.restore(decoded)) {
                assertEquals(world.snapshot(), restored.snapshot());
            }
        }
    }

    @Test
    void malformedVersionsTrailingBytesAndOversizedPayloadsAreRejected() {
        byte[] valid;
        try (var world = new GridSimulationWorld(settings())) {
            valid = GridWorldCheckpointCodec.encode(world.checkpoint());
        }
        byte[] badVersion = valid.clone();
        badVersion[7] = 2;
        assertThrows(IllegalArgumentException.class, () -> GridWorldCheckpointCodec.decode(badVersion));

        byte[] trailing = java.util.Arrays.copyOf(valid, valid.length + 1);
        assertThrows(IllegalArgumentException.class, () -> GridWorldCheckpointCodec.decode(trailing));
        assertThrows(IllegalArgumentException.class,
                () -> GridWorldCheckpointCodec.decode(new byte[GridWorldCheckpointCodec.MAX_ENCODED_BYTES + 1]));
        assertThrows(IllegalArgumentException.class,
                () -> GridWorldCheckpointCodec.decode(java.util.Arrays.copyOf(valid, valid.length - 1)));
    }

    @Test
    void replayCheckpointRoundTripsAndReexecutesEveryInput() {
        GridWorldCheckpoint baseline;
        GridWorldCheckpoint current;
        List<StepInput> inputs = new java.util.ArrayList<>();
        try (GridSimulationWorld world = new GridSimulationWorld(settings())) {
            baseline = world.checkpoint();
            StepInput gather = new StepInput(0, List.of(new StepInput.Command(new AgentId(new UUID(1, 2)), 0,
                    "gather", Map.of())));
            world.step(gather);
            inputs.add(gather);
            StepInput deposit = new StepInput(1, List.of(new StepInput.Command(new AgentId(new UUID(1, 2)), 0,
                    "deposit", Map.of("amount", 1L))));
            world.step(deposit);
            inputs.add(deposit);
            current = world.checkpoint();
        }
        GridWorldReplayCheckpoint checkpoint = new GridWorldReplayCheckpoint(baseline, current, inputs);
        byte[] encoded = GridWorldReplayCheckpointCodec.encode(checkpoint);
        assertEquals(checkpoint, GridWorldReplayCheckpointCodec.decode(encoded));
    }

    @Test
    void replayCheckpointRejectsForgedCurrentStateAndCorruptEncoding() {
        GridWorldCheckpoint baseline;
        GridWorldCheckpoint current;
        try (GridSimulationWorld world = new GridSimulationWorld(settings())) {
            baseline = world.checkpoint();
            world.step(new StepInput(0, List.of(new StepInput.Command(new AgentId(new UUID(1, 2)), 0,
                    "wait", Map.of()))));
            current = world.checkpoint();
        }
        GridWorldCheckpoint forged = new GridWorldCheckpoint(baseline.settings(), baseline.snapshot());
        byte[] encoded = GridWorldReplayCheckpointCodec.encode(new GridWorldReplayCheckpoint(baseline, current,
                List.of(new StepInput(0, List.of(new StepInput.Command(new AgentId(new UUID(1, 2)), 0, "wait", Map.of()))))));
        assertThrows(IllegalArgumentException.class,
                () -> new GridWorldReplayCheckpoint(baseline, forged, List.of(new StepInput(0, List.of(
                        new StepInput.Command(new AgentId(new UUID(1, 2)), 0, "wait", Map.of()))))));
        encoded[encoded.length - 1] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> GridWorldReplayCheckpointCodec.decode(encoded));
    }

    @Test
    void replayCheckpointRejectsTooManyFramesAndCommands() {
        try (GridSimulationWorld world = new GridSimulationWorld(settings())) {
            GridWorldCheckpoint checkpoint = world.checkpoint();
            assertThrows(IllegalArgumentException.class, () -> new GridWorldReplayCheckpoint(checkpoint, checkpoint,
                    java.util.Collections.nCopies(GridWorldReplayCheckpoint.MAX_REPLAY_COMMANDS + 1,
                            new StepInput(0, List.of()))));
            assertThrows(IllegalArgumentException.class, () -> GridWorldReplayCheckpointCodec.decode(new byte[] {0, 1}));
        }
    }
}
