package dev.stargenesis.sim;

import dev.stargenesis.api.design.DesignId;

import java.util.Objects;

/** A bounded matter type with a fixed integer mass used to validate reaction balance. */
public record MatterSpecies(DesignId id, long massPerUnit) {
    public static final long MAX_MASS_PER_UNIT = 1_000_000;

    public MatterSpecies {
        Objects.requireNonNull(id, "id");
        if (massPerUnit < 1 || massPerUnit > MAX_MASS_PER_UNIT) {
            throw new IllegalArgumentException("Matter species mass is outside the finite bounds");
        }
    }
}
