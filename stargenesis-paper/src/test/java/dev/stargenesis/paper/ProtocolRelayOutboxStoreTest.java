package dev.stargenesis.paper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolRelayOutboxStoreTest {
    @TempDir Path temporaryDirectory;

    @Test
    void outboxPersistsAndRemovesAuthenticatedPacketRecords() throws IOException {
        ProtocolRelayOutboxStore store = new ProtocolRelayOutboxStore(temporaryDirectory);
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000010");
        byte[] packet = ProtocolRelayPacketCodec.encode(new ProtocolRelayPacket("hub_a", "hub_b", id,
                UUID.fromString("00000000-0000-0000-0000-000000000011"), "sender",
                UUID.fromString("00000000-0000-0000-0000-000000000012"), "genesis:protocol",
                "8:sequence=1:7", 1_800_000_000_000L, 1_800_000_060_000L), new byte[32]);
        store.save(id, packet);
        store.save(id, packet);
        List<ProtocolRelayOutboxStore.PendingRelay> restored = store.loadAll();
        assertEquals(1, restored.size());
        assertEquals(id, restored.getFirst().messageId());
        assertArrayEquals(packet, restored.getFirst().packet());
        assertArrayEquals(packet, store.load(id).orElseThrow().packet());
        assertTrue(store.load(UUID.randomUUID()).isEmpty());
        assertTrue(store.remove(id));
        assertFalse(store.remove(id));
        assertTrue(store.loadAll().isEmpty());
    }

    @Test
    void outboxRejectsUnboundedRecordsAndDefensiveCopiesPayloads() throws IOException {
        ProtocolRelayOutboxStore store = new ProtocolRelayOutboxStore(temporaryDirectory);
        UUID id = UUID.randomUUID();
        byte[] packet = {1, 2, 3};
        store.save(id, packet);
        packet[0] = 9;
        ProtocolRelayOutboxStore.PendingRelay record = store.loadAll().getFirst();
        assertEquals(1, record.packet()[0]);
        byte[] copy = record.packet();
        copy[0] = 8;
        assertEquals(1, record.packet()[0]);
        assertThrows(IllegalArgumentException.class, () -> store.save(UUID.randomUUID(),
                new byte[ProtocolRelayPacketCodec.MAX_PACKET_BYTES + 1]));
        assertThrows(IllegalArgumentException.class, () -> store.save(UUID.randomUUID(), new byte[0]));
    }

    @Test
    void outboxDoesNotReplaceARequestIdentityWithDifferentContent() throws IOException {
        ProtocolRelayOutboxStore store = new ProtocolRelayOutboxStore(temporaryDirectory);
        UUID id = UUID.randomUUID();
        byte[] packet = {1, 2, 3};
        store.save(id, packet);

        assertThrows(IOException.class, () -> store.save(id, new byte[] {3, 2, 1}));
        assertArrayEquals(packet, store.load(id).orElseThrow().packet());
    }
}
