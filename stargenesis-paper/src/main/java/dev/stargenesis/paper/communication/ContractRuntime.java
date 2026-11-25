package dev.stargenesis.paper.communication;

import dev.stargenesis.api.design.DesignId;
import dev.stargenesis.api.design.DesignDependency;
import dev.stargenesis.design.Contract;
import dev.stargenesis.design.DesignCodec;
import dev.stargenesis.design.DesignValidationException;
import dev.stargenesis.design.Protocol;
import dev.stargenesis.design.ProtocolMessageCodec;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/** Bounded, simulation-only contract state machine. It never settles inventory or currency. */
public final class ContractRuntime {
    public static final int MAX_RETAINED_CONTRACTS = 256;
    public static final int MAX_EVENTS = 8192;
    public static final int MAX_SEQUENCE_EVIDENCE = 8192;
    private static final List<String> REQUIRED_CONTROL_FIELDS = List.of("contract", "action", "units");
    private final Contract contract;
    private final Protocol protocol;
    private final ProtocolMessageCodec messageCodec = new ProtocolMessageCodec();
    private final int maxContracts;
    private final Map<String, ContractState> states = new TreeMap<>();
    private final Map<SequenceKey, ProtocolMessageCodec.Message> received = new HashMap<>();
    private final List<ContractEvent> events = new ArrayList<>();
    private long tick;

    public ContractRuntime(Contract contract, Protocol protocol, long initialTick, int maxContracts) {
        this.contract = Objects.requireNonNull(contract, "contract");
        this.protocol = Objects.requireNonNull(protocol, "protocol");
        if (initialTick < 0) throw new IllegalArgumentException("Negative contract tick");
        if (maxContracts < 1 || maxContracts > MAX_RETAINED_CONTRACTS)
            throw new IllegalArgumentException("Contract budget outside 1.." + MAX_RETAINED_CONTRACTS);
        validatePinnedDefinitions(contract, protocol);
        this.tick = initialTick;
        this.maxContracts = maxContracts;
    }

    public synchronized boolean receive(ProtocolMessageCodec.Message message) {
        Objects.requireNonNull(message, "message");
        validateMessageProtocol(message);
        if (message.tick() > tick) throw new IllegalArgumentException("Contract message is from a future tick");
        SequenceKey sequenceKey = new SequenceKey(message.sender(), message.sequence());
        ProtocolMessageCodec.Message previous = received.get(sequenceKey);
        if (previous != null) {
            if (!sameRequest(previous, message)) throw new IllegalArgumentException("Contract sequence reused with different content");
            return false;
        }
        if (received.size() >= MAX_SEQUENCE_EVIDENCE) throw new IllegalStateException("Contract sequence evidence budget exhausted");
        String reference = requiredToken(message, "contract");
        String action = requiredAction(message);
        long units = requiredUnits(message);
        ContractState current = states.get(reference);
        ContractState updated = transition(reference, action, units, message, current);
        if (events.size() >= MAX_EVENTS) throw new IllegalStateException("Contract event budget exhausted");

        if (updated != current) states.put(reference, updated);
        received.put(sequenceKey, message);
        events.add(new ContractEvent(tick, "contract." + action, reference, message.sender(), message.recipient(),
                units, updated.state(), message.sequence()));
        return true;
    }

    public synchronized List<ContractEvent> advance(long nextTick) {
        if (nextTick != Math.addExact(tick, 1)) throw new IllegalArgumentException("Contract ticks must be sequential");
        List<String> expiring = states.values().stream().filter(state -> !state.state().terminal())
                .filter(state -> nextTick > state.expiresAt()).map(ContractState::id).sorted().toList();
        if (events.size() + expiring.size() > MAX_EVENTS)
            throw new IllegalStateException("Contract event budget exhausted");
        tick = nextTick;
        List<ContractEvent> advanced = new ArrayList<>();
        for (String id : expiring) {
            ContractState previous = states.get(id);
            ContractState expired = new ContractState(previous.id(), previous.requester(), previous.provider(),
                    previous.requestedUnits(), previous.fulfilledUnits(), previous.createdAt(), previous.expiresAt(), State.EXPIRED);
            states.put(id, expired);
            ContractEvent event = new ContractEvent(tick, "contract.expire", id, previous.provider(), previous.requester(),
                    0, State.EXPIRED, -1);
            events.add(event);
            advanced.add(event);
        }
        return List.copyOf(advanced);
    }

