package dev.stargenesis.sim;

import dev.stargenesis.api.sim.AgentId;
import dev.stargenesis.api.sim.EventRecord;
import dev.stargenesis.api.sim.EvidenceId;
import dev.stargenesis.api.sim.SimulationWorld;
import dev.stargenesis.api.sim.StepInput;
import dev.stargenesis.api.sim.StepResult;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/** Deterministic bounded grid engine. All mutable state belongs to its construction thread. */
public final class GridSimulationWorld implements SimulationWorld {
    public static final String ENGINE_VERSION = "grid/1";
    public static final String RESOURCE_ID = "material:star_shard";
    private static final long TERRAIN_SALT = 0x6a09e667f3bcc909L;
    private static final long RESOURCE_SALT = 0xbb67ae8584caa73bL;

    private final OwnerThread owner = new OwnerThread();
    private final GridWorldSettings settings;
    private final FixedSimulationClock clock;
    private final boolean[] blocked;
    private final long[] resources;
    private final long scenarioResourceTotal;
    private final TreeMap<AgentId, AgentState> agents = new TreeMap<>();
    private final List<GridReplayFrame> replayFrames = new ArrayList<>();
    private final GridWorldCheckpoint replayBaseline;
    private long sharedStock;
    private int replayCommandCount;
    private boolean closed;

    public GridSimulationWorld(GridWorldSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        clock = new FixedSimulationClock(settings.stepDurationMillis());
        blocked = new boolean[settings.width() * settings.height()];
        resources = new long[blocked.length];
        initializeConfiguredGrid();
        for (GridAgentStart start : settings.agents()) {
            agents.put(start.id(), new AgentState(start.x(), start.y(), 0));
        }
        scenarioResourceTotal = sumResources();
        replayBaseline = createCheckpoint();
    }

    private GridSimulationWorld(GridWorldCheckpoint checkpoint) {
        settings = checkpoint.settings();
        GridWorldSnapshot snapshot = checkpoint.snapshot();
        if (snapshot.tick() > settings.budget().maxSteps()) {
            throw new IllegalArgumentException("Checkpoint exceeds the configured step budget");
        }
        clock = new FixedSimulationClock(snapshot.tick(), settings.stepDurationMillis());
        blocked = new boolean[settings.width() * settings.height()];
        resources = new long[blocked.length];
        initializeConfiguredGrid();
        scenarioResourceTotal = sumResources();
        long[] initialResources = resources.clone();
        for (GridWorldSnapshot.Cell cell : snapshot.cells()) {
            int index = index(cell.x(), cell.y());
            if (cell.blocked() != blocked[index]) {
                throw new IllegalArgumentException("Checkpoint changes generated terrain");
            }
            if (cell.resourceUnits() > initialResources[index]) {
                throw new IllegalArgumentException("Checkpoint adds generated resources");
            }
            if (cell.blocked() && cell.resourceUnits() != 0) {
                throw new IllegalArgumentException("Blocked checkpoint cell contains resources");
            }
            blocked[index] = cell.blocked();
            resources[index] = cell.resourceUnits();
        }
        if (snapshot.agents().size() != settings.agents().size()) {
            throw new IllegalArgumentException("Checkpoint agent set does not match settings");
        }
        List<AgentId> configuredAgentIds = settings.agents().stream().map(GridAgentStart::id).sorted().toList();
        for (int i = 0; i < snapshot.agents().size(); i++) {
            GridWorldSnapshot.Agent agent = snapshot.agents().get(i);
            if (!agent.id().equals(configuredAgentIds.get(i))) {
                throw new IllegalArgumentException("Checkpoint agent set does not match settings");
            }
            agents.put(agent.id(), new AgentState(agent.x(), agent.y(), agent.inventory()));
        }
        sharedStock = snapshot.sharedStock();
        if (sumResources() != scenarioResourceTotal) {
            throw new IllegalArgumentException("Checkpoint does not conserve scenario resources");
        }
        if (!snapshot.stateHash().equals(stateHashInternal())) {
            throw new IllegalArgumentException("Checkpoint state hash mismatch");
        }
        replayBaseline = createCheckpoint();
    }

    public static GridSimulationWorld restore(GridWorldCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        if (!ENGINE_VERSION.equals(checkpoint.snapshot().engineVersion())) {
            throw new IllegalArgumentException("Unsupported checkpoint engine version");
        }
        return new GridSimulationWorld(checkpoint);
    }

    /** Replay every frame and reject the first output or state-hash mismatch. */
    public static GridSimulationWorld replay(GridWorldReplay replay) {
        Objects.requireNonNull(replay, "replay");
        GridSimulationWorld world = restore(replay.baseline());
        for (GridReplayFrame frame : replay.frames()) {
            StepResult actual = world.step(frame.input());
            if (!actual.equals(frame.result())) {
                world.close();
                throw new IllegalArgumentException("Replay mismatch at tick " + frame.input().tick());
            }
        }
        return world;
    }

    @Override public String worldId() { requireOpen(); return settings.worldId(); }
    @Override public long seed() { requireOpen(); return settings.seed(); }
    @Override public long tick() { requireOpen(); return clock.tick(); }

