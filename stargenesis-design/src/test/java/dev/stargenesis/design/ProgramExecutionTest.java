package dev.stargenesis.design;

import dev.stargenesis.api.design.DesignId;
import dev.stargenesis.api.design.DesignMetadata;
import dev.stargenesis.api.design.DesignPermissions;
import dev.stargenesis.api.sim.AgentId;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProgramExecutionTest {
    private static final AgentId ACTOR = new AgentId(new UUID(0, 1));
    private final ProgramCompiler compiler = new ProgramCompiler();

    @Test
    void compilerProducesStableDependencyOrderedTypedInstructions() {
        Program.Node later = node("sum", Program.Operation.ADD, Program.ValueType.INTEGER,
                Program.ValueType.INTEGER, Map.of());
        Program.Node right = node("right", Program.Operation.CONSTANT, Program.ValueType.NONE,
                Program.ValueType.INTEGER, Map.of("value", "7"));
        Program.Node left = node("left", Program.Operation.CONSTANT, Program.ValueType.NONE,
                Program.ValueType.INTEGER, Map.of("value", "5"));
        Program first = program(List.of(later, right, left), List.of(edge("left", "sum"), edge("right", "sum")), 16);
        Program second = program(List.of(left, later, right), List.of(edge("left", "sum"), edge("right", "sum")), 16);

        assertEquals(compiler.compile(first), compiler.compile(second));
        assertEquals(List.of("left", "right", "sum"), compiler.compile(first).instructions().stream()
                .map(TypedProgram.Instruction::id).toList());
        assertEquals(25, Program.Operation.values().length);
    }

    @Test
    void orderedInputsPreserveNonCommutativeArithmeticSemantics() {
        Program program = program(List.of(
                node("left", Program.Operation.CONSTANT, Program.ValueType.NONE, Program.ValueType.INTEGER, Map.of("value", "19")),
                node("right", Program.Operation.CONSTANT, Program.ValueType.NONE, Program.ValueType.INTEGER, Map.of("value", "6")),
                node("difference", Program.Operation.SUBTRACT, Program.ValueType.INTEGER, Program.ValueType.INTEGER, Map.of())),
                List.of(edge("left", "difference"), edge("right", "difference")), 16);
        ProgramExecutionSession session = new ProgramExecutionSession(compiler.compile(program), world(1, 1, Map.of()), ACTOR);

        session.continueUntilPause();

        assertEquals("IntegerValue[value=13]", session.result().trace().getLast().output());
    }

    @Test
    void operationSignaturesAndInputArityAreValidatedDuringModelConstruction() {
        assertThrows(IllegalArgumentException.class, () -> node("add", Program.Operation.ADD,
                Program.ValueType.NONE, Program.ValueType.INTEGER, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> node("move", Program.Operation.MOVE,
                Program.ValueType.VECTOR, Program.ValueType.NONE, Map.of("command", "say hello")));
        Program.Node add = node("add", Program.Operation.ADD, Program.ValueType.INTEGER,
                Program.ValueType.INTEGER, Map.of());
        assertThrows(IllegalArgumentException.class, () -> program(List.of(add), List.of(), 16));
    }

    @Test
    void typedIrRejectsInvalidInstructionParametersAndMismatchedDependencies() {
        TypedProgram.Instruction invalidSelect = new TypedProgram.Instruction("select", Program.Operation.SELECT,
                Program.ValueType.BOOLEAN, Program.ValueType.INTEGER, Map.of("true_value", "x", "false_value", "0"),
                List.of(0));
        assertThrows(IllegalArgumentException.class, () -> new TypedProgram(List.of(
                new TypedProgram.Instruction("condition", Program.Operation.CONSTANT, Program.ValueType.NONE,
                        Program.ValueType.BOOLEAN, Map.of("value", "true"), List.of()), invalidSelect),
                Map.of("condition", 0, "select", 1), 4));

        TypedProgram.Instruction wrongType = new TypedProgram.Instruction("move", Program.Operation.MOVE,
                Program.ValueType.VECTOR, Program.ValueType.NONE, Map.of(), List.of(0));
        assertThrows(IllegalArgumentException.class, () -> new TypedProgram(List.of(
                new TypedProgram.Instruction("number", Program.Operation.CONSTANT, Program.ValueType.NONE,
                        Program.ValueType.INTEGER, Map.of("value", "1"), List.of()), wrongType),
                Map.of("number", 0, "move", 1), 4));
    }

    @Test
    void arithmeticExecutesWithTypedValuesAndTrace() {
        Program program = program(List.of(
                node("left", Program.Operation.CONSTANT, Program.ValueType.NONE, Program.ValueType.INTEGER, Map.of("value", "5")),
                node("right", Program.Operation.CONSTANT, Program.ValueType.NONE, Program.ValueType.INTEGER, Map.of("value", "7")),
                node("sum", Program.Operation.ADD, Program.ValueType.INTEGER, Program.ValueType.INTEGER, Map.of())),
                List.of(edge("left", "sum"), edge("right", "sum")), 16);
        ProgramExecutionSession session = new ProgramExecutionSession(compiler.compile(program), world(1, 1, Map.of()), ACTOR);

        session.continueUntilPause();
        ProgramExecutionResult result = session.result();

        assertTrue(result.complete());
        assertEquals("IntegerValue[value=12]", result.trace().getLast().output());
        assertEquals(3, result.instructionsExecuted());
        assertTrue(result.commands().isEmpty());
    }

    @Test
    void conditionSelectsExactlyOneMovementActionFromSnapshot() {
        Program program = program(List.of(
                node("blocked", Program.Operation.SENSE, Program.ValueType.NONE, Program.ValueType.BOOLEAN, Map.of("field", "blocked_north")),
                node("direction", Program.Operation.VECTOR_SELECT, Program.ValueType.BOOLEAN, Program.ValueType.VECTOR,
                        Map.of("true_dx", "1", "true_dy", "0", "false_dx", "0", "false_dy", "-1")),
                node("move", Program.Operation.MOVE, Program.ValueType.VECTOR, Program.ValueType.NONE, Map.of())),
                List.of(edge("blocked", "direction"), edge("direction", "move")), 16);
        ProgramExecutionSession session = new ProgramExecutionSession(compiler.compile(program), world(2, 2,
                Map.of("1:0", new ProgramWorldView.Cell(1, 0, true, 0))), ACTOR);

        session.continueUntilPause();
        ProgramExecutionResult result = session.result();

        assertEquals(1, result.commands().size());
        assertEquals("move", result.commands().getFirst().action());
        assertEquals(Map.of("dx", 1L, "dy", 0L), result.commands().getFirst().arguments());
    }

    @Test
    void selectUsesSchemaParameterNamesAndChoosesOneIntegerBranch() {
        Program program = program(List.of(
                node("blocked", Program.Operation.SENSE, Program.ValueType.NONE, Program.ValueType.BOOLEAN,
                        Map.of("field", "blocked_south")),
                node("choice", Program.Operation.SELECT, Program.ValueType.BOOLEAN, Program.ValueType.INTEGER,
                        Map.of("true_value", "7", "false_value", "3"))),
                List.of(edge("blocked", "choice")), 8);
        ProgramExecutionSession session = new ProgramExecutionSession(compiler.compile(program), world(2, 2,
                Map.of("0:1", new ProgramWorldView.Cell(0, 1, true, 0))), ACTOR);

        session.continueUntilPause();

        assertEquals("IntegerValue[value=7]", session.result().trace().getLast().output());
    }

    @Test
    void resourceActionsEmitOnlyWhenTheProgramValueRequestsThem() {
        Program harvest = program(List.of(
                node("nearby", Program.Operation.SENSE, Program.ValueType.NONE, Program.ValueType.RESOURCE, Map.of("field", "resource_here")),
                node("harvest", Program.Operation.HARVEST, Program.ValueType.RESOURCE, Program.ValueType.NONE, Map.of())),
                List.of(edge("nearby", "harvest")), 8);
        ProgramExecutionSession harvestSession = new ProgramExecutionSession(compiler.compile(harvest), world(2, 1,
                Map.of("0:0", new ProgramWorldView.Cell(0, 0, false, 1))), ACTOR);
        harvestSession.continueUntilPause();
        ProgramExecutionResult result = harvestSession.result();

        assertEquals("gather", result.commands().getFirst().action());
        ProgramExecutionSession emptySession = new ProgramExecutionSession(compiler.compile(harvest), world(2, 1, Map.of()), ACTOR);
        emptySession.continueUntilPause();
        ProgramExecutionResult empty = emptySession.result();
        assertTrue(empty.commands().isEmpty());
    }

    @Test
    void arithmeticOverflowAndDivisionByZeroFailWithoutReturningPartialCommands() {
        Program overflow = arithmeticProgram(Program.Operation.ADD, Long.MAX_VALUE, 1);
        ProgramExecutionSession overflowSession = new ProgramExecutionSession(compiler.compile(overflow), world(1, 1, Map.of()), ACTOR);
        overflowSession.continueUntilPause();
        ProgramExecutionResult overflowResult = overflowSession.result();
        assertEquals("ARITHMETIC_ERROR", overflowResult.status());
        assertTrue(overflowResult.commands().isEmpty());

        Program divide = arithmeticProgram(Program.Operation.DIVIDE, 8, 0);
        ProgramExecutionSession divideSession = new ProgramExecutionSession(compiler.compile(divide), world(1, 1, Map.of()), ACTOR);
        divideSession.continueUntilPause();
        ProgramExecutionResult divideResult = divideSession.result();
        assertEquals("ARITHMETIC_ERROR", divideResult.status());
    }

    @Test
    void instructionBudgetAndBreakpointPauseAreDeterministic() {
        Program program = program(List.of(
                node("a", Program.Operation.CONSTANT, Program.ValueType.NONE, Program.ValueType.INTEGER, Map.of("value", "1")),
                node("b", Program.Operation.CONSTANT, Program.ValueType.NONE, Program.ValueType.INTEGER, Map.of("value", "2"))),
                List.of(), 1);
        ProgramExecutionSession budgeted = new ProgramExecutionSession(compiler.compile(program), world(1, 1, Map.of()), ACTOR);
        budgeted.continueUntilPause();
        ProgramExecutionResult exhausted = budgeted.result();
        assertEquals("INSTRUCTION_BUDGET_EXHAUSTED", exhausted.status());

        Program unlimited = program(List.of(
                node("a", Program.Operation.CONSTANT, Program.ValueType.NONE, Program.ValueType.INTEGER, Map.of("value", "1")),
                node("b", Program.Operation.CONSTANT, Program.ValueType.NONE, Program.ValueType.INTEGER, Map.of("value", "2"))),
                List.of(), 8);
        ProgramExecutionSession debugger = new ProgramExecutionSession(compiler.compile(unlimited), world(1, 1, Map.of()), ACTOR);
        debugger.addBreakpoint("b");
        assertEquals("BREAKPOINT", debugger.continueUntilPause().status());
        assertEquals("EXECUTED", debugger.stepInstruction().status());
        assertTrue(debugger.result().complete());
    }

    @Test
    void actorMustBelongToTheSuppliedImmutableWorldView() {
        assertThrows(IllegalArgumentException.class, () -> new ProgramExecutionSession(
                compiler.compile(program(List.of(node("wait", Program.Operation.WAIT, Program.ValueType.NONE,
                        Program.ValueType.NONE, Map.of())), List.of(), 1)), world(1, 1, Map.of()),
                new AgentId(new UUID(0, 2))));
    }

    private static Program arithmeticProgram(Program.Operation operation, long first, long second) {
        return program(List.of(
                node("left", Program.Operation.CONSTANT, Program.ValueType.NONE, Program.ValueType.INTEGER, Map.of("value", Long.toString(first))),
                node("right", Program.Operation.CONSTANT, Program.ValueType.NONE, Program.ValueType.INTEGER, Map.of("value", Long.toString(second))),
                node("result", operation, Program.ValueType.INTEGER, Program.ValueType.INTEGER, Map.of())),
                List.of(edge("left", "result"), edge("right", "result")), 16);
    }

    private static ProgramWorldView world(int width, int height, Map<String, ProgramWorldView.Cell> overrides) {
        List<ProgramWorldView.Cell> cells = new ArrayList<>();
        for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
            ProgramWorldView.Cell cell = overrides.getOrDefault(x + ":" + y, new ProgramWorldView.Cell(x, y, false, 0));
            cells.add(cell);
        }
        return new ProgramWorldView(width, height, 12, 0, cells,
                Map.of(ACTOR, new ProgramWorldView.Agent(ACTOR, 0, 0, 0)));
    }

    private static Program.Node node(String id, Program.Operation operation, Program.ValueType input,
                                     Program.ValueType output, Map<String, String> parameters) {
        return new Program.Node(id, operation, input, output, parameters);
    }

    private static Program.Edge edge(String from, String to) { return new Program.Edge(from, to); }

    private static Program program(List<Program.Node> nodes, List<Program.Edge> edges, int budget) {
        DesignMetadata metadata = new DesignMetadata(new DesignId("genesis:test-program"), 1, "1.0.0",
                ACTOR.value(), "test:program", List.of(), "0".repeat(64), new DesignPermissions(true, true, Set.of()));
        return new Program(metadata, nodes, edges, budget);
    }
}
