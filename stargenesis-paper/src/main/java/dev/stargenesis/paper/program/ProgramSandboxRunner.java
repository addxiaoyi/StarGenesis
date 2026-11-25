package dev.stargenesis.paper.program;

import dev.stargenesis.api.sim.AgentId;
import dev.stargenesis.api.sim.StepInput;
import dev.stargenesis.api.sim.StepResult;
import dev.stargenesis.design.ProgramIR;
import dev.stargenesis.design.ProgramInterpreter;
import dev.stargenesis.design.ProgramValue;
import dev.stargenesis.sim.ResourceSandboxWorld;
import dev.stargenesis.sim.ResourceType;
import dev.stargenesis.sim.SandboxView;
import dev.stargenesis.sim.WorldSnapshot;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Maps typed program outputs into whitelisted, conserved sandbox commands. */
public final class ProgramSandboxRunner {
    private static final int MAX_WORLD_ACTIONS_PER_AGENT_STEP = 2;
    private final ResourceSandboxWorld world;
    private final Map<AgentId, ProgramIR> programs;
    private final ProgramInterpreter interpreter = new ProgramInterpreter();
    private final Map<AgentId, Long> nextSequence = new TreeMap<>();
    private Map<String, Long> signals = Map.of();

    public ProgramSandboxRunner(ResourceSandboxWorld world, Map<AgentId, ProgramIR> programs) {
        this.world = Objects.requireNonNull(world, "world");
        if (programs == null || programs.isEmpty()) throw new IllegalArgumentException("At least one program binding is required");
        SandboxView view = world.view();
        TreeMap<AgentId, ProgramIR> sorted = new TreeMap<>();
        programs.forEach((agent, program) -> {
            if (view.agents().stream().noneMatch(value -> value.id().equals(agent)))
                throw new IllegalArgumentException("Program bound to an unknown sandbox agent");
            sorted.put(Objects.requireNonNull(agent), ProgramExecutionBudget.bounded(Objects.requireNonNull(program), world.config().budget()));
        });
        this.programs = Collections.unmodifiableMap(sorted);
        for (WorldSnapshot.AgentState agent : world.snapshot().agents())
            nextSequence.put(agent.id(), Math.addExact(agent.lastSequence(), 1));
    }

    public RuntimeStep step() {
        SandboxView view = world.view();
        if (view.terminal()) return new RuntimeStep(Status.TERMINAL, null, Map.of(), List.of(), signals, view.reason());
        List<StepInput.Command> commands = new ArrayList<>();
        Map<AgentId, ProgramInterpreter.Execution> executions = new TreeMap<>();
        Map<String, Long> nextSignals = new TreeMap<>(signals);
        Map<AgentId, Long> plannedSequences = new TreeMap<>(nextSequence);
        int emittedMessages = 0;

        for (Map.Entry<AgentId, ProgramIR> binding : programs.entrySet()) {
            SandboxView.AgentView agent = view.agents().stream().filter(value -> value.id().equals(binding.getKey()))
                    .findFirst().orElseThrow();
            ProgramInterpreter.ProgramContext context = context(view, agent, signals);
            ProgramInterpreter.Execution execution = interpreter.execute(binding.getValue(), context,
                    ProgramInterpreter.MAX_QUANTUM, java.util.Set.of());
            executions.put(binding.getKey(), execution);
            if (execution.status() != ProgramInterpreter.Status.COMPLETE)
                return new RuntimeStep(Status.PROGRAM_FAILED, null, executions, List.of(), signals,
                        binding.getKey() + ":" + execution.status() + ":" + execution.message());

            int worldActions = 0;
            long sequence = plannedSequences.get(binding.getKey());
            commands.add(new StepInput.Command(binding.getKey(), sequence++, "pause", Map.of()));
            for (ProgramInterpreter.Action action : execution.actions()) {
                if (action instanceof ProgramInterpreter.SignalAction signal) {
                    if (++emittedMessages > world.config().budget().messagesPerStep())
                        return new RuntimeStep(Status.MESSAGE_BUDGET_EXCEEDED, null, executions, List.of(), signals,
                                "MESSAGES_PER_STEP_BUDGET");
                    if (!nextSignals.containsKey(signal.channel()) && nextSignals.size() >= 64)
                        return new RuntimeStep(Status.SIGNAL_CHANNEL_BUDGET_EXCEEDED, null, executions, List.of(), signals,
                                "SIGNAL_CHANNEL_COUNT_BUDGET");
                    nextSignals.put(signal.channel(), (long) signal.value());
                    continue;
                }
                if (++worldActions > MAX_WORLD_ACTIONS_PER_AGENT_STEP)
                    return new RuntimeStep(Status.ACTION_BUDGET_EXCEEDED, null, executions, List.of(), signals,
                            "PROGRAM_ACTIONS_PER_AGENT_STEP_BUDGET");
                try {
                    commands.add(command(binding.getKey(), sequence++, action));
                } catch (RuntimeException failure) {
                    return new RuntimeStep(Status.WORLD_REJECTED, null, executions, List.of(), signals,
                            failure.getClass().getSimpleName() + ":" + failure.getMessage());
                }
            }
            plannedSequences.put(binding.getKey(), sequence);
        }

        try {
            StepResult result = world.step(new StepInput(view.tick(), commands));
            nextSequence.clear();
            nextSequence.putAll(plannedSequences);
            signals = Collections.unmodifiableMap(new TreeMap<>(nextSignals));
            List<ProgramInterpreter.Action> actions = executions.entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .flatMap(entry -> entry.getValue().actions().stream()).toList();
            return new RuntimeStep(Status.ADVANCED, result, executions, actions, signals, "");
        } catch (RuntimeException failure) {
            return new RuntimeStep(Status.WORLD_REJECTED, null, executions, List.of(), signals,
                    failure.getClass().getSimpleName() + ":" + failure.getMessage());
        }
    }

