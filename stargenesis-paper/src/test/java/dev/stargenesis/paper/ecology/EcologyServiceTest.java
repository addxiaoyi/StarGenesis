package dev.stargenesis.paper.ecology;

import dev.stargenesis.sim.EcologyConfig;
import dev.stargenesis.sim.EcologyInput;
import dev.stargenesis.sim.EcologyReplay;
import dev.stargenesis.sim.EcologyReplayVerifier;
import dev.stargenesis.sim.EcologyWorld;
import dev.stargenesis.sim.MatterProgram;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EcologyServiceTest {
    private static final UUID OWNER = new UUID(0, 901), OTHER = new UUID(0, 902);

    @Test void defaultStartRetainsTheOriginalConfigAndCannotOverwriteAnExistingTank(@TempDir Path directory) throws Exception {
        var persistence = new EcologyPersistence(directory);
        var service = new EcologyService(persistence);
        assertTrue(service.execute(OWNER, new String[]{"start", "42"}).getFirst().contains("0/500"));
        var saved = persistence.load(OWNER).orElseThrow();
        assertEquals(EcologyConfig.defaults(), saved.config());
        assertEquals(EcologyWorld.ENGINE_VERSION, saved.engineVersion());
        assertTrue(service.execute(OWNER, new String[]{"dashboard"}).stream().anyMatch(line -> line.contains("繁殖成熟年龄=")));
        assertThrows(IllegalStateException.class, () -> service.execute(OWNER, new String[]{"start", "42", "1536"}));
        assertEquals(saved, persistence.load(OWNER).orElseThrow());
    }

    @Test void explicitLongDurationSurvivesRestartAndDashboardReportsTheStoredLimits(@TempDir Path directory) throws Exception {
        var service = new EcologyService(new EcologyPersistence(directory));
        assertTrue(service.execute(OWNER, new String[]{"start", "42", "1536"}).getFirst().contains("0/1536"));
        var restarted = new EcologyService(new EcologyPersistence(directory));
        assertTrue(restarted.execute(OWNER, new String[]{"step", "20"}).getFirst().contains("20/1536"));
        assertTrue(restarted.execute(OWNER, new String[]{"dashboard"}).getFirst().contains("20/1536"));
        assertEquals(98_304, new EcologyPersistence(directory).load(OWNER).orElseThrow().config().maxLedgerAttempts());
        assertTrue(restarted.execute(OWNER, new String[]{"replay"}).getFirst().contains("20 ticks"));
    }

    @Test void invalidDurationNeverCreatesAPartialTank(@TempDir Path directory) throws Exception {
        var persistence = new EcologyPersistence(directory);
        var service = new EcologyService(persistence);
        for (String value : new String[]{"0", "-1", "1537", "2147483647", "3.5", "unknown"}) {
            assertThrows(IllegalArgumentException.class, () -> service.execute(OWNER, new String[]{"start", "42", value}));
            assertTrue(persistence.load(OWNER).isEmpty());
        }
        assertThrows(IllegalArgumentException.class, () -> service.execute(OWNER, new String[]{"start", "42", "500", "extra"}));
    }

    @Test void capturedLegacyDefaultReplayKeepsOriginalHashesAndContinues(@TempDir Path directory) throws Exception {
        Path file = directory.resolve(OWNER + ".ecology.json");
        try (var fixture = getClass().getResourceAsStream("/ecology-v1-default.json")) {
            assertNotNull(fixture);
            Files.copy(fixture, file);
        }
        var persistence = new EcologyPersistence(directory);
        var replay = persistence.load(OWNER).orElseThrow();
        byte[] legacyBytes = Files.readAllBytes(file);
        assertEquals(EcologyWorld.LEGACY_ENGINE_VERSION, replay.engineVersion());
        assertEquals(EcologyConfig.defaults(), replay.config());
        assertEquals("45cd70b40510e6b389f942384df8b4b13c6910ffd762294a0da749b814684020", replay.initialStateHash());
        assertTrue(EcologyReplayVerifier.verify(replay).passed());
        assertTrue(new EcologyService(persistence).execute(OWNER, new String[]{"dashboard"}).stream()
                .anyMatch(line -> line.contains("沿用旧版繁殖规则")));
        assertArrayEquals(legacyBytes, Files.readAllBytes(file));
        try (var world = EcologyWorld.restore(replay)) {
            assertEquals("e3eb39f8abc153755d22ed95a47c8e44800fe57b0ee454ebd1b3324aeffc01e8", world.stateHash());
            world.step(EcologyInput.idle(world.tick()));
            new EcologyService(persistence).execute(OWNER, new String[]{"step", "1"});
            assertEquals(world.replay(), persistence.load(OWNER).orElseThrow());
            assertEquals(EcologyWorld.LEGACY_ENGINE_VERSION, persistence.load(OWNER).orElseThrow().engineVersion());
        }
    }

    @Test void maturityBoundaryAndFailedProgramUpdateResumeIdenticallyAfterRestart(@TempDir Path directory) throws Exception {
        var persistence = new EcologyPersistence(directory);
        try (var world = new EcologyWorld(worldId(), 42, EcologyConfig.forDuration(128))) {
            var programs = Map.of(
                    "producer", new MatterProgram("producer", MatterProgram.Role.PRODUCER, 8, 2, 64, 0),
                    "grazer", new MatterProgram("grazer", MatterProgram.Role.GRAZER, 8, 2, 64, 0),
                    "decomposer", new MatterProgram("decomposer", MatterProgram.Role.DECOMPOSER, 8, 2, 64, 0));
            world.step(new EcologyInput(0, programs));
            while (world.tick() < 31) world.step(EcologyInput.idle(world.tick()));
            assertEquals(0, world.view().births());
            persistence.save(OWNER, world.replay());
            byte[] before = Files.readAllBytes(directory.resolve(OWNER + ".ecology.json"));
            Path temporary = directory.resolve(OWNER + ".ecology.json.tmp");
            Files.createDirectory(temporary);
            var command = new String[]{"program", "producer", "8", "2", "64", "0"};
            assertThrows(IOException.class, () -> new EcologyService(persistence).execute(OWNER, command));
            assertArrayEquals(before, Files.readAllBytes(directory.resolve(OWNER + ".ecology.json")));
            Files.delete(temporary);
            new EcologyService(new EcologyPersistence(directory)).execute(OWNER, command);
            world.step(new EcologyInput(world.tick(), Map.of("producer", programs.get("producer"))));
            assertTrue(world.view().births() > 0);
            assertEquals(world.replay(), persistence.load(OWNER).orElseThrow());
            new EcologyService(new EcologyPersistence(directory)).execute(OWNER, new String[]{"step", "20"});
            for (int i = 0; i < 20; i++) world.step(EcologyInput.idle(world.tick()));
            assertEquals(world.replay(), persistence.load(OWNER).orElseThrow());
            assertTrue(EcologyReplayVerifier.verify(persistence.load(OWNER).orElseThrow()).passed());
        }
    }

    @Test void unknownOrRelabelledEngineCannotAdvanceOrRewriteAnExistingTank(@TempDir Path directory) throws Exception {
        var service = new EcologyService(new EcologyPersistence(directory));
        service.execute(OWNER, new String[]{"start", "42"});
        var file = directory.resolve(OWNER + ".ecology.json");
        String original = Files.readString(file);
        for (String version : new String[]{EcologyWorld.LEGACY_ENGINE_VERSION, "genesis-ecology/99"}) {
            String changed = original.replace(EcologyWorld.ENGINE_VERSION, version);
            Files.writeString(file, changed);
            assertThrows(IllegalArgumentException.class, () -> service.execute(OWNER, new String[]{"step", "1"}));
            assertThrows(IllegalStateException.class, () -> service.execute(OWNER, new String[]{"replay"}));
            assertEquals(changed, Files.readString(file));
        }
    }

    @Test void foreignWorldCannotBeSavedOrReadEvenWithARewrittenOwnerWrapper(@TempDir Path directory) throws Exception {
        var persistence = new EcologyPersistence(directory);
        new EcologyService(persistence).execute(OWNER, new String[]{"start"});
        var replay = persistence.load(OWNER).orElseThrow();
        assertThrows(IOException.class, () -> persistence.save(OTHER, replay));
        String stored = Files.readString(directory.resolve(OWNER + ".ecology.json"));
        Files.writeString(directory.resolve(OTHER + ".ecology.json"), stored.replace(OWNER.toString(), OTHER.toString()));
        assertThrows(IOException.class, () -> persistence.load(OTHER));
        assertEquals(replay, persistence.load(OWNER).orElseThrow());
    }

    @Test void failedSaveBeyondTheOldHorizonLeavesThePreviousReplayAndRetryIsExact(@TempDir Path directory) throws Exception {
        var persistence = new EcologyPersistence(directory);
        EcologyReplay before;
        EcologyReplay expected;
        try (var world = new EcologyWorld(worldId(), 52_008, EcologyConfig.forDuration(1_536))) {
            Map<String, MatterProgram> programs = candidate();
            world.step(new EcologyInput(0, programs));
            while (world.tick() < 511) world.step(EcologyInput.idle(world.tick()));
            before = world.replay();
            persistence.save(OWNER, before);
            for (int tick = 0; tick < 20 && !world.view().terminal(); tick++) world.step(EcologyInput.idle(world.tick()));
            expected = world.replay();
        }
        Path file = directory.resolve(OWNER + ".ecology.json");
        byte[] beforeBytes = Files.readAllBytes(file);
        Path temporary = directory.resolve(OWNER + ".ecology.json.tmp");
        Files.createDirectory(temporary);
        var service = new EcologyService(persistence);
        assertThrows(IOException.class, () -> service.execute(OWNER, new String[]{"step", "20"}));
        assertArrayEquals(beforeBytes, Files.readAllBytes(file));
        assertEquals(before, persistence.load(OWNER).orElseThrow());
        Files.delete(temporary);
        new EcologyService(new EcologyPersistence(directory)).execute(OWNER, new String[]{"step", "20"});
        assertEquals(expected, persistence.load(OWNER).orElseThrow());
        assertTrue(EcologyReplayVerifier.verify(expected).passed());
    }

    @Test void maximumInputEnvelopeFitsFourMiBAndStillRequiresValidReplayCommitments(@TempDir Path directory) throws Exception {
        // Capacity probe only: maximum-size inputs in all 1,536 frames, with intentionally invalid commitments.
        var updates = Map.of(
                "producer", new MatterProgram("producer", MatterProgram.Role.PRODUCER, 8, 32, 512, 500),
                "grazer", new MatterProgram("grazer", MatterProgram.Role.GRAZER, 8, 32, 512, 500),
                "decomposer", new MatterProgram("decomposer", MatterProgram.Role.DECOMPOSER, 8, 32, 512, 500));
        var frames = new ArrayList<EcologyReplay.Frame>();
        for (int tick = 0; tick < EcologyConfig.MAX_TICKS; tick++)
            frames.add(new EcologyReplay.Frame(new EcologyInput(tick, updates), "0".repeat(64), "0".repeat(64)));
        var envelope = new EcologyReplay(1, EcologyWorld.ENGINE_VERSION, worldId(), Long.MIN_VALUE,
                EcologyConfig.forDuration(EcologyConfig.MAX_TICKS), "0".repeat(64), frames);
        var persistence = new EcologyPersistence(directory);
        persistence.save(OWNER, envelope);
        assertTrue(Files.size(directory.resolve(OWNER + ".ecology.json")) < EcologyPersistence.MAX_FILE_BYTES);
        assertEquals(envelope, persistence.load(OWNER).orElseThrow());
        assertThrows(IllegalArgumentException.class, () -> new EcologyService(persistence).execute(OWNER, new String[]{"dashboard"}));
    }

    private Map<String, MatterProgram> candidate() {
        return Map.of("producer", new MatterProgram("producer", MatterProgram.Role.PRODUCER, 6, 2, 512, 0),
                "grazer", new MatterProgram("grazer", MatterProgram.Role.GRAZER, 1, 12, 512, 0),
                "decomposer", new MatterProgram("decomposer", MatterProgram.Role.DECOMPOSER, 1, 12, 512, 0));
    }

    private String worldId() { return "ecology-" + OWNER.toString().replace("-", ""); }
}
