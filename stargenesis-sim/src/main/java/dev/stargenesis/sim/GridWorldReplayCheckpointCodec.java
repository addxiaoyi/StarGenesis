package dev.stargenesis.sim;

import dev.stargenesis.api.sim.AgentId;
import dev.stargenesis.api.sim.StepInput;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Versioned player replay persistence verified by deterministic re-execution on load. */
public final class GridWorldReplayCheckpointCodec {
    public static final int MAX_ENCODED_BYTES = 1_048_576;
    private static final int MAGIC = 0x53475250;
    private static final int FORMAT_VERSION = 1;

    private GridWorldReplayCheckpointCodec() { }

    public static byte[] encode(GridWorldReplayCheckpoint checkpoint) {
        if (checkpoint == null) throw new IllegalArgumentException("Replay checkpoint is required");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(MAGIC);
                output.writeInt(FORMAT_VERSION);
                writeCheckpoint(output, checkpoint.baseline());
                writeCheckpoint(output, checkpoint.current());
                output.writeInt(checkpoint.inputs().size());
                for (StepInput input : checkpoint.inputs()) writeInput(output, input);
            }
            if (bytes.size() > MAX_ENCODED_BYTES)
                throw new IllegalArgumentException("Player replay checkpoint exceeds encoded size limit");
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("Cannot encode player replay checkpoint", impossible);
        }
    }

    public static GridWorldReplayCheckpoint decode(byte[] encoded) {
        if (encoded == null || encoded.length < 16 || encoded.length > MAX_ENCODED_BYTES)
            throw new IllegalArgumentException("Player replay checkpoint size is outside the allowed range");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != MAGIC || input.readInt() != FORMAT_VERSION)
                throw new IllegalArgumentException("Unsupported player replay checkpoint format");
            GridWorldCheckpoint baseline = readCheckpoint(input);
            GridWorldCheckpoint current = readCheckpoint(input);
            int frameCount = readCount(input, 0, 100_000, "replay frame");
            List<StepInput> inputs = new ArrayList<>(frameCount);
            for (int index = 0; index < frameCount; index++) inputs.add(readInput(input));
            if (input.available() != 0) throw new IllegalArgumentException("Trailing replay checkpoint data");
            GridWorldReplayCheckpoint checkpoint = new GridWorldReplayCheckpoint(baseline, current, inputs);
            verifyReplay(checkpoint);
            return checkpoint;
        } catch (IOException failure) {
            throw new IllegalArgumentException("Malformed or truncated player replay checkpoint", failure);
        }
    }

    public static boolean hasReplayFormat(byte[] encoded) {
        return encoded != null && encoded.length >= Integer.BYTES && ByteBuffer.wrap(encoded).getInt() == MAGIC;
    }

    private static void verifyReplay(GridWorldReplayCheckpoint checkpoint) {
        try (GridSimulationWorld world = GridSimulationWorld.restore(checkpoint.baseline())) {
            for (StepInput input : checkpoint.inputs()) world.step(input);
            if (!world.snapshot().equals(checkpoint.current().snapshot()))
                throw new IllegalArgumentException("Replay output does not match the stored current checkpoint");
        }
    }

    private static void writeCheckpoint(DataOutputStream output, GridWorldCheckpoint checkpoint) throws IOException {
        byte[] encoded = GridWorldCheckpointCodec.encode(checkpoint);
        output.writeInt(encoded.length);
        output.write(encoded);
    }

    private static GridWorldCheckpoint readCheckpoint(DataInputStream input) throws IOException {
        int length = readCount(input, 8, GridWorldCheckpointCodec.MAX_ENCODED_BYTES, "checkpoint byte");
        if (length > input.available()) throw new IllegalArgumentException("Truncated replay checkpoint state");
        return GridWorldCheckpointCodec.decode(input.readNBytes(length));
    }

    private static void writeInput(DataOutputStream output, StepInput input) throws IOException {
        output.writeLong(input.tick());
        output.writeInt(input.commands().size());
        for (StepInput.Command command : input.commands()) {
            output.writeLong(command.agent().value().getMostSignificantBits());
            output.writeLong(command.agent().value().getLeastSignificantBits());
            output.writeLong(command.sequence());
            writeText(output, command.action(), 64);
            output.writeInt(command.arguments().size());
            for (Map.Entry<String, Long> argument : new TreeMap<>(command.arguments()).entrySet()) {
                writeText(output, argument.getKey(), 32);
                output.writeLong(argument.getValue());
            }
        }
    }

    private static StepInput readInput(DataInputStream input) throws IOException {
        long tick = input.readLong();
        int commandCount = readCount(input, 0, 1024, "replay command");
        List<StepInput.Command> commands = new ArrayList<>(commandCount);
        for (int index = 0; index < commandCount; index++) {
            AgentId actor = new AgentId(new UUID(input.readLong(), input.readLong()));
            long sequence = input.readLong();
            String action = readText(input, 64);
            int argumentCount = readCount(input, 0, 16, "replay argument");
            Map<String, Long> arguments = new HashMap<>();
            for (int argument = 0; argument < argumentCount; argument++) {
                String key = readText(input, 32);
                if (arguments.putIfAbsent(key, input.readLong()) != null)
                    throw new IllegalArgumentException("Duplicate replay command argument");
            }
            commands.add(new StepInput.Command(actor, sequence, action, arguments));
        }
        return new StepInput(tick, commands);
    }

    private static int readCount(DataInputStream input, int minimum, int maximum, String label) throws IOException {
        int count = input.readInt();
        if (count < minimum || count > maximum) throw new IllegalArgumentException("Invalid " + label + " count");
        return count;
    }

    private static void writeText(DataOutputStream output, String value, int maxBytes) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > maxBytes) throw new IllegalArgumentException("Replay command text exceeds limit");
        output.writeInt(encoded.length);
        output.write(encoded);
    }

    private static String readText(DataInputStream input, int maxBytes) throws IOException {
        int length = readCount(input, 0, maxBytes, "replay text byte");
        if (length > input.available()) throw new IllegalArgumentException("Truncated replay command text");
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(input.readNBytes(length))).toString();
    }
}
