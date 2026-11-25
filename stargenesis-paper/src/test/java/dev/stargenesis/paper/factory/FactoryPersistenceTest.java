package dev.stargenesis.paper.factory;

import dev.stargenesis.sim.FactoryAction;
import dev.stargenesis.sim.FactoryConfig;
import dev.stargenesis.sim.FactoryInput;
import dev.stargenesis.sim.FactoryReplay;
import dev.stargenesis.sim.FactoryWorld;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FactoryPersistenceTest {
    private static final List<UUID> PLAYERS = List.of(
            new UUID(0, 3001), new UUID(0, 3002), new UUID(0, 3003), new UUID(0, 3004));

    @Test void fourPlayerMatchReplaySurvivesRestartAndResolvesByPlayer(@TempDir Path directory) throws Exception {
        FactoryPersistence persistence = new FactoryPersistence(directory);
        String matchId = FactoryWorld.matchIdFor(PLAYERS);
        try (FactoryWorld world = new FactoryWorld(matchId, PLAYERS, 77, FactoryConfig.defaults())) {
            world.step(new FactoryInput(0, new FactoryAction(PLAYERS.getFirst(), FactoryAction.Type.BID, "ore_1", 10)));
            persistence.save(world.replay());
        }

        FactoryReplay replay = persistence.findForPlayer(PLAYERS.get(2)).getFirst();
        assertEquals(matchId, replay.matchId());
        try (FactoryWorld restored = FactoryWorld.restore(replay)) {
            assertEquals(1, restored.tick());
            assertEquals(1, restored.view().auction().bids().size());
            assertEquals(FactoryWorld.matchIdFor(PLAYERS), restored.matchId());
        }
        assertTrue(persistence.findForPlayer(new UUID(0, 3999)).isEmpty());
    }

    @Test void factoryReplayFilesRejectUnknownFields(@TempDir Path directory) throws Exception {
        FactoryPersistence persistence = new FactoryPersistence(directory);
        String matchId = FactoryWorld.matchIdFor(PLAYERS);
        try (FactoryWorld world = new FactoryWorld(matchId, PLAYERS, 9, FactoryConfig.defaults())) {
            persistence.save(world.replay());
        }
        Path file = directory.resolve(matchId + ".factory.json");
        String encoded = Files.readString(file);
        Files.writeString(file, encoded.replaceFirst("\\{", "{\"futureField\":true,"));

        assertThrows(java.io.IOException.class, () -> persistence.load(matchId));
    }

    @Test void organizationPairsPersistSymmetricallyAndRejectInvalidMembership(@TempDir Path directory) throws Exception {
        FactoryPersistence persistence = new FactoryPersistence(directory);
        Map<UUID, UUID> organizations = Map.of(PLAYERS.get(0), PLAYERS.get(1), PLAYERS.get(1), PLAYERS.get(0));

        persistence.saveOrganizations(organizations);

        assertEquals(organizations, persistence.loadOrganizations());
        assertThrows(java.io.IOException.class,
                () -> persistence.saveOrganizations(Map.of(PLAYERS.get(0), PLAYERS.get(1))));
        assertEquals(organizations, persistence.loadOrganizations());
    }
}
