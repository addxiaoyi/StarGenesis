package dev.stargenesis.design;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/** Compiles the original declarative Program graph into the typed P3 execution IR. */
public final class ProgramCompiler {
    public ProgramIR compile(Program program) {
        new DesignCodec().write(program);
        Map<String, Program.Node> nodes = new HashMap<>();
        Map<String, List<String>> inputs = new HashMap<>();
        Map<String, List<String>> outgoing = new HashMap<>();
        Map<String, Integer> indegree = new HashMap<>();
        for (Program.Node node : program.nodes()) {
            nodes.put(node.id(), node);
            inputs.put(node.id(), new ArrayList<>());
            indegree.put(node.id(), 0);
        }
        for (Program.Edge edge : program.edges()) {
            inputs.get(edge.to()).add(edge.from());
            outgoing.computeIfAbsent(edge.from(), ignored -> new ArrayList<>()).add(edge.to());
            indegree.merge(edge.to(), 1, Integer::sum);
        }

        PriorityQueue<String> ready = new PriorityQueue<>();
        indegree.forEach((id, degree) -> { if (degree == 0) ready.add(id); });
        List<ProgramIR.Instruction> instructions = new ArrayList<>();
        while (!ready.isEmpty()) {
            String id = ready.remove();
            Program.Node node = nodes.get(id);
            Primitive primitive = primitive(node);
            List<String> dependencies = inputs.get(id).stream().sorted().toList();
            if (dependencies.size() != PrimitiveCatalog.descriptor(primitive.operation()).inputTypes().size())
                throw new IllegalArgumentException("Program input count does not match operation " + id);
            instructions.add(new ProgramIR.Instruction(id, primitive.operation(), dependencies, primitive.parameters()));
            for (String next : outgoing.getOrDefault(id, List.of()))
                if (indegree.merge(next, -1, Integer::sum) == 0) ready.add(next);
        }
        if (instructions.size() != nodes.size()) throw new IllegalArgumentException("Program contains an unsupported cycle");
        return new ProgramIR(instructions, program.maxInstructionsPerStep());
    }

    private Primitive primitive(Program.Node node) {
        Map<String, String> parameters = node.parameters();
        return switch (node.operation()) {
            case SENSE -> {
                requireParameters(parameters, Set.of("key"));
                String key = required(parameters, "key");
                PrimitiveCatalog.Operation operation = switch (node.outputType()) {
                    case BOOLEAN -> PrimitiveCatalog.Operation.SENSE_BOOLEAN;
                    case INTEGER -> PrimitiveCatalog.Operation.SENSE_INTEGER;
                    case VECTOR -> PrimitiveCatalog.Operation.SENSE_VECTOR;
                    case RESOURCE -> PrimitiveCatalog.Operation.SENSE_RESOURCE;
                    case NONE -> throw new IllegalArgumentException("SENSE requires a value output");
                };
                yield new Primitive(operation, Map.of("key", key));
            }
            case CONSTANT -> {
                requireParameters(parameters, Set.of("value"));
                String value = required(parameters, "value");
                PrimitiveCatalog.Operation operation = switch (node.outputType()) {
                    case BOOLEAN -> PrimitiveCatalog.Operation.CONSTANT_BOOLEAN;
                    case INTEGER -> PrimitiveCatalog.Operation.CONSTANT_INTEGER;
                    default -> throw new IllegalArgumentException("CONSTANT supports boolean or integer values");
                };
                yield new Primitive(operation, Map.of("value", value));
            }
            case COMPARE -> {
                requireParameters(parameters, Set.of("operator", "value"));
                String operator = required(parameters, "operator");
                String value = required(parameters, "value");
                if (!Set.of("eq", "lt", "gt").contains(operator))
                    throw new IllegalArgumentException("COMPARE operator must be eq, lt, or gt");
                yield new Primitive(PrimitiveCatalog.Operation.COMPARE_THRESHOLD_INTEGER,
                        Map.of("operator", operator, "value", value));
            }
            case AND -> noParameters(parameters, PrimitiveCatalog.Operation.AND_BOOLEAN);
            case NOT -> noParameters(parameters, PrimitiveCatalog.Operation.NOT_BOOLEAN);
            case MOVE -> noParameters(parameters, PrimitiveCatalog.Operation.MOVE);
            case HARVEST -> noParameters(parameters, PrimitiveCatalog.Operation.HARVEST);
            case DEPOSIT -> noParameters(parameters, PrimitiveCatalog.Operation.DEPOSIT);
            case WAIT -> noParameters(parameters, PrimitiveCatalog.Operation.WAIT);
        };
    }

    private Primitive noParameters(Map<String, String> parameters, PrimitiveCatalog.Operation operation) {
        requireParameters(parameters, Set.of());
        return new Primitive(operation, Map.of());
    }

    private void requireParameters(Map<String, String> parameters, Set<String> expected) {
        if (!parameters.keySet().equals(expected)) throw new IllegalArgumentException("Unexpected Program parameters");
    }

    private String required(Map<String, String> parameters, String key) {
        String value = parameters.get(key);
        if (value == null) throw new IllegalArgumentException("Missing Program parameter " + key);
        return value;
    }

    private record Primitive(PrimitiveCatalog.Operation operation, Map<String, String> parameters) { }
}
