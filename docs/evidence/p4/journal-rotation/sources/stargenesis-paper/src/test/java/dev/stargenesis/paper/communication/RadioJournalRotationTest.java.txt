package dev.stargenesis.paper.communication;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Finite journal rotation through the real service, codecs, atomic files and recovery validators. */
class RadioJournalRotationTest {
    private static final UUID ALICE = new UUID(0, 81), BOB = new UUID(0, 82), OTHER = new UUID(0, 83);
    private static final Set<VirtualMessageBus.ProtocolKey> INSTALLED = Set.of(
            RadioProtocol.key(), ServiceContractDefinition.key());
    private static final VirtualMessageBus.Endpoint ENDPOINT = new VirtualMessageBus.Endpoint("journal", 0, 0, 0, 16);
    @TempDir Path directory;

    @Test void rotationPreservesOldRequestIdentityAcrossRestartsAndFreesOnlyTheActiveSegment() throws Exception {
        var service = service(1);
        join(service);
        deliveredAndAcknowledged(service, 1);
        long tick = service.tick();
        assertEquals(1, service.rotate());
        assertEquals(1, service.archiveCount());
        assertEquals(tick, service.tick());
        assertEquals(2, service.participantCount());
        assertEquals(0, service.retainedEvidenceCount());
        assertTrue(Files.isRegularFile(archive(1)));
        byte[] immutableArchive = Files.readAllBytes(archive(1));
        assertFalse(service.send(ALICE, RadioProtocol.protocol(), BOB, 1, radio(1)).queued());
        assertThrows(IllegalArgumentException.class,
                () -> service.send(ALICE, RadioProtocol.protocol(), BOB, 1, radio(2)));
        assertThrows(IllegalArgumentException.class,
                () -> service.send(ALICE, RadioProtocol.protocol(), OTHER, 1, radio(1)));
        assertTrue(service.send(ALICE, RadioProtocol.protocol(), BOB, 2, radio(2)).queued());
        assertThrows(IllegalStateException.class,
                () -> service.send(ALICE, RadioProtocol.protocol(), BOB, 3, radio(3)));
        service.advance();
        assertTrue(service.acknowledge(BOB, service.poll(BOB, 1).getFirst()));
        assertEquals(2, service.rotate());
        assertArrayEquals(immutableArchive, Files.readAllBytes(archive(1)));

        var restarted = service(1);
        assertEquals(2, restarted.archiveCount());
        assertEquals(0, restarted.participantCount());
        assertEquals(0, restarted.retainedEvidenceCount());
        assertFalse(restarted.send(ALICE, RadioProtocol.protocol(), BOB, 1, radio(1)).queued());
        assertFalse(restarted.send(ALICE, RadioProtocol.protocol(), BOB, 2, radio(2)).queued());
        assertThrows(IllegalArgumentException.class,
                () -> restarted.send(ALICE, RadioProtocol.protocol(), BOB, 2, radio(1)));
        assertThrows(IllegalArgumentException.class,
                () -> restarted.send(ALICE, RadioProtocol.protocol(), BOB, 3, radio(3)));
        join(restarted);
        assertTrue(restarted.send(ALICE, RadioProtocol.protocol(), BOB, 3, radio(3)).queued());
        assertArrayEquals(immutableArchive, Files.readAllBytes(archive(1)));
    }

    @Test void pendingMessagesBlockRotationWithoutChangingMemoryOrDisk() throws Exception {
        var service = service(8);
        join(service);
        service.send(ALICE, RadioProtocol.protocol(), BOB, 1, radio(1));
        var before = service.radioSnapshot();
        byte[] bytes = Files.readAllBytes(file());
        assertThrows(IllegalStateException.class, service::rotate);
        assertEquals(before, service.radioSnapshot());
        assertArrayEquals(bytes, Files.readAllBytes(file()));
        assertEquals(0, service.archiveCount());
        assertEquals(1, service.pendingCount(BOB));
        service.advance();
        assertTrue(service.acknowledge(BOB, service.poll(BOB, 1).getFirst()));
        assertEquals(1, service.rotate());
    }

