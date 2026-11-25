package dev.stargenesis.paper;

import dev.stargenesis.api.sim.AgentId;
import dev.stargenesis.api.sim.SimulationBudget;
import dev.stargenesis.api.sim.StepInput;
import dev.stargenesis.api.sim.StepResult;
import dev.stargenesis.api.design.DesignDocument;
import dev.stargenesis.api.design.DesignPermissions;
import dev.stargenesis.design.Program;
import dev.stargenesis.design.ProgramCompiler;
import dev.stargenesis.design.ProgramExecutionResult;
import dev.stargenesis.design.ProgramExecutionSession;
import dev.stargenesis.design.ProgramWorldView;
import dev.stargenesis.design.DesignCodec;
import dev.stargenesis.design.TypedProgram;
import dev.stargenesis.sim.GridAgentStart;
import dev.stargenesis.sim.GridSimulationWorld;
import dev.stargenesis.sim.GridWorldCheckpoint;
import dev.stargenesis.sim.GridWorldCheckpointCodec;
import dev.stargenesis.sim.GridWorldReplayCheckpoint;
import dev.stargenesis.sim.GridWorldReplayCheckpointCodec;
import dev.stargenesis.sim.GridWorldSettings;
import dev.stargenesis.sim.GridWorldSnapshot;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.Objects;
import java.util.UUID;

final class LaboratorySession {
    private static final int WIDTH = 16;
    private static final int HEIGHT = 8;
    private static final int SPAWN_X = 8;
    private static final int SPAWN_Y = 4;

    private final UUID ownerId;
    private final GridWorldCheckpoint baseline;
    private GridWorldCheckpoint checkpoint;
    private List<StepInput> replayInputs;

    private LaboratorySession(UUID ownerId, GridWorldCheckpoint baseline, GridWorldCheckpoint checkpoint,
                              List<StepInput> replayInputs) {
        this.ownerId = ownerId;
        this.baseline = baseline;
        this.checkpoint = checkpoint;
        this.replayInputs = List.copyOf(replayInputs);
    }

    static LaboratorySession start(UUID ownerId, SimulationBudget budget, long stepDurationMillis) {
        Objects.requireNonNull(ownerId, "ownerId");
        GridWorldSettings settings = expectedSettings(ownerId, budget, stepDurationMillis);
        try (GridSimulationWorld world = new GridSimulationWorld(settings)) {
            GridWorldCheckpoint checkpoint = world.checkpoint();
            return new LaboratorySession(ownerId, checkpoint, checkpoint, List.of());
        }
    }

    static LaboratorySession resume(UUID ownerId, byte[] encoded, SimulationBudget budget, long stepDurationMillis) {
        Objects.requireNonNull(ownerId, "ownerId");
        GridWorldCheckpoint baseline;
        GridWorldCheckpoint checkpoint;
        List<StepInput> replayInputs;
        if (GridWorldReplayCheckpointCodec.hasReplayFormat(encoded)) {
            GridWorldReplayCheckpoint replay = GridWorldReplayCheckpointCodec.decode(encoded);
            baseline = replay.baseline();
            checkpoint = replay.current();
            replayInputs = replay.inputs();
        } else {
            checkpoint = GridWorldCheckpointCodec.decode(encoded);
            baseline = checkpoint;
            replayInputs = List.of();
        }
        if (!checkpoint.settings().equals(expectedSettings(ownerId, budget, stepDurationMillis))) {
            throw new IllegalArgumentException("Laboratory checkpoint belongs to another player or configuration");
        }
        try (GridSimulationWorld world = GridSimulationWorld.restore(baseline)) {
            for (StepInput replayInput : replayInputs) world.step(replayInput);
            if (!world.snapshot().equals(checkpoint.snapshot()))
                throw new IllegalArgumentException("Laboratory replay does not match its current checkpoint");
            return new LaboratorySession(ownerId, baseline, checkpoint, replayInputs);
        } catch (RuntimeException failure) {
            throw new IllegalArgumentException("Laboratory checkpoint state is invalid", failure);
        }
    }

    GridWorldSnapshot snapshot() {
        try (GridSimulationWorld world = GridSimulationWorld.restore(checkpoint)) {
            return world.snapshot();
        }
    }

