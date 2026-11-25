package dev.stargenesis.design;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable, topologically ordered typed IR. Every dependency points to an earlier instruction. */
public record ProgramIR(List<Instruction> instructions, int maxInstructionsPerStep) {
    public static final int MAX_INSTRUCTIONS = 256;
    public static final int MAX_BUDGET = 4096;

    public ProgramIR {
        instructions = List.copyOf(instructions);
        if (instructions.isEmpty() || instructions.size() > MAX_INSTRUCTIONS)
            throw new IllegalArgumentException("IR instruction count outside 1.." + MAX_INSTRUCTIONS);
        if (maxInstructionsPerStep < 1 || maxInstructionsPerStep > MAX_BUDGET)
            throw new IllegalArgumentException("IR execution budget outside 1.." + MAX_BUDGET);

        Map<String, Instruction> prior = new HashMap<>();
        for (Instruction instruction : instructions) {
            if (prior.containsKey(instruction.id())) throw new IllegalArgumentException("Duplicate IR instruction ID");
            PrimitiveCatalog.Descriptor primitive = PrimitiveCatalog.descriptor(instruction.operation());
            if (instruction.inputs().size() != primitive.inputTypes().size())
                throw new IllegalArgumentException("Wrong input count for " + instruction.id());
            for (int index = 0; index < instruction.inputs().size(); index++) {
                Instruction dependency = prior.get(instruction.inputs().get(index));
                if (dependency == null) throw new IllegalArgumentException("IR input must reference an earlier instruction");
                if (dependency.outputType() != primitive.inputTypes().get(index))
                    throw new IllegalArgumentException("Typed IR input mismatch at " + instruction.id());
            }
            validateParameters(instruction, primitive);
            prior.put(instruction.id(), instruction);
        }
    }

    private static void validateParameters(Instruction instruction, PrimitiveCatalog.Descriptor primitive) {
        if (!instruction.parameters().keySet().equals(primitive.parameters().keySet()))
            throw new IllegalArgumentException("Invalid parameters for " + instruction.operation());
        primitive.parameters().forEach((name, type) -> {
            String value = instruction.parameters().get(name);
            switch (type) {
                case TOKEN -> {
                    if (!value.matches("[a-z][a-z0-9_:-]{0,63}"))
                        throw new IllegalArgumentException("Invalid token parameter " + name);
                }
                case BOOLEAN -> {
                    if (!value.equals("true") && !value.equals("false"))
                        throw new IllegalArgumentException("Invalid boolean parameter " + name);
                }
                case INTEGER -> {
                    try { Long.parseLong(value); }
                    catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid integer parameter " + name, invalid); }
                }
            }
        });
        if (instruction.operation() == PrimitiveCatalog.Operation.CLAMP_INTEGER
                && Long.parseLong(instruction.parameters().get("min")) > Long.parseLong(instruction.parameters().get("max")))
            throw new IllegalArgumentException("Clamp minimum exceeds maximum");
        if (instruction.operation() == PrimitiveCatalog.Operation.RANDOM_INTEGER
                && (Long.parseLong(instruction.parameters().get("bound")) < 1
                || Long.parseLong(instruction.parameters().get("bound")) > 1_000_000))
            throw new IllegalArgumentException("Random bound outside 1..1000000");
    }

    public record Instruction(String id, PrimitiveCatalog.Operation operation, List<String> inputs,
                              Map<String, String> parameters) {
        public Instruction {
            if (id == null || !id.matches("[a-z][a-z0-9_:-]{0,63}"))
                throw new IllegalArgumentException("Invalid IR instruction ID");
            Objects.requireNonNull(operation, "operation");
            inputs = List.copyOf(inputs);
            parameters = Map.copyOf(parameters);
            if (inputs.stream().anyMatch(input -> input == null || !input.matches("[a-z][a-z0-9_:-]{0,63}")))
                throw new IllegalArgumentException("Invalid IR dependency ID");
            if (parameters.values().stream().anyMatch(Objects::isNull))
                throw new IllegalArgumentException("Null IR parameter");
        }

        public PrimitiveCatalog.ValueType outputType() {
            return PrimitiveCatalog.descriptor(operation).outputType();
        }
    }
}