    public SandboxView view() { return world.view(); }
    public WorldSnapshot snapshot() { return world.snapshot(); }
    public dev.stargenesis.sim.ReplayLog replayLog() { return world.replayLog(); }
    public String stateHash() { return world.stateHash(); }
    public Map<String, Long> signals() { return signals; }

    private ProgramInterpreter.ProgramContext context(SandboxView view, SandboxView.AgentView agent,
                                                       Map<String, Long> currentSignals) {
        Map<String, ProgramValue> sensors = new TreeMap<>();
        sensor(sensors, "energy", agent.energy());
        sensor(sensors, "ore", agent.ore());
        sensor(sensors, "biomass", agent.biomass());
        sensor(sensors, "capacity", agent.capacity());
        sensor(sensors, "x", agent.position().x());
        sensor(sensors, "y", agent.position().y());
        sensor(sensors, "tick", view.tick());
        sensor(sensors, "score", view.score());
        sensor(sensors, "base_x", view.base().x());
        sensor(sensors, "base_y", view.base().y());
        sensor(sensors, "width", view.width());
        sensor(sensors, "height", view.height());
        sensors.put("has_ore", new ProgramValue.BooleanValue(agent.ore() > 0));
        sensors.put("has_biomass", new ProgramValue.BooleanValue(agent.biomass() > 0));
        sensors.put("at_base", new ProgramValue.BooleanValue(agent.position().distance(view.base()) <= 1));
        for (SandboxView.ResourceNodeView node : view.nodes()) {
            String prefix = node.resource() == ResourceType.ORE ? "ore_node" : "biomass_node";
            sensor(sensors, prefix + "_x", node.position().x());
            sensor(sensors, prefix + "_y", node.position().y());
        }
        sensors.put("ore_resource", new ProgramValue.ResourceValue("ore"));
        sensors.put("biomass_resource", new ProgramValue.ResourceValue("biomass"));
        Map<String, Long> resources = new TreeMap<>();
        for (SandboxView.ResourceNodeView node : view.nodes())
            resources.merge(node.resource().ledgerKey(), node.remaining(), Math::addExact);
        return new ProgramInterpreter.ProgramContext(view.seed(), view.tick(), sensors, resources, currentSignals);
    }

    private void sensor(Map<String, ProgramValue> sensors, String key, long value) {
        sensors.put(key, new ProgramValue.IntegerValue(value));
    }

    private StepInput.Command command(AgentId agent, long sequence, ProgramInterpreter.Action action) {
        if (action instanceof ProgramInterpreter.MoveAction move) {
            ProgramValue.VectorValue destination = move.destination();
            if (destination.z() != 0) throw new IllegalArgumentException("Sandbox moves require z=0");
            return new StepInput.Command(agent, sequence, "program_move",
                    Map.of("x", (long) destination.x(), "y", (long) destination.y(), "z", 0L));
        }
        if (action instanceof ProgramInterpreter.ResourceAction resource) {
            long resourceId = resourceId(resource.resourceId());
            if (resource.type() == ProgramInterpreter.ResourceActionType.HARVEST)
                return new StepInput.Command(agent, sequence, "program_harvest", Map.of("resource", resourceId));
            return new StepInput.Command(agent, sequence, "program_deposit", Map.of("resource", resourceId));
        }
        if (action == ProgramInterpreter.WaitAction.INSTANCE)
            return new StepInput.Command(agent, sequence, "program_wait", Map.of());
        throw new IllegalArgumentException("Unsupported sandbox action " + action.getClass().getSimpleName());
    }

    private long resourceId(String id) {
        return switch (id) {
            case "ore" -> 0;
            case "biomass" -> 1;
            default -> throw new IllegalArgumentException("Unknown sandbox resource " + id);
        };
    }

    public enum Status { ADVANCED, PROGRAM_FAILED, ACTION_BUDGET_EXCEEDED, MESSAGE_BUDGET_EXCEEDED,
        SIGNAL_CHANNEL_BUDGET_EXCEEDED, WORLD_REJECTED, TERMINAL }

    public record RuntimeStep(Status status, StepResult worldResult,
                              Map<AgentId, ProgramInterpreter.Execution> executions,
                              List<ProgramInterpreter.Action> actions, Map<String, Long> signals,
                              String message) {
        public RuntimeStep {
            executions = Collections.unmodifiableMap(new TreeMap<>(executions));
            actions = List.copyOf(actions);
            signals = Collections.unmodifiableMap(new TreeMap<>(signals));
        }
    }
}
