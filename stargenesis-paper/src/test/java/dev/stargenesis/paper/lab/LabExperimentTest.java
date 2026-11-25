package dev.stargenesis.paper.lab;

import dev.stargenesis.api.sim.SimulationBudget;
import dev.stargenesis.api.sim.StepInput;
import dev.stargenesis.sim.ReplayVerifier;
import dev.stargenesis.sim.ResourceSandboxWorld;
import dev.stargenesis.sim.SandboxConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LabExperimentTest {
    private static final UUID OWNER = new UUID(0, 91);
    @TempDir Path directory;
    private LabPersistence persistence;

    @BeforeEach void setup() throws Exception { persistence = new LabPersistence(directory); }

    @Test void stoppingPublishesMatchingCheckpointAndReplayAndReadOnlyReplayLeavesThemUntouched() throws Exception {
        var experiment = experiment(LabExperiment.Mode.NEW);
        advanceTo(experiment, 135);
        assertEquals(100, persistence.loadSnapshot(OWNER).orElseThrow().tick());
        experiment.advance(true);
        assertEquals(LabExperiment.Phase.STOPPED, experiment.phase());
        assertEquals(135, persistence.loadSnapshot(OWNER).orElseThrow().tick());
        assertEquals(135, persistence.loadReplay(OWNER).orElseThrow().frames().size());
        assertTrue(ReplayVerifier.verify(persistence.loadReplay(OWNER).orElseThrow()).passed());
        byte[] saved = Files.readAllBytes(file());
        experiment.advance(true);
        experiment.close();
        assertArrayEquals(saved, Files.readAllBytes(file()));
        var replay = experiment(LabExperiment.Mode.REPLAY);
        while (replay.active()) replay.advance(false);
        assertEquals(LabExperiment.Phase.FINISHED, replay.phase());
        assertEquals("REPLAY_HASH_AND_EVENTS_VERIFIED", replay.message());
        assertEquals(experiment.view(), replay.view());
        replay.close();
        assertArrayEquals(saved, Files.readAllBytes(file()));
    }

    @Test void stoppingBeforeInitializationPreservesThePreviousRun() throws Exception {
        var previous = savedRun(12);
        byte[] saved = Files.readAllBytes(file());
        var cancelled = experiment(LabExperiment.Mode.NEW);
        cancelled.advance(true);
        assertEquals(LabExperiment.Phase.STOPPED, cancelled.phase());
        assertNull(cancelled.view());
        cancelled.close();
        assertArrayEquals(saved, Files.readAllBytes(file()));
        assertEquals(previous.view().worldId(), persistence.loadSnapshot(OWNER).orElseThrow().worldId());
    }

    @Test void failedInitialSaveCanBeCancelledWithoutReplacingTheLastSuccessfulRun() throws Exception {
        savedRun(12);
        byte[] previous = Files.readAllBytes(file());
        Path blocker = block();
        var experiment = experiment(LabExperiment.Mode.NEW);
        assertThrows(IOException.class, () -> experiment.advance(false));
        assertEquals(LabExperiment.Phase.SAVING, experiment.phase());
        assertEquals(0, experiment.view().tick());
        experiment.advance(true);
        assertEquals(LabExperiment.Phase.STOPPED, experiment.phase());
        Files.delete(blocker);
        experiment.close();
        assertArrayEquals(previous, Files.readAllBytes(file()));
    }

    @Test void failedInitialSaveRetryBeginsAtTickZeroAndDoesNotSkipInputs() throws Exception {
        Path blocker = block();
        var experiment = experiment(LabExperiment.Mode.NEW);
        assertThrows(IOException.class, () -> experiment.advance(false));
        assertFalse(Files.exists(file()));
        Files.delete(blocker);
        experiment.advance(false);
        assertEquals(0, experiment.view().tick());
        assertEquals(LabExperiment.Phase.RUNNING, experiment.phase());
        assertEquals(0, persistence.loadSnapshot(OWNER).orElseThrow().tick());
        experiment.advance(false);
        assertEquals(1, experiment.view().tick());
        experiment.close();
        assertEquals(1, persistence.loadReplay(OWNER).orElseThrow().frames().size());
    }

    @Test void checkpointFailureFreezesSimulationAndRetryPublishesBothViewsTogether() throws Exception {
        var experiment = experiment(LabExperiment.Mode.NEW);
        advanceTo(experiment, 99);
        byte[] before = Files.readAllBytes(file());
        Path blocker = block();
        assertThrows(IOException.class, () -> experiment.advance(false));
        var frozen = experiment.view();
        assertEquals(100, frozen.tick());
        assertEquals(LabExperiment.Phase.SAVING, experiment.phase());
        assertTrue(experiment.active());
        assertThrows(IOException.class, () -> experiment.advance(false));
        assertEquals(frozen, experiment.view());
        assertArrayEquals(before, Files.readAllBytes(file()));
        assertEquals(0, persistence.loadReplay(OWNER).orElseThrow().frames().size());
        Files.delete(blocker);
        experiment.advance(false);
        assertEquals(frozen, experiment.view());
        assertEquals(100, persistence.loadSnapshot(OWNER).orElseThrow().tick());
        assertEquals(100, persistence.loadReplay(OWNER).orElseThrow().frames().size());
        experiment.advance(false);
        assertEquals(101, experiment.view().tick());
        experiment.close();
    }

    @Test void failedStopRetriesWithoutAdvancingAndRepeatedStopIsIdempotent() throws Exception {
        var experiment = experiment(LabExperiment.Mode.NEW);
        advanceTo(experiment, 25);
        byte[] before = Files.readAllBytes(file());
        Path blocker = block();
        assertThrows(IOException.class, () -> experiment.advance(true));
        assertThrows(IOException.class, () -> experiment.advance(true));
        assertEquals(25, experiment.view().tick());
        assertEquals(LabExperiment.Phase.SAVING, experiment.phase());
        assertArrayEquals(before, Files.readAllBytes(file()));
        Files.delete(blocker);
        experiment.advance(false);
        assertEquals(LabExperiment.Phase.STOPPED, experiment.phase());
        assertEquals(25, persistence.loadSnapshot(OWNER).orElseThrow().tick());
        byte[] stopped = Files.readAllBytes(file());
        experiment.advance(true);
        experiment.close();
        assertArrayEquals(stopped, Files.readAllBytes(file()));
    }

    @Test void terminalSaveFailureRetriesFinalStateAndTerminalResumeDoesNotStepAgain() throws Exception {
        var defaults = SandboxConfig.defaults();
        var shortConfig = new SandboxConfig(9, 5, 2, 5, 3, defaults.energyPerAgent(), defaults.nodeStock(),
                defaults.carryCapacity(), defaults.budget());
        var experiment = new LabExperiment(OWNER, "lab-short", 42, shortConfig, LabExperiment.Mode.NEW,
                SimulationBudget.defaults(), persistence);
        advanceTo(experiment, 2);
        Path blocker = block();
        assertThrows(IOException.class, () -> experiment.advance(false));
        assertTrue(experiment.view().terminal());
        assertEquals(3, experiment.view().tick());
        Files.delete(blocker);
        experiment.advance(false);
        assertEquals(LabExperiment.Phase.FINISHED, experiment.phase());
        byte[] before = Files.readAllBytes(file());
        var resumed = experiment(LabExperiment.Mode.RESUME);
        resumed.advance(false);
        assertEquals(LabExperiment.Phase.FINISHED, resumed.phase());
        assertEquals(experiment.view(), resumed.view());
        resumed.close();
        assertArrayEquals(before, Files.readAllBytes(file()));
    }

    @Test void closeCanRetryAndFinalShutdownAbortPreservesTheLastDurableCheckpoint() throws Exception {
        var experiment = experiment(LabExperiment.Mode.NEW);
        advanceTo(experiment, 25);
        Path blocker = block();
        assertThrows(IOException.class, experiment::close);
        assertEquals(LabExperiment.Phase.SAVING, experiment.phase());
        Files.delete(blocker);
        experiment.close();
        assertEquals(LabExperiment.Phase.STOPPED, experiment.phase());
        assertEquals(25, persistence.loadSnapshot(OWNER).orElseThrow().tick());
        var resumed = experiment(LabExperiment.Mode.RESUME);
        resumed.advance(false);
        assertEquals(26, resumed.view().tick());
        byte[] before = Files.readAllBytes(file());
        blocker = block();
        assertThrows(IOException.class, resumed::close);
        resumed.abort("SHUTDOWN_SAVE_FAILED_PREVIOUS_CHECKPOINT_PRESERVED");
        resumed.close();
        assertEquals(LabExperiment.Phase.ERROR, resumed.phase());
        assertArrayEquals(before, Files.readAllBytes(file()));
        Files.delete(blocker);
        var restarted = experiment(LabExperiment.Mode.RESUME);
        restarted.advance(false);
        assertEquals(26, restarted.view().tick());
        restarted.close();
    }

    @Test void capturedLegacyCheckpointContinuesWithOriginalHashesAndPreservesLegacyFiles() throws Exception {
        legacy("snapshot"); legacy("replay");
        byte[] oldSnapshot = Files.readAllBytes(legacyPath("snapshot"));
        byte[] oldReplay = Files.readAllBytes(legacyPath("replay"));
        assertEquals("f72a9e0fc1ffbb4d904d9546e18b3dfcaae421e7ce13a65674e3ac65ce3955c0",
                persistence.loadSnapshot(OWNER).orElseThrow().stateHash());
        var resumed = experiment(LabExperiment.Mode.RESUME);
        advanceTo(resumed, 100);
        assertEquals("6e683a16dc594cc47ec543dd035bd2ecc2d902a1eb7096958ef31e70cbf7a703", resumed.view().stateHash());
        resumed.advance(true);
        assertEquals("lab-legacy-1", resumed.view().worldId());
        assertEquals(100, persistence.loadReplay(OWNER).orElseThrow().frames().size());
        assertTrue(ReplayVerifier.verify(persistence.loadReplay(OWNER).orElseThrow()).passed());
        assertArrayEquals(oldSnapshot, Files.readAllBytes(legacyPath("snapshot")));
        assertArrayEquals(oldReplay, Files.readAllBytes(legacyPath("replay")));
    }

    @Test void interruptedReplayAndInvalidReplayNeverReplaceTheCheckpointOrRecording() throws Exception {
        savedRun(30);
        byte[] before = Files.readAllBytes(file());
        var replay = experiment(LabExperiment.Mode.REPLAY);
        advanceTo(replay, 5);
        replay.advance(true);
        assertEquals(LabExperiment.Phase.STOPPED, replay.phase());
        assertArrayEquals(before, Files.readAllBytes(file()));
        // Exercise legacy incremental replay rejection without touching the valid combined evidence.
        var legacyDirectory = directory.resolve("legacy");
        Files.createDirectories(legacyDirectory);
        String legacyJson;
        try (var fixture = getClass().getResourceAsStream("/lab-v1-replay.json")) {
            assertNotNull(fixture);
            legacyJson = new String(fixture.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        legacyJson = legacyJson.replaceFirst("\"stateHash\":\"[0-9a-f]{64}\"", "\"stateHash\":\"" + "0".repeat(64) + "\"");
        Path corrupt = legacyDirectory.resolve(OWNER + ".replay.json");
        Files.writeString(corrupt, legacyJson);
        var invalid = new LabExperiment(OWNER, "unused", 42, SandboxConfig.defaults(), LabExperiment.Mode.REPLAY,
                SimulationBudget.defaults(), new LabPersistence(legacyDirectory));
        assertThrows(IllegalArgumentException.class, () -> invalid.advance(false));
        assertEquals(LabExperiment.Phase.ERROR, invalid.phase());
        invalid.close();
        assertEquals(legacyJson, Files.readString(corrupt));
        assertFalse(Files.exists(legacyDirectory.resolve(OWNER + ".lab.json")));
        assertArrayEquals(before, Files.readAllBytes(file()));
    }

    @Test void resumedHistoryCannotExceedTheCurrentHostBudget() throws Exception {
        savedRun(30);
        byte[] before = Files.readAllBytes(file());
        var tighter = new SimulationBudget(64, 16, 8, 3_000, 16, 64);
        var rejected = new LabExperiment(OWNER, "unused", 42, SandboxConfig.defaults(), LabExperiment.Mode.RESUME,
                tighter, persistence);
        assertThrows(IllegalArgumentException.class, () -> rejected.advance(false));
        assertEquals(LabExperiment.Phase.ERROR, rejected.phase());
        rejected.close();
        assertArrayEquals(before, Files.readAllBytes(file()));
    }

    @Test void stoppingWhileCheckpointIsPendingPersistsThatTickAsStopped() throws Exception {
        var experiment = experiment(LabExperiment.Mode.NEW);
        advanceTo(experiment, 99);
        Path blocker = block();
        assertThrows(IOException.class, () -> experiment.advance(false));
        assertThrows(IOException.class, () -> experiment.advance(true));
        assertEquals(100, experiment.view().tick());
        Files.delete(blocker);
        experiment.advance(false);
        assertEquals(LabExperiment.Phase.STOPPED, experiment.phase());
        assertEquals(100, persistence.loadSnapshot(OWNER).orElseThrow().tick());
        assertTrue(ReplayVerifier.verify(persistence.loadReplay(OWNER).orElseThrow()).passed());
    }

    @Test void repeatingASeedStartsANewIdentityAndResumeRetainsThatIdentity() throws Exception {
        var first = savedRun(10);
        var second = savedRun(10);
        assertEquals(first.seed(), second.seed());
        assertNotEquals(first.view().worldId(), second.view().worldId());
        assertNotEquals(first.view().stateHash(), second.view().stateHash());
        var resumed = experiment(LabExperiment.Mode.RESUME);
        resumed.advance(false);
        assertEquals(second.view().worldId(), resumed.view().worldId());
        assertEquals(11, resumed.view().tick());
        resumed.close();
    }

    private LabExperiment experiment(LabExperiment.Mode mode) {
        return new LabExperiment(OWNER, "lab-" + UUID.randomUUID(), 42, SandboxConfig.defaults(), mode,
                SimulationBudget.defaults(), persistence);
    }

    private LabExperiment savedRun(int ticks) throws IOException {
        var experiment = experiment(LabExperiment.Mode.NEW);
        advanceTo(experiment, ticks);
        experiment.advance(true);
        return experiment;
    }

    private void advanceTo(LabExperiment experiment, int ticks) throws IOException {
        while (experiment.view() == null || experiment.view().tick() < ticks) {
            assertTrue(experiment.active());
            experiment.advance(false);
        }
    }

    private Path file() { return directory.resolve(OWNER + ".lab.json"); }
    private Path block() throws IOException { return Files.createDirectory(directory.resolve(OWNER + ".lab.json.tmp")); }
    private Path legacyPath(String type) { return directory.resolve(OWNER + "." + type + ".json"); }
    private void legacy(String type) throws IOException {
        try (var fixture = getClass().getResourceAsStream("/lab-v1-" + type + ".json")) {
            assertNotNull(fixture);
            Files.copy(fixture, legacyPath(type));
        }
    }
}
