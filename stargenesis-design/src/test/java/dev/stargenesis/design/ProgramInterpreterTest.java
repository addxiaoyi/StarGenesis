package dev.stargenesis.design;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ProgramInterpreterTest {
    private final ProgramInterpreter interpreter = new ProgramInterpreter();

    @Test void catalogueContainsMoreThanTwentyFourTypedPrimitives() {
        assertTrue(PrimitiveCatalog.all().size() >= 24);
        assertEquals(PrimitiveCatalog.Operation.values().length, PrimitiveCatalog.all().size());
        assertTrue(PrimitiveCatalog.all().stream().allMatch(descriptor -> descriptor.cost() > 0));
    }

    @Test void everyRegisteredPrimitiveExecutesThroughItsTypedSignature() {
        var context = new ProgramInterpreter.ProgramContext(17, 3, Map.of(
                "sense_boolean", new ProgramValue.BooleanValue(true),
                "sense_integer", new ProgramValue.IntegerValue(2),
                "sense_vector", new ProgramValue.VectorValue(1, 0, -1),
                "sense_resource", new ProgramValue.ResourceValue("ore")), Map.of("ore", 2L), Map.of("channel", 7L));

        for (PrimitiveCatalog.Descriptor descriptor : PrimitiveCatalog.all()) {
            List<ProgramIR.Instruction> instructions = new java.util.ArrayList<>();
            List<String> inputs = new java.util.ArrayList<>();
            for (int index = 0; index < descriptor.inputTypes().size(); index++)
                inputs.add(addInput(instructions, descriptor.inputTypes().get(index), index));
            ProgramIR.Instruction target = instruction("target", descriptor.operation(), inputs,
                    parametersFor(descriptor.operation()));
            instructions.add(target);

            ProgramInterpreter.Execution result = interpreter.execute(
                    new ProgramIR(instructions, 64), context, 64, Set.of());

            assertEquals(ProgramInterpreter.Status.COMPLETE, result.status(), descriptor.operation().name());
            assertEquals(descriptor.outputType(), result.values().get("target").type(), descriptor.operation().name());
        }
    }

    @Test void typedGraphComputesValuesAndEmitsOnlyAbstractActions() {
        ProgramIR program = new ProgramIR(List.of(
                instruction("two", PrimitiveCatalog.Operation.CONSTANT_INTEGER, List.of(), Map.of("value", "2")),
                instruction("three", PrimitiveCatalog.Operation.CONSTANT_INTEGER, List.of(), Map.of("value", "3")),
                instruction("sum", PrimitiveCatalog.Operation.ADD_INTEGER, List.of("two", "three"), Map.of()),
                instruction("limit", PrimitiveCatalog.Operation.CONSTANT_INTEGER, List.of(), Map.of("value", "4")),
                instruction("ready", PrimitiveCatalog.Operation.GREATER_THAN_INTEGER, List.of("sum", "limit"), Map.of()),
                instruction("x", PrimitiveCatalog.Operation.CONSTANT_INTEGER, List.of(), Map.of("value", "1")),
                instruction("y", PrimitiveCatalog.Operation.CONSTANT_INTEGER, List.of(), Map.of("value", "0")),
                instruction("z", PrimitiveCatalog.Operation.CONSTANT_INTEGER, List.of(), Map.of("value", "-1")),
                instruction("position", PrimitiveCatalog.Operation.MAKE_VECTOR, List.of("x", "y", "z"), Map.of()),
                instruction("move", PrimitiveCatalog.Operation.MOVE, List.of("position"), Map.of())), 32);

        ProgramInterpreter.Execution result = interpreter.execute(program, emptyContext(), 32, Set.of());

        assertEquals(ProgramInterpreter.Status.COMPLETE, result.status());
        assertEquals(new ProgramValue.BooleanValue(true), result.values().get("ready"));
        assertEquals(List.of(new ProgramInterpreter.MoveAction(new ProgramValue.VectorValue(1, 0, -1))), result.actions());
        assertEquals(program.instructions().size(), result.trace().size());
    }

    @Test void breakpointsAndInstructionQuantumsResumeFromTheExactInstruction() {
        ProgramIR program = new ProgramIR(List.of(
                instruction("one", PrimitiveCatalog.Operation.CONSTANT_INTEGER, List.of(), Map.of("value", "1")),
                instruction("two", PrimitiveCatalog.Operation.CONSTANT_INTEGER, List.of(), Map.of("value", "2")),
                instruction("sum", PrimitiveCatalog.Operation.ADD_INTEGER, List.of("one", "two"), Map.of()),
                instruction("done", PrimitiveCatalog.Operation.WAIT, List.of(), Map.of())), 8);

        ProgramInterpreter.Execution paused = interpreter.execute(program, emptyContext(), 2, Set.of("sum"));
        assertEquals(ProgramInterpreter.Status.BREAKPOINT, paused.status());
        assertEquals("sum", paused.nextInstructionId());
        assertEquals(2, paused.trace().size());

        ProgramInterpreter.Execution resumed = interpreter.resume(paused, 1, Set.of("sum"));
        assertEquals(ProgramInterpreter.Status.YIELDED, resumed.status());
        assertEquals("done", resumed.nextInstructionId());
        assertEquals(new ProgramValue.IntegerValue(3), resumed.values().get("sum"));

        ProgramInterpreter.Execution completed = interpreter.resume(resumed, 4, Set.of());
        assertEquals(ProgramInterpreter.Status.COMPLETE, completed.status());
        assertEquals(4, completed.executedInstructions());
    }

    @Test void interpreterStopsAtBudgetAndRecordsArithmeticFaults() {
        ProgramIR bounded = new ProgramIR(List.of(
                instruction("first", PrimitiveCatalog.Operation.WAIT, List.of(), Map.of()),
                instruction("second", PrimitiveCatalog.Operation.WAIT, List.of(), Map.of()),
                instruction("third", PrimitiveCatalog.Operation.WAIT, List.of(), Map.of())), 2);
        ProgramInterpreter.Execution exhausted = interpreter.execute(bounded, emptyContext(), 8, Set.of());
        assertEquals(ProgramInterpreter.Status.BUDGET_EXCEEDED, exhausted.status());
        assertEquals(2, exhausted.executedInstructions());
        assertEquals("third", exhausted.nextInstructionId());

        ProgramIR division = new ProgramIR(List.of(
                instruction("numerator", PrimitiveCatalog.Operation.CONSTANT_INTEGER, List.of(), Map.of("value", "12")),
                instruction("denominator", PrimitiveCatalog.Operation.CONSTANT_INTEGER, List.of(), Map.of("value", "0")),
                instruction("quotient", PrimitiveCatalog.Operation.DIVIDE_INTEGER, List.of("numerator", "denominator"), Map.of())), 4);
        ProgramInterpreter.Execution fault = interpreter.execute(division, emptyContext(), 4, Set.of());
        assertEquals(ProgramInterpreter.Status.FAULT, fault.status());
        assertEquals(2, fault.executedInstructions());
        assertTrue(fault.trace().getLast().fault().contains("INTEGER_DIVISION_OVERFLOW"));
    }

    @Test void seededRandomAndSensorReadsAreDeterministicAndTyped() {
        ProgramIR program = new ProgramIR(List.of(
                instruction("energy", PrimitiveCatalog.Operation.SENSE_INTEGER, List.of(), Map.of("key", "energy")),
                instruction("roll", PrimitiveCatalog.Operation.RANDOM_INTEGER, List.of(), Map.of("bound", "1000"))), 4);
        var context = new ProgramInterpreter.ProgramContext(71, 9,
                Map.of("energy", new ProgramValue.IntegerValue(14)), Map.of(), Map.of());

        ProgramInterpreter.Execution first = interpreter.execute(program, context, 4, Set.of());
        ProgramInterpreter.Execution second = interpreter.execute(program, context, 4, Set.of());

        assertEquals(ProgramInterpreter.Status.COMPLETE, first.status());
        assertEquals(first.values(), second.values());
        assertEquals(new ProgramValue.IntegerValue(14), first.values().get("energy"));
        assertTrue(((ProgramValue.IntegerValue) first.values().get("roll")).value() < 1000);
    }

    @Test void oldProgramDocumentsCompileIntoTypedIr() throws IOException {
        Program source = loadProgramExample();
        ProgramIR compiled = new ProgramCompiler().compile(source);
        ProgramInterpreter.Execution result = interpreter.execute(compiled, emptyContext(), 8, Set.of());
        assertEquals(ProgramInterpreter.Status.COMPLETE, result.status());
        assertEquals(PrimitiveCatalog.Operation.WAIT, compiled.instructions().getFirst().operation());
    }

    @Test void irRejectsForwardReferencesTypeMismatchesAndInvalidParameters() {
        assertThrows(IllegalArgumentException.class, () -> new ProgramIR(List.of(
                instruction("move", PrimitiveCatalog.Operation.MOVE, List.of("position"), Map.of()),
                instruction("position", PrimitiveCatalog.Operation.CONSTANT_BOOLEAN, List.of(), Map.of("value", "true"))), 2));
        assertThrows(IllegalArgumentException.class, () -> new ProgramIR(List.of(
                instruction("flag", PrimitiveCatalog.Operation.CONSTANT_BOOLEAN, List.of(), Map.of("value", "true")),
                instruction("sum", PrimitiveCatalog.Operation.ADD_INTEGER, List.of("flag", "flag"), Map.of())), 2));
        assertThrows(IllegalArgumentException.class, () -> new ProgramIR(List.of(
                instruction("random", PrimitiveCatalog.Operation.RANDOM_INTEGER, List.of(), Map.of("bound", "0"))), 1));
    }

    @Test void programWorkspaceCommitsOnlyValidEditsAndKeepsBoundedUndoHistory() {
        ProgramWorkspace workspace = new ProgramWorkspace(64);
        workspace.append("one", PrimitiveCatalog.Operation.CONSTANT_INTEGER, List.of(), Map.of("value", "1"));
        workspace.append("two", PrimitiveCatalog.Operation.CONSTANT_INTEGER, List.of(), Map.of("value", "2"));
        workspace.append("sum", PrimitiveCatalog.Operation.ADD_INTEGER, List.of("one", "two"), Map.of());
        ProgramIR before = workspace.current();

        assertThrows(IllegalArgumentException.class, () -> workspace.replace(
                "sum", PrimitiveCatalog.Operation.NOT_BOOLEAN, List.of("one"), Map.of()));
        assertEquals(before, workspace.current());
        assertThrows(IllegalArgumentException.class, () -> workspace.remove("one"));
        assertTrue(workspace.undo());
        assertFalse(workspace.instructions().stream().anyMatch(instruction -> instruction.id().equals("sum")));
        assertTrue(workspace.undo());
        assertEquals(0, workspace.undoDepth());
        assertFalse(workspace.undo());

        for (int index = 0; index < 40; index++)
            workspace.append("node" + index, PrimitiveCatalog.Operation.WAIT, List.of(), Map.of());
        assertEquals(ProgramWorkspace.MAX_UNDO_STEPS, workspace.undoDepth());
    }

    @Test void legacyProgramCompilerRunsAValidatedComparisonGraph() throws IOException {
        Program example = loadProgramExample();
        var meta = example.metadata();
        var unsignedMetadata = new dev.stargenesis.api.design.DesignMetadata(meta.id(), meta.schemaVersion(),
                meta.version(), meta.author(), meta.source(), meta.dependencies(), "", meta.permissions());
        Program unsigned = new Program(unsignedMetadata, List.of(
                new Program.Node("five", Program.Operation.CONSTANT, Program.ValueType.NONE, Program.ValueType.INTEGER,
                        Map.of("value", "5")),
                new Program.Node("enough", Program.Operation.COMPARE, Program.ValueType.INTEGER, Program.ValueType.BOOLEAN,
                        Map.of("operator", "gt", "value", "4"))),
                List.of(new Program.Edge("five", "enough")), 8);
        DesignCodec codec = new DesignCodec();
        Program source = (Program) codec.load(codec.seal(unsigned));

        ProgramIR compiled = new ProgramCompiler().compile(source);
        ProgramInterpreter.Execution result = interpreter.execute(compiled, emptyContext(), 8, Set.of());

        assertEquals(new ProgramValue.BooleanValue(true), result.values().get("enough"));
    }

    @Test void compilerRejectsUnsignedProgramObjectsAndUnknownParameters() throws IOException {
        Program example = loadProgramExample();
        var metadata = example.metadata();
        var unsignedMetadata = new dev.stargenesis.api.design.DesignMetadata(metadata.id(), metadata.schemaVersion(),
                metadata.version(), metadata.author(), metadata.source(), metadata.dependencies(), "", metadata.permissions());
        Program unsigned = new Program(unsignedMetadata, example.nodes(), example.edges(), example.maxInstructionsPerStep());
        assertThrows(IllegalArgumentException.class, () -> new ProgramCompiler().compile(unsigned));

        Program withUnknownParameter = new Program(unsignedMetadata, List.of(
                new Program.Node("wait", Program.Operation.WAIT, Program.ValueType.NONE, Program.ValueType.NONE,
                        Map.of("execute", "command"))), List.of(), 8);
        DesignCodec codec = new DesignCodec();
        Program signed = (Program) codec.load(codec.seal(withUnknownParameter));
        assertThrows(IllegalArgumentException.class, () -> new ProgramCompiler().compile(signed));
    }

    private ProgramIR.Instruction instruction(String id, PrimitiveCatalog.Operation operation,
                                              List<String> inputs, Map<String, String> parameters) {
        return new ProgramIR.Instruction(id, operation, inputs, parameters);
    }

    private ProgramInterpreter.ProgramContext emptyContext() {
        return new ProgramInterpreter.ProgramContext(0, 0, Map.of(), Map.of(), Map.of());
    }

    private Program loadProgramExample() throws IOException {
        try (var input = getClass().getResourceAsStream("/examples/program.json")) {
            assertNotNull(input);
            return (Program) new DesignCodec().load(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private String addInput(List<ProgramIR.Instruction> instructions, PrimitiveCatalog.ValueType type, int index) {
        String id = "input" + index;
        switch (type) {
            case INTEGER -> instructions.add(instruction(id, PrimitiveCatalog.Operation.CONSTANT_INTEGER,
                    List.of(), Map.of("value", "2")));
            case BOOLEAN -> instructions.add(instruction(id, PrimitiveCatalog.Operation.CONSTANT_BOOLEAN,
                    List.of(), Map.of("value", "true")));
            case RESOURCE -> instructions.add(instruction(id, PrimitiveCatalog.Operation.SENSE_RESOURCE,
                    List.of(), Map.of("key", "sense_resource")));
            case VECTOR -> {
                String x = id + "x"; String y = id + "y"; String z = id + "z";
                instructions.add(instruction(x, PrimitiveCatalog.Operation.CONSTANT_INTEGER, List.of(), Map.of("value", "1")));
                instructions.add(instruction(y, PrimitiveCatalog.Operation.CONSTANT_INTEGER, List.of(), Map.of("value", "0")));
                instructions.add(instruction(z, PrimitiveCatalog.Operation.CONSTANT_INTEGER, List.of(), Map.of("value", "-1")));
                instructions.add(instruction(id, PrimitiveCatalog.Operation.MAKE_VECTOR, List.of(x, y, z), Map.of()));
            }
            case NONE -> throw new IllegalArgumentException("NONE is not a value input");
        }
        return id;
    }

    private Map<String, String> parametersFor(PrimitiveCatalog.Operation operation) {
        return switch (operation) {
            case CONSTANT_BOOLEAN -> Map.of("value", "true");
            case CONSTANT_INTEGER -> Map.of("value", "2");
            case SENSE_BOOLEAN -> Map.of("key", "sense_boolean");
            case SENSE_INTEGER -> Map.of("key", "sense_integer");
            case SENSE_VECTOR -> Map.of("key", "sense_vector");
            case SENSE_RESOURCE -> Map.of("key", "sense_resource");
            case CLAMP_INTEGER -> Map.of("min", "0", "max", "4");
            case COMPARE_THRESHOLD_INTEGER -> Map.of("operator", "eq", "value", "2");
            case EMIT_SIGNAL, READ_SIGNAL -> Map.of("channel", "channel");
            case RANDOM_INTEGER -> Map.of("bound", "10");
            default -> Map.of();
        };
    }
}
