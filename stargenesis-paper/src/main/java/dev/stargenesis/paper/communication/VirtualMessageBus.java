package dev.stargenesis.paper.communication;

import dev.stargenesis.api.design.DesignId;
import dev.stargenesis.design.Protocol;
import dev.stargenesis.design.ProtocolMessageCodec;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Bounded simulation-only radio relay. It has no Bukkit, chat or player-inventory side effects. */
public final class VirtualMessageBus {
    public static final int MAX_PARTICIPANTS = 64;
    public static final int MAX_UNACKNOWLEDGED_PER_RECIPIENT = 64;
    public static final int MAX_RETAINED_EVIDENCE = 4096;
    public static final int MAX_RETAINED_EVENTS = 8192;
    public static final int MAX_ENVELOPE_BYTES = 8192;
    private final int maxMessagesPerTick;
    private final ProtocolMessageCodec codec = new ProtocolMessageCodec();
    private final Map<UUID, EndpointState> endpoints = new HashMap<>();
    private final Map<EnvelopeKey, ProtocolMessageCodec.Message> evidence = new HashMap<>();
    private final Set<EnvelopeKey> acknowledged = new HashSet<>();
    private final List<RadioEvent> events = new ArrayList<>();
    // Only recipients with pending deliveries are retained; idle endpoint churn cannot grow this map.
    private final Map<UUID, List<Delivery>> mailboxes = new HashMap<>();
    private long tick;
    private int messagesThisTick;

    public VirtualMessageBus(long initialTick, int maxMessagesPerTick) {
        if (initialTick < 0) throw new IllegalArgumentException("Negative initial message tick");
        if (maxMessagesPerTick < 0 || maxMessagesPerTick > 1024)
            throw new IllegalArgumentException("Message budget outside 0..1024");
        tick = initialTick;
        this.maxMessagesPerTick = maxMessagesPerTick;
    }