    @Test void activeContractBlocksRotationEvenAfterItsOfferWasAcknowledged() throws Exception {
        var service = service(8);
        join(service);
        service.send(ALICE, ServiceContractDefinition.protocol(), BOB, 1, contract("offer", "order", 3));
        service.advance();
        service.inbox(BOB, 8);
        assertEquals(0, service.pendingCount(BOB));
        var radio = service.radioSnapshot();
        var contracts = service.contractSnapshot();
        byte[] bytes = Files.readAllBytes(file());
        assertThrows(IllegalStateException.class, service::rotate);
        assertEquals(radio, service.radioSnapshot());
        assertEquals(contracts, service.contractSnapshot());
        assertArrayEquals(bytes, Files.readAllBytes(file()));
        assertEquals(0, service.archiveCount());
    }

    @Test void terminalContractsRemainPrivateQueryableAndCannotReuseTheirIdentifiers() throws Exception {
        var service = service(8);
        join(service);
        service.send(ALICE, ServiceContractDefinition.protocol(), BOB, 1, contract("offer", "order", 3));
        service.advance();
        service.inbox(BOB, 8);
        service.send(BOB, ServiceContractDefinition.protocol(), ALICE, 1, contract("accept", "order", 3));
        service.advance();
        service.inbox(ALICE, 8);
        service.send(BOB, ServiceContractDefinition.protocol(), ALICE, 2, contract("fulfill", "order", 3));
        service.advance();
        service.inbox(ALICE, 8);
        var completed = service.contract(ALICE, "order");
        assertEquals(ContractRuntime.State.COMPLETED, completed.state());
        assertEquals(1, service.rotate());
        assertEquals(completed, service.contract(BOB, "order"));
        assertThrows(SecurityException.class, () -> service.contract(OTHER, "order"));
        assertFalse(service.send(BOB, ServiceContractDefinition.protocol(), ALICE, 2,
                contract("fulfill", "order", 3)).queued());
        assertThrows(IllegalArgumentException.class, () -> service.send(ALICE,
                ServiceContractDefinition.protocol(), BOB, 3, contract("offer", "order", 3)));
        var restarted = service(8);
        assertEquals(completed, restarted.contract(ALICE, "order"));
        assertThrows(SecurityException.class, () -> restarted.contract(OTHER, "order"));
        join(restarted);
        assertThrows(IllegalArgumentException.class, () -> restarted.send(ALICE,
                ServiceContractDefinition.protocol(), BOB, 3, contract("offer", "order", 3)));
        assertTrue(restarted.send(ALICE, ServiceContractDefinition.protocol(), BOB, 3,
                contract("offer", "new_order", 3)).queued());
    }

    @Test void archivePublicationFailurePreservesTheCommittedSegmentAndCanBeRetried() throws Exception {
        var service = service(8);
        join(service);
        deliveredAndAcknowledged(service, 1);
        var before = service.radioSnapshot();
        byte[] bytes = Files.readAllBytes(file());
        Files.writeString(archiveDirectory(), "not a directory");
        assertThrows(IOException.class, service::rotate);
        assertEquals(before, service.radioSnapshot());
        assertArrayEquals(bytes, Files.readAllBytes(file()));
        assertEquals(0, service.archiveCount());
        assertFalse(service.send(ALICE, RadioProtocol.protocol(), BOB, 1, radio(1)).queued());
        Files.delete(archiveDirectory());
        assertEquals(1, service.rotate());
        assertFalse(service(8).send(ALICE, RadioProtocol.protocol(), BOB, 1, radio(1)).queued());
    }

    @Test void failedActivePublicationLeavesOnePreparedSlotAndPreservesTheOldStateForRetry() throws Exception {
        var service = service(8);
        join(service);
        deliveredAndAcknowledged(service, 1);
        var before = service.radioSnapshot();
        byte[] bytes = Files.readAllBytes(file());
        Path blocker = blockActivePublication();
        assertThrows(IOException.class, service::rotate);
        assertEquals(before, service.radioSnapshot());
        assertArrayEquals(bytes, Files.readAllBytes(file()));
        assertEquals(0, service.archiveCount());
        assertTrue(Files.isRegularFile(archive(1)));
        var restarted = service(8);
        assertEquals(before, restarted.radioSnapshot());
        assertEquals(0, restarted.archiveCount());
        unblockActivePublication(blocker);
        assertEquals(1, service.rotate());
        assertEquals(1, service(8).archiveCount());
        assertEquals(1, archiveFileCount());
    }

