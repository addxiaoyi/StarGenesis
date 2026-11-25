package dev.stargenesis.sim;

import dev.stargenesis.api.design.DesignId;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public record MatterReactionStep(Map<DesignId, Long> inventory,
                                 List<MatterReactionEvent> events,
                                 long conservedMatterMass) {
    public MatterReactionStep {
        Objects.requireNonNull(inventory, "inventory");
        Map<DesignId, Long> copy = new TreeMap<>();
        for (Map.Entry<DesignId, Long> entry : inventory.entrySet()) {
            DesignId id = Objects.requireNonNull(entry.getKey(), "matter species");
            Long amount = Objects.requireNonNull(entry.getValue(), "matter amount");
            if (amount < 0 || amount > MatterReactionNetwork.MAX_UNITS_PER_SPECIES) {
                throw new IllegalArgumentException("Matter inventory is outside the finite bounds");
            }
            copy.put(id, amount);
        }
        inventory = Collections.unmodifiableMap(copy);
        events = List.copyOf(Objects.requireNonNull(events, "events"));
        if (copy.size() > MatterReactionNetwork.MAX_SPECIES
                || events.size() > MatterReactionNetwork.MAX_REACTIONS
                || conservedMatterMass < 0) {
            throw new IllegalArgumentException("Invalid matter reaction result");
        }
    }
}
