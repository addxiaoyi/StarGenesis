package dev.stargenesis.paper.lab;

import dev.stargenesis.api.sim.SimulationBudget;
import dev.stargenesis.api.sim.StepInput;
import dev.stargenesis.sim.ReplayLog;
import dev.stargenesis.sim.ResourceSandboxWorld;
import dev.stargenesis.sim.SandboxConfig;
import dev.stargenesis.sim.SandboxView;
import dev.stargenesis.sim.WorldSnapshot;

import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Worker-owned experiment lifecycle, independent of Paper. A failed save freezes progress until retry. */
final class LabExperiment implements AutoCloseable {
    enum Phase { STARTING, RUNNING, SAVING, FINISHED, STOPPED, ERROR }
    enum Mode { NEW, REPLAY, RESUME }
    private final UUID owner;
    private final String newWorldId;
    private final SimulationBudget budget;
    private final LabPersistence persistence;
    private final Mode mode;
    private long seed;
    private SandboxConfig config;
    private ResourceSandboxWorld world;
    private ReplayLog replay;
    private int replayIndex;
    private Phase phase = Phase.STARTING;
    private String message = "STARTING";
    private SandboxView view;
    private WorldSnapshot pending;
    private Phase afterSave;
    private String afterSaveMessage;
    private boolean established;

    LabExperiment(UUID owner, String newWorldId, long seed, SandboxConfig config, Mode mode,
                  SimulationBudget budget, LabPersistence persistence) {
        this.owner = Objects.requireNonNull(owner);
        this.newWorldId = Objects.requireNonNull(newWorldId);
        this.seed = seed;
        this.config = Objects.requireNonNull(config);
        this.mode = Objects.requireNonNull(mode);
        this.budget = Objects.requireNonNull(budget);
        this.persistence = Objects.requireNonNull(persistence);
    }

    Phase phase() { return phase; }
    String message() { return message; }
    SandboxView view() { return view; }
    long seed() { return seed; }
    SandboxConfig config() { return config; }
    boolean active() { return phase == Phase.STARTING || phase == Phase.RUNNING || phase == Phase.SAVING; }

    void advance(boolean stopRequested) throws IOException {
        if (!active()) return;
        try {
            if (stopRequested) { stop("PLAYER_STOPPED"); return; }
            if (pending != null) { retrySave(); return; }
            if (world == null) initialize();
            if (!active()) return;
            if (mode == Mode.REPLAY) {
                if (replayIndex == replay.frames().size()) { finishReadOnly(Phase.FINISHED, "REPLAY_HASH_AND_EVENTS_VERIFIED"); return; }
                var expected = replay.frames().get(replayIndex);
                var result = world.step(expected.input());
                view = world.view();
                if (!result.stateHash().equals(expected.stateHash()) || !result.events().equals(expected.events()))
                    throw new IllegalArgumentException("REPLAY_DIVERGED_AT_STEP_" + expected.input().tick());
                replayIndex++;
                if (replayIndex == replay.frames().size()) finishReadOnly(Phase.FINISHED, "REPLAY_HASH_AND_EVENTS_VERIFIED");
            } else {
                var result = world.step(new StepInput(world.tick(), List.of()));
                view = world.view();
                if (result.terminal()) save(Phase.FINISHED, result.reason());
                else if (world.tick() % 100 == 0) save(Phase.RUNNING, "RUNNING");
            }
        } catch (IOException failure) {
            if (pending == null) abort("EVIDENCE_LOAD_FAILED:" + failure.getClass().getSimpleName());
            throw failure;
        } catch (RuntimeException invalid) {
            abort("SIMULATION_REJECTED:" + invalid.getMessage());
            throw invalid;
        }
    }

    private void initialize() throws IOException {
        if (mode == Mode.RESUME) {
            var snapshot = persistence.loadSnapshot(owner).orElseThrow(() -> new IllegalArgumentException("NO_CHECKPOINT_AVAILABLE"));
            requireCurrentBudget(snapshot.config());
            config = snapshot.config();
            seed = snapshot.seed();
            world = ResourceSandboxWorld.restore(snapshot);
            established = true;
        } else if (mode == Mode.REPLAY) {
            replay = persistence.loadReplay(owner).orElseThrow(() -> new IllegalArgumentException("NO_REPLAY_AVAILABLE"));
            if (!ResourceSandboxWorld.SANDBOX_ENGINE_VERSION.equals(replay.engineVersion()))
                throw new IllegalArgumentException("UNSUPPORTED_REPLAY_ENGINE");
            requireCurrentBudget(replay.config());
            config = replay.config();
            seed = replay.seed();
            world = new ResourceSandboxWorld(replay.worldId(), seed, config);
        } else {
            requireCurrentBudget(config);
            world = new ResourceSandboxWorld(newWorldId, seed, config);
            view = world.view();
            save(Phase.RUNNING, "RUNNING");
        }
        view = world.view();
        phase = Phase.RUNNING;
        message = mode == Mode.REPLAY ? "REPLAY_VERIFYING" : "RUNNING";
        if (view.terminal()) finishReadOnly(Phase.FINISHED, "CHECKPOINT_ALREADY_TERMINAL_USE_START");
    }

    private void stop(String reason) throws IOException {
        if (!active()) return;
        // Cancelling an initial save must not replace a previous run that was never successfully superseded.
        if (world == null || mode == Mode.REPLAY || !established) {
            pending = null;
            finishReadOnly(Phase.STOPPED, reason);
        } else save(Phase.STOPPED, reason);
    }

    private void save(Phase completedPhase, String completedMessage) throws IOException {
        pending = world.snapshot();
        afterSave = completedPhase;
        afterSaveMessage = completedMessage;
        phase = Phase.SAVING;
        message = "CHECKPOINT_SAVE_PENDING";
        retrySave();
    }

    private void retrySave() throws IOException {
        persistence.saveEvidence(owner, pending);
        pending = null;
        established = true;
        phase = afterSave;
        message = afterSaveMessage;
        if (phase == Phase.FINISHED || phase == Phase.STOPPED) closeWorld();
    }

    private void finishReadOnly(Phase completedPhase, String completedMessage) {
        phase = completedPhase;
        message = completedMessage;
        closeWorld();
    }

    /** Used after invalid simulation or final plugin shutdown failure; never writes suspect/failed state. */
    void abort(String reason) {
        pending = null;
        phase = Phase.ERROR;
        message = reason;
        closeWorld();
    }

    private void closeWorld() {
        if (world != null) { world.close(); world = null; }
    }

    @Override public void close() throws IOException { stop("PLUGIN_STOPPED"); }

    private void requireCurrentBudget(SandboxConfig candidate) {
        SimulationBudget previous = candidate.budget();
        if (candidate.width() > 9 || candidate.height() > 5 || candidate.agentCount() > budget.maxAgents()
                || previous.nodesPerAgentStep() > budget.nodesPerAgentStep() || previous.maxAgents() > budget.maxAgents()
                || previous.pathRequestsPerStep() > budget.pathRequestsPerStep() || candidate.durationSteps() > budget.maxSteps()
                || previous.sensorRadius() > budget.sensorRadius() || previous.messagesPerStep() > budget.messagesPerStep())
            throw new IllegalArgumentException("EVIDENCE_EXCEEDS_CURRENT_LAB_BUDGET");
    }
}