    @Test void retryAfterMoreCommittedWorkReplacesOnlyTheSameUnreferencedPreparedSlot() throws Exception {
        var service = service(8);
        join(service);
        deliveredAndAcknowledged(service, 1);
        Path blocker = blockActivePublication();
        assertThrows(IOException.class, service::rotate);
        byte[] prepared = Files.readAllBytes(archive(1));
        unblockActivePublication(blocker);
        service.advance();
        deliveredAndAcknowledged(service, 2);
        assertEquals(1, archiveFileCount());
        assertEquals(1, service.rotate());
        assertFalse(java.util.Arrays.equals(prepared, Files.readAllBytes(archive(1))));
        assertEquals(1, archiveFileCount());
        var restarted = service(8);
        assertFalse(restarted.send(ALICE, RadioProtocol.protocol(), BOB, 1, radio(1)).queued());
        assertFalse(restarted.send(ALICE, RadioProtocol.protocol(), BOB, 2, radio(2)).queued());
    }

    @Test void archiveFailureDoesNotFlushAnOtherwiseUnsavedDirtyTick() throws Exception {
        var service = service(8);
        join(service);
        deliveredAndAcknowledged(service, 1);
        byte[] committed = Files.readAllBytes(file());
        service.advance();
        var radio = service.radioSnapshot();
        var contracts = service.contractSnapshot();
        assertEquals(2, service.tick());
        Files.writeString(archiveDirectory(), "not a directory");
        assertThrows(IOException.class, service::rotate);
        assertEquals(radio, service.radioSnapshot());
        assertEquals(contracts, service.contractSnapshot());
        assertArrayEquals(committed, Files.readAllBytes(file()));
        assertThrows(IOException.class, () -> service(8));
        Files.delete(archiveDirectory());
        assertEquals(1, service(8).tick());
        assertEquals(1, service.rotate());
        assertEquals(2, service.tick());
        assertEquals(2, service(8).tick());
        assertFalse(service(8).send(ALICE, RadioProtocol.protocol(), BOB, 1, radio(1)).queued());
    }

    @Test void activePublicationFailureDoesNotFlushAnOtherwiseUnsavedDirtyTick() throws Exception {
        var service = service(8);
        join(service);
        deliveredAndAcknowledged(service, 1);
        byte[] committed = Files.readAllBytes(file());
        service.advance();
        var radio = service.radioSnapshot();
        var contracts = service.contractSnapshot();
        Path blocker = blockActivePublication();
        assertThrows(IOException.class, service::rotate);
        assertEquals(radio, service.radioSnapshot());
        assertEquals(contracts, service.contractSnapshot());
        assertArrayEquals(committed, Files.readAllBytes(file()));
        assertEquals(1, service(8).tick());
        assertEquals(1, archiveFileCount());
        unblockActivePublication(blocker);
        assertEquals(1, service.rotate());
        assertEquals(2, service.tick());
        assertEquals(2, service(8).tick());
        assertFalse(service(8).send(ALICE, RadioProtocol.protocol(), BOB, 1, radio(1)).queued());
    }