    @Override public StepResult step(StepInput input) {
        requireOpen();
        Objects.requireNonNull(input, "input");
        long currentTick = clock.tick();
        if (input.tick() != currentTick) throw new IllegalArgumentException("Input tick does not match world tick");
        if (currentTick >= settings.budget().maxSteps()) throw new IllegalStateException("World step budget exhausted");
        if (replayCommandCount + input.commands().size() > GridWorldReplay.MAX_REPLAY_COMMANDS) {
            throw new IllegalStateException("Replay command budget exhausted");
        }

        List<EventRecord> events = new ArrayList<>(input.commands().size());
        for (int i = 0; i < input.commands().size(); i++) {
            StepInput.Command command = input.commands().get(i);
            ActionOutcome outcome = apply(command);
            AgentState agent = agents.get(command.agent());
            String x = agent == null ? "-1" : Integer.toString(agent.x);
            String y = agent == null ? "-1" : Integer.toString(agent.y);
            Map<String, String> details = Map.of(
                    "agent", command.agent().value().toString(),
                    "action", command.action(),
                    "result", outcome.applied() ? "applied" : "rejected",
                    "reason", outcome.reason(),
                    "x", x,
                    "y", y);
            events.add(new EventRecord(new EvidenceId(settings.worldId(), currentTick, i),
                    outcome.applied() ? "action.applied" : "action.rejected", details));
        }

        long resultingTick = clock.advance();
        boolean terminal = resultingTick >= settings.budget().maxSteps();
        StepResult result = new StepResult(resultingTick, events, stateHashInternal(), terminal,
                terminal ? "MAX_STEPS_REACHED" : "");
        replayFrames.add(new GridReplayFrame(input, result));
        replayCommandCount += input.commands().size();
        return result;
    }

    @Override public String stateHash() { requireOpen(); return stateHashInternal(); }

    public GridWorldSnapshot snapshot() {
        requireOpen();
        return snapshotInternal(stateHashInternal());
    }

    public GridWorldCheckpoint checkpoint() {
        requireOpen();
        return createCheckpoint();
    }

    public GridWorldReplay replayLog() {
        requireOpen();
        return new GridWorldReplay(replayBaseline, replayFrames);
    }

    @Override public void close() {
        owner.check();
        closed = true;
    }

    private ActionOutcome apply(StepInput.Command command) {
        AgentState actor = agents.get(command.agent());
        if (actor == null) return ActionOutcome.rejected("unknown_agent");
        return switch (command.action()) {
            case "move" -> move(actor, command.arguments());
            case "gather" -> gather(actor, command.arguments());
            case "deposit" -> deposit(actor, command.arguments());
            case "wait" -> command.arguments().isEmpty() ? ActionOutcome.ok() : ActionOutcome.rejected("invalid_arguments");
            default -> ActionOutcome.rejected("unknown_action");
        };
    }

    private ActionOutcome move(AgentState actor, Map<String, Long> arguments) {
        if (!arguments.keySet().equals(java.util.Set.of("dx", "dy"))) return ActionOutcome.rejected("invalid_arguments");
        long dx = arguments.get("dx");
        long dy = arguments.get("dy");
        boolean cardinal = (dx == -1 || dx == 1) && dy == 0 || (dy == -1 || dy == 1) && dx == 0;
        if (!cardinal) return ActionOutcome.rejected("invalid_direction");
        long targetX = actor.x + dx;
        long targetY = actor.y + dy;
        if (targetX < 0 || targetX >= settings.width() || targetY < 0 || targetY >= settings.height()) {
            return ActionOutcome.rejected("out_of_bounds");
        }
        int x = (int) targetX;
        int y = (int) targetY;
        if (blocked[index(x, y)]) return ActionOutcome.rejected("blocked");
        if (agents.values().stream().anyMatch(other -> other != actor && other.x == x && other.y == y)) {
            return ActionOutcome.rejected("occupied");
        }
        actor.x = x;
        actor.y = y;
        return ActionOutcome.ok();
    }

    private ActionOutcome gather(AgentState actor, Map<String, Long> arguments) {
        if (!arguments.isEmpty()) return ActionOutcome.rejected("invalid_arguments");
        int index = index(actor.x, actor.y);
        if (resources[index] == 0) return ActionOutcome.rejected("no_resource");
        if (actor.inventory == Long.MAX_VALUE) return ActionOutcome.rejected("inventory_full");
        resources[index]--;
        actor.inventory++;
        return ActionOutcome.ok();
    }

    private ActionOutcome deposit(AgentState actor, Map<String, Long> arguments) {
        if (!arguments.keySet().equals(java.util.Set.of("amount"))) return ActionOutcome.rejected("invalid_arguments");
        long amount = arguments.get("amount");
        if (amount <= 0) return ActionOutcome.rejected("invalid_amount");
        if (actor.inventory < amount) return ActionOutcome.rejected("insufficient_inventory");
        long nextStock;
        try {
            nextStock = Math.addExact(sharedStock, amount);
        } catch (ArithmeticException overflow) {
            return ActionOutcome.rejected("stock_full");
        }
        actor.inventory -= amount;
        sharedStock = nextStock;
        return ActionOutcome.ok();
    }