    StepResult act(String action, Map<String, Long> arguments) {
        try (GridSimulationWorld world = GridSimulationWorld.restore(checkpoint)) {
            StepInput.Command command = new StepInput.Command(new AgentId(ownerId), 0, action, arguments);
            StepInput input = new StepInput(world.tick(), List.of(command));
            StepResult result = world.step(input);
            commit(world, input);
            return result;
        }
    }

    ProgramRun run(Program program) {
        Objects.requireNonNull(program, "program");
        DesignPermissions permissions = program.metadata().permissions();
        if (!program.metadata().author().equals(ownerId)
                && !permissions.publicRead() && !permissions.editors().contains(ownerId))
            throw new SecurityException("Player is not authorized to run this program");
        DesignCodec codec = new DesignCodec();
        DesignDocument verified = codec.load(codec.write(program));
        if (!(verified instanceof Program executable)) throw new IllegalArgumentException("Design is not a program");
        try (GridSimulationWorld world = GridSimulationWorld.restore(checkpoint)) {
            if (world.tick() >= checkpoint.settings().budget().maxSteps())
                throw new IllegalStateException("Laboratory step budget exhausted; reset the experiment to continue");
            TypedProgram compiled = new ProgramCompiler().compile(executable);
            if (compiled.maxInstructionsPerStep() > checkpoint.settings().budget().nodesPerAgentStep())
                throw new IllegalArgumentException("Program instruction budget exceeds the player's simulation budget");
            GridWorldSnapshot snapshot = world.snapshot();
            ProgramExecutionSession execution = new ProgramExecutionSession(compiled, view(snapshot), new AgentId(ownerId));
            execution.continueUntilPause();
            ProgramExecutionResult result = execution.result();
            if (!result.complete() || !result.status().equals("COMPLETED"))
                return new ProgramRun(result, null, encodeCheckpoint());
            StepInput input = new StepInput(world.tick(), result.commands());
            StepResult step = world.step(input);
            LaboratorySession staged = stage(world, input);
            byte[] encoded = staged.encodeCheckpoint();
            checkpoint = staged.checkpoint;
            replayInputs = staged.replayInputs;
            return new ProgramRun(result, step, encoded);
        }
    }

    private static ProgramWorldView view(GridWorldSnapshot snapshot) {
        List<ProgramWorldView.Cell> cells = snapshot.cells().stream()
                .map(cell -> new ProgramWorldView.Cell(cell.x(), cell.y(), cell.blocked(), cell.resourceUnits())).toList();
        Map<AgentId, ProgramWorldView.Agent> agents = new HashMap<>();
        snapshot.agents().forEach(agent -> agents.put(agent.id(), new ProgramWorldView.Agent(agent.id(), agent.x(),
                agent.y(), agent.inventory())));
        return new ProgramWorldView(snapshot.width(), snapshot.height(), snapshot.tick(), snapshot.sharedStock(), cells, agents);
    }

    byte[] encodeCheckpoint() {
        return GridWorldReplayCheckpointCodec.encode(new GridWorldReplayCheckpoint(baseline, checkpoint, replayInputs));
    }

    private LaboratorySession stage(GridSimulationWorld world, StepInput input) {
        List<StepInput> nextReplay = new ArrayList<>(replayInputs);
        nextReplay.add(input);
        GridWorldCheckpoint nextCheckpoint = world.checkpoint();
        return new LaboratorySession(ownerId, baseline, nextCheckpoint, nextReplay);
    }

    private void commit(GridSimulationWorld world, StepInput input) {
        LaboratorySession staged = stage(world, input);
        GridWorldReplayCheckpointCodec.encode(new GridWorldReplayCheckpoint(baseline, staged.checkpoint, staged.replayInputs));
        replayInputs = staged.replayInputs;
        checkpoint = staged.checkpoint;
    }

    private static GridWorldSettings expectedSettings(UUID ownerId, SimulationBudget budget, long stepDurationMillis) {
        String worldId = "lab-" + ownerId.toString().replace("-", "");
        long seed = ownerId.getMostSignificantBits() ^ ownerId.getLeastSignificantBits();
        return new GridWorldSettings(worldId, seed, WIDTH, HEIGHT, stepDurationMillis, budget,
                List.of(new GridAgentStart(new AgentId(ownerId), SPAWN_X, SPAWN_Y)));
    }

    record ProgramRun(ProgramExecutionResult execution, StepResult step, byte[] checkpoint) {
        ProgramRun { checkpoint = checkpoint.clone(); }
        @Override public byte[] checkpoint() { return checkpoint.clone(); }
    }
}
