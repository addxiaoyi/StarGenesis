package dev.stargenesis.design;

import dev.stargenesis.api.sim.AgentId;
import dev.stargenesis.api.sim.StepInput;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ProgramExecutionSession {
    public static final int MAX_EMITTED_COMMANDS = 1024;
    private final TypedProgram program;
    private final ProgramWorldView snapshot;
    private final AgentId actor;
    private final Map<String, ProgramValue> values = new HashMap<>();
    private final List<StepInput.Command> commands = new ArrayList<>();
    private final List<ProgramDebugEvent> trace = new ArrayList<>();
    private final Set<String> breakpoints = new HashSet<>();
    private int nextInstruction;
    private int executed;
    private boolean complete;
    private String status = "READY";
    private String bypassBreakpoint;

    public ProgramExecutionSession(TypedProgram program, ProgramWorldView snapshot, AgentId actor) {
        this.program = java.util.Objects.requireNonNull(program, "program");
        this.snapshot = java.util.Objects.requireNonNull(snapshot, "snapshot");
        this.actor = java.util.Objects.requireNonNull(actor, "actor");
        if (!snapshot.agents().containsKey(actor))
            throw new IllegalArgumentException("Program actor is not present in the world snapshot");
    }

    public void addBreakpoint(String nodeId) {
        requireKnownNode(nodeId);
        breakpoints.add(nodeId);
    }

    public void removeBreakpoint(String nodeId) { breakpoints.remove(nodeId); }
    public boolean complete() { return complete; }

    public ProgramDebugEvent stepInstruction() {
        if (complete) return event(-1, "", "", "", List.of(), status);
        return executeNext();
    }

    public ProgramDebugEvent continueUntilPause() {
        if (complete) return event(-1, "", "", "", List.of(), status);
        while (nextInstruction < program.instructions().size()) {
            TypedProgram.Instruction instruction = program.instructions().get(nextInstruction);
            if (breakpoints.contains(instruction.id())) {
                if (!instruction.id().equals(bypassBreakpoint)) {
                    bypassBreakpoint = instruction.id();
                    return event(nextInstruction, instruction.id(), instruction.operation().name(), "", List.of(), "BREAKPOINT");
                }
            }
            bypassBreakpoint = null;
            ProgramDebugEvent result = executeNext();
            if (complete) return result;
        }
        return finish();
    }

    public ProgramExecutionResult result() {
        return new ProgramExecutionResult(commands, trace, executed, complete, status);
    }

    private ProgramDebugEvent executeNext() {
        if (executed >= program.maxInstructionsPerStep()) return fail("INSTRUCTION_BUDGET_EXHAUSTED");
        if (nextInstruction >= program.instructions().size()) return finish();
        TypedProgram.Instruction instruction = program.instructions().get(nextInstruction);
        List<StepInput.Command> emitted = new ArrayList<>(1);
        try {
            ProgramValue output = evaluate(instruction, emitted);
            if (instruction.outputType() != Program.ValueType.NONE) values.put(instruction.id(), output);
            commands.addAll(emitted);
            int index = nextInstruction++;
            executed++;
            String rendered = output instanceof ProgramValue.None ? "" : output.toString();
            ProgramDebugEvent event = event(index, instruction.id(), instruction.operation().name(), rendered,
                    emitted, "EXECUTED");
            trace.add(event);
            if (nextInstruction == program.instructions().size()) {
                complete = true;
                status = "COMPLETED";
            }
            return event;
        } catch (ArithmeticException | IllegalArgumentException failure) {
            return fail(failure instanceof ArithmeticException ? "ARITHMETIC_ERROR" : "EXECUTION_ERROR: " + failure.getMessage());
        }
    }

    private ProgramValue evaluate(TypedProgram.Instruction instruction, List<StepInput.Command> emitted) {
        List<ProgramValue> inputs = instruction.inputs().stream()
                .map(index -> values.get(program.instructions().get(index).id())).toList();
        if (instruction.operation() == Program.Operation.EQUAL && inputs.get(0).type() != inputs.get(1).type())
            throw new IllegalArgumentException("Equality operands must have the same type");
        return switch (instruction.operation()) {
            case SENSE -> sense(instruction.parameters().get("field"));
            case CONSTANT -> constant(instruction.outputType(), instruction.parameters().get("value"));
            case VECTOR -> new ProgramValue.VectorValue(integerParameter(instruction, "dx"), integerParameter(instruction, "dy"));
            case VECTOR_SELECT -> vectorSelect(instruction, inputs.getFirst());
            case COMPARE -> compare(instruction, integer(inputs.getFirst()));
            case AND -> new ProgramValue.BooleanValue(bool(inputs.get(0)) && bool(inputs.get(1)));
            case OR -> new ProgramValue.BooleanValue(bool(inputs.get(0)) || bool(inputs.get(1)));
            case XOR -> new ProgramValue.BooleanValue(bool(inputs.get(0)) ^ bool(inputs.get(1)));
            case NOT -> new ProgramValue.BooleanValue(!bool(inputs.getFirst()));
            case EQUAL -> new ProgramValue.BooleanValue(inputs.get(0).equals(inputs.get(1)));
            case ADD -> new ProgramValue.IntegerValue(Math.addExact(integer(inputs.get(0)), integer(inputs.get(1))));
            case SUBTRACT -> new ProgramValue.IntegerValue(Math.subtractExact(integer(inputs.get(0)), integer(inputs.get(1))));
            case MULTIPLY -> new ProgramValue.IntegerValue(Math.multiplyExact(integer(inputs.get(0)), integer(inputs.get(1))));
            case DIVIDE -> divide(inputs.get(0), inputs.get(1));
            case REMAINDER -> remainder(inputs.get(0), inputs.get(1));
            case MIN -> new ProgramValue.IntegerValue(Math.min(integer(inputs.get(0)), integer(inputs.get(1))));
            case MAX -> new ProgramValue.IntegerValue(Math.max(integer(inputs.get(0)), integer(inputs.get(1))));
            case ABS -> new ProgramValue.IntegerValue(Math.absExact(integer(inputs.getFirst())));
            case NEGATE -> new ProgramValue.IntegerValue(Math.negateExact(integer(inputs.getFirst())));
            case CLAMP -> new ProgramValue.IntegerValue(Math.clamp(integer(inputs.getFirst()),
                    integer(instruction, "min"), integer(instruction, "max")));
            case SELECT -> new ProgramValue.IntegerValue(bool(inputs.getFirst())
                    ? integer(instruction, "true_value") : integer(instruction, "false_value"));
            case MOVE -> {
                ProgramValue.VectorValue direction = vector(inputs.getFirst());
                emit(instruction, emitted, "move", Map.of("dx", (long) direction.dx(), "dy", (long) direction.dy()));
                yield new ProgramValue.None();
            }
            case HARVEST -> {
                if (resource(inputs.getFirst()) > 0) emit(instruction, emitted, "gather", Map.of());
                yield new ProgramValue.None();
            }
            case DEPOSIT -> {
                long amount = resource(inputs.getFirst());
                if (amount > 0) emit(instruction, emitted, "deposit", Map.of("amount", amount));
                yield new ProgramValue.None();
            }
            case WAIT -> {
                emit(instruction, emitted, "wait", Map.of());
                yield new ProgramValue.None();
            }
        };
    }

    private ProgramValue sense(String field) {
        ProgramWorldView.Agent agent = snapshot.agents().get(actor);
        int x = agent.x();
        int y = agent.y();
        return switch (field) {
            case "x" -> new ProgramValue.IntegerValue(x);
            case "y" -> new ProgramValue.IntegerValue(y);
            case "tick" -> new ProgramValue.IntegerValue(snapshot.tick());
            case "inventory" -> new ProgramValue.ResourceValue(agent.inventory());
            case "shared_stock" -> new ProgramValue.ResourceValue(snapshot.sharedStock());
            case "resource_here" -> new ProgramValue.ResourceValue(cell(x, y).resourceUnits());
            case "resource_north" -> new ProgramValue.ResourceValue(cell(x, y - 1).resourceUnits());
            case "resource_east" -> new ProgramValue.ResourceValue(cell(x + 1, y).resourceUnits());
            case "resource_south" -> new ProgramValue.ResourceValue(cell(x, y + 1).resourceUnits());
            case "resource_west" -> new ProgramValue.ResourceValue(cell(x - 1, y).resourceUnits());
            case "blocked_here" -> new ProgramValue.BooleanValue(cell(x, y).blocked());
            case "blocked_north" -> new ProgramValue.BooleanValue(cell(x, y - 1).blocked());
            case "blocked_east" -> new ProgramValue.BooleanValue(cell(x + 1, y).blocked());
            case "blocked_south" -> new ProgramValue.BooleanValue(cell(x, y + 1).blocked());
            case "blocked_west" -> new ProgramValue.BooleanValue(cell(x - 1, y).blocked());
            default -> throw new IllegalArgumentException("Unknown sensor field");
        };
    }

    private ProgramWorldView.Cell cell(int x, int y) { return snapshot.cellAt(x, y); }

    private static ProgramValue constant(Program.ValueType type, String value) {
        return switch (type) {
            case INTEGER -> new ProgramValue.IntegerValue(Long.parseLong(value));
            case RESOURCE -> new ProgramValue.ResourceValue(Long.parseLong(value));
            case BOOLEAN -> new ProgramValue.BooleanValue(Boolean.parseBoolean(value));
            default -> throw new IllegalArgumentException("Unsupported constant type");
        };
    }

    private static ProgramValue compare(TypedProgram.Instruction instruction, long value) {
        long expected = integer(instruction, "value");
        return new ProgramValue.BooleanValue(switch (instruction.parameters().get("operator")) {
            case "EQ" -> value == expected;
            case "NE" -> value != expected;
            case "LT" -> value < expected;
            case "LE" -> value <= expected;
            case "GT" -> value > expected;
            case "GE" -> value >= expected;
            default -> throw new IllegalArgumentException("Unknown comparison operator");
        });
    }

    private static ProgramValue vectorSelect(TypedProgram.Instruction instruction, ProgramValue value) {
        boolean selected = bool(value);
        String prefix = selected ? "true" : "false";
        return new ProgramValue.VectorValue(integerParameter(instruction, prefix + "_dx"), integerParameter(instruction, prefix + "_dy"));
    }

    private static ProgramValue divide(ProgramValue first, ProgramValue second) {
        long divisor = integer(second);
        if (divisor == 0) throw new ArithmeticException("division by zero");
        if (integer(first) == Long.MIN_VALUE && divisor == -1) throw new ArithmeticException("integer overflow");
        return new ProgramValue.IntegerValue(Math.floorDiv(integer(first), divisor));
    }

    private static ProgramValue remainder(ProgramValue first, ProgramValue second) {
        long divisor = integer(second);
        if (divisor == 0) throw new ArithmeticException("division by zero");
        return new ProgramValue.IntegerValue(Math.floorMod(integer(first), divisor));
    }

    private void emit(TypedProgram.Instruction instruction, List<StepInput.Command> emitted, String action,
                      Map<String, Long> arguments) {
        if (commands.size() + emitted.size() >= MAX_EMITTED_COMMANDS)
            throw new IllegalArgumentException("PROGRAM_COMMAND_BUDGET_EXHAUSTED");
        emitted.add(new StepInput.Command(actor, commands.size() + emitted.size(), action, arguments));
    }

    private ProgramDebugEvent fail(String reason) {
        commands.clear();
        complete = true;
        status = reason;
        ProgramDebugEvent failure = event(nextInstruction, "", "", "", List.of(), reason);
        trace.add(failure);
        return failure;
    }

    private ProgramDebugEvent finish() {
        complete = true;
        status = "COMPLETED";
        ProgramDebugEvent event = event(nextInstruction, "", "", "", List.of(), status);
        trace.add(event);
        return event;
    }

    private static ProgramDebugEvent event(int index, String id, String operation, String output,
                                           List<StepInput.Command> actions, String status) {
        return new ProgramDebugEvent(index, id, operation, output, actions, status);
    }

    private void requireKnownNode(String nodeId) {
        if (!program.indexes().containsKey(nodeId)) throw new IllegalArgumentException("Unknown program node " + nodeId);
    }

    private static long integer(TypedProgram.Instruction instruction, String parameter) {
        return Long.parseLong(instruction.parameters().get(parameter));
    }

    private static int integerParameter(TypedProgram.Instruction instruction, String parameter) {
        return Math.toIntExact(integer(instruction, parameter));
    }

    private static long integer(ProgramValue value) {
        if (value instanceof ProgramValue.IntegerValue integer) return integer.value();
        throw new IllegalArgumentException("Expected integer value");
    }

    private static boolean bool(ProgramValue value) {
        if (value instanceof ProgramValue.BooleanValue bool) return bool.value();
        throw new IllegalArgumentException("Expected boolean value");
    }

    private static ProgramValue.VectorValue vector(ProgramValue value) {
        if (value instanceof ProgramValue.VectorValue vector) return vector;
        throw new IllegalArgumentException("Expected vector value");
    }

    private static long resource(ProgramValue value) {
        if (value instanceof ProgramValue.ResourceValue resource) return resource.amount();
        throw new IllegalArgumentException("Expected resource value");
    }
}
