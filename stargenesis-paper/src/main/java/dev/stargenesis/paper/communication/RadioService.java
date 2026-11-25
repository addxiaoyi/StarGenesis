package dev.stargenesis.paper.communication;

import dev.stargenesis.design.Protocol;
import dev.stargenesis.design.ProtocolMessageCodec;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Serialized simulation-only radio transactions. Candidates become visible only after an atomic save. */
final class RadioService implements AutoCloseable {
    private final RadioPersistence persistence;
    private State state;
    private boolean dirty;
    private boolean closed;

    RadioService(RadioPersistence persistence, int messagesPerTick) throws IOException {
        this.persistence = persistence;
        var saved = persistence.load();
        long tick = saved.map(snapshot -> snapshot.radio().tick()).orElse(0L);
        var bus = new VirtualMessageBus(tick, messagesPerTick);
        ContractRuntime contracts;
        if (saved.isPresent()) {
            bus.restore(saved.get().radio(), pinnedProtocols());
            contracts = ContractRuntime.restore(ServiceContractDefinition.contract(), ServiceContractDefinition.protocol(),
                    ContractRuntime.MAX_RETAINED_CONTRACTS, saved.get().contracts());
        } else contracts = new ContractRuntime(ServiceContractDefinition.contract(), ServiceContractDefinition.protocol(),
                0, ContractRuntime.MAX_RETAINED_CONTRACTS);
        if (saved.isPresent()) {
            var radio = saved.get().radio();
            var evidence = Set.copyOf(radio.evidence());
            var acknowledged = Set.copyOf(radio.acknowledged());
            for (var received : saved.get().contracts().received()) {
                var identity = new VirtualMessageBus.MessageIdentity(received.protocol(), received.protocolHash(),
                        received.sender(), received.sequence());
                if (!evidence.contains(received) || !acknowledged.contains(identity))
                    throw new IOException("Applied contract message lacks matching acknowledged radio evidence");
            }
        }
        state = new State(bus, contracts);
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
        if (contract == null) throw new IllegalArgumentException("未知合同 " + id);
        if (!viewer.equals(contract.requester()) && !viewer.equals(contract.provider()))
            throw new SecurityException("只有合同参与者可以查看状态");
        return contract;
    }

    synchronized VirtualMessageBus.Submission send(UUID sender, Protocol protocol, UUID recipient,
                                                 int sequence, Map<String, String> payload) throws IOException {
        requireOpen();
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
        persistence.save(candidate.bus, candidate.contracts);
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
