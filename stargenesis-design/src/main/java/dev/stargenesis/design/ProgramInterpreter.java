package dev.stargenesis.design;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Deterministic bounded interpreter that emits abstract actions and never touches a game server. */
public final class ProgramInterpreter {
    public static final int MAX_QUANTUM = 256;

    public Execution execute(ProgramIR program, ProgramContext context, int quantum, Set<String> breakpoints) {
        Objects.requireNonNull(program, "program");
        Objects.requireNonNull(context, "context");
        validateRun(quantum, breakpoints, program);
        return run(program, context, 0, Map.of(), List.of(), List.of(), 0, quantum, breakpoints, false);
    }

    public Execution resume(Execution previous, int quantum, Set<String> breakpoints) {
        Objects.requireNonNull(previous, "previous");
        if (previous.status != Status.YIELDED && previous.status != Status.BREAKPOINT)
            throw new IllegalArgumentException("Only a yielded or breakpoint execution can resume");
        validateRun(quantum, breakpoints, previous.program);
        return run(previous.program, previous.context, previous.nextIndex, previous.values, previous.actions,
                previous.trace, previous.executed, quantum, breakpoints, previous.status == Status.BREAKPOINT);
    }

    private void validateRun(int quantum, Set<String> breakpoints, ProgramIR program) {
        if (quantum < 1 || quantum > MAX_QUANTUM) throw new IllegalArgumentException("Execution quantum outside 1.." + MAX_QUANTUM);
        if (breakpoints == null || breakpoints.size() > ProgramIR.MAX_INSTRUCTIONS)
            throw new IllegalArgumentException("Invalid breakpoint set");
        Set<String> instructionIds = new HashSet<>();
        program.instructions().forEach(instruction -> instructionIds.add(instruction.id()));
        if (!instructionIds.containsAll(breakpoints)) throw new IllegalArgumentException("Breakpoint references an unknown instruction");
    }

    private Execution run(ProgramIR program, ProgramContext context, int startIndex,
                          Map<String, ProgramValue> priorValues, List<Action> priorActions,
                          List<TraceEntry> priorTrace, int executed, int quantum,
                          Set<String> breakpoints, boolean skipCurrentBreakpoint) {
        Map<String, ProgramValue> values = new LinkedHashMap<>(priorValues);
        List<Action> actions = new ArrayList<>(priorActions);
        List<TraceEntry> trace = new ArrayList<>(priorTrace);
        int index = startIndex;
        int inQuantum = 0;

        while (index < program.instructions().size()) {
            ProgramIR.Instruction instruction = program.instructions().get(index);
            if (breakpoints.contains(instruction.id()) && !(skipCurrentBreakpoint && index == startIndex))
                return new Execution(program, context, index, values, actions, trace, executed, Status.BREAKPOINT, "");
            skipCurrentBreakpoint = false;
            if (inQuantum >= quantum)
                return new Execution(program, context, index, values, actions, trace, executed, Status.YIELDED, "");
            if (executed >= program.maxInstructionsPerStep())
                return new Execution(program, context, index, values, actions, trace, executed,
                        Status.BUDGET_EXCEEDED, "MAX_INSTRUCTIONS_PER_STEP");

            List<ProgramValue> inputs = instruction.inputs().stream().map(values::get).toList();
            try {
                ProgramValue output = evaluate(instruction, inputs, context, actions);
                values.put(instruction.id(), output);
                trace.add(new TraceEntry(index, instruction.id(), instruction.operation(), inputs, output, ""));
                index++;
                executed++;
                inQuantum++;
            } catch (RuntimeException failure) {
                trace.add(new TraceEntry(index, instruction.id(), instruction.operation(), inputs,
                        ProgramValue.NoneValue.INSTANCE, failure.getMessage()));
                return new Execution(program, context, index, values, actions, trace, executed,
                        Status.FAULT, failure.getMessage());
            }
        }
        return new Execution(program, context, index, values, actions, trace, executed, Status.COMPLETE, "");
    }

