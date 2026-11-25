package dev.stargenesis.sim;

import dev.stargenesis.api.sim.AgentId;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Single-owner, deterministic private mailboxes with explicit per-step and storage budgets. */
public final class ProtocolMailboxHub {
    public static final int MAX_MESSAGE_BYTES = 4096;
    public static final int MAX_MESSAGES_PER_STEP = 64;
    public static final int MAX_PENDING_PER_AGENT = 128;
    public static final int MAX_PENDING_TOTAL = 4096;
    public static final long MAX_TTL_STEPS = 72_000;

    private final Map<AgentId, ArrayDeque<ProtocolMessage>> pending = new HashMap<>();
    private final Set<AgentId> participants;
    private final Map<AgentId, Set<String>> protocolPermissions;
    private long tick;
    private final OwnerThread owner = new OwnerThread();
    private final Map<AgentId, Integer> nextSequenceBySender = new HashMap<>();
    private int sendsThisStep;
    private int pendingCount;

    public ProtocolMailboxHub() {
        participants = new HashSet<>();
        protocolPermissions = new HashMap<>();
    }

    public ProtocolMailboxHub(Set<AgentId> participants) {
        this(participants, Map.of());
    }

    public ProtocolMailboxHub(Set<AgentId> participants, Map<AgentId, Set<String>> protocolPermissions) {
        if (participants == null || participants.isEmpty() || participants.size() > 256
                || participants.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("Invalid protocol participants");
        this.participants = new HashSet<>(participants);
        Objects.requireNonNull(protocolPermissions, "protocolPermissions");
        if (!this.participants.containsAll(protocolPermissions.keySet()))
            throw new IllegalArgumentException("Protocol permissions contain an unknown participant");
        Map<AgentId, Set<String>> copiedPermissions = new HashMap<>();
        protocolPermissions.forEach((agent, protocols) -> {
            if (protocols == null || protocols.size() > 64
                    || protocols.stream().anyMatch(id -> id == null
                    || !id.matches("[a-z][a-z0-9_-]{0,31}:[a-z0-9][a-z0-9_./-]{0,94}")))
                throw new IllegalArgumentException("Invalid participant protocol permissions");
            copiedPermissions.put(agent, Set.copyOf(protocols));
        });
        this.protocolPermissions = new HashMap<>(copiedPermissions);
    }

    public void register(AgentId participant, Set<String> protocols) {
        owner.check();
        Objects.requireNonNull(participant, "participant");
        Objects.requireNonNull(protocols, "protocols");
        if (protocols.size() > 64 || protocols.stream().anyMatch(id -> id == null
                || !id.matches("[a-z][a-z0-9_-]{0,31}:[a-z0-9][a-z0-9_./-]{0,94}")))
            throw new IllegalArgumentException("Invalid participant protocol permissions");
        if (!participants.contains(participant) && participants.size() >= 256)
            throw new IllegalStateException("Protocol participant budget exhausted");
        participants.add(participant);
        Set<String> merged = new HashSet<>(protocolPermissions.getOrDefault(participant, Set.of()));
        merged.addAll(protocols);
        protocolPermissions.put(participant, Set.copyOf(merged));
    }

    public Set<AgentId> participants() {
        owner.check();
        return Set.copyOf(participants);
    }

    public Set<AgentId> pendingRecipients() {
        owner.check();
        return Set.copyOf(pending.keySet());
    }

    public List<ProtocolMessage> pendingMessages(AgentId recipient) {
        owner.check();
        requireParticipant(recipient);
        ArrayDeque<ProtocolMessage> mailbox = pending.get(recipient);
        if (mailbox == null || mailbox.isEmpty()) return List.of();
        return mailbox.stream().sorted(messageOrder()).toList();
    }

    public void acknowledgePersisted(AgentId recipient, List<ProtocolMessage> persisted) {
        owner.check();
        requireParticipant(recipient);
        Objects.requireNonNull(persisted, "persisted");
        if (persisted.isEmpty()) return;
        Set<ProtocolMessage> unique = Set.copyOf(persisted);
        if (unique.size() != persisted.size()) throw new IllegalArgumentException("Duplicate persisted message");
        ArrayDeque<ProtocolMessage> mailbox = pending.get(recipient);
        if (mailbox == null || !mailbox.containsAll(unique))
            throw new IllegalArgumentException("Cannot acknowledge a message that is not pending");
        for (ProtocolMessage message : unique) {
            mailbox.remove(message);
            pendingCount--;
        }
        if (mailbox.isEmpty()) pending.remove(recipient);
    }

    public boolean retireIfIdle(AgentId participant) {
        owner.check();
        if (!participants.contains(participant)) return true;
        boolean isRecipient = pending.containsKey(participant);
        boolean isSender = pending.values().stream().flatMap(java.util.Collection::stream)
                .anyMatch(message -> message.sender().equals(participant));
        if (isRecipient || isSender || nextSequenceBySender.containsKey(participant)) return false;
        participants.remove(participant);
        protocolPermissions.remove(participant);
        return true;
    }

    public long tick() {
        owner.check();
        return tick;
    }

    public ProtocolMailboxCheckpoint checkpoint() {
        owner.check();
        List<ProtocolMessage> messages = pending.values().stream()
                .flatMap(java.util.Collection::stream)
                .toList();
        return new ProtocolMailboxCheckpoint(tick, participants, protocolPermissions, messages,
                nextSequenceBySender, sendsThisStep);
    }

    public static ProtocolMailboxHub restore(ProtocolMailboxCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        ProtocolMailboxHub hub = new ProtocolMailboxHub(checkpoint.participants(), checkpoint.protocolPermissions());
        hub.tick = checkpoint.tick();
        for (ProtocolMessage message : checkpoint.pendingMessages()) {
            hub.pending.computeIfAbsent(message.recipient(), ignored -> new ArrayDeque<>()).addLast(message);
            hub.pendingCount++;
        }
        hub.nextSequenceBySender.putAll(checkpoint.nextSequenceBySender());
        hub.sendsThisStep = checkpoint.sendsThisStep();
        return hub;
    }

    public ProtocolDelivery send(AgentId sender, AgentId recipient, String protocolId, String payload, long ttlSteps) {
        owner.check();
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(recipient, "recipient");
        if (!participants.contains(sender) || !participants.contains(recipient))
            throw new IllegalArgumentException("Sender and recipient must be registered participants");
        if (sendsThisStep >= MAX_MESSAGES_PER_STEP) throw new IllegalStateException("Message budget exhausted");
        if (ttlSteps < 1 || ttlSteps > MAX_TTL_STEPS) throw new IllegalArgumentException("Invalid message lifetime");
        int sequence = nextSequenceBySender.getOrDefault(sender, 0);
        ProtocolMessage message = new ProtocolMessage(sequence, tick, Math.addExact(tick, ttlSteps),
                sender, recipient, protocolId, payload);
        sendsThisStep++;
        nextSequenceBySender.put(sender, sequence + 1);
        Set<String> senderProtocols = protocolPermissions.getOrDefault(sender, Set.of());
        Set<String> recipientProtocols = protocolPermissions.getOrDefault(recipient, Set.of());
        if (!senderProtocols.contains(protocolId) || !recipientProtocols.contains(protocolId))
            return new ProtocolDelivery(message, ProtocolDelivery.Status.REJECTED, "PROTOCOL_NOT_AUTHORIZED");
        ArrayDeque<ProtocolMessage> mailbox = pending.computeIfAbsent(recipient, ignored -> new ArrayDeque<>());
        if (mailbox.size() >= MAX_PENDING_PER_AGENT || pendingCount >= MAX_PENDING_TOTAL)
            return new ProtocolDelivery(message, ProtocolDelivery.Status.REJECTED, "MAILBOX_FULL");
        mailbox.addLast(message);
        pendingCount++;
        return new ProtocolDelivery(message, ProtocolDelivery.Status.QUEUED, "QUEUED_FOR_RECIPIENT");
    }

    public List<ProtocolDelivery> drain(AgentId recipient) {
        owner.check();
        requireParticipant(recipient);
        ArrayDeque<ProtocolMessage> mailbox = pending.get(recipient);
        if (mailbox == null || mailbox.isEmpty()) return List.of();
        List<ProtocolMessage> messages = new ArrayList<>(mailbox);
        messages.sort(Comparator.comparing(ProtocolMessage::sender).thenComparingLong(ProtocolMessage::sentAt)
                .thenComparingLong(ProtocolMessage::sequence));
        List<ProtocolDelivery> results = new ArrayList<>(messages.size());
        for (ProtocolMessage message : messages) {
            mailbox.remove(message);
            pendingCount--;
            if (message.expiresAt() <= tick) {
                results.add(new ProtocolDelivery(message, ProtocolDelivery.Status.EXPIRED, "MESSAGE_EXPIRED"));
                continue;
            }
            results.add(new ProtocolDelivery(message, ProtocolDelivery.Status.DELIVERED, "DELIVERED"));
        }
        pending.remove(recipient);
        return List.copyOf(results);
    }

    /** Advances one logical step, returning expired messages in stable recipient/sender order. */
    public List<ProtocolDelivery> advance() {
        owner.check();
        tick = Math.addExact(tick, 1);
        sendsThisStep = 0;
        nextSequenceBySender.clear();
        List<ProtocolMessage> expired = pending.values().stream().flatMap(java.util.Collection::stream)
                .filter(message -> message.expiresAt() <= tick)
                .sorted(Comparator.comparing(ProtocolMessage::recipient).thenComparing(ProtocolMessage::sender)
                        .thenComparingLong(ProtocolMessage::sentAt).thenComparingLong(ProtocolMessage::sequence))
                .toList();
        List<ProtocolDelivery> results = new ArrayList<>(expired.size());
        Set<AgentId> changedRecipients = new java.util.HashSet<>();
        for (ProtocolMessage message : expired) {
            ArrayDeque<ProtocolMessage> mailbox = pending.get(message.recipient());
            mailbox.remove(message);
            changedRecipients.add(message.recipient());
            pendingCount--;
            results.add(new ProtocolDelivery(message, ProtocolDelivery.Status.EXPIRED, "MESSAGE_EXPIRED"));
        }
        changedRecipients.stream().filter(recipient -> pending.get(recipient).isEmpty()).forEach(pending::remove);
        return List.copyOf(results);
    }

    private void requireParticipant(AgentId participant) {
        if (!participants.contains(participant)) throw new IllegalArgumentException("Unknown recipient");
    }

    private static Comparator<ProtocolMessage> messageOrder() {
        return Comparator.comparing(ProtocolMessage::sender).thenComparingLong(ProtocolMessage::sentAt)
                .thenComparingLong(ProtocolMessage::sequence);
    }
}
