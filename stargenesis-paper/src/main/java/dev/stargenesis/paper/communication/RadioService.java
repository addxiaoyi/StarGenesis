package dev.stargenesis.paper.communication;

import dev.stargenesis.design.Protocol;
import dev.stargenesis.design.ProtocolMessageCodec;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Serialized simulation-only radio transactions. Candidates become visible only after an atomic save. */
final class RadioService implements AutoCloseable {
    private final RadioPersistence persistence;
    private State state;
    private List<RadioPersistence.ArchiveReference> archives;
    private final Map<VirtualMessageBus.MessageIdentity, String> retiredMessages = new HashMap<>();
    private final Map<String, ContractRuntime.ContractState> retiredContracts = new HashMap<>();
    private boolean dirty;
    private boolean closed;

    RadioService(RadioPersistence persistence, int messagesPerTick) throws IOException {
        this.persistence = persistence;
        var journal = persistence.loadJournal();
        archives = journal.references();
        long lowerTick = 0;
        for (var reference : archives) {
            var archived = persistence.loadArchive(reference);
            State restored = restore(archived, messagesPerTick, lowerTick);
            if (!archived.radio().pending().isEmpty() || restored.contracts.activeCount() != 0
                    || archived.radio().evidence().isEmpty())
                throw new IOException("Archived radio journal is not a nonempty quiescent segment");
            retain(archived);
            lowerTick = archived.radio().tick();
        }
        var saved = journal.active();
        if (saved.isPresent()) {
            state = restore(saved.get(), messagesPerTick, lowerTick);
            rejectRetiredOverlap(saved.get());
        } else state = new State(new VirtualMessageBus(0, messagesPerTick), runtime(0));
    }

    private static State restore(RadioPersistence.Snapshot snapshot, int messagesPerTick, long lowerTick) throws IOException {
        try {
            if (snapshot.radio().tick() < lowerTick
                    || snapshot.radio().evidence().stream().anyMatch(message -> message.tick() < lowerTick)
                    || snapshot.radio().events().stream().anyMatch(event -> event.tick() < lowerTick)
                    || snapshot.contracts().events().stream().anyMatch(event -> event.tick() < lowerTick))
                throw new IOException("Radio journal segments are not chronological");
            var bus = new VirtualMessageBus(snapshot.radio().tick(), messagesPerTick);
            bus.restore(snapshot.radio(), pinnedProtocols());
            var contracts = ContractRuntime.restore(ServiceContractDefinition.contract(), ServiceContractDefinition.protocol(),
                    ContractRuntime.MAX_RETAINED_CONTRACTS, snapshot.contracts());
            var evidence = Set.copyOf(snapshot.radio().evidence());
            var acknowledged = Set.copyOf(snapshot.radio().acknowledged());
            for (var received : snapshot.contracts().received()) {
                if (!evidence.contains(received) || !acknowledged.contains(identity(received)))
                    throw new IOException("Applied contract message lacks matching acknowledged radio evidence");
            }
            return new State(bus, contracts);
        } catch (IllegalArgumentException | IllegalStateException | ArithmeticException invalid) {
            throw new IOException("Invalid radio journal history", invalid);
        }
    }

    private void rejectRetiredOverlap(RadioPersistence.Snapshot snapshot) throws IOException {
        for (var message : snapshot.radio().evidence()) {
            if (retiredMessages.containsKey(identity(message)))
                throw new IOException("Radio journal reuses a message identity from an archived segment");
            if (message.protocol().equals(ServiceContractDefinition.PROTOCOL_ID)
                    && retiredContracts.containsKey(message.payload().get("contract")))
                throw new IOException("Radio journal references an archived contract");
        }
        for (var contract : snapshot.contracts().states())
            if (retiredContracts.containsKey(contract.id()))
                throw new IOException("Radio journal reuses an archived contract identity");
    }

    private void retain(RadioPersistence.Snapshot snapshot) throws IOException {
        rejectRetiredOverlap(snapshot);
        for (var message : snapshot.radio().evidence()) retiredMessages.put(identity(message), requestHash(message));
        for (var contract : snapshot.contracts().states()) retiredContracts.put(contract.id(), contract);
    }

    private static ContractRuntime runtime(long tick) {
        return new ContractRuntime(ServiceContractDefinition.contract(), ServiceContractDefinition.protocol(),
                tick, ContractRuntime.MAX_RETAINED_CONTRACTS);
    }