    private ProgramValue evaluate(ProgramIR.Instruction instruction, List<ProgramValue> inputs,
                                  ProgramContext context, List<Action> actions) {
        PrimitiveCatalog.Operation operation = instruction.operation();
        Map<String, String> parameters = instruction.parameters();
        return switch (operation) {
            case CONSTANT_BOOLEAN -> new ProgramValue.BooleanValue(Boolean.parseBoolean(parameters.get("value")));
            case CONSTANT_INTEGER -> new ProgramValue.IntegerValue(Long.parseLong(parameters.get("value")));
            case SENSE_BOOLEAN, SENSE_INTEGER, SENSE_VECTOR, SENSE_RESOURCE -> sense(instruction, context);
            case ADD_INTEGER -> integer(Math.addExact(number(inputs, 0), number(inputs, 1)));
            case SUBTRACT_INTEGER -> integer(Math.subtractExact(number(inputs, 0), number(inputs, 1)));
            case MULTIPLY_INTEGER -> integer(Math.multiplyExact(number(inputs, 0), number(inputs, 1)));
            case DIVIDE_INTEGER -> divide(number(inputs, 0), number(inputs, 1));
            case MINIMUM_INTEGER -> integer(Math.min(number(inputs, 0), number(inputs, 1)));
            case MAXIMUM_INTEGER -> integer(Math.max(number(inputs, 0), number(inputs, 1)));
            case CLAMP_INTEGER -> integer(Math.max(Long.parseLong(parameters.get("min")),
                    Math.min(Long.parseLong(parameters.get("max")), number(inputs, 0))));
            case EQUAL_INTEGER -> bool(number(inputs, 0) == number(inputs, 1));
            case COMPARE_THRESHOLD_INTEGER -> compareThreshold(number(inputs, 0), parameters);
            case LESS_THAN_INTEGER -> bool(number(inputs, 0) < number(inputs, 1));
            case GREATER_THAN_INTEGER -> bool(number(inputs, 0) > number(inputs, 1));
            case AND_BOOLEAN -> bool(truth(inputs, 0) && truth(inputs, 1));
            case OR_BOOLEAN -> bool(truth(inputs, 0) || truth(inputs, 1));
            case NOT_BOOLEAN -> bool(!truth(inputs, 0));
            case XOR_BOOLEAN -> bool(truth(inputs, 0) ^ truth(inputs, 1));
            case SELECT_INTEGER -> integer(truth(inputs, 0) ? number(inputs, 1) : number(inputs, 2));
            case MAKE_VECTOR -> new ProgramValue.VectorValue(Math.toIntExact(number(inputs, 0)),
                    Math.toIntExact(number(inputs, 1)), Math.toIntExact(number(inputs, 2)));
            case VECTOR_X -> integer(vector(inputs, 0).x());
            case VECTOR_Y -> integer(vector(inputs, 0).y());
            case VECTOR_Z -> integer(vector(inputs, 0).z());
            case DISTANCE_SQUARED -> distanceSquared(vector(inputs, 0), vector(inputs, 1));
            case HAS_RESOURCE -> bool(context.resources().getOrDefault(resource(inputs, 0).id(), 0L) > 0);
            case MOVE -> { actions.add(new MoveAction(vector(inputs, 0))); yield ProgramValue.NoneValue.INSTANCE; }
            case HARVEST -> { actions.add(new ResourceAction(ResourceActionType.HARVEST, resource(inputs, 0).id())); yield ProgramValue.NoneValue.INSTANCE; }
            case DEPOSIT -> { actions.add(new ResourceAction(ResourceActionType.DEPOSIT, resource(inputs, 0).id())); yield ProgramValue.NoneValue.INSTANCE; }
            case WAIT -> { actions.add(WaitAction.INSTANCE); yield ProgramValue.NoneValue.INSTANCE; }
            case EMIT_SIGNAL -> { actions.add(new SignalAction(parameters.get("channel"), Math.toIntExact(number(inputs, 0)))); yield ProgramValue.NoneValue.INSTANCE; }
            case READ_SIGNAL -> integer(context.signals().getOrDefault(parameters.get("channel"), 0L));
            case RANDOM_INTEGER -> integer(randomValue(context.seed(), context.tick(), instruction.id(),
                    Math.toIntExact(Long.parseLong(parameters.get("bound")))));
        };
    }

    private ProgramValue sense(ProgramIR.Instruction instruction, ProgramContext context) {
        String key = instruction.parameters().get("key");
        ProgramValue value = context.sensors().get(key);
        if (value == null) throw new IllegalArgumentException("Missing sensor " + key);
        if (value.type() != instruction.outputType()) throw new IllegalArgumentException("Sensor type mismatch for " + key);
        return value;
    }

    private ProgramValue compareThreshold(long left, Map<String, String> parameters) {
        long right = Long.parseLong(parameters.get("value"));
        return switch (parameters.get("operator")) {
            case "eq" -> bool(left == right);
            case "lt" -> bool(left < right);
            case "gt" -> bool(left > right);
            default -> throw new IllegalArgumentException("Unsupported integer comparison");
        };
    }

    private ProgramValue divide(long left, long right) {
        if (right == 0 || left == Long.MIN_VALUE && right == -1) throw new ArithmeticException("INTEGER_DIVISION_OVERFLOW");
        return integer(left / right);
    }

    private ProgramValue distanceSquared(ProgramValue.VectorValue first, ProgramValue.VectorValue second) {
        long dx = first.x() - second.x();
        long dy = first.y() - second.y();
        long dz = first.z() - second.z();
        return integer(Math.addExact(Math.addExact(Math.multiplyExact(dx, dx), Math.multiplyExact(dy, dy)), Math.multiplyExact(dz, dz)));
    }

