package dev.stargenesis.design;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

public final class ProgramCompiler {
    public TypedProgram compile(Program program) {
        java.util.Objects.requireNonNull(program, "program");
        Map<String, Program.Node> nodes = new HashMap<>();
        Map<String, List<String>> dependencies = new HashMap<>();
        Map<String, List<String>> dependents = new HashMap<>();
        Map<String, Integer> indegree = new HashMap<>();
        program.nodes().forEach(node -> {
            nodes.put(node.id(), node);
            dependencies.put(node.id(), new ArrayList<>());
            indegree.put(node.id(), 0);
        });
        for (Program.Edge edge : program.edges()) {
            dependencies.get(edge.to()).add(edge.from());
            dependents.computeIfAbsent(edge.from(), ignored -> new ArrayList<>()).add(edge.to());
            indegree.merge(edge.to(), 1, Integer::sum);
        }
        dependents.values().forEach(values -> values.sort(Comparator.naturalOrder()));
        nodes.values().forEach(node -> ProgramSemantics.validateEdgeCounts(node, dependencies.get(node.id()).size()));

        PriorityQueue<String> ready = new PriorityQueue<>();
        indegree.forEach((id, count) -> { if (count == 0) ready.add(id); });
        List<String> order = new ArrayList<>(nodes.size());
        while (!ready.isEmpty()) {
            String current = ready.remove();
            order.add(current);
            for (String dependent : dependents.getOrDefault(current, List.of())) {
                if (indegree.merge(dependent, -1, Integer::sum) == 0) ready.add(dependent);
            }
        }
        if (order.size() != nodes.size()) throw new IllegalArgumentException("Cannot compile a cyclic program");

        Map<String, Integer> indexes = new HashMap<>();
        for (int index = 0; index < order.size(); index++) indexes.put(order.get(index), index);
        List<TypedProgram.Instruction> instructions = order.stream().map(id -> {
            Program.Node node = nodes.get(id);
            List<Integer> inputs = dependencies.get(id).stream().map(indexes::get).toList();
            return new TypedProgram.Instruction(id, node.operation(), node.inputType(), node.outputType(),
                    node.parameters(), inputs);
        }).toList();
        return new TypedProgram(instructions, indexes, program.maxInstructionsPerStep());
    }
}