    private static VirtualMessageBus.MessageIdentity identity(ProtocolMessageCodec.Message message) {
        return new VirtualMessageBus.MessageIdentity(message.protocol(), message.protocolHash(), message.sender(), message.sequence());
    }

    /** Length-prefixed UTF-8 fields, ordered payload keys and no tick: identical cross-tick retries stay duplicates. */
    private static String requestHash(ProtocolMessageCodec.Message message) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            for (String field : List.of(message.protocol().value(), message.protocolHash(), message.sender().toString(),
                    message.recipient().toString(), Integer.toString(message.sequence()))) hashField(digest, field);
            for (var entry : new java.util.TreeMap<>(message.payload()).entrySet()) {
                hashField(digest, entry.getKey());
                hashField(digest, entry.getValue());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void hashField(MessageDigest digest, String field) {
        byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
        digest.update(java.nio.ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    synchronized int archiveCount() { return archives.size(); }

    /** Archives only a fully resolved segment. The active manifest is the commit point. */
    synchronized int rotate() throws IOException {
        requireOpen();
        if (archives.size() >= RadioPersistence.MAX_ARCHIVES)
            throw new IllegalStateException("Radio archive segment budget exhausted");
        if (state.bus.snapshot().evidence().isEmpty()) throw new IllegalStateException("Radio journal is empty");
        if (!state.bus.snapshot().pending().isEmpty() || state.contracts.activeCount() != 0)
            throw new IllegalStateException("Resolve every pending message and active contract before rotation");
        State candidate = new State(state.bus.rotated(), runtime(state.bus.tick()));
        var snapshot = new RadioPersistence.Snapshot(RadioPersistence.SNAPSHOT_VERSION,
                RadioProtocol.ID.value(), RadioProtocol.protocol().metadata().hash(),
                ServiceContractDefinition.PROTOCOL_ID.value(), ServiceContractDefinition.protocol().metadata().hash(),
                ServiceContractDefinition.contract().metadata().id().value(), ServiceContractDefinition.contract().metadata().hash(),
                state.bus.snapshot(), state.contracts.snapshot());
        rejectRetiredOverlap(snapshot);
        // The CAS in persistence also rejects a different writer between these two atomic publications.
        var reference = persistence.archive(snapshot);
        var nextArchives = new ArrayList<>(archives);
        nextArchives.add(reference);
        persistence.saveJournal(candidate.bus, candidate.contracts, nextArchives);
        retain(snapshot);
        archives = List.copyOf(nextArchives);
        state = candidate;
        dirty = false;
        return archives.size();
    }

    synchronized void join(UUID player, VirtualMessageBus.Endpoint endpoint, Set<VirtualMessageBus.ProtocolKey> protocols) {
        requireOpen();
        state.bus.register(player, endpoint, protocols);
    }

    synchronized void leave(UUID player) { requireOpen(); state.bus.unregister(player); }
    synchronized void disconnected(UUID player) { state.bus.unregisterIfRegistered(player); }
    synchronized long tick() { return state.bus.tick(); }
    synchronized int participantCount() { return state.bus.participantCount(); }
    synchronized int retainedEvidenceCount() { return state.bus.retainedEvidenceCount(); }
    synchronized int pendingCount(UUID player) { return state.bus.pendingCount(player); }
    synchronized int activeContracts() { return state.contracts.activeCount(); }
    synchronized VirtualMessageBus.Snapshot radioSnapshot() { return state.bus.snapshot(); }
    synchronized ContractRuntime.Snapshot contractSnapshot() { return state.contracts.snapshot(); }

    synchronized ContractRuntime.ContractState contract(UUID viewer, String id) {
        requireOpen();
        var contract = state.contracts.state(id);
        if (contract == null) contract = retiredContracts.get(id);
        if (contract == null) throw new IllegalArgumentException("未知合同 " + id);
        if (!viewer.equals(contract.requester()) && !viewer.equals(contract.provider()))
            throw new SecurityException("只有合同参与者可以查看状态");
        return contract;
    }

    synchronized VirtualMessageBus.Submission send(UUID sender, Protocol protocol, UUID recipient,
                                                 int sequence, Map<String, String> payload) throws IOException {
        requireOpen();
        var codec = new ProtocolMessageCodec();
        String encoded = codec.encode(protocol, sender, recipient, state.bus.tick(), sequence, payload);
        var message = codec.decode(protocol, encoded);
        String retired = retiredMessages.get(identity(message));
        if (retired != null) {
            if (!retired.equals(requestHash(message))) throw new IllegalArgumentException("Archived message sequence reused with different content");
            return new VirtualMessageBus.Submission(encoded, false);
        }
        if (message.protocol().equals(ServiceContractDefinition.PROTOCOL_ID)
                && retiredContracts.containsKey(message.payload().get("contract")))
            throw new IllegalArgumentException("Archived contract identity cannot be reused");
        State candidate = state.copy();
        var submission = candidate.bus.sendNow(sender, protocol, recipient, sequence, payload);
        if (submission.queued()) commit(candidate);
        return submission;
    }

    synchronized List<ProtocolMessageCodec.Message> poll(UUID recipient, int limit) {
        requireOpen();
        return state.bus.poll(recipient, limit);
    }

    synchronized boolean acknowledge(UUID recipient, ProtocolMessageCodec.Message message) throws IOException {
        requireOpen();
        State candidate = state.copy();
        boolean acknowledged = candidate.bus.acknowledge(recipient, message);
        if (acknowledged) commit(candidate);
        return acknowledged;
    }

    synchronized boolean acknowledge(UUID recipient, RadioAcknowledgement command) throws IOException {
        requireOpen();
        var message = command.select(state.bus.poll(recipient, VirtualMessageBus.MAX_UNACKNOWLEDGED_PER_RECIPIENT));
        return acknowledge(recipient, message);
    }

    synchronized List<String> inbox(UUID recipient, int limit) throws IOException {
        requireOpen();
        var messages = state.bus.poll(recipient, limit);
        State candidate = state;
        boolean changed = false;
        List<String> output = new ArrayList<>();
        for (var message : messages) {
            if (!message.protocol().equals(ServiceContractDefinition.PROTOCOL_ID)
                    || !message.protocolHash().equals(ServiceContractDefinition.protocol().metadata().hash())) {
                output.add("from=" + message.sender() + " · protocol=" + message.protocol().value()
                        + " · tick=" + message.tick() + " · seq=" + message.sequence() + " · " + message.payload());
                continue;
            }
            State next = candidate.copy();
            try {
                boolean applied = next.contracts.receive(message);
                boolean acked = next.bus.acknowledge(recipient, message);
                var contract = next.contracts.state(message.payload().get("contract"));
                output.add("Contract " + message.payload().get("contract") + " · " + contract.state()
                        + " · units=" + contract.fulfilledUnits() + "/" + contract.requestedUnits()
                        + " · " + (applied ? "applied" : "duplicate") + " · "
                        + (acked ? "acknowledged" : "already acknowledged"));
                candidate = next;
                changed |= applied || acked;
            } catch (IllegalArgumentException | IllegalStateException | SecurityException invalid) {
                output.add("Contract message retained for review · " + invalid.getMessage());
                output.add("确认丢弃此消息（不执行契约）：/genesis radio ack " + message.sender()
                        + " " + message.tick() + " " + message.sequence() + " " + message.protocol().value());
            }
        }
        if (changed || dirty) commit(candidate);
        output.addFirst("虚拟信箱 " + messages.size() + " 条 · tick=" + state.bus.tick()
                + " · 未确认=" + state.bus.pendingCount(recipient));
        return List.copyOf(output);
    }

    synchronized void advance() throws IOException {
        requireOpen();
        long nextTick = Math.addExact(state.bus.tick(), 1);
        if (nextTick % 20 == 0 || state.contracts.expiresBy(nextTick)) {
            State candidate = state.copy();
            candidate.contracts.advance(nextTick);
            candidate.bus.advance(nextTick);
            commit(candidate);
        } else {
            // With no expirations, ContractRuntime validates before changing its clock; the bus uses the same tick.
            state.contracts.advance(nextTick);
            state.bus.advance(nextTick);
            dirty = true;
        }
    }

    synchronized void flush() throws IOException {
        requireOpen();
        if (dirty) commit(state);
    }

    @Override public synchronized void close() throws IOException {
        if (closed) return;
        flush();
        closed = true;
    }

    private void commit(State candidate) throws IOException {
        persistence.saveJournal(candidate.bus, candidate.contracts, archives);
        state = candidate;
        dirty = false;
    }

    private void requireOpen() { if (closed) throw new IllegalStateException("虚拟无线信箱已停止。"); }

    private static Map<VirtualMessageBus.ProtocolKey, Protocol> pinnedProtocols() {
        return Map.of(RadioProtocol.key(), RadioProtocol.protocol(), ServiceContractDefinition.key(), ServiceContractDefinition.protocol());
    }

    private record State(VirtualMessageBus bus, ContractRuntime contracts) {
        State copy() { return new State(bus.copy(), contracts.copy()); }
    }
}
