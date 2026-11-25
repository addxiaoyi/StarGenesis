package dev.stargenesis.sim;

import dev.stargenesis.api.design.DesignId;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Deterministic, bounded reaction evaluator over integer matter quantities. */
public final class MatterReactionNetwork {
    public static final int MAX_SPECIES = 64;
    public static final int MAX_REACTIONS = 48;
    public static final int MAX_FIRINGS_PER_STEP = 1_024;
    public static final long MAX_TOTAL_MATTER_MASS = 1_000_000_000_000L;
    public static final long MAX_UNITS_PER_SPECIES = 1_000_000;

    private final Map<DesignId, MatterSpecies> species;
    private final List<MatterReaction> reactions;

    public MatterReactionNetwork(List<MatterSpecies> species, List<MatterReaction> reactions) {
        Objects.requireNonNull(species, "species");
        Objects.requireNonNull(reactions, "reactions");
        if (species.size() > MAX_SPECIES || reactions.size() > MAX_REACTIONS
                || species.isEmpty() && !reactions.isEmpty()) {
            throw new IllegalArgumentException("Reaction network size is outside the finite bounds");
        }
        Map<DesignId, MatterSpecies> speciesById = new TreeMap<>();
        for (MatterSpecies matter : species) {
            Objects.requireNonNull(matter, "species");
            if (speciesById.putIfAbsent(matter.id(), matter) != null) {
                throw new IllegalArgumentException("Duplicate matter species ID");
            }
        }
        Map<DesignId, MatterReaction> reactionsById = new TreeMap<>();
        for (MatterReaction reaction : reactions) {
            Objects.requireNonNull(reaction, "reaction");
            if (reactionsById.putIfAbsent(reaction.id(), reaction) != null) {
                throw new IllegalArgumentException("Duplicate matter reaction ID");
            }
            validateSide(reaction.inputs(), speciesById);
            validateSide(reaction.outputs(), speciesById);
            if (sideMass(reaction.inputs(), speciesById) != sideMass(reaction.outputs(), speciesById)) {
                throw new IllegalArgumentException("Matter reaction does not conserve mass");
            }
        }
        this.species = Collections.unmodifiableMap(speciesById);
        this.reactions = List.copyOf(reactionsById.values());
    }

    public MatterReactionStep applyStep(Map<DesignId, Long> inventory) {
        Map<DesignId, Long> amounts = canonicalInventory(inventory);
        long conservedMass = totalMass(amounts);
        List<MatterReactionEvent> events = new ArrayList<>();
        int remainingFirings = MAX_FIRINGS_PER_STEP;
        for (MatterReaction reaction : reactions) {
            int firings = maximumFirings(reaction, amounts, remainingFirings);
            if (firings == 0) continue;
            Map<DesignId, Long> next = applyReaction(reaction, amounts, firings);
            if (totalMass(next) != conservedMass) {
                throw new IllegalStateException("Reaction step violated matter conservation");
            }
            amounts = next;
            events.add(new MatterReactionEvent(reaction.id(), firings));
            remainingFirings -= firings;
            if (remainingFirings == 0) break;
        }
        return new MatterReactionStep(amounts, events, conservedMass);
    }

    public List<MatterSpecies> species() {
        return List.copyOf(species.values());
    }

    public List<MatterReaction> reactions() {
        return reactions;
    }

    public Map<DesignId, Long> canonicalInventory(Map<DesignId, Long> inventory) {
        Objects.requireNonNull(inventory, "inventory");
        if (inventory.size() > species.size()) throw new IllegalArgumentException("Unknown matter species in inventory");
        Map<DesignId, Long> amounts = new TreeMap<>();
        for (DesignId id : species.keySet()) amounts.put(id, 0L);
        for (Map.Entry<DesignId, Long> entry : inventory.entrySet()) {
            DesignId id = Objects.requireNonNull(entry.getKey(), "matter species");
            Long amount = Objects.requireNonNull(entry.getValue(), "matter amount");
            if (!species.containsKey(id) || amount < 0 || amount > MAX_UNITS_PER_SPECIES) {
                throw new IllegalArgumentException("Matter inventory is outside the network bounds");
            }
            amounts.put(id, amount);
        }
        totalMass(amounts);
        return Collections.unmodifiableMap(amounts);
    }

    public long matterMass(Map<DesignId, Long> inventory) {
        return totalMass(canonicalInventory(inventory));
    }

    private int maximumFirings(MatterReaction reaction, Map<DesignId, Long> amounts, int remaining) {
        int firings = Math.min(reaction.maxFiringsPerStep(), remaining);
        for (Map.Entry<DesignId, Integer> input : reaction.inputs().entrySet()) {
            long possible = amounts.get(input.getKey()) / input.getValue();
            firings = (int) Math.min(firings, possible);
        }
        for (MatterSpecies matter : species.values()) {
            long netPerFiring = reaction.outputs().getOrDefault(matter.id(), 0)
                    - reaction.inputs().getOrDefault(matter.id(), 0);
            if (netPerFiring > 0) {
                long capacity = MAX_UNITS_PER_SPECIES - amounts.get(matter.id());
                firings = (int) Math.min(firings, capacity / netPerFiring);
            } else if (netPerFiring < 0) {
                firings = (int) Math.min(firings, amounts.get(matter.id()) / -netPerFiring);
            }
        }
        return firings;
    }

    private Map<DesignId, Long> applyReaction(MatterReaction reaction,
                                              Map<DesignId, Long> amounts, int firings) {
        Map<DesignId, Long> deltas = new TreeMap<>();
        reaction.inputs().forEach((id, units) -> deltas.put(id, -Math.multiplyExact((long) units, firings)));
        reaction.outputs().forEach((id, units) -> deltas.merge(id,
                Math.multiplyExact((long) units, firings), Math::addExact));
        Map<DesignId, Long> next = new TreeMap<>();
        for (Map.Entry<DesignId, MatterSpecies> entry : species.entrySet()) {
            long amount = Math.addExact(amounts.get(entry.getKey()), deltas.getOrDefault(entry.getKey(), 0L));
            if (amount < 0 || amount > MAX_UNITS_PER_SPECIES) {
                throw new IllegalStateException("Reaction step exceeds a matter species bound");
            }
            next.put(entry.getKey(), amount);
        }
        return next;
    }

    private static void validateSide(Map<DesignId, Integer> side, Map<DesignId, MatterSpecies> species) {
        for (DesignId id : side.keySet()) {
            if (!species.containsKey(id)) throw new IllegalArgumentException("Reaction references an unknown species");
        }
    }

    private static long sideMass(Map<DesignId, Integer> side, Map<DesignId, MatterSpecies> species) {
        long total = 0;
        for (Map.Entry<DesignId, Integer> entry : side.entrySet()) {
            total = Math.addExact(total,
                    Math.multiplyExact(species.get(entry.getKey()).massPerUnit(), entry.getValue()));
        }
        return total;
    }

    private long totalMass(Map<DesignId, Long> amounts) {
        long total = 0;
        for (Map.Entry<DesignId, MatterSpecies> entry : species.entrySet()) {
            total = Math.addExact(total,
                    Math.multiplyExact(entry.getValue().massPerUnit(), amounts.get(entry.getKey())));
        }
        if (total > MAX_TOTAL_MATTER_MASS) throw new IllegalArgumentException("Matter pool exceeds the finite bound");
        return total;
    }
}
