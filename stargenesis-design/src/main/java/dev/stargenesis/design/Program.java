package dev.stargenesis.design;

import dev.stargenesis.api.design.*;
import java.util.*;

/** Persisted schema-v1 graph. Compilation to the transient typed IR is explicit. */
public record Program(DesignMetadata metadata, List<Node> nodes, List<Edge> edges,
                      int maxInstructionsPerStep) implements DesignDocument {
    public Program {
        Checks.metadata(metadata); nodes = List.copyOf(nodes); edges = List.copyOf(edges);
        Checks.range(nodes.size(), 1, 256, "nodes"); Checks.range(edges.size(), 0, 512, "edges");
        Checks.range(maxInstructionsPerStep, 1, 4096, "maxInstructionsPerStep");
        Checks.unique(nodes, Node::id, "node ID");
        Map<String, Node> indexed = new HashMap<>(); nodes.forEach(n -> indexed.put(n.id(), n));
        Map<String, Integer> indegree = new HashMap<>(); nodes.forEach(n -> indegree.put(n.id(), 0));
        Map<String, List<String>> adjacency = new HashMap<>(); Set<String> pairs = new HashSet<>();
        for (Edge edge : edges) {
            Node from = indexed.get(edge.from()), to = indexed.get(edge.to());
            if (from == null || to == null) Checks.fail("Unknown edge endpoint");
            if (!pairs.add(edge.from()+"/"+edge.to())) Checks.fail("Duplicate edge");
            if (from.outputType() != to.inputType()) Checks.fail("Edge type mismatch");
            indegree.merge(edge.to(), 1, Integer::sum);
            adjacency.computeIfAbsent(edge.from(), key -> new ArrayList<>()).add(edge.to());
        }
        ArrayDeque<String> ready = new ArrayDeque<>();
        nodes.stream().map(Node::id).filter(id -> indegree.get(id) == 0).forEach(ready::add);
        int visited = 0;
        while (!ready.isEmpty()) {
            String id = ready.remove(); visited++;
            for (String next : adjacency.getOrDefault(id, List.of())) {
                if (indegree.merge(next, -1, Integer::sum) == 0) ready.add(next);
            }
        }
        if (visited != nodes.size()) Checks.fail("Unbounded graph cycle rejected; explicit bounded iteration is future IR");
    }
    @Override public DesignKind kind() { return DesignKind.PROGRAM; }
    public enum ValueType { NONE, BOOLEAN, INTEGER, VECTOR, RESOURCE }
    public enum Operation { SENSE, CONSTANT, COMPARE, AND, NOT, MOVE, HARVEST, DEPOSIT, WAIT }
    public record Node(String id, Operation operation, ValueType inputType, ValueType outputType,
                       Map<String, String> parameters) {
        public Node {
            Checks.token(id); Objects.requireNonNull(operation); Objects.requireNonNull(inputType);
            Objects.requireNonNull(outputType); parameters = Map.copyOf(parameters);
            boolean signatureValid = switch (operation) {
                case SENSE -> inputType == ValueType.NONE && outputType != ValueType.NONE;
                case CONSTANT -> inputType == ValueType.NONE
                        && (outputType == ValueType.INTEGER || outputType == ValueType.BOOLEAN);
                case COMPARE -> inputType == ValueType.INTEGER && outputType == ValueType.BOOLEAN;
                case AND, NOT -> inputType == ValueType.BOOLEAN && outputType == ValueType.BOOLEAN;
                case MOVE -> inputType == ValueType.VECTOR && outputType == ValueType.NONE;
                case HARVEST, DEPOSIT -> inputType == ValueType.RESOURCE && outputType == ValueType.NONE;
                case WAIT -> inputType == ValueType.NONE && outputType == ValueType.NONE;
            };
            if (!signatureValid) Checks.fail("Invalid operation type signature");
            Checks.range(parameters.size(), 0, 16, "parameters");
            parameters.forEach((key, value) -> {
                Checks.token(key);
                if (value.length() > 128 || value.chars().anyMatch(c -> c < 32)) Checks.fail("Invalid parameter value");
            });
        }
    }
    public record Edge(String from, String to) {
        public Edge { Checks.token(from); Checks.token(to); }
    }
}