    public synchronized ContractState state(String id) { return states.get(id); }
    public synchronized long tick() { return tick; }
    public synchronized List<ContractEvent> events() { return List.copyOf(events); }
    public synchronized int activeCount() { return (int) states.values().stream().filter(state -> !state.state().terminal()).count(); }
    public Contract contract() { return contract; }

    public synchronized Snapshot snapshot() {
        List<ProtocolMessageCodec.Message> savedMessages = received.values().stream()
                .sorted(Comparator.comparing(ProtocolMessageCodec.Message::sender)
                        .thenComparingInt(ProtocolMessageCodec.Message::sequence)).toList();
        return new Snapshot(tick, List.copyOf(states.values()), savedMessages, events);
    }

    public static ContractRuntime restore(Contract contract, Protocol protocol, int maxContracts, Snapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        ContractRuntime runtime = new ContractRuntime(contract, protocol, snapshot.tick(), maxContracts);
        runtime.restore(snapshot);
        return runtime;
    }

    private synchronized void restore(Snapshot snapshot) {
        if (tick != snapshot.tick() || !states.isEmpty() || !received.isEmpty() || !events.isEmpty()
                || snapshot.tick() < 0 || snapshot.states().size() > maxContracts
                || snapshot.received().size() > MAX_SEQUENCE_EVIDENCE || snapshot.events().size() > MAX_EVENTS)
            throw new IllegalArgumentException("Contract snapshot exceeds its state budget or tick");

        Map<String, ContractState> restoredStates = new TreeMap<>();
        for (ContractState state : snapshot.states()) {
            validateState(state, snapshot.tick());
            if (restoredStates.putIfAbsent(state.id(), state) != null)
                throw new IllegalArgumentException("Contract snapshot contains duplicate contract state");
        }

        Map<SequenceKey, ProtocolMessageCodec.Message> restoredMessages = new HashMap<>();
        for (ProtocolMessageCodec.Message message : snapshot.received()) {
            validateMessageProtocol(message);
            if (message.tick() > snapshot.tick()
                    || restoredMessages.putIfAbsent(new SequenceKey(message.sender(), message.sequence()), message) != null)
                throw new IllegalArgumentException("Contract snapshot contains invalid sequence evidence");
        }

        for (ContractEvent event : snapshot.events()) {
            if (event.tick() < 0 || event.tick() > snapshot.tick() || event.contractId() == null
                    || !event.contractId().matches("[a-z][a-z0-9_:-]{0,63}")
                    || !restoredStates.containsKey(event.contractId()) || event.actor() == null || event.peer() == null
                    || event.actor().equals(event.peer()) || event.units() < 0 || event.state() == null
                    || !validEvent(event))
                throw new IllegalArgumentException("Contract snapshot contains an invalid event");
        }

        states.putAll(restoredStates);
        received.putAll(restoredMessages);
        events.addAll(snapshot.events());
    }

    private void validateState(ContractState state, long snapshotTick) {
        Objects.requireNonNull(state, "contract state");
        if (state.id() == null || !state.id().matches("[a-z][a-z0-9_:-]{0,63}")
                || state.requester() == null || state.provider() == null || state.requester().equals(state.provider())
                || state.requestedUnits() < 1 || state.requestedUnits() > contract.maxUnits()
                || state.fulfilledUnits() < 0 || state.fulfilledUnits() > state.requestedUnits()
                || state.createdAt() < 0 || state.createdAt() > snapshotTick
                || state.expiresAt() != Math.addExact(state.createdAt(), contract.timeoutSteps())
                || state.state() == null || snapshotTick > state.expiresAt() && !state.state().terminal())
            throw new IllegalArgumentException("Contract snapshot contains an invalid state");
        if (state.state() == State.OFFERED && state.fulfilledUnits() != 0
                || state.state() == State.ACCEPTED && state.fulfilledUnits() >= state.requestedUnits()
                || state.state() == State.COMPLETED && state.fulfilledUnits() != state.requestedUnits()
                || state.state() == State.EXPIRED && snapshotTick <= state.expiresAt())
            throw new IllegalArgumentException("Contract snapshot state does not match its fulfilled units or deadline");
    }

