package dev.stargenesis.sim;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Program updates are applied at the beginning of their recorded logical step. */
public record EcologyInput(long tick, Map<String, MatterProgram> programUpdates) {
    public EcologyInput {
        if (tick < 0) throw new IllegalArgumentException("Negative ecology input tick");
        Objects.requireNonNull(programUpdates, "programUpdates");
        if (programUpdates.size() > 3) throw new IllegalArgumentException("Matter program update budget exceeded");
        TreeMap<String, MatterProgram> copy = new TreeMap<>();
        programUpdates.forEach((species, program) -> {
            Objects.requireNonNull(program, "program");
            if (!program.species().equals(species) || copy.putIfAbsent(species, program) != null)
                throw new IllegalArgumentException("Matter program update identity mismatch");
        });
        programUpdates = Collections.unmodifiableMap(copy);
    }

    public static EcologyInput idle(long tick) {
        return new EcologyInput(tick, Map.of());
    }
}
