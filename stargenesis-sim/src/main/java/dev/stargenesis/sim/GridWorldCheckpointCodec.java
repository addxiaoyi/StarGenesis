package dev.stargenesis.sim;

import dev.stargenesis.api.sim.AgentId;
import dev.stargenesis.api.sim.SimulationBudget;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Versioned, bounded binary encoding for player-owned grid checkpoints. */
public final class GridWorldCheckpointCodec {
    public static final int MAX_ENCODED_BYTES = 65_536;
    private static final int MAGIC = 0x53474743;
    private static final int FORMAT_VERSION = 1;

    private GridWorldCheckpointCodec() { }

    public static byte[] encode(GridWorldCheckpoint checkpoint) {
        if (checkpoint == null) throw new IllegalArgumentException("Checkpoint is required");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(MAGIC);
                output.writeInt(FORMAT_VERSION);
                writeSettings(output, checkpoint.settings());
                writeSnapshot(output, checkpoint.snapshot());
            }
            if (bytes.size() > MAX_ENCODED_BYTES) throw new IllegalArgumentException("Checkpoint exceeds encoded size limit");
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("Cannot encode grid checkpoint", impossible);
        }
    }

    public static GridWorldCheckpoint decode(byte[] encoded) {
        if (encoded == null || encoded.length < 8 || encoded.length > MAX_ENCODED_BYTES) {
            throw new IllegalArgumentException("Checkpoint size is outside the allowed range");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != MAGIC || input.readInt() != FORMAT_VERSION) {
                throw new IllegalArgumentException("Unsupported checkpoint format");
            }
            GridWorldSettings settings = readSettings(input);
            GridWorldSnapshot snapshot = readSnapshot(input, settings);
            if (input.available() != 0) throw new IllegalArgumentException("Trailing checkpoint data");
            return new GridWorldCheckpoint(settings, snapshot);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Malformed or truncated checkpoint", failure);
        }
    }

    private static void writeSettings(DataOutputStream output, GridWorldSettings settings) throws IOException {
        writeText(output, settings.worldId(), 64);
        output.writeLong(settings.seed());
        output.writeInt(settings.width());
        output.writeInt(settings.height());
        output.writeLong(settings.stepDurationMillis());
        SimulationBudget budget = settings.budget();
        output.writeInt(budget.nodesPerAgentStep());
        output.writeInt(budget.maxAgents());
        output.writeInt(budget.pathRequestsPerStep());
        output.writeLong(budget.maxSteps());
        output.writeInt(budget.sensorRadius());
        output.writeInt(budget.messagesPerStep());
        output.writeInt(settings.agents().size());
        for (GridAgentStart agent : settings.agents()) {
            writeUuid(output, agent.id().value());
            output.writeInt(agent.x());
            output.writeInt(agent.y());
        }
    }

    private static GridWorldSettings readSettings(DataInputStream input) throws IOException {
        String worldId = readText(input, 64);
        long seed = input.readLong();
        int width = input.readInt();
        int height = input.readInt();
        long stepDurationMillis = input.readLong();
        SimulationBudget budget = new SimulationBudget(input.readInt(), input.readInt(), input.readInt(),
                input.readLong(), input.readInt(), input.readInt());
        int agentCount = readCount(input, 1, budget.maxAgents(), "agent");
        List<GridAgentStart> agents = new ArrayList<>(agentCount);
        for (int i = 0; i < agentCount; i++) {
            agents.add(new GridAgentStart(new AgentId(readUuid(input)), input.readInt(), input.readInt()));
        }
        return new GridWorldSettings(worldId, seed, width, height, stepDurationMillis, budget, agents);
    }

    private static void writeSnapshot(DataOutputStream output, GridWorldSnapshot snapshot) throws IOException {
        writeText(output, snapshot.engineVersion(), 32);
        output.writeLong(snapshot.tick());
        output.writeLong(snapshot.sharedStock());
        writeText(output, snapshot.stateHash(), 64);
        output.writeInt(snapshot.cells().size());
        for (GridWorldSnapshot.Cell cell : snapshot.cells()) {
            output.writeBoolean(cell.blocked());
            output.writeLong(cell.resourceUnits());
        }
        output.writeInt(snapshot.agents().size());
        for (GridWorldSnapshot.Agent agent : snapshot.agents()) {
            writeUuid(output, agent.id().value());
            output.writeInt(agent.x());
            output.writeInt(agent.y());
            output.writeLong(agent.inventory());
        }
    }

    private static GridWorldSnapshot readSnapshot(DataInputStream input, GridWorldSettings settings) throws IOException {
        String engineVersion = readText(input, 32);
        long tick = input.readLong();
        long sharedStock = input.readLong();
        String stateHash = readText(input, 64);
        int expectedCells = settings.width() * settings.height();
        int cellCount = readCount(input, expectedCells, expectedCells, "cell");
        List<GridWorldSnapshot.Cell> cells = new ArrayList<>(cellCount);
        for (int i = 0; i < cellCount; i++) {
            cells.add(new GridWorldSnapshot.Cell(i % settings.width(), i / settings.width(),
                    input.readBoolean(), input.readLong()));
        }
        int agentCount = readCount(input, 1, settings.budget().maxAgents(), "snapshot agent");
        List<GridWorldSnapshot.Agent> agents = new ArrayList<>(agentCount);
        for (int i = 0; i < agentCount; i++) {
            agents.add(new GridWorldSnapshot.Agent(new AgentId(readUuid(input)), input.readInt(),
                    input.readInt(), input.readLong()));
        }
        return new GridWorldSnapshot(engineVersion, settings.worldId(), settings.seed(), settings.width(),
                settings.height(), tick, sharedStock, cells, agents, stateHash);
    }

    private static int readCount(DataInputStream input, int minimum, int maximum, String label) throws IOException {
        int count = input.readInt();
        if (count < minimum || count > maximum) throw new IllegalArgumentException("Invalid " + label + " count");
        return count;
    }

    private static void writeUuid(DataOutputStream output, UUID uuid) throws IOException {
        output.writeLong(uuid.getMostSignificantBits());
        output.writeLong(uuid.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }

    private static void writeText(DataOutputStream output, String value, int maxBytes) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) throw new IllegalArgumentException("Checkpoint text exceeds limit");
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readText(DataInputStream input, int maxBytes) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > maxBytes) throw new IllegalArgumentException("Invalid checkpoint text length");
        byte[] bytes = new byte[length];
        input.readFully(bytes);
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
    }
}