    private boolean validEvent(ContractEvent event) {
        if (event.type() == null) return false;
        if (event.type().equals("contract.expire"))
            return event.sequence() == -1 && event.units() == 0 && event.state() == State.EXPIRED;
        if (event.sequence() < 0 || !event.type().startsWith("contract.")) return false;
        return switch (event.type().substring("contract.".length())) {
            case "offer" -> event.state() == State.OFFERED;
            case "accept" -> event.state() == State.ACCEPTED;
            case "fulfill" -> event.state() == State.ACCEPTED || event.state() == State.COMPLETED;
            case "reject" -> event.state() == State.REJECTED;
            case "cancel" -> event.state() == State.CANCELLED;
            default -> false;
        };
    }
    public Protocol protocol() { return protocol; }

    private ContractState transition(String reference, String action, long units,
                                     ProtocolMessageCodec.Message message, ContractState current) {
        return switch (action) {
            case "offer" -> offer(reference, units, message, current);
            case "accept" -> accept(reference, units, message, current);
            case "reject" -> reject(reference, units, message, current);
            case "fulfill" -> fulfill(reference, units, message, current);
            case "cancel" -> cancel(reference, units, message, current);
            default -> throw new IllegalArgumentException("Unknown contract action");
        };
    }

    private ContractState offer(String reference, long units, ProtocolMessageCodec.Message message, ContractState current) {
        if (current != null) throw new IllegalArgumentException("Contract reference already exists");
        if (states.size() >= maxContracts) throw new IllegalStateException("Contract count budget exhausted");
        if (message.sender().equals(message.recipient())) throw new IllegalArgumentException("A contract requires two participants");
        requireUnits(units);
        return new ContractState(reference, message.sender(), message.recipient(), units, 0, tick,
                Math.addExact(tick, contract.timeoutSteps()), State.OFFERED);
    }

    private ContractState accept(String reference, long units, ProtocolMessageCodec.Message message, ContractState current) {
        ContractState active = requireState(reference, current, State.OFFERED, message);
        requireCounterparty(active.provider(), active.requester(), message);
        if (units != active.requestedUnits()) throw new IllegalArgumentException("Acceptance units differ from the offer");
        return withState(active, State.ACCEPTED, active.fulfilledUnits());
    }

    private ContractState reject(String reference, long units, ProtocolMessageCodec.Message message, ContractState current) {
        ContractState active = requireState(reference, current, State.OFFERED, message);
        requireCounterparty(active.provider(), active.requester(), message);
        if (units != 0) throw new IllegalArgumentException("Reject action must carry zero units");
        return withState(active, State.REJECTED, active.fulfilledUnits());
    }

    private ContractState fulfill(String reference, long units, ProtocolMessageCodec.Message message, ContractState current) {
        ContractState active = requireState(reference, current, State.ACCEPTED, message);
        requireCounterparty(active.provider(), active.requester(), message);
        requireUnits(units);
        long fulfilled = Math.addExact(active.fulfilledUnits(), units);
        if (fulfilled > active.requestedUnits()) throw new IllegalArgumentException("Fulfillment exceeds the accepted units");
        State result = fulfilled == active.requestedUnits() ? State.COMPLETED : State.ACCEPTED;
        return withState(active, result, fulfilled);
    }

    private ContractState cancel(String reference, long units, ProtocolMessageCodec.Message message, ContractState current) {
        if (current == null || current.state().terminal()) throw new IllegalArgumentException("Contract is not cancellable");
        if (message.tick() >= current.expiresAt()) throw new IllegalStateException("Contract has expired");
        if (units != 0) throw new IllegalArgumentException("Cancel action must carry zero units");
        if (!current.requester().equals(message.sender()) || !current.provider().equals(message.recipient())) {
            if (!current.provider().equals(message.sender()) || !current.requester().equals(message.recipient()))
                throw new SecurityException("Cancel message participants do not match contract parties");
        }
        return withState(current, State.CANCELLED, current.fulfilledUnits());
    }

    private ContractState requireState(String id, ContractState current, State expected,
                                       ProtocolMessageCodec.Message message) {
        if (current == null || current.state() != expected) throw new IllegalArgumentException("Contract " + id + " is not " + expected);
        if (message.tick() >= current.expiresAt()) throw new IllegalStateException("Contract has expired");
        return current;
    }