    private long randomValue(long seed, long tick, String instructionId, int bound) {
        long hash = 0xcbf29ce484222325L;
        for (byte value : instructionId.getBytes(StandardCharsets.US_ASCII)) {
            hash ^= value & 0xffL;
            hash *= 0x100000001b3L;
        }
        long random = seed ^ Long.rotateLeft(tick * 0x9e3779b97f4a7c15L, 23) ^ hash;
        random ^= random >>> 12;
        random ^= random << 25;
        random ^= random >>> 27;
        return Math.floorMod(random * 0x2545F4914F6CDD1DL, bound);
    }

    private ProgramValue.IntegerValue integer(long value) { return new ProgramValue.IntegerValue(value); }
    private ProgramValue.BooleanValue bool(boolean value) { return new ProgramValue.BooleanValue(value); }
    private long number(List<ProgramValue> values, int index) { return ((ProgramValue.IntegerValue) values.get(index)).value(); }
    private boolean truth(List<ProgramValue> values, int index) { return ((ProgramValue.BooleanValue) values.get(index)).value(); }
    private ProgramValue.VectorValue vector(List<ProgramValue> values, int index) { return (ProgramValue.VectorValue) values.get(index); }
    private ProgramValue.ResourceValue resource(List<ProgramValue> values, int index) { return (ProgramValue.ResourceValue) values.get(index); }

    public record ProgramContext(long seed, long tick, Map<String, ProgramValue> sensors,
                                 Map<String, Long> resources, Map<String, Long> signals) {
        public ProgramContext {
            if (tick < 0) throw new IllegalArgumentException("Negative logical tick");
            sensors = Map.copyOf(sensors);
            resources = Map.copyOf(resources);
            signals = Map.copyOf(signals);
            if (sensors.size() > 64 || resources.size() > 64 || signals.size() > 64)
                throw new IllegalArgumentException("Program context exceeds 64 entries per domain");
            sensors.forEach((key, value) -> {
                requireToken(key);
                Objects.requireNonNull(value, "sensor value");
                if (value.type() == PrimitiveCatalog.ValueType.NONE) throw new IllegalArgumentException("NONE is not a sensor value");
            });
            resources.forEach((key, value) -> {
                requireToken(key);
                if (value == null || value < 0 || value > 1_000_000) throw new IllegalArgumentException("Invalid resource quantity");
            });
            signals.forEach((key, value) -> {
                requireToken(key);
                if (value == null || value < Integer.MIN_VALUE || value > Integer.MAX_VALUE)
                    throw new IllegalArgumentException("Signal value outside integer range");
            });
        }

        private static void requireToken(String key) {
            if (key == null || !key.matches("[a-z][a-z0-9_:-]{0,63}")) throw new IllegalArgumentException("Invalid context key");
        }
    }

    public enum Status { COMPLETE, BREAKPOINT, YIELDED, BUDGET_EXCEEDED, FAULT }
    public enum ResourceActionType { HARVEST, DEPOSIT }

    public sealed interface Action permits MoveAction, ResourceAction, WaitAction, SignalAction { }
    public record MoveAction(ProgramValue.VectorValue destination) implements Action { }
    public record ResourceAction(ResourceActionType type, String resourceId) implements Action { }
    public enum WaitAction implements Action { INSTANCE }
    public record SignalAction(String channel, int value) implements Action { }

    public record TraceEntry(int instructionIndex, String instructionId, PrimitiveCatalog.Operation operation,
                             List<ProgramValue> inputs, ProgramValue output, String fault) {
        public TraceEntry {
            inputs = List.copyOf(inputs);
        }
    }

    public static final class Execution {
        private final ProgramIR program;
        private final ProgramContext context;
        private final int nextIndex;
        private final Map<String, ProgramValue> values;
        private final List<Action> actions;
        private final List<TraceEntry> trace;
        private final int executed;
        private final Status status;
        private final String message;

        private Execution(ProgramIR program, ProgramContext context, int nextIndex, Map<String, ProgramValue> values,
                          List<Action> actions, List<TraceEntry> trace, int executed, Status status, String message) {
            this.program = program;
            this.context = context;
            this.nextIndex = nextIndex;
            this.values = Map.copyOf(values);
            this.actions = List.copyOf(actions);
            this.trace = List.copyOf(trace);
            this.executed = executed;
            this.status = status;
            this.message = message;
        }

        public Status status() { return status; }
        public Map<String, ProgramValue> values() { return values; }
        public List<Action> actions() { return actions; }
        public List<TraceEntry> trace() { return trace; }
        public int executedInstructions() { return executed; }
        public String nextInstructionId() {
            return nextIndex < program.instructions().size() ? program.instructions().get(nextIndex).id() : "";
        }
        public String message() { return message; }
    }
}
