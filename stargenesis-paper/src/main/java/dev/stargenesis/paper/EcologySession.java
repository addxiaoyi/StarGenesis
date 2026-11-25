package dev.stargenesis.paper;

import dev.stargenesis.api.design.DesignId;
import dev.stargenesis.sim.EcologyCheckpoint;
import dev.stargenesis.sim.EcologyCheckpointCodec;
import dev.stargenesis.sim.EcologyFounder;
import dev.stargenesis.sim.EcologyGenome;
import dev.stargenesis.sim.EcologySettings;
import dev.stargenesis.sim.EcologySnapshot;
import dev.stargenesis.sim.EcologyStepResult;
import dev.stargenesis.sim.EcologyWorld;
import dev.stargenesis.sim.MatterReaction;
import dev.stargenesis.sim.MatterReactionNetwork;
import dev.stargenesis.sim.MatterSpecies;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

final class EcologySession {
    private final UUID ownerId;
    private EcologyCheckpoint checkpoint;

    private EcologySession(UUID ownerId, EcologyCheckpoint checkpoint) {
        this.ownerId = ownerId;
        this.checkpoint = checkpoint;
    }

    static EcologySession start(UUID ownerId) {
        Objects.requireNonNull(ownerId, "ownerId");
        EcologySettings settings = expectedSettings(ownerId);
        try (EcologyWorld world = new EcologyWorld(settings)) {
            return new EcologySession(ownerId, world.checkpoint());
        }
    }

    static EcologySession resume(UUID ownerId, byte[] encoded) {
        Objects.requireNonNull(ownerId, "ownerId");
        EcologySettings expectedSettings = expectedSettings(ownerId);
        EcologyCheckpoint checkpoint = EcologyCheckpointCodec.decode(encoded, expectedSettings.energyInputPerStep());
        if (!isCompatibleSettings(expectedSettings, checkpoint.settings())) {
            throw new IllegalArgumentException("Ecology checkpoint belongs to another player or configuration");
        }
        if (checkpoint.settings().reactionSpecies().isEmpty()) {
            try (EcologyWorld world = EcologyWorld.restore(checkpoint)) {
                world.installReactionCatalog(expectedSettings.reactionSpecies());
                checkpoint = world.checkpoint();
            }
            EcologyCheckpointCodec.encode(checkpoint);
        }
        return new EcologySession(ownerId, checkpoint);
    }

    EcologySnapshot snapshot(UUID caller) {
        requireOwner(caller);
        try (EcologyWorld world = EcologyWorld.restore(checkpoint)) {
            return world.snapshot();
        }
    }

    EcologyStepResult step(UUID caller) {
        requireOwner(caller);
        try (EcologyWorld world = EcologyWorld.restore(checkpoint)) {
            EcologyStepResult result = world.step();
            EcologyCheckpoint staged = world.checkpoint();
            EcologyCheckpointCodec.encode(staged);
            checkpoint = staged;
            return result;
        }
    }

    void editGenome(UUID caller, long organismId, int trait, int direction) {
        requireOwner(caller);
        try (EcologyWorld world = EcologyWorld.restore(checkpoint)) {
            world.editGenome(organismId, trait, direction);
            EcologyCheckpoint staged = world.checkpoint();
            EcologyCheckpointCodec.encode(staged);
            checkpoint = staged;
        }
    }

    void addReaction(UUID caller, MatterReaction reaction) {
        requireOwner(caller);
        try (EcologyWorld world = EcologyWorld.restore(checkpoint)) {
            world.addReaction(reaction);
            EcologyCheckpoint staged = world.checkpoint();
            EcologyCheckpointCodec.encode(staged);
            checkpoint = staged;
        }
    }

    void removeReaction(UUID caller, DesignId reactionId) {
        requireOwner(caller);
        try (EcologyWorld world = EcologyWorld.restore(checkpoint)) {
            world.removeReaction(reactionId);
            EcologyCheckpoint staged = world.checkpoint();
            EcologyCheckpointCodec.encode(staged);
            checkpoint = staged;
        }
    }

    byte[] encodeCheckpoint(UUID caller) {
        requireOwner(caller);
        return EcologyCheckpointCodec.encode(checkpoint);
    }

    EcologySettings settings(UUID caller) {
        requireOwner(caller);
        return checkpoint.settings();
    }

    private void requireOwner(UUID caller) {
        if (!ownerId.equals(caller)) throw new SecurityException("Ecology session belongs to another player");
        if (checkpoint == null) throw new IllegalStateException("Ecology session is unavailable");
    }

    private static EcologySettings expectedSettings(UUID ownerId) {
        String worldId = "eco-" + ownerId.toString().replace("-", "");
        long seed = ownerId.getMostSignificantBits() ^ ownerId.getLeastSignificantBits();
        return settings(worldId, seed);
    }

    static EcologySettings sharedSettings(UUID groupId) {
        Objects.requireNonNull(groupId, "groupId");
        long seed = groupId.getMostSignificantBits() ^ groupId.getLeastSignificantBits();
        return settings(sharedWorldId(groupId), seed);
    }

    static String sharedWorldId(UUID groupId) {
        Objects.requireNonNull(groupId, "groupId");
        return "eco-shared-" + groupId.toString().replace("-", "");
    }

    private static EcologySettings settings(String worldId, long seed) {
        DesignId biomass = new DesignId("ecology:biomass");
        List<MatterSpecies> species = List.of(
                new MatterSpecies(biomass, 2),
                new MatterSpecies(new DesignId("ecology:detritus"), 3),
                new MatterSpecies(new DesignId("ecology:mineral"), 1),
                new MatterSpecies(EcologyWorld.EDIBLE_MATTER_ID, 1),
                new MatterSpecies(new DesignId("ecology:toxin"), 1));
        return new EcologySettings(worldId, seed, 24, 3_000, 128, 8, 6, 24, 180,
                List.of(new EcologyFounder(new EcologyGenome(3, 1, 48, 4), 1),
                        new EcologyFounder(new EcologyGenome(2, 1, 72, 6), 1),
                        new EcologyFounder(new EcologyGenome(1, 1, 96, 8), 2)),
                species, List.of(), Map.of(biomass, 48L));
    }

    private static boolean isCompatibleSettings(EcologySettings expected, EcologySettings actual) {
        boolean samePolicy = expected.worldId().equals(actual.worldId()) && expected.seed() == actual.seed()
                && expected.maxPopulation() == actual.maxPopulation() && expected.maxSteps() == actual.maxSteps()
                && expected.initialNutrient() == actual.initialNutrient()
                && expected.decompositionPerStep() == actual.decompositionPerStep()
                && expected.reproductionEnergy() == actual.reproductionEnergy()
                && expected.energyInputPerStep() == actual.energyInputPerStep()
                && expected.mutationPermille() == actual.mutationPermille()
                && expected.founders().equals(actual.founders());
        boolean emptyStock = actual.initialReactionInventory().values().stream().allMatch(amount -> amount == 0);
        boolean currentCatalog = expected.reactionSpecies().equals(actual.reactionSpecies())
                && (expected.initialReactionInventory().equals(actual.initialReactionInventory()) || emptyStock);
        boolean legacyCatalog = actual.reactionSpecies().isEmpty() && actual.matterReactions().isEmpty()
                && actual.initialReactionInventory().isEmpty();
        return samePolicy && (currentCatalog && actual.matterReactions().size() <= MatterReactionNetwork.MAX_REACTIONS
                || legacyCatalog);
    }
}