    @Test void fullAcknowledgedEvidenceCanRotateAndAcceptThe4097thDistinctRequest() throws Exception {
        var bus = new VirtualMessageBus(0, 64);
        bus.register(ALICE, ENDPOINT, INSTALLED);
        bus.register(BOB, ENDPOINT, INSTALLED);
        var contracts = new ContractRuntime(ServiceContractDefinition.contract(), ServiceContractDefinition.protocol(),
                0, ContractRuntime.MAX_RETAINED_CONTRACTS);
        for (int first = 0; first < VirtualMessageBus.MAX_RETAINED_EVIDENCE; first += 64) {
            for (int sequence = first; sequence < first + 64; sequence++)
                assertTrue(bus.sendNow(ALICE, RadioProtocol.protocol(), BOB, sequence, radio(sequence)).queued());
            bus.advance(bus.tick() + 1);
            contracts.advance(contracts.tick() + 1);
            for (var message : bus.poll(BOB, 64)) assertTrue(bus.acknowledge(BOB, message));
        }
        assertEquals(4096, bus.retainedEvidenceCount());
        assertEquals(8192, bus.events().size());
        store().save(bus, contracts);
        var service = service(64);
        join(service);
        assertThrows(IllegalStateException.class,
                () -> service.send(ALICE, RadioProtocol.protocol(), BOB, 4096, radio(4096)));
        assertEquals(1, service.rotate());
        assertFalse(service.send(ALICE, RadioProtocol.protocol(), BOB, 0, radio(0)).queued());
        assertThrows(IllegalArgumentException.class,
                () -> service.send(ALICE, RadioProtocol.protocol(), BOB, 0, radio(1)));
        assertTrue(service.send(ALICE, RadioProtocol.protocol(), BOB, 4096, radio(4096)).queued());
        var restarted = service(64);
        assertEquals(1, restarted.archiveCount());
        assertEquals(1, restarted.retainedEvidenceCount());
        assertEquals(1, restarted.pendingCount(BOB));
        assertFalse(restarted.send(ALICE, RadioProtocol.protocol(), BOB, 4095, radio(4095)).queued());
    }

    @Test void emptySegmentsCannotConsumeAnArchiveSlot() throws Exception {
        var service = service(8);
        join(service);
        assertThrows(IllegalStateException.class, service::rotate);
        deliveredAndAcknowledged(service, 1);
        assertEquals(1, service.rotate());
        byte[] bytes = Files.readAllBytes(file());
        assertThrows(IllegalStateException.class, service::rotate);
        assertArrayEquals(bytes, Files.readAllBytes(file()));
        assertEquals(1, service.archiveCount());
    }

    @Test void repeatedMessageIdentitiesAcrossIndividuallyValidSegmentsFailRecoveryEvenWithMatchingPayloads() throws Exception {
        var service = service(8);
        join(service);
        deliveredAndAcknowledged(service, 1);
        service.rotate();
        deliveredAndAcknowledged(service, 2);
        service.rotate();
        for (var payload : java.util.List.of(radio(1), radio(99))) {
            replaceArchive(2, acknowledgedRadioSnapshot(1, 1, payload));
            assertThrows(IOException.class, () -> service(8));
        }
    }

    @Test void anActiveSegmentCannotReuseAnArchivedIdentityEvenWhenItsOwnHistoryIsValid() throws Exception {
        var service = service(8);
        join(service);
        deliveredAndAcknowledged(service, 1);
        service.rotate();
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode journal = (ObjectNode) mapper.readTree(Files.readAllBytes(file()));
        journal.set("active", mapper.valueToTree(acknowledgedRadioSnapshot(1, 1, radio(1))));
        Files.write(file(), mapper.writeValueAsBytes(journal));
        assertThrows(IOException.class, () -> service(8));
    }

    @Test void terminalContractIdentifiersCannotAppearInMoreThanOneValidSegment() throws Exception {
        var service = service(8);
        join(service);
        service.send(ALICE, ServiceContractDefinition.protocol(), BOB, 1, contract("offer", "order", 3));
        service.advance();
        service.inbox(BOB, 8);
        service.send(ALICE, ServiceContractDefinition.protocol(), BOB, 2, contract("cancel", "order", 0));
        service.advance();
        service.inbox(BOB, 8);
        service.rotate();
        deliveredAndAcknowledged(service, 3);
        service.rotate();
        replaceArchive(2, contractSnapshot(2, 20, true));
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode journal = (ObjectNode) mapper.readTree(Files.readAllBytes(file()));
        journal.set("active", mapper.valueToTree(emptySnapshot(4)));
        Files.write(file(), mapper.writeValueAsBytes(journal));
        assertThrows(IOException.class, () -> service(8));
    }

