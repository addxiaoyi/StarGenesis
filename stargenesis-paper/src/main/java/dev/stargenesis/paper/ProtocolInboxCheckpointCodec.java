package dev.stargenesis.paper;

import dev.stargenesis.api.sim.AgentId;
import dev.stargenesis.sim.ProtocolMailboxHub;
import dev.stargenesis.sim.ProtocolMessage;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class ProtocolInboxCheckpointCodec {
    static final int MAX_ENCODED_BYTES = 600_000;
    private static final int MAGIC = 0x53475049;
    private static final int FORMAT_VERSION = 2;

    private ProtocolInboxCheckpointCodec() { }

    static byte[] encode(ProtocolInboxCheckpoint checkpoint) {
        if (checkpoint == null) throw new IllegalArgumentException("Protocol inbox checkpoint is required");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(MAGIC);
                output.writeInt(FORMAT_VERSION);
                writeUuid(output, checkpoint.ownerId());
                output.writeInt(checkpoint.entries().size());
                for (ProtocolInboxEntry entry : checkpoint.entries()) {
                    ProtocolMessage message = entry.message();
                    output.writeLong(message.sequence());
                    output.writeLong(message.sentAt());
                    output.writeLong(message.expiresAt());
                    writeAgent(output, message.sender());
                    writeAgent(output, message.recipient());
                    writeText(output, message.protocolId(), 127);
                    writeText(output, message.payload(), ProtocolMailboxHub.MAX_MESSAGE_BYTES);
                    writeText(output, entry.senderName(), 16);
                    writeUuid(output, entry.sessionId());
                }
                output.writeInt(checkpoint.relayReceipts().size());
                for (var receipt : checkpoint.relayReceipts().entrySet().stream()
                        .sorted(java.util.Map.Entry.comparingByKey()).toList()) {
                    writeUuid(output, receipt.getKey());
                    output.writeLong(receipt.getValue());
                }
            }
            if (bytes.size() > MAX_ENCODED_BYTES)
                throw new IllegalArgumentException("Protocol inbox exceeds encoded size limit");
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("Cannot encode protocol inbox", impossible);
        }
    }

    static ProtocolInboxCheckpoint decode(byte[] encoded, UUID expectedOwner) {
        if (encoded == null || encoded.length < 28 || encoded.length > MAX_ENCODED_BYTES)
            throw new IllegalArgumentException("Protocol inbox checkpoint size is outside the allowed range");
        if (expectedOwner == null) throw new IllegalArgumentException("Expected protocol inbox owner is required");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != MAGIC)
                throw new IllegalArgumentException("Unsupported protocol inbox checkpoint format");
            int version = input.readInt();
            if (version < 1 || version > FORMAT_VERSION)
                throw new IllegalArgumentException("Unsupported protocol inbox checkpoint format");
            UUID owner = readUuid(input);
            if (!owner.equals(expectedOwner)) throw new IllegalArgumentException("Protocol inbox belongs to another player");
            int count = input.readInt();
            if (count < 0 || count > ProtocolInboxCheckpoint.MAX_MESSAGES)
                throw new IllegalArgumentException("Invalid protocol inbox message count");
            List<ProtocolInboxEntry> entries = new ArrayList<>(count);
            for (int index = 0; index < count; index++) {
                long sequence = input.readLong();
                long sentAt = input.readLong();
                long expiresAt = input.readLong();
                AgentId sender = readAgent(input);
                AgentId recipient = readAgent(input);
                String protocolId = readText(input, 127);
                String payload = readText(input, ProtocolMailboxHub.MAX_MESSAGE_BYTES);
                String senderName = readText(input, 16);
                entries.add(new ProtocolInboxEntry(new ProtocolMessage(sequence, sentAt, expiresAt,
                        sender, recipient, protocolId, payload), senderName, readUuid(input)));
            }
            java.util.Map<UUID, Long> receipts = new java.util.HashMap<>();
            if (version >= 2) {
                int receiptCount = input.readInt();
                if (receiptCount < 0 || receiptCount > ProtocolInboxCheckpoint.MAX_RELAY_RECEIPTS)
                    throw new IllegalArgumentException("Invalid protocol relay receipt count");
                for (int index = 0; index < receiptCount; index++) {
                    UUID messageId = readUuid(input);
                    long expiresAtMillis = input.readLong();
                    if (receipts.putIfAbsent(messageId, expiresAtMillis) != null)
                        throw new IllegalArgumentException("Duplicate protocol relay receipt");
                }
            }
            if (input.available() != 0) throw new IllegalArgumentException("Trailing protocol inbox data");
            return new ProtocolInboxCheckpoint(owner, entries, receipts);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Malformed or truncated protocol inbox checkpoint", failure);
        }
    }

    private static void writeAgent(DataOutputStream output, AgentId agent) throws IOException {
        writeUuid(output, agent.value());
    }

    private static AgentId readAgent(DataInputStream input) throws IOException {
        return new AgentId(readUuid(input));
    }

    private static void writeUuid(DataOutputStream output, UUID value) throws IOException {
        output.writeLong(value.getMostSignificantBits());
        output.writeLong(value.getLeastSignificantBits());
    }

    private static UUID readUuid(DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }

    private static void writeText(DataOutputStream output, String value, int maximumBytes) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maximumBytes) throw new IllegalArgumentException("Protocol inbox text exceeds size limit");
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readText(DataInputStream input, int maximumBytes) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > maximumBytes || length > input.available())
            throw new IllegalArgumentException("Invalid protocol inbox text length");
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(input.readNBytes(length))).toString();
    }
}
