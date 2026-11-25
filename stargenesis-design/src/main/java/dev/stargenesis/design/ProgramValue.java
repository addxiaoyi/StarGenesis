package dev.stargenesis.design;

import java.util.Objects;

/** Closed runtime value types accepted by the typed program interpreter. */
public sealed interface ProgramValue permits ProgramValue.BooleanValue, ProgramValue.IntegerValue,
        ProgramValue.VectorValue, ProgramValue.ResourceValue, ProgramValue.NoneValue {
    PrimitiveCatalog.ValueType type();

    record BooleanValue(boolean value) implements ProgramValue {
        @Override public PrimitiveCatalog.ValueType type() { return PrimitiveCatalog.ValueType.BOOLEAN; }
    }

    record IntegerValue(long value) implements ProgramValue {
        @Override public PrimitiveCatalog.ValueType type() { return PrimitiveCatalog.ValueType.INTEGER; }
    }

    record VectorValue(int x, int y, int z) implements ProgramValue {
        public VectorValue {
            if (x < -16 || x > 16 || y < -16 || y > 16 || z < -16 || z > 16)
                throw new IllegalArgumentException("Vector component outside -16..16");
        }
        @Override public PrimitiveCatalog.ValueType type() { return PrimitiveCatalog.ValueType.VECTOR; }
    }

    record ResourceValue(String id) implements ProgramValue {
        public ResourceValue {
            Objects.requireNonNull(id, "id");
            if (!id.matches("[a-z][a-z0-9_:-]{0,63}")) throw new IllegalArgumentException("Invalid resource ID");
        }
        @Override public PrimitiveCatalog.ValueType type() { return PrimitiveCatalog.ValueType.RESOURCE; }
    }

    enum NoneValue implements ProgramValue {
        INSTANCE;
        @Override public PrimitiveCatalog.ValueType type() { return PrimitiveCatalog.ValueType.NONE; }
    }
}
