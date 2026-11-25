package dev.stargenesis.sim;

/** Immutable matter inventory for one cell in the ecology habitat. */
public record EcologyHabitatCell(int id, long nutrient, long waste) {
    public EcologyHabitatCell {
        if (id < 0 || id >= EcologyWorld.HABITAT_CELL_COUNT || nutrient < 0 || waste < 0) {
            throw new IllegalArgumentException("Invalid ecology habitat cell");
        }
    }
}
