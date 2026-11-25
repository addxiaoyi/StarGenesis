package dev.stargenesis.paper;

import dev.stargenesis.api.sim.AgentId;
import dev.stargenesis.sim.ProtocolMessage;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

record ProtocolInboxCheckpoint(UUID ownerId, List<ProtocolInboxEntry> entries, Map<UUID, Long> relayReceipts) {
    static final int MAX_MESSAGES = 128;
    static final int MAX_RELAY_RECEIPTS = 1700;

    ProtocolInboxCheckpoint {
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(relayReceipts, "relayReceipts");
        if (entries.size() > MAX_MESSAGES) throw new IllegalArgumentException("Protocol inbox is full");
        List<ProtocolInboxEntry> copiedEntries = new ArrayList<>(entries);
        Set<MessageIdentity> identities = new HashSet<>();
        for (ProtocolInboxEntry entry : copiedEntries) {
            Objects.requireNonNull(entry, "entry");
            if (!entry.message().recipient().equals(new AgentId(ownerId)))
                throw new IllegalArgumentException("Protocol inbox contains a message for another player");
            if (!identities.add(MessageIdentity.of(entry)))
                throw new IllegalArgumentException("Protocol inbox contains a duplicate message");
        }
        entries = List.copyOf(copiedEntries);
        if (relayReceipts.size() > MAX_RELAY_RECEIPTS
                || relayReceipts.entrySet().stream().anyMatch(receipt -> receipt.getKey() == null
                || receipt.getValue() == null || receipt.getValue() < 0))
            throw new IllegalArgumentException("Invalid protocol relay receipt history");
        relayReceipts = Map.copyOf(relayReceipts);
    }

    ProtocolInboxCheckpoint(UUID ownerId, List<ProtocolInboxEntry> entries) {
        this(ownerId, entries, Map.of());
    }

    static ProtocolInboxCheckpoint empty(UUID ownerId) {
        return new ProtocolInboxCheckpoint(ownerId, List.of());
    }

    ProtocolInboxCheckpoint append(List<ProtocolInboxEntry> incoming) {
        Objects.requireNonNull(incoming, "incoming");
        List<ProtocolInboxEntry> updated = new ArrayList<>(entries);
        Set<MessageIdentity> identities = new HashSet<>();
        entries.forEach(entry -> identities.add(MessageIdentity.of(entry)));
        for (ProtocolInboxEntry entry : incoming) {
            Objects.requireNonNull(entry, "entry");
            if (!entry.message().recipient().equals(new AgentId(ownerId)))
                throw new IllegalArgumentException("Protocol inbox contains a message for another player");
            if (identities.add(MessageIdentity.of(entry))) updated.add(entry);
        }
        return new ProtocolInboxCheckpoint(ownerId, updated, relayReceipts);
    }

    ProtocolInboxCheckpoint appendRelay(ProtocolInboxEntry incoming, UUID messageId, long expiresAtMillis,
                                        long nowMillis) {
        Objects.requireNonNull(incoming, "incoming");
        Objects.requireNonNull(messageId, "messageId");
        if (!incoming.message().recipient().equals(new AgentId(ownerId)))
            throw new IllegalArgumentException("Protocol inbox contains a message for another player");
        if (expiresAtMillis <= nowMillis || expiresAtMillis - nowMillis > ProtocolRelayPacketCodec.MAX_LIFETIME_MILLIS)
            throw new IllegalArgumentException("Protocol relay message has expired");
        Map<UUID, Long> receipts = new java.util.HashMap<>(relayReceipts);
        receipts.entrySet().removeIf(receipt -> receipt.getValue() <= nowMillis);
        if (receipts.containsKey(messageId)) return this;
        if (receipts.size() >= MAX_RELAY_RECEIPTS)
            throw new IllegalStateException("Protocol relay receipt history is full");
        ProtocolInboxCheckpoint appended = append(List.of(incoming));
        receipts.put(messageId, expiresAtMillis);
        return new ProtocolInboxCheckpoint(ownerId, appended.entries(), receipts);
    }

    ProtocolInboxCheckpoint remove(ProtocolInboxEntry selected) {
        Objects.requireNonNull(selected, "selected");
        List<ProtocolInboxEntry> updated = entries.stream()
                .filter(entry -> !MessageIdentity.of(entry).equals(MessageIdentity.of(selected)))
                .toList();
        return updated.size() == entries.size() ? this : new ProtocolInboxCheckpoint(ownerId, updated, relayReceipts);
    }

    ProtocolInboxCheckpoint clearMessages() {
        return entries.isEmpty() ? this : new ProtocolInboxCheckpoint(ownerId, List.of(), relayReceipts);
    }

    private record MessageIdentity(UUID sessionId, AgentId sender, long sentAt, long sequence) {
        private static MessageIdentity of(ProtocolInboxEntry entry) {
            ProtocolMessage message = entry.message();
            return new MessageIdentity(entry.sessionId(), message.sender(), message.sentAt(), message.sequence());
        }
    }
}