    @Test void archiveTicksCannotRunBackwardsAcrossOtherwiseValidSegments() throws Exception {
        var service = service(8);
        join(service);
        for (int index = 0; index < 4; index++) service.advance();
        deliveredAndAcknowledged(service, 1);
        service.rotate();
        deliveredAndAcknowledged(service, 2);
        service.rotate();
        replaceArchive(2, acknowledgedRadioSnapshot(0, 2, radio(2)));
        assertThrows(IOException.class, () -> service(8));
    }

    @Test void referencedArchivesWithPendingMessagesOrActiveContractsFailRecovery() throws Exception {
        var service = service(8);
        join(service);
        deliveredAndAcknowledged(service, 1);
        service.rotate();
        var bus = new VirtualMessageBus(0, 8);
        bus.register(ALICE, ENDPOINT, INSTALLED);
        bus.register(BOB, ENDPOINT, INSTALLED);
        bus.sendNow(ALICE, RadioProtocol.protocol(), BOB, 7, radio(7));
        bus.advance(1);
        var contracts = runtime(1);
        replaceArchive(1, snapshot(bus, contracts));
        assertThrows(IOException.class, () -> service(8));
        replaceArchive(1, contractSnapshot(0, 7, false));
        assertThrows(IOException.class, () -> service(8));
    }

    @Test void theSixteenthArchiveIsTheLastAndFurtherRotationPreservesEveryCommittedByte() throws Exception {
        var service = service(8);
        join(service);
        for (int sequence = 1; sequence <= 16; sequence++) {
            deliveredAndAcknowledged(service, sequence);
            assertEquals(sequence, service.rotate());
        }
        byte[] first = Files.readAllBytes(archive(1));
        byte[] last = Files.readAllBytes(archive(16));
        deliveredAndAcknowledged(service, 17);
        var before = service.radioSnapshot();
        byte[] active = Files.readAllBytes(file());
        assertThrows(IllegalStateException.class, service::rotate);
        assertEquals(before, service.radioSnapshot());
        assertEquals(16, service.archiveCount());
        assertEquals(16, archiveFileCount());
        assertArrayEquals(active, Files.readAllBytes(file()));
        assertArrayEquals(first, Files.readAllBytes(archive(1)));
        assertArrayEquals(last, Files.readAllBytes(archive(16)));
        var restarted = service(8);
        assertEquals(16, restarted.archiveCount());
        assertFalse(restarted.send(ALICE, RadioProtocol.protocol(), BOB, 1, radio(1)).queued());
        assertFalse(restarted.send(ALICE, RadioProtocol.protocol(), BOB, 16, radio(16)).queued());
        assertFalse(restarted.send(ALICE, RadioProtocol.protocol(), BOB, 17, radio(17)).queued());
    }

    private RadioPersistence.Snapshot acknowledgedRadioSnapshot(long initialTick, int sequence,
                                                                 Map<String, String> payload) {
        var bus = new VirtualMessageBus(initialTick, 8);
        bus.register(ALICE, ENDPOINT, INSTALLED);
        bus.register(BOB, ENDPOINT, INSTALLED);
        bus.sendNow(ALICE, RadioProtocol.protocol(), BOB, sequence, payload);
        bus.advance(initialTick + 1);
        assertTrue(bus.acknowledge(BOB, bus.poll(BOB, 1).getFirst()));
        return snapshot(bus, runtime(bus.tick()));
    }

    private RadioPersistence.Snapshot contractSnapshot(long initialTick, int firstSequence, boolean terminal) {
        var bus = new VirtualMessageBus(initialTick, 8);
        bus.register(ALICE, ENDPOINT, INSTALLED);
        bus.register(BOB, ENDPOINT, INSTALLED);
        var contracts = runtime(initialTick);
        bus.sendNow(ALICE, ServiceContractDefinition.protocol(), BOB, firstSequence, contract("offer", "order", 3));
        bus.advance(bus.tick() + 1);
        contracts.advance(contracts.tick() + 1);
        var offer = bus.poll(BOB, 1).getFirst();
        assertTrue(contracts.receive(offer));
        assertTrue(bus.acknowledge(BOB, offer));
        if (terminal) {
            bus.sendNow(ALICE, ServiceContractDefinition.protocol(), BOB, firstSequence + 1, contract("cancel", "order", 0));
            bus.advance(bus.tick() + 1);
            contracts.advance(contracts.tick() + 1);
            var cancel = bus.poll(BOB, 1).getFirst();
            assertTrue(contracts.receive(cancel));
            assertTrue(bus.acknowledge(BOB, cancel));
        }
        return snapshot(bus, contracts);
    }

