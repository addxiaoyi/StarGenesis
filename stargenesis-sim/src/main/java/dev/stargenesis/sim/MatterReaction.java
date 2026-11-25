package dev.stargenesis.sim;

import dev.stargenesis.api.design.DesignId;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** A finite stoichiometric conversion with no executable player code. */
public record MatterReaction(DesignId id, Map<DesignId, Integer> inputs,
                             Map<DesignId, Integer> outputs, int maxFiringsPerStep) {
    public static final int MAX_SPECIES_PER_SIDE = 16;
    public static final int MAX_UNITS_PER_SPECIES = 1_000;
    public static final int MAX_FIRINGS_PER_REACTION = 1_024;

    public MatterReaction {
        Objects.requireNonNull(id, "id");
        inputs = copyStoichiometry(inputs, "inputs");
        outputs = copyStoichiometry(outputs, "outputs");
        if (maxFiringsPerStep < 1 || maxFiringsPerStep > MAX_FIRINGS_PER_REACTION) {
            throw new IllegalArgumentException("Reaction firing limit is outside the finite bounds");
        }
    }

    private static Map<DesignId, Integer> copyStoichiometry(Map<DesignId, Integer> source, String label) {
        Objects.requireNonNull(source, label);
        if (source.isEmpty() || source.size() > MAX_SPECIES_PER_SIDE) {
            throw new IllegalArgumentException("Reaction " + label + " count is outside the finite bounds");
        }
        Map<DesignId, Integer> copy = new TreeMap<>();
        for (Map.Entry<DesignId, Integer> entry : source.entrySet()) {
            DesignId species = Objects.requireNonNull(entry.getKey(), "reaction species");
            Integer amount = Objects.requireNonNull(entry.getValue(), "reaction amount");
            if (amount < 1 || amount > MAX_UNITS_PER_SPECIES) {
                throw new IllegalArgumentException("Reaction coefficient is outside the finite bounds");
            }
            copy.put(species, amount);
        }
        return Collections.unmodifiableMap(copy);
    }
}
