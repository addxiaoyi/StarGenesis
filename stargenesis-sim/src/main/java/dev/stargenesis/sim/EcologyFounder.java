package dev.stargenesis.sim;

import java.util.Objects;

public record EcologyFounder(EcologyGenome genome, long initialEnergy) {
    public EcologyFounder {
        Objects.requireNonNull(genome, "genome");
        if (initialEnergy < 0 || initialEnergy > 100_000) {
            throw new IllegalArgumentException("Invalid founder energy");
        }
    }
}
