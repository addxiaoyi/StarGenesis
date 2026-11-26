package dev.stargenesis.sim;

import java.util.Objects;

public record FactoryInput(long tick, FactoryAction action) {
    public FactoryInput {
        if (tick < 0) throw new IllegalArgumentException("Negative factory tick");
        Objects.requireNonNull(action, "action");
    }
}
