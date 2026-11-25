package dev.stargenesis.design;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

public record TypedProgram(List<Instruction> instructions, Map<String, Integer> indexes,
                           int maxInstructionsPerStep) {
    public TypedProgram {
        instructions = List.copyOf(instructions);
        indexes = Map.copyOf(new TreeMap<>(indexes));
        if (instructions.isEmpty() || instructions.size() > 256 || maxInstructionsPerStep < 1
                || maxInstructionsPerStep > 4096 || indexes.size() != instructions.size())
            throw new IllegalArgumentException("Invalid typed program bounds");
        Set<String> ids = new HashSet<>();
        for (int index = 0; index < instructions.size(); index++) {
            Instruction instruction = Objects.requireNonNull(instructions.get(index), "instruction");
            if (!ids.add(instruction.id()) || !Integer.valueOf(index).equals(indexes.get(instruction.id())))
                throw new IllegalArgumentException("Invalid typed program index");
            Program.Node checked = new Program.Node(instruction.id(), instruction.operation(), instruction.inputType(),
                    instruction.outputType(), instruction.parameters());
            ProgramSemantics.validateEdgeCounts(checked, instruction.inputs().size());
            Set<Integer> uniqueInputs = new HashSet<>();
            for (int dependency : instruction.inputs()) {
                if (dependency < 0 || dependency >= index) throw new IllegalArgumentException("Typed IR is not in dependency order");
                if (!uniqueInputs.add(dependency)) throw new IllegalArgumentException("Typed IR contains a duplicate dependency");
                if (instructions.get(dependency).outputType() != instruction.inputType())
                    throw new IllegalArgumentException("Typed IR dependency type mismatch");
            }
        }
    }

    public record Instruction(String id, Program.Operation operation, Program.ValueType inputType,
                              Program.ValueType outputType, Map<String, String> parameters, List<Integer> inputs) {
        public Instruction {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(inputType, "inputType");
            Objects.requireNonNull(outputType, "outputType");
            parameters = Map.copyOf(Objects.requireNonNull(parameters, "parameters"));
            inputs = List.copyOf(Objects.requireNonNull(inputs, "inputs"));
        }
    }
}
