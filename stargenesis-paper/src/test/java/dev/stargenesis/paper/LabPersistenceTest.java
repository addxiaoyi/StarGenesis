package dev.stargenesis.paper;

import dev.stargenesis.api.sim.StepInput;
import dev.stargenesis.paper.lab.LabPersistence;
import dev.stargenesis.sim.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class LabPersistenceTest {
    @TempDir Path directory;
    @Test void checkpointAndReplaySurviveRealFileRoundTrip() throws Exception {
        var store = new LabPersistence(directory);
        UUID player = new UUID(0, 1);
        try (var world = new ResourceSandboxWorld("persist-test", 42, SandboxConfig.defaults())) {
            for (int i = 0; i < 150; i++) world.step(new StepInput(world.tick(), List.of()));
            store.saveSnapshot(player, world.snapshot());
            store.saveReplay(player, world.replayLog());
            var reopened = new LabPersistence(directory);
            try (var restored = ResourceSandboxWorld.restore(reopened.loadSnapshot(player).orElseThrow())) {
                assertEquals(world.stateHash(), restored.stateHash());
                world.step(new StepInput(world.tick(), List.of()));
                restored.step(new StepInput(restored.tick(), List.of()));
                assertEquals(world.stateHash(), restored.stateHash());
            }
            assertTrue(ReplayVerifier.verify(reopened.loadReplay(player).orElseThrow()).passed());
            assertTrue(store.loadReplay(new UUID(0, 2)).isEmpty());
        }
    }
    @Test void oversizedAndMalformedEvidenceFailsClosed() throws Exception {
        var store = new LabPersistence(directory); UUID player = new UUID(0, 1);
        Path file = directory.resolve(player + ".snapshot.json");
        Files.writeString(file, "{}");
        assertThrows(IOException.class, () -> store.loadSnapshot(player));
        try (var output = Files.newOutputStream(file)) { output.write(new byte[(int) LabPersistence.MAX_FILE_BYTES + 1]); }
        assertThrows(IOException.class, () -> store.loadSnapshot(player));
    }
}
