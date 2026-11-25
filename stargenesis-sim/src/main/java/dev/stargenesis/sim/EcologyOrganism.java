package dev.stargenesis.sim;

import java.util.Objects;

public record EcologyOrganism(long id, EcologyGenome genome, long age, long energy, int habitatCell) {
    public EcologyOrganism(long id, EcologyGenome genome, long age, long energy) {
        this(id, genome, age, energy, 0);
    }

    public EcologyOrganism {
        if (id < 0 || age < 0 || energy < 0
                || habitatCell < 0 || habitatCell >= EcologyWorld.HABITAT_CELL_COUNT) {
            throw new IllegalArgumentException("Invalid ecology organism state");
        }
        Objects.requireNonNull(genome, "genome");
    }
}
