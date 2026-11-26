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

/** Finite capacity samples using real protocol codecs, history validation and atomic files, not load benchmarks. */
class RadioCapacityTest {
    private static final UUID REQUESTER = new UUID(0, 101), PROVIDER = new UUID(0, 102);
    private static final VirtualMessageBus.Endpoint ENDPOINT = new VirtualMessageBus.Endpoint("capacity", 0, 0, 0, 16);
    private static final Set<VirtualMessageBus.ProtocolKey> INSTALLED = Set.of(RadioProtocol.key(), ServiceContractDefinition.key());
    @TempDir Path directory;

    @Test void fullEvidenceCapacityCanRecoverAndAcknowledgeAllOfflineRecipients() throws Exception {
        long initialTick = Long.MAX_VALUE - 32;
        var bus = new VirtualMessageBus(initialTick, 1024);
        bus.register(REQUESTER, ENDPOINT, INSTALLED);
        Map<String, String> payload = Map.of("sequence", Long.toString(Long.MIN_VALUE),
                "channel", "c".repeat(64), "value", "v".repeat(64));
        for (int index = 0; index < VirtualMessageBus.MAX_RETAINED_EVIDENCE; index++) {
            UUID recipient = new UUID(5, index);
            bus.register(recipient, ENDPOINT, INSTALLED);
            bus.sendNow(REQUESTER, RadioProtocol.protocol(), recipient, Integer.MAX_VALUE - index, payload);
            bus.unregister(recipient);
            if ((index + 1) % 1024 == 0) bus.advance(bus.tick() + 1);
        }
        assertEquals(4096, bus.retainedMailboxCount());
        assertEquals(1, bus.participantCount());
        var persistence = store();
        persistence.save(bus, runtime(bus.tick()));
        long pendingBytes = snapshotBytes();
        bus = restoredBus(persistence.load().orElseThrow());
        assertEquals(4096, bus.retainedMailboxCount());
        assertEquals(0, bus.participantCount());
        bus.register(REQUESTER, ENDPOINT, INSTALLED);
        bus.register(PROVIDER, ENDPOINT, INSTALLED);
        var full = bus;
        var before = bus.snapshot();
        assertThrows(IllegalStateException.class,
                () -> full.sendNow(REQUESTER, RadioProtocol.protocol(), PROVIDER, 0, payload));
        assertEquals(before, bus.snapshot());
        assertFalse(bus.sendNow(REQUESTER, RadioProtocol.protocol(), new UUID(5, 0), Integer.MAX_VALUE, payload).queued());
        for (int index = 0; index < VirtualMessageBus.MAX_RETAINED_EVIDENCE; index++) {
            UUID recipient = new UUID(5, index);
            bus.register(recipient, ENDPOINT, INSTALLED);
            var message = bus.poll(recipient, 1).getFirst();
            assertTrue(bus.acknowledge(recipient, message));
            assertFalse(bus.acknowledge(recipient, message));
            bus.unregister(recipient);
        }
        assertEquals(0, bus.retainedMailboxCount());
        assertEquals(4096, bus.retainedEvidenceCount());
        assertEquals(8192, bus.events().size());
        persistence.save(bus, runtime(bus.tick()));
        long acknowledgedBytes = snapshotBytes();
        var recovered = restoredBus(persistence.load().orElseThrow());
        assertEquals(bus.snapshot(), recovered.snapshot());
        assertEquals(0, recovered.retainedMailboxCount());
        assertFalse(recovered.sendNow(REQUESTER, RadioProtocol.protocol(), new UUID(5, 0), Integer.MAX_VALUE, payload).queued());
        System.out.printf("P4 capacity sample: messages=4096 recipients=4096 events=8192 pendingBytes=%d acknowledgedBytes=%d%n",
                pendingBytes, acknowledgedBytes);
    }

