package dev.stargenesis.sim;

import dev.stargenesis.api.sim.AgentId;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/** Versioned bounded binary format for resumable protocol mailbox state. */
public final class ProtocolMailboxCheckpointCodec {
    public static final int MAX_ENCODED_BYTES = 20 * 1024 * 1024;
    private static final int MAGIC = 0x5347504d;
    private static final int FORMAT_VERSION = 1;

    private ProtocolMailboxCheckpointCodec() { }

    public static byte[] encode(ProtocolMailboxCheckpoint checkpoint) {
        if (checkpoint == null) throw new IllegalArgumentException("Protocol checkpoint is required");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(MAGIC);
                output.writeInt(FORMAT_VERSION);
                output.writeLong(checkpoint.tick());
                output.writeInt(checkpoint.sendsThisStep());
                writeParticipants(output, checkpoint.participants());
                writePermissions(output, checkpoint.protocolPermissions());
                writeSequences(output, checkpoint.nextSequenceBySender());
                writeMessages(output, checkpoint.pendingMessages());
            }
            if (bytes.size() > MAX_ENCODED_BYTES)
                throw new IllegalArgumentException("Protocol checkpoint exceeds encoded size limit");
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("Cannot encode protocol checkpoint", impossible);
        }
    }

    public static ProtocolMailboxCheckpoint decode(byte[] encoded) {
        if (encoded == null || encoded.length < 20 || encoded.length > MAX_ENCODED_BYTES)
            throw new IllegalArgumentException("Protocol checkpoint size is outside the allowed range");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != MAGIC || input.readInt() != FORMAT_VERSION)
                throw new IllegalArgumentException("Unsupported protocol checkpoint format");
            long tick = input.readLong();
            int sendsThisStep = input.readInt();
            Set<AgentId> participants = readParticipants(input);
            Map<AgentId, Set<String>> permissions = readPermissions(input, participants);
            Map<AgentId, Integer> sequences = readSequences(input, participants);
            List<ProtocolMessage> messages = readMessages(input);
            if (input.available() != 0) throw new IllegalArgumentException("Trailing protocol checkpoint data");
            return new ProtocolMailboxCheckpoint(tick, participants, permissions, messages, sequences, sendsThisStep);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Malformed or truncated protocol checkpoint", failure);
        }
    }

    public static boolean hasCheckpointFormat(byte[] encoded) {
        return encoded != null && encoded.length >= Integer.BYTES && ByteBuffer.wrap(encoded).getInt() == MAGIC;
    }

    private static void writeParticipants(DataOutputStream output, Set<AgentId> participants) throws IOException {
        List<AgentId> ordered = participants.stream().sorted().toList();
        output.writeInt(ordered.size());
        for (AgentId participant : ordered) writeAgent(output, participant);
    }

    private static Set<AgentId> readParticipants(DataInputStream input) throws IOException {
        int count = readCount(input, 1, 256, "participant");
        Set<AgentId> participants = new HashSet<>();
        for (int index = 0; index < count; index++) {
            if (!participants.add(readAgent(input))) throw new IllegalArgumentException("Duplicate protocol participant");
        }
        return participants;
    }

    private static void writePermissions(DataOutputStream output, Map<AgentId, Set<String>> permissions) throws IOException {
        Map<AgentId, Set<String>> ordered = new TreeMap<>(permissions);
        output.writeInt(ordered.size());
        for (Map.Entry<AgentId, Set<String>> entry : ordered.entrySet()) {
            writeAgent(output, entry.getKey());
            List<String> protocols = entry.getValue().stream().sorted().toList();
            output.writeInt(protocols.size());
            for (String protocol : protocols) writeText(output, protocol, 127);
        }
    }

    private static Map<AgentId, Set<String>> readPermissions(DataInputStream input, Set<AgentId> participants) throws IOException {
        int agentCount = readCount(input, 0, participants.size(), "permission participant");
        Map<AgentId, Set<String>> permissions = new HashMap<>();
        for (int index = 0; index < agentCount; index++) {
            AgentId agent = readAgent(input);
            if (!participants.contains(agent)) throw new IllegalArgumentException("Permission references an unknown participant");
            int count = readCount(input, 0, 64, "protocol permission");
            Set<String> protocols = new TreeSet<>();
            for (int protocol = 0; protocol < count; protocol++) {
                if (!protocols.add(readText(input, 127))) throw new IllegalArgumentException("Duplicate protocol permission");
            }
            if (permissions.putIfAbsent(agent, protocols) != null)
                throw new IllegalArgumentException("Duplicate permission participant");
        }
        return permissions;
    }

    private static void writeSequences(DataOutputStream output, Map<AgentId, Integer> sequences) throws IOException {
        Map<AgentId, Integer> ordered = new TreeMap<>(sequences);
        output.writeInt(ordered.size());
        for (Map.Entry<AgentId, Integer> entry : ordered.entrySet()) {
            writeAgent(output, entry.getKey());
            output.writeInt(entry.getValue());
        }
    }

    private static Map<AgentId, Integer> readSequences(DataInputStream input, Set<AgentId> participants) throws IOException {
        int count = readCount(input, 0, participants.size(), "sender sequence");
        Map<AgentId, Integer> sequences = new HashMap<>();
        for (int index = 0; index < count; index++) {
            AgentId agent = readAgent(input);
            int sequence = input.readInt();
            if (!participants.contains(agent)) throw new IllegalArgumentException("Sequence references an unknown participant");
            if (sequences.putIfAbsent(agent, sequence) != null)
                throw new IllegalArgumentException("Duplicate sender sequence participant");
        }
        return sequences;
    }

    private static void writeMessages(DataOutputStream output, List<ProtocolMessage> messages) throws IOException {
        output.writeInt(messages.size());
        for (ProtocolMessage message : messages.stream().sorted(Comparator.comparing(ProtocolMessage::recipient)
                .thenComparing(ProtocolMessage::sender).thenComparingLong(ProtocolMessage::sentAt)
                .thenComparingLong(ProtocolMessage::sequence)).toList()) {
            output.writeLong(message.sequence());
            output.writeLong(message.sentAt());
            output.writeLong(message.expiresAt());
            writeAgent(output, message.sender());
            writeAgent(output, message.recipient());
            writeText(output, message.protocolId(), 127);
            writeText(output, message.payload(), ProtocolMailboxHub.MAX_MESSAGE_BYTES);
        }
    }

    private static List<ProtocolMessage> readMessages(DataInputStream input) throws IOException {
        int count = readCount(input, 0, ProtocolMailboxHub.MAX_PENDING_TOTAL, "pending message");
        List<ProtocolMessage> messages = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            long sequence = input.readLong();
            long sentAt = input.readLong();
            long expiresAt = input.readLong();
            AgentId sender = readAgent(input);
            AgentId recipient = readAgent(input);
            String protocol = readText(input, 127);
            String payload = readText(input, ProtocolMailboxHub.MAX_MESSAGE_BYTES);
            messages.add(new ProtocolMessage(sequence, sentAt, expiresAt, sender, recipient, protocol, payload));
        }
        return messages;
    }

    private static void writeAgent(DataOutputStream output, AgentId agent) throws IOException {
        output.writeLong(agent.value().getMostSignificantBits());
        output.writeLong(agent.value().getLeastSignificantBits());
    }

    private static AgentId readAgent(DataInputStream input) throws IOException {
        return new AgentId(new UUID(input.readLong(), input.readLong()));
    }

    private static int readCount(DataInputStream input, int minimum, int maximum, String label) throws IOException {
        int count = input.readInt();
        if (count < minimum || count > maximum) throw new IllegalArgumentException("Invalid " + label + " count");
        return count;
    }

    private static void writeText(DataOutputStream output, String value, int maximumBytes) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        if (encoded.length > maximumBytes) throw new IllegalArgumentException("Protocol checkpoint text exceeds limit");
        output.writeInt(encoded.length);
        output.write(encoded);
    }

    private static String readText(DataInputStream input, int maximumBytes) throws IOException {
        int length = readCount(input, 0, maximumBytes, "text byte");
        if (length > input.available()) throw new IllegalArgumentException("Truncated protocol checkpoint text");
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(input.readNBytes(length))).toString();
    }
}
