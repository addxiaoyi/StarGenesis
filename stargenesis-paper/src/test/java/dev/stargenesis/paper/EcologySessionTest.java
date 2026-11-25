package dev.stargenesis.paper;

import dev.stargenesis.api.design.DesignId;
import dev.stargenesis.sim.EcologyFounder;
import dev.stargenesis.sim.EcologyGenome;
import dev.stargenesis.sim.EcologySettings;
import dev.stargenesis.sim.EcologyWorld;
import dev.stargenesis.sim.MatterReaction;
import dev.stargenesis.sim.MatterSpecies;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EcologySessionTest {
    private static final UUID OWNER = UUID.fromString("00000000-0000-0000-0000-000000000071");
    private static final UUID OTHER = UUID.fromString("00000000-0000-0000-0000-000000000072");

    @Test
    void ownerCanSaveResumeAndContinueTheSamePrivateEcosystem() {
        EcologySession session = EcologySession.start(OWNER);
        session.editGenome(OWNER, 0, 0, 1);
        for (int i = 0; i < 5; i++) session.step(OWNER);
        byte[] checkpoint = session.encodeCheckpoint(OWNER);
        EcologySession resumed = EcologySession.resume(OWNER, checkpoint);
        assertEquals(session.snapshot(OWNER), resumed.snapshot(OWNER));
        assertEquals(session.step(OWNER), resumed.step(OWNER));
        assertEquals(session.snapshot(OWNER), resumed.snapshot(OWNER));
    }

    @Test
    void otherPlayerCannotOpenOrAdvanceTheCheckpoint() {
        EcologySession session = EcologySession.start(OWNER);
        byte[] checkpoint = session.encodeCheckpoint(OWNER);
        assertThrows(IllegalArgumentException.class, () -> EcologySession.resume(OTHER, checkpoint));
        assertThrows(SecurityException.class, () -> session.step(OTHER));
        assertThrows(SecurityException.class, () -> session.editGenome(OTHER, 0, 0, 1));
        assertThrows(SecurityException.class, () -> session.encodeCheckpoint(OTHER));
    }

    @Test
    void malformedCheckpointCannotCreateASession() {
        assertThrows(IllegalArgumentException.class, () -> EcologySession.resume(OWNER, new byte[32]));
    }

    @Test
    void playerRecipeSurvivesResumeAndConnectsReactionProductsToFeeding() {
        EcologySession session = EcologySession.start(OWNER);
        EcologySettings settings = session.settings(OWNER);
        DesignId biomass = new DesignId("ecology:biomass");
        MatterReaction conversion = new MatterReaction(new DesignId("player:recipe-01"),
                Map.of(biomass, 1), Map.of(EcologyWorld.EDIBLE_MATTER_ID, 2), 16);
        session.addReaction(OWNER, conversion);

        byte[] saved = session.encodeCheckpoint(OWNER);
        EcologySession resumed = EcologySession.resume(OWNER, saved);
        assertEquals(List.of(conversion), resumed.settings(OWNER).matterReactions());
        var result = resumed.step(OWNER);
        assertTrue(result.reactionEvents().stream().anyMatch(event -> event.reactionId().equals(conversion.id())));
        assertEquals(0, resumed.snapshot(OWNER).reactionInventory().get(EcologyWorld.EDIBLE_MATTER_ID));
        assertTrue(resumed.snapshot(OWNER).statistics().foodConsumed() > 0);

        EcologySession continued = EcologySession.resume(OWNER, resumed.encodeCheckpoint(OWNER));
        assertEquals(resumed.snapshot(OWNER), continued.snapshot(OWNER));
        assertEquals(1, continued.settings(OWNER).matterReactions().size());
    }

    @Test
    void oldSessionWithoutReactionCatalogUpgradesWithoutMintingMatter() {
        String worldId = "eco-" + OWNER.toString().replace("-", "");
        long seed = OWNER.getMostSignificantBits() ^ OWNER.getLeastSignificantBits();
        EcologySettings legacySettings = new EcologySettings(worldId, seed, 24, 3_000, 128, 8, 6, 24, 180,
                List.of(new EcologyFounder(new EcologyGenome(3, 1, 48, 4), 1),
                        new EcologyFounder(new EcologyGenome(2, 1, 72, 6), 1),
                        new EcologyFounder(new EcologyGenome(1, 1, 96, 8), 2)));
        byte[] legacyCheckpoint;
        long conservedMatter;
        try (EcologyWorld world = new EcologyWorld(legacySettings)) {
            legacyCheckpoint = dev.stargenesis.sim.EcologyCheckpointCodec.encode(world.checkpoint());
            conservedMatter = world.snapshot().statistics().conservedMatter();
        }

        EcologySession resumed = EcologySession.resume(OWNER, legacyCheckpoint);
        assertFalse(resumed.settings(OWNER).reactionSpecies().isEmpty());
        assertTrue(resumed.settings(OWNER).initialReactionInventory().values().stream().allMatch(amount -> amount == 0));
        assertEquals(conservedMatter, resumed.snapshot(OWNER).statistics().conservedMatter());
    }

    @Test
    void playerCannotInstallUnbalancedRecipe() {
        EcologySession session = EcologySession.start(OWNER);
        DesignId biomass = new DesignId("ecology:biomass");
        MatterReaction unbalanced = new MatterReaction(new DesignId("player:bad-recipe"),
                Map.of(biomass, 1), Map.of(EcologyWorld.EDIBLE_MATTER_ID, 1), 4);
        assertThrows(IllegalArgumentException.class, () -> session.addReaction(OWNER, unbalanced));
    }
}
