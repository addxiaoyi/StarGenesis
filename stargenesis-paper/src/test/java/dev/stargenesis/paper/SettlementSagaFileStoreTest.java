package dev.stargenesis.paper;

import dev.stargenesis.integrations.SettlementCheckpoint;
import dev.stargenesis.integrations.SettlementPlan;
import dev.stargenesis.integrations.SettlementTransfer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.List;
import java.util.UUID;

import static dev.stargenesis.integrations.SettlementCheckpoint.State.*;
import static org.junit.jupiter.api.Assertions.*;

class SettlementSagaFileStoreTest {
    private static final UUID SAGA = UUID.fromString("00000000-0000-0000-0000-000000000041");
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000042");

    @Test
    void checkpointsAreChecksummedAtomicAndReloadable(@TempDir Path directory) throws Exception {
        SettlementSagaFileStore store = new SettlementSagaFileStore(directory);
        SettlementCheckpoint prepared = prepared();
        store.save(prepared);
        assertEquals(prepared, store.find(SAGA).orElseThrow());

        SettlementCheckpoint applying = new SettlementCheckpoint(prepared.plan(), APPLYING, 0, 1, "");
        store.save(applying);
        assertEquals(List.of(applying), store.loadAll());
        assertThrows(IOException.class, () -> store.save(prepared));
    }

    @Test
    void corruptAndIdentityMismatchedRecordsFailClosed(@TempDir Path directory) throws Exception {
        SettlementSagaFileStore store = new SettlementSagaFileStore(directory);
        store.save(prepared());
        Path checkpoint = directory.resolve("settlement-sagas").resolve(SAGA + ".saga");
        byte[] encoded = Files.readAllBytes(checkpoint);
        encoded[encoded.length - 1] ^= 1;
        Files.write(checkpoint, encoded);

        assertThrows(IOException.class, () -> store.find(SAGA));
        assertThrows(IOException.class, store::loadAll);
    }

    @Test
    void invalidStateTransitionsAreRejectedBeforeReplacingTheCheckpoint(@TempDir Path directory) throws Exception {
        SettlementSagaFileStore store = new SettlementSagaFileStore(directory);
        SettlementCheckpoint prepared = prepared();
        store.save(prepared);
        SettlementCheckpoint skippedAhead = new SettlementCheckpoint(prepared.plan(), COMMITTED, 1, 1, "");

        assertThrows(IOException.class, () -> store.save(skippedAhead));
        assertEquals(prepared, store.find(SAGA).orElseThrow());
    }

    @Test
    void everyValidTransferAndDiagnosticFitsTheCheckpointBudget(@TempDir Path directory) throws Exception {
        SettlementTransfer maximum = new SettlementTransfer("\u4e00".repeat(128), "\u4e01".repeat(128),
                "\u4e02".repeat(128), SettlementTransfer.MAX_AMOUNT);
        SettlementPlan plan = new SettlementPlan(SAGA, ACTOR, "s".repeat(64),
                java.util.Collections.nCopies(SettlementPlan.MAX_TRANSFERS, maximum));
        SettlementSagaFileStore store = new SettlementSagaFileStore(directory);
        store.save(new SettlementCheckpoint(plan, PREPARED, 0, 0, ""));
        store.save(new SettlementCheckpoint(plan, APPLYING, 0, 1, ""));
        SettlementCheckpoint review = new SettlementCheckpoint(plan, APPLY_REVIEW, 0, 2, "\u4e03".repeat(240));
        store.save(review);

        assertEquals(review, store.find(SAGA).orElseThrow());
    }

    @Test
    void everyLegacyV1StateReadsThroughAFixedMappingWithoutRewriting(@TempDir Path directory) throws Exception {
        for (StateFixture fixture : stateFixtures()) {
            Path legacyDirectory = directory.resolve("legacy-" + fixture.legacyCode());
            byte[] encoded = independentRecord(1, fixture.legacyCode(), fixture.cursor(), fixture.revision());
            Path file = writeFixture(legacyDirectory, encoded);
            SettlementSagaFileStore store = new SettlementSagaFileStore(legacyDirectory);
            SettlementCheckpoint expected = new SettlementCheckpoint(prepared().plan(), fixture.state(),
                    fixture.cursor(), fixture.revision(), "");

            assertEquals(expected, store.find(SAGA).orElseThrow(), fixture.state().toString());
            assertEquals(List.of(expected), store.loadAll(), fixture.state().toString());
            assertArrayEquals(encoded, Files.readAllBytes(file), "Reading must not migrate a legacy file");
            assertEquals(1, wireHeader(Files.readAllBytes(file)).format());
        }
    }

