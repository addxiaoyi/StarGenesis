package dev.stargenesis.paper.ecology;

import dev.stargenesis.sim.EcologyConfig;
import dev.stargenesis.sim.EcologyInput;
import dev.stargenesis.sim.EcologyReplay;
import dev.stargenesis.sim.EcologyView;
import dev.stargenesis.sim.EcologyWorld;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EcologyPersistenceTest {
    @Test void perPlayerReplayRestoresTheExactEcologyState(@TempDir Path directory) throws Exception {
        UUID owner = new UUID(0, 901);
        EcologyPersistence persistence = new EcologyPersistence(directory);
        EcologyView expected;
        try (EcologyWorld world = new EcologyWorld("ecology-00000000000000000000000000000385", 77,
                EcologyConfig.defaults())) {
            for (int i = 0; i < 25; i++) world.step(EcologyInput.idle(world.tick()));
            expected = world.view();
            persistence.save(owner, world.replay());
        }

        EcologyReplay stored = persistence.load(owner).orElseThrow();
        try (EcologyWorld restored = EcologyWorld.restore(stored)) {
            assertEquals(expected, restored.view());
        }
        assertTrue(persistence.load(new UUID(0, 902)).isEmpty());
    }

    @Test void replayFileCannotBeMovedAcrossPlayerOwnership(@TempDir Path directory) throws Exception {
        UUID owner = new UUID(0, 911);
        UUID other = new UUID(0, 912);
        EcologyPersistence persistence = new EcologyPersistence(directory);
        try (EcologyWorld world = new EcologyWorld("ecology-0000000000000000000000000000038f", 9,
                EcologyConfig.defaults())) {
            persistence.save(owner, world.replay());
        }

        Files.copy(directory.resolve(owner + ".ecology.json"), directory.resolve(other + ".ecology.json"));

        assertThrows(java.io.IOException.class, () -> persistence.load(other));
    }

    @Test void unknownPersistenceFieldsAreRejected(@TempDir Path directory) throws Exception {
        UUID owner = new UUID(0, 921);
        EcologyPersistence persistence = new EcologyPersistence(directory);
        try (EcologyWorld world = new EcologyWorld("ecology-00000000000000000000000000000399", 13,
                EcologyConfig.defaults())) {
            persistence.save(owner, world.replay());
        }
        Path file = directory.resolve(owner + ".ecology.json");
        String encoded = Files.readString(file);
        Files.writeString(file, encoded.replaceFirst("\\{", "{\"futureField\":true,"));

        assertThrows(java.io.IOException.class, () -> persistence.load(owner));
    }
}
