package dev.stargenesis.paper;

import dev.stargenesis.api.sim.AgentId;
import dev.stargenesis.sim.ProtocolMessage;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolInboxCheckpointCodecTest {
    private static final UUID OWNER = new UUID(0, 2);
    private static final UUID SENDER = new UUID(0, 1);

    @Test void inboxRoundTripIsBoundToItsOwnerAndPreservesReceivedMessages() {
        ProtocolInboxCheckpoint checkpoint = ProtocolInboxCheckpoint.empty(OWNER)
                .append(List.of(entry(0, "one"), entry(1, "two")));
        byte[] encoded = ProtocolInboxCheckpointCodec.encode(checkpoint);
        assertEquals(checkpoint, ProtocolInboxCheckpointCodec.decode(encoded, OWNER));
        assertThrows(IllegalArgumentException.class, () -> ProtocolInboxCheckpointCodec.decode(encoded, SENDER));
    }

    @Test void inboxAppendIsIdempotentAndRemovalDoesNotDiscardConcurrentMessages() {
        ProtocolInboxEntry first = entry(0, "first");
        ProtocolInboxCheckpoint checkpoint = ProtocolInboxCheckpoint.empty(OWNER).append(List.of(first));
        ProtocolInboxEntry second = entry(1, "second");
        ProtocolInboxCheckpoint updated = checkpoint.append(List.of(first, second));
        assertEquals(List.of(first, second), updated.entries());
        assertEquals(List.of(second), updated.remove(first).entries());
    }

    @Test void messagesFromDifferentServerSessionsDoNotCollideAfterTickAndSequenceReset() {
        ProtocolInboxEntry previousSession = entry(0, "old");
        ProtocolInboxEntry currentSession = new ProtocolInboxEntry(previousSession.message(), "sender", new UUID(0, 11));
        ProtocolInboxCheckpoint checkpoint = ProtocolInboxCheckpoint.empty(OWNER)
                .append(List.of(previousSession, currentSession));
        assertEquals(2, checkpoint.entries().size());
    }

    @Test void relayReceiptAndInboxEntryPersistTogetherAndSuppressReplays() {
        UUID messageId = UUID.fromString("00000000-0000-0000-0000-000000000020");
        long now = 1_800_000_000_000L;
        ProtocolInboxEntry relayed = entry(7, "relay");
        ProtocolInboxCheckpoint checkpoint = ProtocolInboxCheckpoint.empty(OWNER)
                .appendRelay(relayed, messageId, now + 60_000, now);
        ProtocolInboxCheckpoint restored = ProtocolInboxCheckpointCodec.decode(
                ProtocolInboxCheckpointCodec.encode(checkpoint), OWNER);
        assertEquals(checkpoint, restored);
        assertEquals(restored, restored.appendRelay(entry(7, "different"), messageId, now + 60_000, now));
        for (ProtocolInboxCheckpoint cleared : List.of(restored.remove(relayed), restored.clearMessages())) {
            ProtocolInboxCheckpoint reopened = ProtocolInboxCheckpointCodec.decode(
                    ProtocolInboxCheckpointCodec.encode(cleared), OWNER);
            assertTrue(reopened.entries().isEmpty());
            assertTrue(reopened.relayReceipts().containsKey(messageId));
            assertEquals(reopened, reopened.appendRelay(entry(7, "retry"), messageId, now + 60_000, now));
        }
    }

    @Test void versionOneInboxCheckpointsMigrateWithoutRelayReceipts() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(0x53475049);
            output.writeInt(1);
            output.writeLong(OWNER.getMostSignificantBits());
            output.writeLong(OWNER.getLeastSignificantBits());
            output.writeInt(1);
            ProtocolMessage message = entry(0, "legacy").message();
            output.writeLong(message.sequence());
            output.writeLong(message.sentAt());
            output.writeLong(message.expiresAt());
            output.writeLong(message.sender().value().getMostSignificantBits());
            output.writeLong(message.sender().value().getLeastSignificantBits());
            output.writeLong(message.recipient().value().getMostSignificantBits());
            output.writeLong(message.recipient().value().getLeastSignificantBits());
            writeText(output, message.protocolId());
            writeText(output, message.payload());
            writeText(output, "sender");
            output.writeLong(0);
            output.writeLong(10);
        }
        ProtocolInboxCheckpoint restored = ProtocolInboxCheckpointCodec.decode(bytes.toByteArray(), OWNER);
        assertEquals("legacy", restored.entries().getFirst().message().payload());
        assertTrue(restored.relayReceipts().isEmpty());
        assertEquals(restored, ProtocolInboxCheckpointCodec.decode(
                ProtocolInboxCheckpointCodec.encode(restored), OWNER));
    }

    @Test void inboxCodecRejectsTruncatedTrailingAndOversizedCheckpoints() {
        byte[] encoded = ProtocolInboxCheckpointCodec.encode(
                ProtocolInboxCheckpoint.empty(OWNER).append(List.of(entry(0, "payload"))));
        assertThrows(IllegalArgumentException.class,
                () -> ProtocolInboxCheckpointCodec.decode(java.util.Arrays.copyOf(encoded, encoded.length - 1), OWNER));
        assertThrows(IllegalArgumentException.class,
                () -> ProtocolInboxCheckpointCodec.decode(java.util.Arrays.copyOf(encoded, encoded.length + 1), OWNER));
        assertThrows(IllegalArgumentException.class, () -> ProtocolInboxCheckpointCodec.decode(
                new byte[ProtocolInboxCheckpointCodec.MAX_ENCODED_BYTES + 1], OWNER));
    }

    private static ProtocolInboxEntry entry(long sequence, String payload) {
        return new ProtocolInboxEntry(new ProtocolMessage(sequence, 0, 10, new AgentId(SENDER),
                new AgentId(OWNER), "genesis:protocol", payload), "sender", new UUID(0, 10));
    }

    private static void writeText(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }
}
