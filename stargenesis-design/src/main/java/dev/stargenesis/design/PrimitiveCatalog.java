package dev.stargenesis.design;

import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Closed primitive catalogue for the typed IR. */
public final class PrimitiveCatalog {
    private static final Map<Operation, Descriptor> DESCRIPTORS = createDescriptors();

    private PrimitiveCatalog() { }

    public static List<Descriptor> all() {
        return DESCRIPTORS.values().stream().sorted((left, right) -> left.operation().compareTo(right.operation())).toList();
    }

    public static Descriptor descriptor(Operation operation) {
        Descriptor descriptor = DESCRIPTORS.get(operation);
        if (descriptor == null) throw new IllegalArgumentException("Unsupported primitive " + operation);
        return descriptor;
    }

    private static Map<Operation, Descriptor> createDescriptors() {
        EnumMap<Operation, Descriptor> descriptors = new EnumMap<>(Operation.class);
        add(descriptors, Operation.CONSTANT_BOOLEAN, List.of(), ValueType.BOOLEAN, parameters("value", ParameterType.BOOLEAN));
        add(descriptors, Operation.CONSTANT_INTEGER, List.of(), ValueType.INTEGER, parameters("value", ParameterType.INTEGER));
        add(descriptors, Operation.SENSE_BOOLEAN, List.of(), ValueType.BOOLEAN, parameters("key", ParameterType.TOKEN));
        add(descriptors, Operation.SENSE_INTEGER, List.of(), ValueType.INTEGER, parameters("key", ParameterType.TOKEN));
        add(descriptors, Operation.SENSE_VECTOR, List.of(), ValueType.VECTOR, parameters("key", ParameterType.TOKEN));
        add(descriptors, Operation.SENSE_RESOURCE, List.of(), ValueType.RESOURCE, parameters("key", ParameterType.TOKEN));
        add(descriptors, Operation.ADD_INTEGER, ints(2), ValueType.INTEGER, Map.of());
        add(descriptors, Operation.SUBTRACT_INTEGER, ints(2), ValueType.INTEGER, Map.of());
        add(descriptors, Operation.MULTIPLY_INTEGER, ints(2), ValueType.INTEGER, Map.of());
        add(descriptors, Operation.DIVIDE_INTEGER, ints(2), ValueType.INTEGER, Map.of());
        add(descriptors, Operation.MINIMUM_INTEGER, ints(2), ValueType.INTEGER, Map.of());
        add(descriptors, Operation.MAXIMUM_INTEGER, ints(2), ValueType.INTEGER, Map.of());
        add(descriptors, Operation.CLAMP_INTEGER, ints(1), ValueType.INTEGER,
                parameters("min", ParameterType.INTEGER, "max", ParameterType.INTEGER));
        add(descriptors, Operation.EQUAL_INTEGER, ints(2), ValueType.BOOLEAN, Map.of());
        add(descriptors, Operation.COMPARE_THRESHOLD_INTEGER, ints(1), ValueType.BOOLEAN,
                parameters("operator", ParameterType.TOKEN, "value", ParameterType.INTEGER));
        add(descriptors, Operation.LESS_THAN_INTEGER, ints(2), ValueType.BOOLEAN, Map.of());
        add(descriptors, Operation.GREATER_THAN_INTEGER, ints(2), ValueType.BOOLEAN, Map.of());
        add(descriptors, Operation.AND_BOOLEAN, bools(2), ValueType.BOOLEAN, Map.of());
        add(descriptors, Operation.OR_BOOLEAN, bools(2), ValueType.BOOLEAN, Map.of());
        add(descriptors, Operation.NOT_BOOLEAN, bools(1), ValueType.BOOLEAN, Map.of());
        add(descriptors, Operation.XOR_BOOLEAN, bools(2), ValueType.BOOLEAN, Map.of());
        add(descriptors, Operation.SELECT_INTEGER, List.of(ValueType.BOOLEAN, ValueType.INTEGER, ValueType.INTEGER), ValueType.INTEGER, Map.of());
        add(descriptors, Operation.MAKE_VECTOR, ints(3), ValueType.VECTOR, Map.of());
        add(descriptors, Operation.VECTOR_X, List.of(ValueType.VECTOR), ValueType.INTEGER, Map.of());
        add(descriptors, Operation.VECTOR_Y, List.of(ValueType.VECTOR), ValueType.INTEGER, Map.of());
        add(descriptors, Operation.VECTOR_Z, List.of(ValueType.VECTOR), ValueType.INTEGER, Map.of());
        add(descriptors, Operation.DISTANCE_SQUARED, List.of(ValueType.VECTOR, ValueType.VECTOR), ValueType.INTEGER, Map.of());
        add(descriptors, Operation.HAS_RESOURCE, List.of(ValueType.RESOURCE), ValueType.BOOLEAN, Map.of());
        add(descriptors, Operation.MOVE, List.of(ValueType.VECTOR), ValueType.NONE, Map.of());
        add(descriptors, Operation.HARVEST, List.of(ValueType.RESOURCE), ValueType.NONE, Map.of());
        add(descriptors, Operation.DEPOSIT, List.of(ValueType.RESOURCE), ValueType.NONE, Map.of());
        add(descriptors, Operation.WAIT, List.of(), ValueType.NONE, Map.of());
        add(descriptors, Operation.EMIT_SIGNAL, ints(1), ValueType.NONE, parameters("channel", ParameterType.TOKEN));
        add(descriptors, Operation.READ_SIGNAL, List.of(), ValueType.INTEGER, parameters("channel", ParameterType.TOKEN));
        add(descriptors, Operation.RANDOM_INTEGER, List.of(), ValueType.INTEGER, parameters("bound", ParameterType.INTEGER));
        return Collections.unmodifiableMap(descriptors);
    }

