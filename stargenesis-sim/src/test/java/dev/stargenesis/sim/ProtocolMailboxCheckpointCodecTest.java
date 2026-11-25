package dev.stargenesis.sim;

import dev.stargenesis.api.sim.AgentId;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolMailboxCheckpointCodecTest {
    private static final AgentId A = id(1);
    private static final AgentId B = id(2);
    private static final AgentId C = id(3);

    @Test void restoredHubContinuesWithTheSameSequencesDeliveriesAndExpirations() {
        ProtocolMailboxHub original = hub(Set.of(A, B, C));
        original.send(A, B, "genesis:trade", "first", 3);
        original.send(A, C, "genesis:unlicensed", "rejected", 3);
        byte[] encoded = ProtocolMailboxCheckpointCodec.encode(original.checkpoint());

        ProtocolMailboxCheckpoint decoded = ProtocolMailboxCheckpointCodec.decode(encoded);
        assertEquals(original.checkpoint(), decoded);
        ProtocolMailboxHub restored = ProtocolMailboxHub.restore(decoded);
        assertEquals(original.send(A, C, "genesis:trade", "second", 1),
                restored.send(A, C, "genesis:trade", "second", 1));
        assertEquals(original.drain(B), restored.drain(B));
        assertEquals(original.advance(), restored.advance());
        assertEquals(original.drain(C), restored.drain(C));
        assertEquals(original.checkpoint(), restored.checkpoint());
    }

    @Test void checkpointEncodingIsCanonicalAndRejectsTruncationVersionAndTrailingBytes() {
        ProtocolMailboxHub first = hub(Set.of(A, B, C));
        ProtocolMailboxHub second = hub(Set.of(C, A, B));
        first.send(A, B, "genesis:trade", "alpha", 5);
        second.send(A, B, "genesis:trade", "alpha", 5);
        byte[] encoded = ProtocolMailboxCheckpointCodec.encode(first.checkpoint());
        assertArrayEquals(encoded, ProtocolMailboxCheckpointCodec.encode(second.checkpoint()));
        assertThrows(IllegalArgumentException.class,
                () -> ProtocolMailboxCheckpointCodec.decode(java.util.Arrays.copyOf(encoded, encoded.length - 1)));

        byte[] unsupported = encoded.clone();
        unsupported[7] = (byte) (unsupported[7] + 1);
        assertThrows(IllegalArgumentException.class, () -> ProtocolMailboxCheckpointCodec.decode(unsupported));

        byte[] trailing = java.util.Arrays.copyOf(encoded, encoded.length + 1);
        assertThrows(IllegalArgumentException.class, () -> ProtocolMailboxCheckpointCodec.decode(trailing));
    }

    @Test void checkpointValueRejectsInconsistentSendCountersAndExpiredPendingMessages() {
        Map<AgentId, Set<String>> permissions = Map.of(A, Set.of("genesis:trade"), B, Set.of("genesis:trade"));
        ProtocolMessage message = new ProtocolMessage(0, 0, 2, A, B, "genesis:trade", "offer");
        assertThrows(IllegalArgumentException.class, () -> new ProtocolMailboxCheckpoint(0, Set.of(A, B),
                permissions, List.of(message), Map.of(A, 1), 0));
        assertThrows(IllegalArgumentException.class, () -> new ProtocolMailboxCheckpoint(2, Set.of(A, B),
                permissions, List.of(message), Map.of(A, 1), 1));
    }

    @Test void checkpointBytesAreDefensivelyReadAndTheCodecHasAFormatMarker() {
        ProtocolMailboxHub hub = hub(Set.of(A, B));
        hub.send(A, B, "genesis:trade", "payload", 2);
        byte[] encoded = ProtocolMailboxCheckpointCodec.encode(hub.checkpoint());
        assertTrue(ProtocolMailboxCheckpointCodec.hasCheckpointFormat(encoded));
        byte[] changed = encoded.clone();
        changed[0]++;
        assertFalse(ProtocolMailboxCheckpointCodec.hasCheckpointFormat(changed));
        assertEquals("payload", ProtocolMailboxCheckpointCodec.decode(encoded).pendingMessages().getFirst().payload());
    }

    private static ProtocolMailboxHub hub(Set<AgentId> participants) {
        Map<AgentId, Set<String>> permissions = participants.stream().collect(
                java.util.stream.Collectors.toMap(agent -> agent, ignored -> Set.of("genesis:trade")));
        return new ProtocolMailboxHub(participants, permissions);
    }

    private static AgentId id(long leastBits) { return new AgentId(new UUID(0, leastBits)); }
}
