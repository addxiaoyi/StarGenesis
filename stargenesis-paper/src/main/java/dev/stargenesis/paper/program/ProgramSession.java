package dev.stargenesis.paper.program;

import dev.stargenesis.api.design.DesignId;
import dev.stargenesis.api.sim.SimulationBudget;
import dev.stargenesis.design.PrimitiveCatalog;
import dev.stargenesis.design.ProgramIR;
import dev.stargenesis.design.ProgramInterpreter;
import dev.stargenesis.design.ProgramValue;
import dev.stargenesis.design.ProgramWorkspace;
import dev.stargenesis.design.TypedProgramDocument;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** One player's editor/debugger state, owned by their command thread; no server objects. */
final class ProgramSession {
    final UUID owner;
    final DesignId id;
    private final SimulationBudget budget;
    private final ProgramInterpreter interpreter = new ProgramInterpreter();
    ProgramWorkspace workspace;
    private TypedProgramDocument saved;
    String baseHash;
    boolean dirty;
    Map<String, ProgramValue> sensors = Map.of();
    Map<String, Long> resources = Map.of();
    Map<String, Long> signals = Map.of();
    final Set<String> breakpoints = new HashSet<>();
    ProgramInterpreter.Execution execution;
    long seed;
    long tick;

    ProgramSession(UUID owner, DesignId id, ProgramWorkspace workspace, TypedProgramDocument saved,
                   SimulationBudget budget) {
        this.owner = Objects.requireNonNull(owner);
        this.id = Objects.requireNonNull(id);
        this.workspace = Objects.requireNonNull(workspace);
        this.budget = Objects.requireNonNull(budget);
        if (saved != null && (!saved.author().equals(owner) || !saved.id().equals(id)))
            throw new IllegalArgumentException("Program session owner or ID mismatch");
        this.saved = saved;
        baseHash = saved == null ? "" : saved.hash();
        dirty = saved == null || !saved.program().equals(workspace.current());
    }

    void append(String name, PrimitiveCatalog.Operation operation, List<String> inputs, Map<String, String> parameters) {
        workspace.append(name, operation, inputs, parameters);
        changed();
    }

    void replace(String name, PrimitiveCatalog.Operation operation, List<String> inputs, Map<String, String> parameters) {
        workspace.replace(name, operation, inputs, parameters);
        changed();
    }

    void remove(String name) { workspace.remove(name); changed(); }

    boolean undo() {
        if (!workspace.undo()) return false;
        changed();
        return true;
    }

    void save(ProgramStore store) throws IOException {
        var committed = store.save(owner, id, workspace.current(), baseHash);
        saved = committed;
        baseHash = committed.hash();
        workspace = new ProgramWorkspace(committed.program());
        dirty = false;
        execution = null;
    }

    void setSensor(String key, ProgramValue value) {
        var candidate = new HashMap<>(sensors); candidate.put(key, value);
        setContext(new ProgramInterpreter.ProgramContext(seed, tick, candidate, resources, signals));
    }

    void setResource(String key, long value) {
        var candidate = new HashMap<>(resources); candidate.put(key, value);
        setContext(new ProgramInterpreter.ProgramContext(seed, tick, sensors, candidate, signals));
    }

    void setSignal(String key, long value) {
        var candidate = new HashMap<>(signals); candidate.put(key, value);
        setContext(new ProgramInterpreter.ProgramContext(seed, tick, sensors, resources, candidate));
    }

    void setClock(long seed, long tick) {
        setContext(new ProgramInterpreter.ProgramContext(seed, tick, sensors, resources, signals));
    }

    void breakpoint(String name, boolean enabled) {
        if (workspace.instructions().stream().noneMatch(instruction -> instruction.id().equals(name)))
            throw new IllegalArgumentException("Unknown instruction " + name);
        if (enabled) breakpoints.add(name); else breakpoints.remove(name);
        execution = null;
    }

    ProgramInterpreter.Execution execute(int quantum, boolean resume) {
        if (!resume || execution == null)
            execution = interpreter.execute(ProgramExecutionBudget.bounded(workspace.current(), budget), context(), quantum, breakpoints);
        else execution = interpreter.resume(execution, quantum, breakpoints);
        return execution;
    }

    ProgramInterpreter.ProgramContext context() {
        return new ProgramInterpreter.ProgramContext(seed, tick, sensors, resources, signals);
    }

    private void changed() {
        dirty = saved == null || !workspace.current().equals(saved.program());
        execution = null;
        var existing = workspace.instructions().stream().map(ProgramIR.Instruction::id).collect(java.util.stream.Collectors.toSet());
        breakpoints.retainAll(existing);
    }

    private void setContext(ProgramInterpreter.ProgramContext context) {
        seed = context.seed(); tick = context.tick();
        sensors = context.sensors(); resources = context.resources(); signals = context.signals();
        execution = null;
    }
}
