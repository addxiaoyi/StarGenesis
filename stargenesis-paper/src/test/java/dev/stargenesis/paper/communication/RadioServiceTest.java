package dev.stargenesis.paper.communication;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RadioServiceTest {
    private static final UUID ALICE = new UUID(0, 81), BOB = new UUID(0, 82);
    private static final Set<VirtualMessageBus.ProtocolKey> INSTALLED = Set.of(RadioProtocol.key(), ServiceContractDefinition.key());
    @TempDir Path directory;

    @Test void restartRestoresCurrentTickMessageBudgetWithoutChargingDuplicateRetries() throws Exception {
        var service = service(1);
        join(service);
        assertTrue(service.send(ALICE, RadioProtocol.protocol(), BOB, 1, radio(1)).queued());
        var restarted = service(1);
        join(restarted);
        assertThrows(IllegalStateException.class, () -> restarted.send(ALICE, RadioProtocol.protocol(), BOB, 2, radio(2)));
        assertFalse(restarted.send(ALICE, RadioProtocol.protocol(), BOB, 1, radio(1)).queued());
        restarted.advance();
        assertTrue(restarted.send(ALICE, RadioProtocol.protocol(), BOB, 2, radio(2)).queued());
    }

    @Test void failedSendPreservesMemoryDiskAndEndpointsAndShutdownNeverCommitsIt() throws Exception {
        var service = service(8);
        join(service);
        service.advance();
        service.flush();
        var before = service.radioSnapshot();
        byte[] bytes = Files.readAllBytes(file());
        Path blocker = block();
        assertThrows(IOException.class, () -> service.send(ALICE, RadioProtocol.protocol(), BOB, 1, radio(1)));
        assertEquals(before, service.radioSnapshot());
        assertEquals(2, service.participantCount());
        assertEquals(0, service.pendingCount(BOB));
        assertArrayEquals(bytes, Files.readAllBytes(file()));
        Files.delete(blocker);
        service.close();
        var restarted = service(8);
        assertEquals(before, restarted.radioSnapshot());
        assertEquals(0, restarted.participantCount());
        join(restarted);
        assertTrue(restarted.send(ALICE, RadioProtocol.protocol(), BOB, 1, radio(1)).queued());
        assertFalse(restarted.send(ALICE, RadioProtocol.protocol(), BOB, 1, radio(1)).queued());
        assertEquals(1, restarted.retainedEvidenceCount());
    }

    @Test void failedAcknowledgementRetainsExactEnvelopeForRedeliveryAndRetry() throws Exception {
        var service = service(8);
        join(service);
        service.send(ALICE, RadioProtocol.protocol(), BOB, 1, radio(1));
        service.advance();
        service.flush();
        var message = service.poll(BOB, 8).getFirst();
        var before = service.radioSnapshot();
        byte[] bytes = Files.readAllBytes(file());
        Path blocker = block();
        assertThrows(IOException.class, () -> service.acknowledge(BOB, message));
        assertEquals(before, service.radioSnapshot());
        assertEquals(message, service.poll(BOB, 8).getFirst());
        assertArrayEquals(bytes, Files.readAllBytes(file()));
        service.disconnected(BOB);
        assertThrows(IllegalArgumentException.class, () -> service.poll(BOB, 8));
        service.join(BOB, new VirtualMessageBus.Endpoint("test", 1, 0, 0, 16), INSTALLED);
        assertEquals(message, service.poll(BOB, 8).getFirst());
        Files.delete(blocker);
        assertTrue(service.acknowledge(BOB, message));
        assertFalse(service.acknowledge(BOB, message));
        var restarted = service(8);
        join(restarted);
        assertTrue(restarted.poll(BOB, 8).isEmpty());
        assertEquals(2, restarted.radioSnapshot().events().size());
        assertFalse(restarted.send(ALICE, RadioProtocol.protocol(), BOB, 1, radio(1)).queued());
    }

    @Test void failedInboxBatchRetainsAllMessagesAndRetriesValidContractsOnce() throws Exception {
        var service = service(8);
        join(service);
        service.send(ALICE, ServiceContractDefinition.protocol(), BOB, 1, contract("offer", "valid", 3));
        service.send(ALICE, ServiceContractDefinition.protocol(), BOB, 2, contract("offer", "too_large", 65));
        service.send(ALICE, RadioProtocol.protocol(), BOB, 3, radio(3));
        service.advance();
        var radioBefore = service.radioSnapshot();
        var contractsBefore = service.contractSnapshot();
        byte[] bytes = Files.readAllBytes(file());
        Path blocker = block();
        assertThrows(IOException.class, () -> service.inbox(BOB, 8));
        assertEquals(radioBefore, service.radioSnapshot());
        assertEquals(contractsBefore, service.contractSnapshot());
        assertArrayEquals(bytes, Files.readAllBytes(file()));
        Files.delete(blocker);
        var lines = service.inbox(BOB, 8);
        assertTrue(lines.stream().anyMatch(line -> line.contains("retained for review")));
        assertTrue(lines.stream().anyMatch(line -> line.contains("valid · OFFERED")));
        assertEquals(2, service.pendingCount(BOB));
        assertEquals(1, service.contractSnapshot().events().size());
        assertEquals(ContractRuntime.State.OFFERED, service.contract(ALICE, "valid").state());
        assertThrows(SecurityException.class, () -> service.contract(new UUID(0, 99), "valid"));
        service.inbox(BOB, 8);
        assertEquals(1, service.contractSnapshot().events().size());
        assertEquals(service.contractSnapshot(), service(8).contractSnapshot());
    }

    @Test void failedFulfillmentAndAckSaveCanBeRetriedAfterRestartWithoutDoubleApplication() throws Exception {
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
        var before = service.contractSnapshot();
        byte[] bytes = Files.readAllBytes(file());
        Path blocker = block();
        assertThrows(IOException.class, () -> service.inbox(ALICE, 8));
        assertEquals(before, service.contractSnapshot());
        assertEquals(1, service.pendingCount(ALICE));
        assertArrayEquals(bytes, Files.readAllBytes(file()));
        Files.delete(blocker);
        var restarted = service(8);
        join(restarted);
        restarted.advance();
        restarted.inbox(ALICE, 8);
        var completed = restarted.contract(ALICE, "order");
        assertEquals(ContractRuntime.State.COMPLETED, completed.state());
        assertEquals(3, completed.fulfilledUnits());
        assertEquals(3, restarted.contractSnapshot().events().size());
        assertEquals(0, restarted.pendingCount(ALICE));
        assertFalse(restarted.send(BOB, ServiceContractDefinition.protocol(), ALICE, 2, contract("fulfill", "order", 3)).queued());
        assertEquals(completed, service(8).contract(ALICE, "order"));
    }

    @Test void failedExpirationSaveKeepsBothClocksAndContractAtThePreviousTickUntilRetry() throws Exception {
        var service = service(8);
        join(service);
        service.send(ALICE, ServiceContractDefinition.protocol(), BOB, 1, contract("offer", "deadline", 3));
        service.advance();
        service.inbox(BOB, 8);
        long deadline = service.contract(ALICE, "deadline").expiresAt();
        while (service.tick() < deadline) service.advance();
        service.flush();
        byte[] bytes = Files.readAllBytes(file());
        Path blocker = block();
        assertThrows(IOException.class, service::advance);
        assertEquals(deadline, service.tick());
        assertEquals(deadline, service.contractSnapshot().tick());
        assertEquals(ContractRuntime.State.OFFERED, service.contract(ALICE, "deadline").state());
        assertArrayEquals(bytes, Files.readAllBytes(file()));
        Files.delete(blocker);
        service.advance();
        assertEquals(deadline + 1, service.tick());
        assertEquals(ContractRuntime.State.EXPIRED, service.contract(ALICE, "deadline").state());
        assertEquals(2, service.contractSnapshot().events().size());
        assertEquals(service.contractSnapshot(), service(8).contractSnapshot());
        service.advance();
        assertEquals(2, service.contractSnapshot().events().size());
    }

    @Test void checkpointFailurePreservesBothClocksAndCanRetryWithoutStoppingTheService() throws Exception {
        var service = service(8);
        join(service);
        service.send(ALICE, RadioProtocol.protocol(), BOB, 1, radio(1));
        for (int tick = 0; tick < 19; tick++) service.advance();
        var before = service.radioSnapshot();
        byte[] bytes = Files.readAllBytes(file());
        Path blocker = block();
        assertThrows(IOException.class, service::advance);
        assertEquals(before, service.radioSnapshot());
        assertEquals(19, service.contractSnapshot().tick());
        assertArrayEquals(bytes, Files.readAllBytes(file()));
        Files.delete(blocker);
        service.advance();
        assertEquals(20, service.tick());
        assertEquals(20, service(8).tick());
        assertEquals(2, service.participantCount());
    }

    @Test void closeIsRetryableAfterFailedFlushAndIdempotentAfterSuccessfulFlush() throws Exception {
        var service = service(8);
        join(service);
        service.send(ALICE, RadioProtocol.protocol(), BOB, 1, radio(1));
        service.advance();
        Path blocker = block();
        assertThrows(IOException.class, service::close);
        assertEquals(1, service.tick());
        assertEquals(1, service.poll(BOB, 8).size());
        Files.delete(blocker);
        service.close();
        byte[] bytes = Files.readAllBytes(file());
        service.close();
        assertArrayEquals(bytes, Files.readAllBytes(file()));
        assertThrows(IllegalStateException.class, service::advance);
        assertThrows(IllegalStateException.class, () -> service.send(ALICE, RadioProtocol.protocol(), BOB, 2, radio(2)));
        var restarted = service(8);
        assertEquals(0, restarted.participantCount());
        join(restarted);
        assertEquals(1, restarted.poll(BOB, 8).size());
    }

    @Test void lowerBudgetOnRestartBlocksNewSendsUntilNextTickAndMailboxLimitReclaimsAfterAck() throws Exception {
        var service = service(64);
        join(service);
        for (int sequence = 0; sequence < 64; sequence++)
            service.send(ALICE, RadioProtocol.protocol(), BOB, sequence, radio(sequence));
        var restarted = service(1);
        join(restarted);
        assertThrows(IllegalStateException.class, () -> restarted.send(ALICE, RadioProtocol.protocol(), BOB, 64, radio(64)));
        restarted.advance();
        var before = restarted.radioSnapshot();
        assertThrows(IllegalStateException.class, () -> restarted.send(ALICE, RadioProtocol.protocol(), BOB, 64, radio(64)));
        assertEquals(before, restarted.radioSnapshot());
        restarted.acknowledge(BOB, restarted.poll(BOB, 1).getFirst());
        assertTrue(restarted.send(ALICE, RadioProtocol.protocol(), BOB, 64, radio(64)).queued());
        assertEquals(64, restarted.pendingCount(BOB));
    }

    @Test void nullUnknownDuplicateAndMismatchedSnapshotsFailBeforeStartingAService() throws Exception {
        var service = service(8);
        service.advance();
        service.flush();
        String valid = Files.readString(file());
        for (String invalid : java.util.List.of("null", valid + " {}", valid.replaceFirst("\\{", "{\"schemaVersion\":1,"),
                valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                valid.replace("\"radioProtocolHash\":\"" + RadioProtocol.protocol().metadata().hash() + "\"",
                        "\"radioProtocolHash\":\"" + "0".repeat(64) + "\""))) {
            Files.writeString(file(), invalid);
            assertThrows(IOException.class, () -> service(8));
        }
        Files.writeString(file(), valid);
        assertEquals(1, service(8).tick());
    }

    @Test void capturedV1PendingFulfillmentResumesAndCompletesWithoutReapplyingOfferOrAcceptance() throws Exception {
        try (var fixture = getClass().getResourceAsStream("/radio-v1-pending-fulfillment.json")) {
            assertNotNull(fixture);
            Files.copy(fixture, file());
        }
        byte[] original = Files.readAllBytes(file());
        var service = service(8);
        assertEquals(2, service.tick());
        assertEquals(ContractRuntime.State.ACCEPTED, service.contract(ALICE, "legacy_order").state());
        assertEquals(0, service.contract(ALICE, "legacy_order").fulfilledUnits());
        assertEquals(1, service.pendingCount(ALICE));
        assertEquals(0, service.participantCount());
        service.flush();
        assertArrayEquals(original, Files.readAllBytes(file()));
        join(service);
        assertTrue(service.poll(ALICE, 8).isEmpty());
        service.advance();
        service.inbox(ALICE, 8);
        assertEquals(1, service.contract(ALICE, "legacy_order").fulfilledUnits());
        service.send(BOB, ServiceContractDefinition.protocol(), ALICE, 3, contract("fulfill", "legacy_order", 2));
        service.advance();
        service.inbox(ALICE, 8);
        var restarted = service(8);
        assertEquals(ContractRuntime.State.COMPLETED, restarted.contract(BOB, "legacy_order").state());
        assertEquals(3, restarted.contract(BOB, "legacy_order").fulfilledUnits());
        assertEquals(4, restarted.contractSnapshot().events().size());
        assertEquals(8, restarted.radioSnapshot().events().size());
        assertEquals(0, restarted.pendingCount(ALICE));
    }

    @Test void recoveredContractMustReferenceTheSameAcknowledgedRadioMessage() throws Exception {
        var bus = new VirtualMessageBus(0, 8);
        bus.register(ALICE, new VirtualMessageBus.Endpoint("test", 0, 0, 0, 16), INSTALLED);
        bus.register(BOB, new VirtualMessageBus.Endpoint("test", 1, 0, 0, 16), INSTALLED);
        bus.sendNow(ALICE, ServiceContractDefinition.protocol(), BOB, 1, contract("offer", "order", 1));
        bus.advance(1);
        var runtime = new ContractRuntime(ServiceContractDefinition.contract(), ServiceContractDefinition.protocol(), 1, 256);
        var message = bus.poll(BOB, 1).getFirst();
        runtime.receive(message);
        var store = new RadioPersistence(file(), RadioProtocol.protocol(), ServiceContractDefinition.protocol(), ServiceContractDefinition.contract());
        store.save(bus, runtime);
        assertThrows(IOException.class, () -> service(8));
        bus.acknowledge(BOB, message);
        store.save(bus, runtime);
        assertEquals(ContractRuntime.State.OFFERED, service(8).contract(ALICE, "order").state());
    }

    private Path file() { return directory.resolve("radio-state.json"); }
    private Path block() throws IOException { return Files.createDirectory(directory.resolve("radio-state.json.tmp")); }

    private Map<String, String> contract(String action, String id, int units) {
        return Map.of("action", action, "contract", id, "units", Integer.toString(units));
    }

    private RadioService service(int budget) throws IOException {
        return new RadioService(new RadioPersistence(directory.resolve("radio-state.json"),
                RadioProtocol.protocol(), ServiceContractDefinition.protocol(), ServiceContractDefinition.contract()), budget);
    }

    private void join(RadioService service) {
        service.join(ALICE, new VirtualMessageBus.Endpoint("test", 0, 0, 0, 16), INSTALLED);
        service.join(BOB, new VirtualMessageBus.Endpoint("test", 1, 0, 0, 16), INSTALLED);
    }

    private Map<String, String> radio(int sequence) {
        return Map.of("sequence", Integer.toString(sequence), "channel", "test", "value", "ready");
    }
}
