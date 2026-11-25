package dev.stargenesis.sim;

import dev.stargenesis.api.design.DesignId;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public record EcologySettings(String worldId, long seed, int maxPopulation, long maxSteps,
                              long initialNutrient, int decompositionPerStep, int reproductionEnergy,
                              int energyInputPerStep, int mutationPermille, List<EcologyFounder> founders,
                              List<MatterSpecies> reactionSpecies, List<MatterReaction> matterReactions,
                              Map<DesignId, Long> initialReactionInventory) {
    public EcologySettings(String worldId, long seed, int maxPopulation, long maxSteps,
                           long initialNutrient, int decompositionPerStep, int reproductionEnergy,
                           int energyInputPerStep, int mutationPermille, List<EcologyFounder> founders) {
        this(worldId, seed, maxPopulation, maxSteps, initialNutrient, decompositionPerStep,
                reproductionEnergy, energyInputPerStep, mutationPermille, founders, List.of(), List.of(), Map.of());
    }

    public EcologySettings {
        if (worldId == null || !worldId.matches("[a-z0-9][a-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("Invalid ecology world ID");
        }
        if (maxPopulation < 1 || maxPopulation > 256 || maxSteps < 1 || maxSteps > 100_000
                || initialNutrient < 0 || initialNutrient > 1_000_000
                || decompositionPerStep < 0 || decompositionPerStep > 256
                || reproductionEnergy < 1 || reproductionEnergy > 100_000
                || energyInputPerStep < 0 || energyInputPerStep > 10_000
                || mutationPermille < 0 || mutationPermille > 1_000) {
            throw new IllegalArgumentException("Ecology settings exceed a hard simulation bound");
        }
        founders = List.copyOf(Objects.requireNonNull(founders, "founders"));
        reactionSpecies = List.copyOf(Objects.requireNonNull(reactionSpecies, "reactionSpecies"));
        matterReactions = List.copyOf(Objects.requireNonNull(matterReactions, "matterReactions"));
        if (founders.isEmpty() || founders.size() > maxPopulation) {
            throw new IllegalArgumentException("Founder population is outside the configured limit");
        }
        founders.forEach(founder -> Objects.requireNonNull(founder, "founder"));
        long initialMatter;
        try {
            initialMatter = Math.addExact(initialNutrient, founders.size());
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Initial ecology matter overflows", overflow);
        }
        if (initialMatter > 1_000_256) {
            throw new IllegalArgumentException("Initial ecology matter exceeds the hard limit");
        }
        Map<DesignId, Long> requestedInventory = new TreeMap<>(Objects.requireNonNull(
                initialReactionInventory, "initialReactionInventory"));
        MatterReactionNetwork reactionNetwork = new MatterReactionNetwork(reactionSpecies, matterReactions);
        reactionSpecies = reactionNetwork.species();
        matterReactions = reactionNetwork.reactions();
        for (MatterSpecies matter : reactionSpecies) {
            if (matter.id().equals(EcologyWorld.EDIBLE_MATTER_ID) && matter.massPerUnit() != 1) {
                throw new IllegalArgumentException("Ecology nutrient matter must have unit mass");
            }
        }
        initialReactionInventory = reactionNetwork.canonicalInventory(requestedInventory);
    }

    public EcologySettings withMatterReactions(List<MatterReaction> reactions) {
        return withReactionNetwork(reactionSpecies, reactions, initialReactionInventory);
    }

    public EcologySettings withReactionNetwork(List<MatterSpecies> species, List<MatterReaction> reactions,
                                               Map<DesignId, Long> initialInventory) {
        return new EcologySettings(worldId, seed, maxPopulation, maxSteps, initialNutrient,
                decompositionPerStep, reproductionEnergy, energyInputPerStep, mutationPermille,
                founders, species, reactions, initialInventory);
    }
}