    private void generateInitialGrid() {
        for (int i = 0; i < blocked.length; i++) {
            long terrain = mix64(settings.seed() ^ TERRAIN_SALT ^ (i * 0x9e3779b97f4a7c15L));
            blocked[i] = Math.floorMod(terrain, 29) == 0;
            long resource = mix64(settings.seed() ^ RESOURCE_SALT ^ (i * 0xd1b54a32d192ed03L));
            resources[i] = Math.floorMod(resource, 13) == 0 ? 1 + Math.floorMod(resource >>> 8, 3) : 0;
            if (blocked[i]) resources[i] = 0;
        }
    }

    private void initializeConfiguredGrid() {
        generateInitialGrid();
        for (GridAgentStart start : settings.agents()) {
            int index = index(start.x(), start.y());
            blocked[index] = false;
            resources[index] = Math.max(resources[index], 1);
        }
    }

    private long sumResources() {
        try {
            long total = 0;
            for (long amount : resources) total = Math.addExact(total, amount);
            for (AgentState agent : agents.values()) total = Math.addExact(total, agent.inventory);
            return Math.addExact(total, sharedStock);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Scenario resource total overflow", overflow);
        }
    }

    private int index(int x, int y) { return y * settings.width() + x; }

    private GridWorldCheckpoint createCheckpoint() {
        GridWorldSnapshot snapshot = snapshotInternal(stateHashInternal());
        return new GridWorldCheckpoint(settings, snapshot);
    }

    private GridWorldSnapshot snapshotInternal(String stateHash) {
        List<GridWorldSnapshot.Cell> cellViews = new ArrayList<>(blocked.length);
        for (int i = 0; i < blocked.length; i++) {
            cellViews.add(new GridWorldSnapshot.Cell(i % settings.width(), i / settings.width(), blocked[i], resources[i]));
        }
        List<GridWorldSnapshot.Agent> agentViews = agents.entrySet().stream()
                .map(entry -> new GridWorldSnapshot.Agent(entry.getKey(), entry.getValue().x,
                        entry.getValue().y, entry.getValue().inventory))
                .toList();
        return new GridWorldSnapshot(ENGINE_VERSION, settings.worldId(), settings.seed(), settings.width(),
                settings.height(), clock.tick(), sharedStock, cellViews, agentViews, stateHash);
    }

    private String stateHashInternal() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                writeText(output, ENGINE_VERSION);
                writeText(output, settings.worldId());
                output.writeLong(settings.seed());
                output.writeInt(settings.width());
                output.writeInt(settings.height());
                output.writeLong(settings.stepDurationMillis());
                output.writeInt(settings.budget().nodesPerAgentStep());
                output.writeInt(settings.budget().maxAgents());
                output.writeInt(settings.budget().pathRequestsPerStep());
                output.writeLong(settings.budget().maxSteps());
                output.writeInt(settings.budget().sensorRadius());
                output.writeInt(settings.budget().messagesPerStep());
                List<GridAgentStart> starts = settings.agents().stream()
                        .sorted(java.util.Comparator.comparing(GridAgentStart::id)).toList();
                output.writeInt(starts.size());
                for (GridAgentStart start : starts) {
                    UUID id = start.id().value();
                    output.writeLong(id.getMostSignificantBits());
                    output.writeLong(id.getLeastSignificantBits());
                    output.writeInt(start.x());
                    output.writeInt(start.y());
                }
                output.writeLong(clock.tick());
                output.writeLong(sharedStock);
                for (int i = 0; i < blocked.length; i++) {
                    output.writeBoolean(blocked[i]);
                    output.writeLong(resources[i]);
                }
                output.writeInt(agents.size());
                for (Map.Entry<AgentId, AgentState> entry : agents.entrySet()) {
                    UUID id = entry.getKey().value();
                    output.writeLong(id.getMostSignificantBits());
                    output.writeLong(id.getLeastSignificantBits());
                    output.writeInt(entry.getValue().x);
                    output.writeInt(entry.getValue().y);
                    output.writeLong(entry.getValue().inventory);
                }
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException impossible) {
            throw new IllegalStateException("Cannot encode grid state", impossible);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void writeText(DataOutputStream output, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(encoded.length);
        output.write(encoded);
    }

    private static long mix64(long value) {
        long mixed = value + 0x9e3779b97f4a7c15L;
        mixed = (mixed ^ (mixed >>> 30)) * 0xbf58476d1ce4e5b9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94d049bb133111ebL;
        return mixed ^ (mixed >>> 31);
    }

    private void requireOpen() {
        owner.check();
        if (closed) throw new IllegalStateException("Simulation world is closed");
    }

    private record ActionOutcome(boolean applied, String reason) {
        static ActionOutcome ok() { return new ActionOutcome(true, ""); }
        static ActionOutcome rejected(String reason) { return new ActionOutcome(false, reason); }
    }

    private static final class AgentState {
        private int x;
        private int y;
        private long inventory;

        private AgentState(int x, int y, long inventory) {
            this.x = x;
            this.y = y;
            this.inventory = inventory;
        }
    }
}
