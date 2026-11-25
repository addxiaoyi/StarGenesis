package dev.stargenesis.paper;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

    @Test void combinedEvidenceRejectsInvalidEnvelopesWithoutFallingBackToLegacy() throws Exception {
        var store = new LabPersistence(directory); UUID owner = new UUID(0, 1);
        Path file = directory.resolve(owner + ".lab.json");
        try (var world = new ResourceSandboxWorld("combined", 42, SandboxConfig.defaults())) {
            world.step(new StepInput(0, List.of()));
            store.saveSnapshot(owner, world.snapshot());
            store.saveReplay(owner, world.replayLog());
            store.saveEvidence(owner, world.snapshot());
            var mapper = new ObjectMapper();
            ObjectNode valid = (ObjectNode) mapper.readTree(Files.readAllBytes(file));
            var wrongOwner = valid.deepCopy().put("owner", new UUID(0, 2).toString());
            var wrongVersion = valid.deepCopy().put("schemaVersion", 2);
            var missing = valid.deepCopy(); missing.remove("owner");
            var nullSnapshot = valid.deepCopy().putNull("snapshot");
            var unknown = valid.deepCopy().put("unexpected", true);
            var floatVersion = valid.deepCopy().put("schemaVersion", 1.0);
            var coercedVersion = valid.deepCopy().put("schemaVersion", "1");
            for (String json : List.of(wrongOwner.toString(), wrongVersion.toString(), missing.toString(),
                    nullSnapshot.toString(), unknown.toString(), floatVersion.toString(), coercedVersion.toString(),
                    valid + " {}", "null", valid.toString().replaceFirst("\\{", "{\"schemaVersion\":1,"))) {
                Files.writeString(file, json);
                assertThrows(IOException.class, () -> store.loadEvidence(owner), json);
                assertThrows(IOException.class, () -> store.loadSnapshot(owner), json);
                assertThrows(IOException.class, () -> store.loadReplay(owner), json);
                assertEquals(json, Files.readString(file));
            }
            Files.delete(file);
            Files.createDirectory(file);
            assertThrows(IOException.class, () -> store.loadSnapshot(owner));
            assertThrows(IOException.class, () -> store.loadReplay(owner));
        }
    }

    @Test void combinedEvidenceMustReproduceTheFinalStateAndEveryRecordedEvent() throws Exception {
        var store = new LabPersistence(directory); UUID owner = new UUID(0, 1);
        Path file = directory.resolve(owner + ".lab.json");
        try (var world = new ResourceSandboxWorld("integrity", 42, SandboxConfig.defaults())) {
            for (int i = 0; i < 20; i++) world.step(new StepInput(world.tick(), List.of()));
            store.saveEvidence(owner, world.snapshot());
            var mapper = new ObjectMapper();
            ObjectNode valid = (ObjectNode) mapper.readTree(Files.readAllBytes(file));
            var falseState = valid.deepCopy();
            ((ObjectNode) falseState.path("snapshot")).put("stateHash", "0".repeat(64));
            var falseEvent = valid.deepCopy();
            ((ObjectNode) falseEvent.path("snapshot").path("history").get(0).path("events").get(0)).put("type", "tampered");
            for (var invalid : List.of(falseState, falseEvent)) {
                Files.writeString(file, invalid.toString());
                assertThrows(IOException.class, () -> store.loadSnapshot(owner));
                assertThrows(IOException.class, () -> store.loadReplay(owner));
            }
        }
    }

    @Test void failedCombinedReplacementKeepsBothPublishedViewsAndTheirBytes() throws Exception {
        var store = new LabPersistence(directory); UUID owner = new UUID(0, 1);
        Path file = directory.resolve(owner + ".lab.json");
        try (var world = new ResourceSandboxWorld("atomic", 42, SandboxConfig.defaults())) {
            world.step(new StepInput(0, List.of()));
            var committedSnapshot = world.snapshot(); var committedReplay = world.replayLog();
            store.saveEvidence(owner, committedSnapshot);
            byte[] before = Files.readAllBytes(file);
            world.step(new StepInput(1, List.of()));
            Path blocker = Files.createDirectory(directory.resolve(owner + ".lab.json.tmp"));
            assertThrows(IOException.class, () -> store.saveEvidence(owner, world.snapshot()));
            assertArrayEquals(before, Files.readAllBytes(file));
            var reopened = new LabPersistence(directory);
            assertEquals(committedSnapshot, reopened.loadSnapshot(owner).orElseThrow());
            assertEquals(committedReplay, reopened.loadReplay(owner).orElseThrow());
            Files.delete(blocker);
            reopened.saveEvidence(owner, world.snapshot());
            assertEquals(world.snapshot(), store.loadSnapshot(owner).orElseThrow());
            assertEquals(world.replayLog(), store.loadReplay(owner).orElseThrow());
        }
    }

    @Test void fourAgentDefaultDurationFitsTheCombinedEvidenceLimitAndReplays() throws Exception {
        var store = new LabPersistence(directory); UUID owner = new UUID(0, 1);
        try (var world = new ResourceSandboxWorld("lab-capacity", 42, SandboxConfig.defaults().withAgentCount(4))) {
            // Keep the last resources pending so evidence covers the full duration, including ordinary action events.
            for (int i = 0; i < 500 && !world.view().terminal(); i++) world.step(new StepInput(world.tick(), List.of()));
            assertFalse(world.view().terminal());
            var pause = world.view().agents().stream().map(a -> new StepInput.Command(a.id(), 0, "pause", java.util.Map.of())).toList();
            world.step(new StepInput(world.tick(), pause));
            while (!world.view().terminal()) world.step(new StepInput(world.tick(), List.of()));
            assertEquals(2400, world.tick());
            store.saveEvidence(owner, world.snapshot());
            assertTrue(Files.size(directory.resolve(owner + ".lab.json")) < LabPersistence.MAX_FILE_BYTES);
            var snapshot = store.loadSnapshot(owner).orElseThrow();
            assertEquals(world.snapshot(), snapshot);
            assertTrue(ReplayVerifier.verify(store.loadReplay(owner).orElseThrow()).passed());
        }
    }
}