    @Test void fullContractMessageHistoryCanRecoverAndPersistEveryRemainingTimeout() throws Exception {
        var bus = new VirtualMessageBus(0, 64);
        bus.register(REQUESTER, ENDPOINT, INSTALLED);
        bus.register(PROVIDER, ENDPOINT, INSTALLED);
        var contracts = runtime(0);
        int sequence = 0;
        // 256 offers, 256 acceptances and 14 partial fulfillments each fill all 4096 radio evidence slots.
        for (int phase = 0; phase < 16; phase++) {
            UUID sender = phase == 0 ? REQUESTER : PROVIDER;
            UUID recipient = phase == 0 ? PROVIDER : REQUESTER;
            String action = phase == 0 ? "offer" : phase == 1 ? "accept" : "fulfill";
            String units = phase < 2 ? "64" : "1";
            for (int index = 0; index < ContractRuntime.MAX_RETAINED_CONTRACTS; index++) {
                String id = "order_" + "x".repeat(55) + String.format(java.util.Locale.ROOT, "%03d", index);
                assertEquals(64, id.length());
                bus.sendNow(sender, ServiceContractDefinition.protocol(), recipient, sequence++,
                        Map.of("contract", id, "action", action, "units", units));
                if ((index + 1) % 64 == 0) {
                    bus.advance(bus.tick() + 1);
                    contracts.advance(bus.tick());
                    for (var message : bus.poll(recipient, 64)) {
                        assertTrue(contracts.receive(message));
                        assertTrue(bus.acknowledge(recipient, message));
                    }
                }
            }
        }
        assertEquals(4096, bus.retainedEvidenceCount());
        assertEquals(8192, bus.events().size());
        assertEquals(0, bus.retainedMailboxCount());
        assertEquals(256, contracts.activeCount());
        assertEquals(4096, contracts.events().size());
        store().save(bus, contracts);
        long activeBytes = snapshotBytes();
        var service = new RadioService(store(), 64);
        assertEquals(0, service.participantCount());
        assertEquals(contracts.snapshot(), service.contractSnapshot());
        while (service.tick() < 205) service.advance();
        assertEquals(0, service.activeContracts());
        assertEquals(4352, service.contractSnapshot().events().size());
        assertTrue(service.contractSnapshot().states().stream()
                .allMatch(state -> state.state() == ContractRuntime.State.EXPIRED && state.fulfilledUnits() == 14));
        service.close();
        long expiredBytes = snapshotBytes();
        var recovered = new RadioService(store(), 64);
        assertEquals(service.radioSnapshot(), recovered.radioSnapshot());
        assertEquals(service.contractSnapshot(), recovered.contractSnapshot());
        System.out.printf("P4 capacity sample: messages=4096 contracts=256 contractEvents=4352 activeBytes=%d expiredBytes=%d%n",
                activeBytes, expiredBytes);
    }

    private RadioPersistence store() throws IOException {
        return new RadioPersistence(directory.resolve("radio-state.json"), RadioProtocol.protocol(),
                ServiceContractDefinition.protocol(), ServiceContractDefinition.contract());
    }

    private long snapshotBytes() throws IOException {
        long bytes = Files.size(directory.resolve("radio-state.json"));
        assertTrue(bytes > 0 && bytes < RadioPersistence.MAX_FILE_BYTES);
        return bytes;
    }

    private ContractRuntime runtime(long tick) {
        return new ContractRuntime(ServiceContractDefinition.contract(), ServiceContractDefinition.protocol(),
                tick, ContractRuntime.MAX_RETAINED_CONTRACTS);
    }

    private VirtualMessageBus restoredBus(RadioPersistence.Snapshot snapshot) {
        var restored = new VirtualMessageBus(snapshot.radio().tick(), 1024);
        restored.restore(snapshot.radio(), Map.of(RadioProtocol.key(), RadioProtocol.protocol(),
                ServiceContractDefinition.key(), ServiceContractDefinition.protocol()));
        return restored;
    }
}