    private void requireCounterparty(UUID sender, UUID recipient, ProtocolMessageCodec.Message message) {
        if (!sender.equals(message.sender()) || !recipient.equals(message.recipient()))
            throw new SecurityException("Contract message direction does not match participants");
    }

    private ContractState withState(ContractState current, State state, long fulfilled) {
        return new ContractState(current.id(), current.requester(), current.provider(), current.requestedUnits(),
                fulfilled, current.createdAt(), current.expiresAt(), state);
    }

    private void validatePinnedDefinitions(Contract contract, Protocol protocol) {
        DesignCodec codec = new DesignCodec();
        codec.write(contract);
        codec.write(protocol);
        if (!contract.protocol().equals(protocol.metadata().id()))
            throw new IllegalArgumentException("Contract references another protocol");
        DesignDependency dependency = contract.metadata().dependencies().stream()
                .filter(item -> item.id().equals(protocol.metadata().id())).findFirst().orElseThrow();
        if (!dependency.version().equals(protocol.metadata().version()) || !dependency.hash().equals(protocol.metadata().hash()))
            throw new IllegalArgumentException("Contract protocol dependency is not pinned to this protocol hash");
        for (String name : REQUIRED_CONTROL_FIELDS) {
            Protocol.Field field = protocol.fields().stream().filter(item -> item.name().equals(name)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Contract protocol is missing field " + name));
            Protocol.FieldType expected = name.equals("units") ? Protocol.FieldType.INTEGER : Protocol.FieldType.TOKEN;
            if (field.type() != expected || !field.required())
                throw new IllegalArgumentException("Contract protocol control field has an incompatible type");
        }
    }

    private void validateMessageProtocol(ProtocolMessageCodec.Message message) {
        if (!message.protocol().equals(protocol.metadata().id()) || !message.protocolHash().equals(protocol.metadata().hash()))
            throw new SecurityException("Contract message protocol is not the pinned protocol");
        ProtocolMessageCodec.Message validated = messageCodec.decode(protocol, messageCodec.encode(protocol,
                message.sender(), message.recipient(), message.tick(), message.sequence(), message.payload()));
        if (!validated.equals(message)) throw new IllegalArgumentException("Contract message failed protocol validation");
    }

    private String requiredToken(ProtocolMessageCodec.Message message, String field) {
        String value = message.payload().get(field);
        if (value == null || !value.matches("[a-z][a-z0-9_:-]{0,63}"))
            throw new IllegalArgumentException("Missing or invalid contract " + field);
        return value;
    }

    private String requiredAction(ProtocolMessageCodec.Message message) {
        String action = requiredToken(message, "action");
        if (!List.of("offer", "accept", "reject", "fulfill", "cancel").contains(action))
            throw new IllegalArgumentException("Unsupported contract action");
        return action;
    }

    private long requiredUnits(ProtocolMessageCodec.Message message) {
        String value = message.payload().get("units");
        if (value == null) throw new IllegalArgumentException("Missing contract units");
        try { return Long.parseLong(value); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid contract units", invalid); }
    }

    private void requireUnits(long units) {
        if (units < 1 || units > contract.maxUnits()) throw new IllegalArgumentException("Contract units outside contract limit");
    }

    private boolean sameRequest(ProtocolMessageCodec.Message first, ProtocolMessageCodec.Message second) {
        return first.protocol().equals(second.protocol()) && first.protocolHash().equals(second.protocolHash())
                && first.sender().equals(second.sender()) && first.recipient().equals(second.recipient())
                && first.sequence() == second.sequence() && first.payload().equals(second.payload());
    }

    public enum State {
        OFFERED, ACCEPTED, COMPLETED, REJECTED, CANCELLED, EXPIRED;
        public boolean terminal() { return this == COMPLETED || this == REJECTED || this == CANCELLED || this == EXPIRED; }
    }

    public record ContractState(String id, UUID requester, UUID provider, long requestedUnits, long fulfilledUnits,
                                long createdAt, long expiresAt, State state) { }

    public record ContractEvent(long tick, String type, String contractId, UUID actor, UUID peer,
                                long units, State state, int sequence) { }

    public record Snapshot(long tick, List<ContractState> states,
                           List<ProtocolMessageCodec.Message> received, List<ContractEvent> events) {
        public Snapshot {
            states = List.copyOf(states);
            received = List.copyOf(received);
            events = List.copyOf(events);
        }
    }

    private record SequenceKey(UUID sender, int sequence) { }
}