    private static void add(Map<Operation, Descriptor> descriptors, Operation operation, List<ValueType> inputs,
                            ValueType output, Map<String, ParameterType> parameters) {
        if (descriptors.put(operation, new Descriptor(operation, inputs, output, 1, parameters)) != null)
            throw new IllegalStateException("Duplicate primitive descriptor");
    }

    private static List<ValueType> ints(int count) { return Collections.nCopies(count, ValueType.INTEGER); }
    private static List<ValueType> bools(int count) { return Collections.nCopies(count, ValueType.BOOLEAN); }

    private static Map<String, ParameterType> parameters(Object... pairs) {
        LinkedHashMap<String, ParameterType> result = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2)
            result.put((String) pairs[index], (ParameterType) pairs[index + 1]);
        return Collections.unmodifiableMap(result);
    }

    public enum Operation {
        CONSTANT_BOOLEAN, CONSTANT_INTEGER, SENSE_BOOLEAN, SENSE_INTEGER, SENSE_VECTOR, SENSE_RESOURCE,
        ADD_INTEGER, SUBTRACT_INTEGER, MULTIPLY_INTEGER, DIVIDE_INTEGER, MINIMUM_INTEGER, MAXIMUM_INTEGER,
        CLAMP_INTEGER, EQUAL_INTEGER, COMPARE_THRESHOLD_INTEGER, LESS_THAN_INTEGER, GREATER_THAN_INTEGER, AND_BOOLEAN, OR_BOOLEAN,
        NOT_BOOLEAN, XOR_BOOLEAN, SELECT_INTEGER, MAKE_VECTOR, VECTOR_X, VECTOR_Y, VECTOR_Z, DISTANCE_SQUARED,
        HAS_RESOURCE, MOVE, HARVEST, DEPOSIT, WAIT, EMIT_SIGNAL, READ_SIGNAL, RANDOM_INTEGER
    }

    public enum ValueType { NONE, BOOLEAN, INTEGER, VECTOR, RESOURCE }
    public enum ParameterType { BOOLEAN, INTEGER, TOKEN }

    public record Descriptor(Operation operation, List<ValueType> inputTypes, ValueType outputType,
                             int cost, Map<String, ParameterType> parameters) {
        public Descriptor {
            inputTypes = List.copyOf(inputTypes);
            parameters = Map.copyOf(parameters);
            if (cost < 1) throw new IllegalArgumentException("Primitive cost must be positive");
        }
    }
}
