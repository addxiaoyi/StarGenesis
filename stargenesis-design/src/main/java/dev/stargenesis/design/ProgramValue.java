package dev.stargenesis.design;

import java.util.Objects;

public sealed interface ProgramValue permits ProgramValue.None, ProgramValue.BooleanValue,
        ProgramValue.IntegerValue, ProgramValue.VectorValue, ProgramValue.ResourceValue {
    Program.ValueType type();

    record None() implements ProgramValue {
        @Override public Program.ValueType type() { return Program.ValueType.NONE; }
    }

    record BooleanValue(boolean value) implements ProgramValue {
        @Override public Program.ValueType type() { return Program.ValueType.BOOLEAN; }
    }

    record IntegerValue(long value) implements ProgramValue {
        @Override public Program.ValueType type() { return Program.ValueType.INTEGER; }
    }

    record VectorValue(int dx, int dy) implements ProgramValue {
        public VectorValue {
            boolean cardinal = (dx == -1 || dx == 1) && dy == 0 || (dy == -1 || dy == 1) && dx == 0;
            if (!cardinal) throw new IllegalArgumentException("Vector must be a cardinal unit direction");
        }
        @Override public Program.ValueType type() { return Program.ValueType.VECTOR; }
    }

    record ResourceValue(long amount) implements ProgramValue {
        public ResourceValue {
            if (amount < 0) throw new IllegalArgumentException("Resource values cannot be negative");
        }
        @Override public Program.ValueType type() { return Program.ValueType.RESOURCE; }
    }
}
