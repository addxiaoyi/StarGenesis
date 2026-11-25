package dev.stargenesis.sim;

import dev.stargenesis.api.sim.AgentId;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable bounded state needed to resume a deterministic private mailbox hub. */
public record ProtocolMailboxCheckpoint(long tick, Set<AgentId> participants,
                                        Map<AgentId, Set<String>> protocolPermissions,
                                        List<ProtocolMessage> pendingMessages,
                                        Map<AgentId, Integer> nextSequenceBySender,
                                        int sendsThisStep) {
    private static final String PROTOCOL_ID = "[a-z][a-z0-9_-]{0,31}:[a-z0-9][a-z0-9_./-]{0,94}";
    private static final Comparator<ProtocolMessage> MESSAGE_ORDER = Comparator
            .comparing(ProtocolMessage::recipient)
            .thenComparing(ProtocolMessage::sender)
            .thenComparingLong(ProtocolMessage::sentAt)
            .thenComparingLong(ProtocolMessage::sequence);

    public ProtocolMailboxCheckpoint {
        if (tick < 0 || sendsThisStep < 0 || sendsThisStep > ProtocolMailboxHub.MAX_MESSAGES_PER_STEP)
            throw new IllegalArgumentException("Invalid protocol checkpoint clock or send count");
        Objects.requireNonNull(participants, "participants");
        if (participants.isEmpty() || participants.size() > 256 || participants.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("Invalid protocol checkpoint participants");
        participants = Set.copyOf(participants);

        Objects.requireNonNull(protocolPermissions, "protocolPermissions");
        if (!participants.containsAll(protocolPermissions.keySet()))
            throw new IllegalArgumentException("Protocol checkpoint permissions contain an unknown participant");
        Map<AgentId, Set<String>> copiedPermissions = new HashMap<>();
        protocolPermissions.forEach((agent, protocols) -> {
            if (protocols == null || protocols.size() > 64
                    || protocols.stream().anyMatch(id -> id == null || !id.matches(PROTOCOL_ID)))
                throw new IllegalArgumentException("Invalid protocol checkpoint permissions");
            copiedPermissions.put(agent, Set.copyOf(protocols));
        });
        protocolPermissions = Map.copyOf(copiedPermissions);

        Objects.requireNonNull(nextSequenceBySender, "nextSequenceBySender");
        if (!participants.containsAll(nextSequenceBySender.keySet()))
            throw new IllegalArgumentException("Protocol checkpoint sequence contains an unknown participant");
        int sequenceTotal = 0;
        for (int nextSequence : nextSequenceBySender.values()) {
            if (nextSequence < 1 || nextSequence > ProtocolMailboxHub.MAX_MESSAGES_PER_STEP)
                throw new IllegalArgumentException("Invalid protocol checkpoint sender sequence");
            sequenceTotal = Math.addExact(sequenceTotal, nextSequence);
        }
        if (sequenceTotal != sendsThisStep)
            throw new IllegalArgumentException("Protocol checkpoint send count does not match sender sequences");
        nextSequenceBySender = Map.copyOf(nextSequenceBySender);

        Objects.requireNonNull(pendingMessages, "pendingMessages");
        if (pendingMessages.size() > ProtocolMailboxHub.MAX_PENDING_TOTAL)
            throw new IllegalArgumentException("Protocol checkpoint exceeds total mailbox capacity");
        List<ProtocolMessage> copiedMessages = new ArrayList<>(pendingMessages);
        copiedMessages.sort(MESSAGE_ORDER);
        Map<AgentId, Integer> mailboxCounts = new HashMap<>();
        Set<MessageIdentity> identities = new HashSet<>();
        for (ProtocolMessage message : copiedMessages) {
            Objects.requireNonNull(message, "pending message");
            if (!participants.contains(message.sender()) || !participants.contains(message.recipient()))
                throw new IllegalArgumentException("Protocol checkpoint message has an unknown participant");
            if (message.sentAt() > tick || message.expiresAt() <= tick
                    || message.expiresAt() - message.sentAt() > ProtocolMailboxHub.MAX_TTL_STEPS)
                throw new IllegalArgumentException("Protocol checkpoint contains an expired or invalid message");
            if (!protocolPermissions.getOrDefault(message.sender(), Set.of()).contains(message.protocolId())
                    || !protocolPermissions.getOrDefault(message.recipient(), Set.of()).contains(message.protocolId()))
                throw new IllegalArgumentException("Protocol checkpoint contains an unauthorized message");
            if (!identities.add(new MessageIdentity(message.sender(), message.sentAt(), message.sequence())))
                throw new IllegalArgumentException("Protocol checkpoint contains a duplicate message sequence");
            if (message.sentAt() == tick
                    && message.sequence() >= nextSequenceBySender.getOrDefault(message.sender(), 0))
                throw new IllegalArgumentException("Protocol checkpoint sender sequence precedes its message");
            int mailboxSize = mailboxCounts.merge(message.recipient(), 1, Integer::sum);
            if (mailboxSize > ProtocolMailboxHub.MAX_PENDING_PER_AGENT)
                throw new IllegalArgumentException("Protocol checkpoint exceeds recipient mailbox capacity");
        }
        pendingMessages = List.copyOf(copiedMessages);
    }

    private record MessageIdentity(AgentId sender, long sentAt, long sequence) { }
}
