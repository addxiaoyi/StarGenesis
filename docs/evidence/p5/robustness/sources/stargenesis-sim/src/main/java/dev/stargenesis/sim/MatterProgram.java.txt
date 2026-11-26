package dev.stargenesis.sim;

import java.util.Objects;

/** Bounded rule parameters interpreted by the ecology engine for one matter species. */
public record MatterProgram(String species, Role role, int intake, int reproductionMass,
                            int lifespanSteps, int mutationPermille) {
    public MatterProgram {
        Objects.requireNonNull(role, "role");
        if (species == null || !species.matches("[a-z][a-z0-9_]{0,31}")
                || intake < 1 || intake > 8 || reproductionMass < 2 || reproductionMass > 32
                || lifespanSteps < 1 || lifespanSteps > 512 || mutationPermille < 0 || mutationPermille > 500)
            throw new IllegalArgumentException("Matter program is outside its bounded rule set");
    }

    public MatterProgram withParameters(int intake, int reproductionMass, int lifespanSteps, int mutationPermille) {
        return new MatterProgram(species, role, intake, reproductionMass, lifespanSteps, mutationPermille);
    }

    public enum Role {
        PRODUCER, GRAZER, DECOMPOSER
    }
}