    private RadioPersistence.Snapshot emptySnapshot(long tick) {
        return snapshot(new VirtualMessageBus(tick, 8), runtime(tick));
    }

    private ContractRuntime runtime(long tick) {
        return new ContractRuntime(ServiceContractDefinition.contract(), ServiceContractDefinition.protocol(),
                tick, ContractRuntime.MAX_RETAINED_CONTRACTS);
    }

    private RadioPersistence.Snapshot snapshot(VirtualMessageBus bus, ContractRuntime contracts) {
        return new RadioPersistence.Snapshot(1, RadioProtocol.ID.value(), RadioProtocol.protocol().metadata().hash(),
                ServiceContractDefinition.PROTOCOL_ID.value(), ServiceContractDefinition.protocol().metadata().hash(),
                ServiceContractDefinition.CONTRACT_ID.value(), ServiceContractDefinition.contract().metadata().hash(),
                bus.snapshot(), contracts.snapshot());
    }

    /** Deliberately repair byte hashes after corruption so recovery must validate semantic history as well. */
    private void replaceArchive(int generation, RadioPersistence.Snapshot replacement) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        byte[] data = mapper.writeValueAsBytes(replacement);
        Files.write(archive(generation), data);
        ObjectNode journal = (ObjectNode) mapper.readTree(Files.readAllBytes(file()));
        ObjectNode reference = (ObjectNode) journal.withArray("archives").get(generation - 1);
        reference.put("sha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)));
        reference.put("bytes", data.length);
        Files.write(file(), mapper.writeValueAsBytes(journal));
    }

    private void deliveredAndAcknowledged(RadioService service, int sequence) throws Exception {
        assertTrue(service.send(ALICE, RadioProtocol.protocol(), BOB, sequence, radio(sequence)).queued());
        service.advance();
        assertTrue(service.acknowledge(BOB, service.poll(BOB, 1).getFirst()));
    }

    private Path file() { return directory.resolve("radio-state.json"); }
    private Path archiveDirectory() { return directory.resolve("radio-state.json.archives"); }
    private Path archive(int generation) {
        return archiveDirectory().resolve(String.format(java.util.Locale.ROOT, "segment-%04d.json", generation));
    }

    private long archiveFileCount() throws IOException {
        try (var entries = Files.list(archiveDirectory())) {
            return entries.filter(path -> path.getFileName().toString().matches("segment-[0-9]{4}\\.json")).count();
        }
    }

    private Path blockActivePublication() throws IOException {
        Path blocker = Files.createDirectory(directory.resolve("radio-state.json.tmp"));
        Files.writeString(blocker.resolve("keep"), "non-empty publication blocker");
        return blocker;
    }

    private void unblockActivePublication(Path blocker) throws IOException {
        Files.delete(blocker.resolve("keep"));
        Files.delete(blocker);
    }

    private RadioPersistence store() throws IOException {
        return new RadioPersistence(file(), RadioProtocol.protocol(), ServiceContractDefinition.protocol(),
                ServiceContractDefinition.contract());
    }

    private RadioService service(int budget) throws IOException { return new RadioService(store(), budget); }

    private void join(RadioService service) {
        service.join(ALICE, ENDPOINT, INSTALLED);
        service.join(BOB, ENDPOINT, INSTALLED);
    }

    private Map<String, String> radio(int sequence) {
        return Map.of("sequence", Integer.toString(sequence), "channel", "journal", "value", "ready");
    }

    private Map<String, String> contract(String action, String id, int units) {
        return Map.of("action", action, "contract", id, "units", Integer.toString(units));
    }
}
