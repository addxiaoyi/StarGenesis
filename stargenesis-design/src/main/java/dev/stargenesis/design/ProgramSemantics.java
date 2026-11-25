package dev.stargenesis.design;

import java.util.Map;
import java.util.Set;

final class ProgramSemantics {
    private ProgramSemantics() { }

    static void validateNode(Program.Operation operation, Program.ValueType input, Program.ValueType output,
                             Map<String, String> parameters) {
        boolean valid = switch (operation) {
            case SENSE -> input == Program.ValueType.NONE && output != Program.ValueType.NONE;
            case CONSTANT -> input == Program.ValueType.NONE && (output == Program.ValueType.INTEGER
                    || output == Program.ValueType.BOOLEAN || output == Program.ValueType.RESOURCE);
            case VECTOR -> input == Program.ValueType.NONE && output == Program.ValueType.VECTOR;
            case VECTOR_SELECT -> input == Program.ValueType.BOOLEAN && output == Program.ValueType.VECTOR;
            case COMPARE -> input == Program.ValueType.INTEGER && output == Program.ValueType.BOOLEAN;
            case AND, OR, XOR -> input == Program.ValueType.BOOLEAN && output == Program.ValueType.BOOLEAN;
            case NOT -> input == Program.ValueType.BOOLEAN && output == Program.ValueType.BOOLEAN;
            case EQUAL -> input != Program.ValueType.NONE && output == Program.ValueType.BOOLEAN;
            case ADD, SUBTRACT, MULTIPLY, DIVIDE, REMAINDER, MIN, MAX, ABS, NEGATE, CLAMP ->
                    input == Program.ValueType.INTEGER && output == Program.ValueType.INTEGER;
            case SELECT -> input == Program.ValueType.BOOLEAN && output == Program.ValueType.INTEGER;
            case MOVE -> input == Program.ValueType.VECTOR && output == Program.ValueType.NONE;
            case HARVEST, DEPOSIT -> input == Program.ValueType.RESOURCE && output == Program.ValueType.NONE;
            case WAIT -> input == Program.ValueType.NONE && output == Program.ValueType.NONE;
        };
        if (!valid) throw new IllegalArgumentException("Invalid operation type signature for " + operation);
        Set<String> required = switch (operation) {
            case SENSE -> Set.of("field");
            case CONSTANT -> Set.of("value");
            case COMPARE -> Set.of("operator", "value");
            case VECTOR -> Set.of("dx", "dy");
            case VECTOR_SELECT -> Set.of("true_dx", "true_dy", "false_dx", "false_dy");
            case SELECT -> Set.of("true_value", "false_value");
            case CLAMP -> Set.of("min", "max");
            default -> Set.of();
        };
        if (!parameters.keySet().equals(required))
            throw new IllegalArgumentException("Invalid parameters for " + operation);
        if (operation == Program.Operation.SENSE) validateSense(parameters.get("field"), output);
        if (operation == Program.Operation.CONSTANT) validateConstant(parameters.get("value"), output);
        if (operation == Program.Operation.COMPARE) {
            if (!Set.of("EQ", "NE", "LT", "LE", "GT", "GE").contains(parameters.get("operator")))
                throw new IllegalArgumentException("Invalid comparison operator");
            parseLong(parameters.get("value"), "comparison value");
        }
        if (operation == Program.Operation.SELECT) {
            parseLong(parameters.get("true_value"), "true branch value");
            parseLong(parameters.get("false_value"), "false branch value");
        }
        if (operation == Program.Operation.CLAMP) {
            long minimum = parseLong(parameters.get("min"), "clamp minimum");
            long maximum = parseLong(parameters.get("max"), "clamp maximum");
            if (minimum > maximum) throw new IllegalArgumentException("Clamp minimum exceeds maximum");
        }
        if (operation == Program.Operation.VECTOR) validateDirection(parameters.get("dx"), parameters.get("dy"));
        if (operation == Program.Operation.VECTOR_SELECT) {
            validateDirection(parameters.get("true_dx"), parameters.get("true_dy"));
            validateDirection(parameters.get("false_dx"), parameters.get("false_dy"));
        }
    }

    static void validateEdgeCounts(Program.Node node, int count) {
        int expected = switch (node.operation()) {
            case SENSE, CONSTANT, VECTOR, WAIT -> 0;
            case AND, OR, XOR, EQUAL, ADD, SUBTRACT, MULTIPLY, DIVIDE, REMAINDER, MIN, MAX -> 2;
            case NOT, COMPARE, MOVE, HARVEST, DEPOSIT, ABS, NEGATE, CLAMP, SELECT, VECTOR_SELECT -> 1;
        };
        if (count != expected) throw new IllegalArgumentException("Invalid input count for " + node.operation() + " node " + node.id());
    }

    private static void validateSense(String field, Program.ValueType output) {
        Program.ValueType expected = switch (field) {
            case "x", "y", "tick" -> Program.ValueType.INTEGER;
            case "inventory", "shared_stock", "resource_here", "resource_north", "resource_east",
                    "resource_south", "resource_west" -> Program.ValueType.RESOURCE;
            case "blocked_here", "blocked_north", "blocked_east", "blocked_south", "blocked_west" -> Program.ValueType.BOOLEAN;
            default -> throw new IllegalArgumentException("Unsupported sensor field");
        };
        if (output != expected) throw new IllegalArgumentException("Sensor field type mismatch");
    }

    private static void validateConstant(String value, Program.ValueType output) {
        switch (output) {
            case INTEGER -> parseLong(value, "integer constant");
            case RESOURCE -> {
                long amount = parseLong(value, "resource constant");
                if (amount < 0) throw new IllegalArgumentException("Resource constant cannot be negative");
            }
            case BOOLEAN -> {
                if (!"true".equals(value) && !"false".equals(value)) throw new IllegalArgumentException("Invalid boolean constant");
            }
            default -> throw new IllegalArgumentException("Unsupported constant output type");
        }
    }

    private static long parseLong(String value, String label) {
        try { return Long.parseLong(value); }
        catch (NumberFormatException failure) { throw new IllegalArgumentException("Invalid " + label, failure); }
    }

    private static void validateDirection(String dxValue, String dyValue) {
        long dx = parseLong(dxValue, "vector dx");
        long dy = parseLong(dyValue, "vector dy");
        boolean cardinal = (dx == -1 || dx == 1) && dy == 0 || (dy == -1 || dy == 1) && dx == 0;
        if (!cardinal) throw new IllegalArgumentException("Vector must be a cardinal unit direction");
    }
}