    @Test
    void newV2WritesUseFixedCodesForEveryStateAndRoundTrip(@TempDir Path directory) throws Exception {
        for (StateFixture fixture : stateFixtures()) {
            Path stateDirectory = directory.resolve("stable-" + fixture.stableCode());
            SettlementSagaFileStore store = new SettlementSagaFileStore(stateDirectory);
            List<SettlementCheckpoint> path = pathTo(fixture.state());
            for (SettlementCheckpoint checkpoint : path) store.save(checkpoint);
            SettlementCheckpoint expected = path.getLast();
            byte[] encoded = Files.readAllBytes(stateDirectory.resolve("settlement-sagas").resolve(SAGA + ".saga"));

            assertEquals(new WireHeader(2, fixture.stableCode()), wireHeader(encoded), fixture.state().toString());
            assertEquals(expected, new SettlementSagaFileStore(stateDirectory).find(SAGA).orElseThrow());
            assertEquals(List.of(expected), new SettlementSagaFileStore(stateDirectory).loadAll());
            assertTrue(encoded.length <= SettlementSagaFileStore.MAX_FILE_BYTES);
        }
    }

    @Test
    void legacyFileChangesToV2OnlyWhenALegalNextCheckpointIsExplicitlySaved(@TempDir Path directory) throws Exception {
        byte[] legacy = independentRecord(1, 3, 0, 2); // Fixed v1 APPLY_REVIEW code.
        Path file = writeFixture(directory, legacy);
        SettlementSagaFileStore store = new SettlementSagaFileStore(directory);
        SettlementCheckpoint review = store.find(SAGA).orElseThrow();
        assertEquals(APPLY_REVIEW, review.state());
        assertArrayEquals(legacy, Files.readAllBytes(file));

        SettlementCheckpoint applied = new SettlementCheckpoint(review.plan(), APPLYING, 1, 3, "");
        store.save(applied);

        assertEquals(new WireHeader(2, 0x20), wireHeader(Files.readAllBytes(file)));
        assertEquals(applied, new SettlementSagaFileStore(directory).find(SAGA).orElseThrow());
    }

    @Test
    void validChecksumsDoNotMakeUnknownFormatsOrStateCodesAcceptable(@TempDir Path directory) throws Exception {
        List<RawFixture> invalid = List.of(
                new RawFixture(0, 0, 0, 0), new RawFixture(3, 0, 0, 0),
                new RawFixture(-1, 0, 0, 0), new RawFixture(Integer.MAX_VALUE, 0, 0, 0),
                new RawFixture(1, 7, 0, 0), new RawFixture(1, 255, 0, 0),
                new RawFixture(2, 0, 0, 0), new RawFixture(2, 1, 0, 0),
                new RawFixture(2, 5, 0, 0), new RawFixture(2, 0x11, 0, 0),
                new RawFixture(2, 255, 0, 0));
        for (int index = 0; index < invalid.size(); index++) {
            RawFixture fixture = invalid.get(index);
            Path invalidDirectory = directory.resolve("unknown-" + index);
            byte[] encoded = independentRecord(fixture.format(), fixture.stateCode(), fixture.cursor(), fixture.revision());
            Path file = writeFixture(invalidDirectory, encoded);
            SettlementSagaFileStore store = new SettlementSagaFileStore(invalidDirectory);

            IOException failure = assertThrows(IOException.class, () -> store.find(SAGA));
            assertTrue(failure.getMessage().contains(fixture.format() == 1 || fixture.format() == 2 ? "state" : "format"));
            assertFalse(failure.getMessage().contains("checksum"));
            assertThrows(IOException.class, store::loadAll);
            assertArrayEquals(encoded, Files.readAllBytes(file));
        }
    }

    @Test
    void validChecksumsAndKnownCodesStillRequireLegalStateCursorAndRevision(@TempDir Path directory) throws Exception {
        int index = 0;
        for (StateFixture fixture : stateFixtures()) {
            if (fixture.state() == APPLYING || fixture.state() == COMPENSATING) continue;
            int invalidCursor = fixture.cursor() == 0 ? 1 : 0;
            for (int format : List.of(1, 2)) {
                int code = format == 1 ? fixture.legacyCode() : fixture.stableCode();
                Path invalidDirectory = directory.resolve("invalid-state-" + index++);
                writeFixture(invalidDirectory, independentRecord(format, code, invalidCursor, fixture.revision()));
                SettlementSagaFileStore store = new SettlementSagaFileStore(invalidDirectory);
                IOException failure = assertThrows(IOException.class, () -> store.find(SAGA));
                assertEquals("Settlement saga checkpoint failed validation", failure.getMessage());
                assertThrows(IOException.class, store::loadAll);
            }
        }
        for (RawFixture fixture : List.of(new RawFixture(1, 1, -1, 1), new RawFixture(2, 0x20, 2, 1),
                new RawFixture(1, 1, 0, -1), new RawFixture(2, 0x20, 0, -1))) {
            Path invalidDirectory = directory.resolve("invalid-bounds-" + index++);
            writeFixture(invalidDirectory,
                    independentRecord(fixture.format(), fixture.stateCode(), fixture.cursor(), fixture.revision()));
            SettlementSagaFileStore store = new SettlementSagaFileStore(invalidDirectory);
            IOException failure = assertThrows(IOException.class, () -> store.find(SAGA));
            assertEquals("Settlement saga checkpoint failed validation", failure.getMessage());
        }
    }

