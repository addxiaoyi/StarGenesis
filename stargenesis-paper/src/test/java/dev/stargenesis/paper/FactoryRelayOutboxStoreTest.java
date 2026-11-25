package dev.stargenesis.paper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FactoryRelayOutboxStoreTest {
    @TempDir Path temporaryDirectory;

    @Test
    void pendingRequestsSurviveStoreRecreationAndCanBeRemovedAfterResponse() throws IOException {
        FactoryRelayOutboxStore first = new FactoryRelayOutboxStore(temporaryDirectory);
        UUID requestId = UUID.fromString("00000000-0000-0000-0000-000000000010");
        byte[] packet = FactoryRelayPacketCodec.encode(FactoryRelayPacket.request("hub_a", "hub_b", requestId,
                UUID.fromString("00000000-0000-0000-0000-000000000011"), List.of("status")), new byte[32]);
        first.save(requestId, packet);
        first.save(requestId, packet);

        FactoryRelayOutboxStore restored = new FactoryRelayOutboxStore(temporaryDirectory);
        List<FactoryRelayOutboxStore.PendingRequest> pending = restored.loadAll();
        assertEquals(1, pending.size());
        assertEquals(requestId, pending.getFirst().requestId());
        assertArrayEquals(packet, pending.getFirst().packet());
        assertArrayEquals(packet, restored.load(requestId).orElseThrow().packet());
        assertTrue(restored.load(UUID.randomUUID()).isEmpty());
        assertTrue(restored.remove(requestId));
        assertFalse(restored.remove(requestId));
        assertTrue(restored.loadAll().isEmpty());
    }

    @Test
    void outboxRejectsConflictingIdentityWithoutReplacingPendingRequest() throws IOException {
        FactoryRelayOutboxStore store = new FactoryRelayOutboxStore(temporaryDirectory);
        UUID requestId = UUID.randomUUID();
        store.save(requestId, new byte[] {1, 2, 3});
        assertThrows(IOException.class, () -> store.save(requestId, new byte[] {3, 2, 1}));
        try (var files = Files.list(temporaryDirectory.resolve("factory-relay").resolve("outbox"))) {
            assertEquals(List.of(requestId + ".bin"), files.map(path -> path.getFileName().toString()).toList());
        }
    }
}