    public synchronized void register(UUID player, Endpoint endpoint, Set<ProtocolKey> installedProtocols) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(endpoint, "endpoint");
        Set<ProtocolKey> protocols = Set.copyOf(installedProtocols);
        if (protocols.isEmpty() || protocols.size() > 64)
            throw new IllegalArgumentException("Participant must install 1..64 protocols");
        if (!endpoints.containsKey(player) && endpoints.size() >= MAX_PARTICIPANTS)
            throw new IllegalStateException("Radio participant budget exhausted");
        endpoints.put(player, new EndpointState(endpoint, protocols));
    }

    /** Returns false for an identical retry, and rejects reuse of the same protocol sequence with different content. */
    public synchronized void unregister(UUID player) {
        requireEndpoint(player);
        endpoints.remove(player);
    }

    public synchronized boolean unregisterIfRegistered(UUID player) {
        return endpoints.remove(player) != null;
    }

    public synchronized Submission sendNow(UUID authenticatedSender, Protocol protocolDefinition, UUID recipient,
                                            int sequence, Map<String, String> payload) {
        Objects.requireNonNull(protocolDefinition, "protocolDefinition");
        String encoded = codec.encode(protocolDefinition, authenticatedSender, recipient, tick, sequence, payload);
        return new Submission(encoded, send(authenticatedSender, protocolDefinition, encoded));
    }

    public synchronized boolean send(UUID authenticatedSender, Protocol protocolDefinition, String encodedEnvelope) {
        ProtocolMessageCodec.Message message = codec.decode(protocolDefinition, encodedEnvelope);
        if (!Objects.equals(authenticatedSender, message.sender()))
            throw new SecurityException("Authenticated player must match the message sender");
        int encodedEnvelopeBytes = encodedEnvelope.getBytes(StandardCharsets.UTF_8).length;
        if (encodedEnvelopeBytes < 1 || encodedEnvelopeBytes > MAX_ENVELOPE_BYTES)
            throw new IllegalArgumentException("Message envelope exceeds its byte budget");
        EnvelopeKey key = new EnvelopeKey(message.protocol(), message.protocolHash(), message.sender(), message.sequence());
        ProtocolMessageCodec.Message previous = evidence.get(key);
        if (previous != null) {
            if (!sameRequest(previous, message))
                throw new IllegalArgumentException("Message sequence reused with different content");
            return false;
        }
        if (message.tick() != tick) throw new IllegalArgumentException("Message tick does not match the bus logical tick");
        EndpointState sender = requireEndpoint(message.sender());
        EndpointState recipient = requireEndpoint(message.recipient());
        ProtocolKey protocol = new ProtocolKey(message.protocol(), message.protocolHash());
        if (!sender.protocols.contains(protocol) || !recipient.protocols.contains(protocol))
            throw new SecurityException("Both participants must install the exact pinned protocol");
        if (!sender.location.network().equals(recipient.location.network())
                || distance(sender.location, recipient.location) > Math.min(sender.location.radioRange(), recipient.location.radioRange()))
            throw new IllegalArgumentException("Recipient is outside the sender radio range");

        if (messagesThisTick >= maxMessagesPerTick) throw new IllegalStateException("Messages-per-tick budget exhausted");
        if (evidence.size() >= MAX_RETAINED_EVIDENCE) throw new IllegalStateException("Message evidence budget exhausted");
        if (events.size() >= MAX_RETAINED_EVENTS) throw new IllegalStateException("Radio event log budget exhausted");
        if (pendingCount(message.recipient()) >= MAX_UNACKNOWLEDGED_PER_RECIPIENT)
            throw new IllegalStateException("Recipient mailbox budget exhausted");

        long availableAt = Math.addExact(tick, 1);
        evidence.put(key, message);
        mailboxes.computeIfAbsent(message.recipient(), ignored -> new ArrayList<>())
                .add(new Delivery(key, message, availableAt));
        messagesThisTick++;
        events.add(new RadioEvent(tick, "radio.sent", message.protocol(), message.protocolHash(),
                message.sender(), message.recipient(), message.sequence()));
        return true;
    }

    /** Advances exactly one logical tick and makes due messages visible. */
    public synchronized void advance(long nextTick) {
        if (nextTick != Math.addExact(tick, 1)) throw new IllegalArgumentException("Message bus ticks must be sequential");
        tick = nextTick;
        messagesThisTick = 0;
        mailboxes.values().forEach(messages -> messages.sort(Comparator
                .comparingLong((Delivery delivery) -> delivery.availableAt)
                .thenComparing(delivery -> delivery.message.sender())
                .thenComparingInt(delivery -> delivery.message.sequence())
                .thenComparing(delivery -> delivery.message.protocol())));
    }

    /** Polls at least once. Repeated polls return the same messages until acknowledged. */
    public synchronized List<ProtocolMessageCodec.Message> poll(UUID recipient, int limit) {
        if (limit < 1 || limit > 64) throw new IllegalArgumentException("Poll limit outside 1..64");
        List<Delivery> mailbox = mailbox(recipient);
        Set<ProtocolKey> installed = requireEndpoint(recipient).protocols;
        return mailbox.stream().filter(delivery -> delivery.availableAt <= tick
                        && installed.contains(new ProtocolKey(delivery.message.protocol(), delivery.message.protocolHash())))
                .limit(limit)
                .map(delivery -> delivery.message).toList();
    }

    /** Acknowledged sequence evidence stays reserved so retries cannot create a second delivery. */
    public synchronized boolean acknowledge(UUID recipient, ProtocolMessageCodec.Message message) {
        Objects.requireNonNull(message, "message");
        List<Delivery> mailbox = mailbox(recipient);
        EnvelopeKey key = new EnvelopeKey(message.protocol(), message.protocolHash(), message.sender(), message.sequence());
        ProtocolMessageCodec.Message known = evidence.get(key);
        if (known == null || !known.equals(message) || !message.recipient().equals(recipient))
            throw new IllegalArgumentException("Message acknowledgement identity mismatch");
        if (!requireEndpoint(recipient).protocols.contains(new ProtocolKey(message.protocol(), message.protocolHash())))
            throw new SecurityException("Recipient no longer has the pinned protocol installed");
        if (acknowledged.contains(key)) return false;
        Delivery delivery = mailbox.stream().filter(item -> item.key.equals(key)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown or already acknowledged message"));
        if (delivery.availableAt > tick) throw new IllegalArgumentException("Cannot acknowledge an undelivered message");
        if (events.size() >= MAX_RETAINED_EVENTS) throw new IllegalStateException("Radio event log budget exhausted");
        mailbox.remove(delivery);
        if (mailbox.isEmpty()) mailboxes.remove(recipient);
        acknowledged.add(key);
        events.add(new RadioEvent(tick, "radio.acknowledged", message.protocol(), message.protocolHash(),
                recipient, message.sender(), message.sequence()));
        return true;
    }

    public synchronized long tick() { return tick; }
    public synchronized int participantCount() { return endpoints.size(); }
    synchronized int retainedMailboxCount() { return mailboxes.size(); }
    public synchronized int retainedEvidenceCount() { return evidence.size(); }
    public synchronized List<RadioEvent> events() { return List.copyOf(events); }
    public synchronized int pendingCount(UUID recipient) {
        List<Delivery> mailbox = mailboxes.get(recipient);
        return mailbox == null ? 0 : mailbox.size();
    }

    /** Transaction candidate; messages and endpoint values are immutable, mailbox lists are not shared. */
    synchronized VirtualMessageBus copy() {
        var copy = new VirtualMessageBus(tick, maxMessagesPerTick);
        copy.endpoints.putAll(endpoints);
        copy.evidence.putAll(evidence);
        copy.acknowledged.addAll(acknowledged);
        copy.events.addAll(events);
        mailboxes.forEach((recipient, deliveries) -> copy.mailboxes.put(recipient, new ArrayList<>(deliveries)));
        copy.messagesThisTick = messagesThisTick;
        return copy;
    }

    public synchronized Snapshot snapshot() {
        List<ProtocolMessageCodec.Message> savedEvidence = evidence.values().stream()
                .sorted(messageOrder()).toList();
        List<MessageIdentity> savedAcknowledgements = acknowledged.stream()
                .map(key -> new MessageIdentity(key.protocol, key.hash, key.sender, key.sequence))
                .sorted(Comparator.comparing((MessageIdentity id) -> id.protocol().value())
                        .thenComparing(MessageIdentity::protocolHash).thenComparing(MessageIdentity::sender)
                        .thenComparingInt(MessageIdentity::sequence)).toList();
        List<PendingDelivery> savedDeliveries = mailboxes.entrySet().stream()
                .flatMap(entry -> entry.getValue().stream().map(delivery ->
                        new PendingDelivery(entry.getKey(), delivery.message, delivery.availableAt)))
                .sorted(Comparator.comparing(PendingDelivery::recipient)
                        .thenComparingLong(PendingDelivery::availableAt)
                        .thenComparing(delivery -> delivery.message().sender())
                        .thenComparingInt(delivery -> delivery.message().sequence())
                        .thenComparing(delivery -> delivery.message().protocol().value())).toList();
        return new Snapshot(tick, savedEvidence, savedAcknowledgements, savedDeliveries, events);
    }

    /** Restores durable message state without restoring transient online endpoints. */
    public synchronized void restore(Snapshot snapshot, Map<ProtocolKey, Protocol> protocols) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(protocols, "protocols");
        if (!endpoints.isEmpty() || !evidence.isEmpty() || !acknowledged.isEmpty() || !events.isEmpty()
                || !mailboxes.isEmpty()) throw new IllegalStateException("Radio bus is not empty");
        if (snapshot.tick() < 0 || snapshot.evidence().size() > MAX_RETAINED_EVIDENCE
                || snapshot.events().size() > MAX_RETAINED_EVENTS)
            throw new IllegalArgumentException("Radio snapshot exceeds its state budget");

        Map<EnvelopeKey, ProtocolMessageCodec.Message> restoredEvidence = new HashMap<>();
        for (ProtocolMessageCodec.Message message : snapshot.evidence()) {
            validateSavedMessage(message, snapshot.tick(), protocols);
            EnvelopeKey key = key(message);
            if (restoredEvidence.putIfAbsent(key, message) != null)
                throw new IllegalArgumentException("Radio snapshot contains duplicate message evidence");
        }

        Set<EnvelopeKey> restoredAcknowledgements = new HashSet<>();
        for (MessageIdentity identity : snapshot.acknowledged()) {
            EnvelopeKey key = new EnvelopeKey(identity.protocol(), identity.protocolHash(), identity.sender(), identity.sequence());
            if (!restoredEvidence.containsKey(key) || !restoredAcknowledgements.add(key))
                throw new IllegalArgumentException("Radio snapshot contains an invalid acknowledgement");
        }

        Map<UUID, List<Delivery>> restoredMailboxes = new HashMap<>();
        Set<EnvelopeKey> pendingKeys = new HashSet<>();
        for (PendingDelivery saved : snapshot.pending()) {
            EnvelopeKey key = key(saved.message());
            ProtocolMessageCodec.Message known = restoredEvidence.get(key);
            long expectedAvailableAt = Math.addExact(saved.message().tick(), 1);
            if (!saved.recipient().equals(saved.message().recipient()) || !saved.message().equals(known)
                    || saved.availableAt() != expectedAvailableAt || saved.availableAt() > Math.addExact(snapshot.tick(), 1)
                    || restoredAcknowledgements.contains(key) || !pendingKeys.add(key))
                throw new IllegalArgumentException("Radio snapshot contains an invalid pending delivery");
            List<Delivery> mailbox = restoredMailboxes.computeIfAbsent(saved.recipient(), ignored -> new ArrayList<>());
            if (mailbox.size() >= MAX_UNACKNOWLEDGED_PER_RECIPIENT)
                throw new IllegalArgumentException("Radio snapshot exceeds a recipient mailbox budget");
            mailbox.add(new Delivery(key, saved.message(), saved.availableAt()));
        }
        if (pendingKeys.size() + restoredAcknowledgements.size() != restoredEvidence.size())
            throw new IllegalArgumentException("Radio snapshot has unaccounted message evidence");

        Map<EnvelopeKey, Integer> sentEvents = new HashMap<>();
        Map<EnvelopeKey, Integer> acknowledgementEvents = new HashMap<>();
        for (RadioEvent event : snapshot.events()) {
            if (event.tick() < 0 || event.tick() > snapshot.tick() || event.actor() == null || event.peer() == null
                    || event.sequence() < 0 || !Set.of("radio.sent", "radio.acknowledged").contains(event.type())
                    || protocols.get(new ProtocolKey(event.protocol(), event.protocolHash())) == null)
                throw new IllegalArgumentException("Radio snapshot contains an invalid event");
            EnvelopeKey key = new EnvelopeKey(event.protocol(), event.protocolHash(),
                    event.type().equals("radio.sent") ? event.actor() : event.peer(), event.sequence());
            ProtocolMessageCodec.Message message = restoredEvidence.get(key);
            if (message == null || event.type().equals("radio.sent")
                    && (!message.sender().equals(event.actor()) || !message.recipient().equals(event.peer())
                    || event.tick() != message.tick())
                    || event.type().equals("radio.acknowledged")
                    && (!message.recipient().equals(event.actor()) || !message.sender().equals(event.peer())
                    || event.tick() < Math.addExact(message.tick(), 1)))
                throw new IllegalArgumentException("Radio snapshot event does not match its message");
            Map<EnvelopeKey, Integer> eventCounts = event.type().equals("radio.sent") ? sentEvents : acknowledgementEvents;
            eventCounts.merge(key, 1, Integer::sum);
        }
        if (sentEvents.size() != restoredEvidence.size()
                || restoredEvidence.keySet().stream().anyMatch(key -> sentEvents.getOrDefault(key, 0) != 1)
                || acknowledgementEvents.size() != restoredAcknowledgements.size()
                || restoredAcknowledgements.stream().anyMatch(key -> acknowledgementEvents.getOrDefault(key, 0) != 1))
            throw new IllegalArgumentException("Radio snapshot event history is incomplete or duplicated");

        tick = snapshot.tick();
        evidence.putAll(restoredEvidence);
        acknowledged.addAll(restoredAcknowledgements);
        restoredMailboxes.forEach((recipient, mailbox) -> {
            mailbox.sort(Comparator.comparingLong((Delivery delivery) -> delivery.availableAt)
                    .thenComparing(delivery -> delivery.message.sender())
                    .thenComparingInt(delivery -> delivery.message.sequence())
                    .thenComparing(delivery -> delivery.message.protocol()));
            mailboxes.put(recipient, mailbox);
        });
        events.addAll(snapshot.events());
        messagesThisTick = (int) restoredEvidence.values().stream().filter(message -> message.tick() == tick).count();
    }

    private void validateSavedMessage(ProtocolMessageCodec.Message message, long snapshotTick,
                                      Map<ProtocolKey, Protocol> protocols) {
        Objects.requireNonNull(message, "saved message");
        Protocol protocol = protocols.get(new ProtocolKey(message.protocol(), message.protocolHash()));
        if (protocol == null || message.tick() > snapshotTick)
            throw new IllegalArgumentException("Radio snapshot references an unpinned message protocol");
        ProtocolMessageCodec.Message validated = codec.decode(protocol, codec.encode(protocol, message.sender(),
                message.recipient(), message.tick(), message.sequence(), message.payload()));
        if (!validated.equals(message)) throw new IllegalArgumentException("Radio snapshot message failed protocol validation");
    }

    private EnvelopeKey key(ProtocolMessageCodec.Message message) {
        return new EnvelopeKey(message.protocol(), message.protocolHash(), message.sender(), message.sequence());
    }

    private Comparator<ProtocolMessageCodec.Message> messageOrder() {
        return Comparator.comparing((ProtocolMessageCodec.Message message) -> message.protocol().value())
                .thenComparing(ProtocolMessageCodec.Message::protocolHash)
                .thenComparing(ProtocolMessageCodec.Message::sender)
                .thenComparingInt(ProtocolMessageCodec.Message::sequence);
    }

    private EndpointState requireEndpoint(UUID player) {
        EndpointState endpoint = endpoints.get(player);
        if (endpoint == null) throw new IllegalArgumentException("Participant is not registered");
        return endpoint;
    }

    private List<Delivery> mailbox(UUID recipient) {
        requireEndpoint(recipient);
        return mailboxes.getOrDefault(recipient, List.of());
    }

    private long distance(Endpoint first, Endpoint second) {
        return Math.abs((long) first.x() - second.x()) + Math.abs((long) first.y() - second.y())
                + Math.abs((long) first.z() - second.z());
    }

    private boolean sameRequest(ProtocolMessageCodec.Message first, ProtocolMessageCodec.Message retry) {
        return first.protocol().equals(retry.protocol()) && first.protocolHash().equals(retry.protocolHash())
                && first.sender().equals(retry.sender()) && first.recipient().equals(retry.recipient())
                && first.sequence() == retry.sequence() && first.payload().equals(retry.payload());
    }

    public record Endpoint(String network, int x, int y, int z, int radioRange) {
        public Endpoint {
            if (network == null || !network.matches("[a-z0-9][a-z0-9_-]{0,63}")
                    || x < -30_000_000 || x > 30_000_000 || y < -30_000_000 || y > 30_000_000
                    || z < -30_000_000 || z > 30_000_000 || radioRange < 0 || radioRange > 256)
                throw new IllegalArgumentException("Invalid radio endpoint");
        }
    }

    public record ProtocolKey(DesignId id, String hash) {
        public ProtocolKey {
            Objects.requireNonNull(id, "id");
            if (hash == null || !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid protocol hash");
        }
    }

    public record RadioEvent(long tick, String type, DesignId protocol, String protocolHash,
                             UUID actor, UUID peer, int sequence) { }

    public record Submission(String encodedEnvelope, boolean queued) { }

    public record MessageIdentity(DesignId protocol, String protocolHash, UUID sender, int sequence) { }

    public record PendingDelivery(UUID recipient, ProtocolMessageCodec.Message message, long availableAt) { }

    public record Snapshot(long tick, List<ProtocolMessageCodec.Message> evidence,
                          List<MessageIdentity> acknowledged, List<PendingDelivery> pending,
                          List<RadioEvent> events) {
        public Snapshot {
            evidence = List.copyOf(evidence);
            acknowledged = List.copyOf(acknowledged);
            pending = List.copyOf(pending);
            events = List.copyOf(events);
        }
    }

    private record EndpointState(Endpoint location, Set<ProtocolKey> protocols) { }
    private record EnvelopeKey(DesignId protocol, String hash, UUID sender, int sequence) { }
    private record Delivery(EnvelopeKey key, ProtocolMessageCodec.Message message, long availableAt) { }
}