    private record StateFixture(SettlementCheckpoint.State state, int legacyCode, int stableCode,
                                int cursor, long revision) { }

    private record RawFixture(int format, int stateCode, int cursor, long revision) { }

    private record WireHeader(int format, int stateCode) { }

    /** The original v1 codes and assigned v2 codes are fixtures, not derived from enum order. */
    private static List<StateFixture> stateFixtures() {
        return List.of(new StateFixture(PREPARED, 0, 0x10, 0, 0),
                new StateFixture(APPLYING, 1, 0x20, 0, 1),
                new StateFixture(COMPENSATING, 2, 0x30, 1, 3),
                new StateFixture(APPLY_REVIEW, 3, 0x40, 0, 2),
                new StateFixture(COMPENSATION_REVIEW, 4, 0x50, 1, 4),
                new StateFixture(COMMITTED, 5, 0x60, 1, 3),
                new StateFixture(ABORTED, 6, 0x70, 0, 2));
    }

    private static List<SettlementCheckpoint> pathTo(SettlementCheckpoint.State state) {
        SettlementCheckpoint prepared = prepared();
        SettlementCheckpoint applying = new SettlementCheckpoint(prepared.plan(), APPLYING, 0, 1, "");
        SettlementCheckpoint applied = new SettlementCheckpoint(prepared.plan(), APPLYING, 1, 2, "");
        SettlementCheckpoint compensating = new SettlementCheckpoint(prepared.plan(), COMPENSATING, 1, 3, "");
        return switch (state) {
            case PREPARED -> List.of(prepared);
            case APPLYING -> List.of(prepared, applying);
            case COMPENSATING -> List.of(prepared, applying, applied, compensating);
            case APPLY_REVIEW -> List.of(prepared, applying,
                    new SettlementCheckpoint(prepared.plan(), APPLY_REVIEW, 0, 2, ""));
            case COMPENSATION_REVIEW -> List.of(prepared, applying, applied, compensating,
                    new SettlementCheckpoint(prepared.plan(), COMPENSATION_REVIEW, 1, 4, ""));
            case COMMITTED -> List.of(prepared, applying, applied,
                    new SettlementCheckpoint(prepared.plan(), COMMITTED, 1, 3, ""));
            case ABORTED -> List.of(prepared, applying,
                    new SettlementCheckpoint(prepared.plan(), ABORTED, 0, 2, ""));
        };
    }

    /** Handwritten frozen layout; deliberately does not call the production encoder. */
    private static byte[] independentRecord(int format, int stateCode, int cursor, long revision) throws Exception {
        ByteArrayOutputStream bodyBytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bodyBytes)) {
            output.writeInt(0x53475331);
            output.writeInt(format);
            output.writeLong(0L);
            output.writeLong(0x41L);
            output.writeLong(0L);
            output.writeLong(0x42L);
            output.writeUTF("starcore");
            output.writeInt(1);
            output.writeUTF("player:a");
            output.writeUTF("escrow");
            output.writeUTF("starcore:coin");
            output.writeLong(10L);
            output.writeByte(stateCode);
            output.writeInt(cursor);
            output.writeLong(revision);
            output.writeUTF("");
        }
        byte[] body = bodyBytes.toByteArray();
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        encoded.write(body);
        encoded.write(MessageDigest.getInstance("SHA-256").digest(body));
        return encoded.toByteArray();
    }

    private static Path writeFixture(Path directory, byte[] encoded) throws IOException {
        Path journal = Files.createDirectories(directory.resolve("settlement-sagas"));
        Path file = journal.resolve(SAGA + ".saga");
        Files.write(file, encoded);
        return file;
    }

    private static WireHeader wireHeader(byte[] encoded) throws IOException {
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            assertEquals(0x53475331, input.readInt());
            int format = input.readInt();
            input.skipNBytes(4 * Long.BYTES);
            input.readUTF();
            int count = input.readInt();
            for (int index = 0; index < count; index++) {
                input.readUTF();
                input.readUTF();
                input.readUTF();
                input.readLong();
            }
            return new WireHeader(format, input.readUnsignedByte());
        }
    }

    private static SettlementCheckpoint prepared() {
        SettlementPlan plan = new SettlementPlan(SAGA, ACTOR, "starcore", List.of(
                new SettlementTransfer("player:a", "escrow", "starcore:coin", 10)));
        return new SettlementCheckpoint(plan, PREPARED, 0, 0, "");
    }
}
