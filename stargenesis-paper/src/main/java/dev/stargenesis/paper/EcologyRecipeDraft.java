package dev.stargenesis.paper;

import dev.stargenesis.api.design.DesignId;
import dev.stargenesis.sim.MatterReaction;
import dev.stargenesis.sim.MatterSpecies;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

final class EcologyRecipeDraft {
    static final int MAX_TERMS = MatterReaction.MAX_SPECIES_PER_SIDE;
    static final int MAX_AMOUNT_PER_TERM = 16;

    private EcologyRecipeDraft() {
    }

    static MatterReaction build(DesignId reactionId, List<MatterSpecies> species,
                                List<Ingredient> inputs, List<Ingredient> outputs, int maxFiringsPerStep) {
        Map<DesignId, MatterSpecies> catalog = catalog(species);
        Map<DesignId, Integer> inputAmounts = coefficients(inputs, catalog);
        Map<DesignId, Integer> outputAmounts = coefficients(outputs, catalog);
        if (inputAmounts.isEmpty() || outputAmounts.isEmpty()) {
            throw new IllegalArgumentException("配方两侧都必须至少选择一种物质");
        }
        if (mass(inputAmounts, catalog) != mass(outputAmounts, catalog)) {
            throw new IllegalArgumentException("配方质量不平衡");
        }
        return new MatterReaction(reactionId, inputAmounts, outputAmounts, maxFiringsPerStep);
    }

    static long mass(List<MatterSpecies> species, List<Ingredient> terms) {
        Map<DesignId, MatterSpecies> catalog = catalog(species);
        return mass(coefficients(terms, catalog), catalog);
    }

    private static Map<DesignId, MatterSpecies> catalog(List<MatterSpecies> species) {
        Objects.requireNonNull(species, "species");
        Map<DesignId, MatterSpecies> catalog = new TreeMap<>();
        for (MatterSpecies matter : species) {
            Objects.requireNonNull(matter, "species entry");
            if (catalog.putIfAbsent(matter.id(), matter) != null) {
                throw new IllegalArgumentException("Duplicate matter species ID");
            }
        }
        return catalog;
    }

    private static Map<DesignId, Integer> coefficients(List<Ingredient> terms,
                                                        Map<DesignId, MatterSpecies> catalog) {
        Objects.requireNonNull(terms, "terms");
        if (terms.size() > MAX_TERMS) throw new IllegalArgumentException("配方物质数量超过上限");
        Map<DesignId, Integer> amounts = new TreeMap<>();
        for (Ingredient term : terms) {
            if (term == null) continue;
            if (!catalog.containsKey(term.speciesId())) {
                throw new IllegalArgumentException("配方引用了未知物质");
            }
            amounts.merge(term.speciesId(), term.amount(), Math::addExact);
        }
        return amounts;
    }

    private static long mass(Map<DesignId, Integer> amounts, Map<DesignId, MatterSpecies> catalog) {
        long total = 0;
        for (Map.Entry<DesignId, Integer> entry : amounts.entrySet()) {
            long unitMass = catalog.get(entry.getKey()).massPerUnit();
            total = Math.addExact(total, Math.multiplyExact(unitMass, entry.getValue()));
        }
        return total;
    }

    record Ingredient(DesignId speciesId, int amount) {
        Ingredient {
            Objects.requireNonNull(speciesId, "speciesId");
            if (amount < 1 || amount > MAX_AMOUNT_PER_TERM) {
                throw new IllegalArgumentException("配方系数超出可编辑范围");
            }
        }
    }
}
