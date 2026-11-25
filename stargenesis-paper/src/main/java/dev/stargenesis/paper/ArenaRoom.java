package dev.stargenesis.paper;

import dev.stargenesis.api.design.DesignDependency;
import dev.stargenesis.design.ArenaRuleset;
import dev.stargenesis.sim.ArenaMatchWorld;
import dev.stargenesis.sim.ArenaMatchWorld.Action;
import dev.stargenesis.sim.ArenaMatchWorld.Outcome;
import dev.stargenesis.sim.ArenaMatchWorld.Snapshot;

import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Single-worker lobby, roster and lockstep input for one published arena. */
final class ArenaRoom {
    static final long TURN_NANOS = TimeUnit.SECONDS.toNanos(30);
    private final UUID id;
    private final UUID host;
    private final DesignDependency release;
    private final ArenaRuleset ruleset;
    private final ArenaMatchWorld world;
    private final Set<String> slots;
    private final TreeMap<String, UUID> seats;
    private final TreeMap<String, Action> pending;
    private boolean started;
    private long deadline;

    ArenaRoom(UUID id, UUID host, ArenaRuleset ruleset) {
        this(id, host, ruleset, new ArenaMatchWorld(ruleset), Map.of(), Map.of(), false, 0);
    }

    private ArenaRoom(UUID id, UUID host, ArenaRuleset ruleset, ArenaMatchWorld world,
                      Map<String, UUID> seats, Map<String, Action> pending, boolean started, long deadline) {
        this.id = id;
        this.host = host;
        this.ruleset = ruleset;
        this.release = reference(ruleset);
        this.world = world;
        this.slots = new TreeSet<>(world.checkpoint().positions().keySet());
        this.seats = new TreeMap<>(seats);
        this.pending = new TreeMap<>(pending);
        this.started = started;
        this.deadline = deadline;
    }

    static ArenaRoom restore(Data data, ArenaRuleset ruleset, long now) {
        if (!data.release().equals(reference(ruleset))) throw new IllegalArgumentException("Arena release mismatch");
        ArenaRoom room = new ArenaRoom(data.id(), data.host(), ruleset, ArenaMatchWorld.restore(ruleset, data.world()),
                data.seats(), data.pending(), data.started(), now + TURN_NANOS);
        if (!room.slots.containsAll(room.seats.keySet()) || new HashSet<>(room.seats.values()).size() != room.seats.size()
                || !room.seats.keySet().containsAll(room.pending.keySet())
                || (room.started && (room.seats.size() != room.slots.size() || !room.seats.containsValue(room.host)))
                || (!room.started && (room.world.tick() != 0 || !room.pending.isEmpty()))
                || (room.finished() && !room.pending.isEmpty()) || room.pending.size() == room.slots.size())
            throw new IllegalArgumentException("Invalid saved arena roster or turn");
        room.pending.forEach(room.world::validateAction);
        return room;
    }

    ArenaRoom copy() {
        return new ArenaRoom(id, host, ruleset, world.copy(), seats, pending, started, deadline);
    }

    void join(UUID player, String unit) {
        requireLobby();
        if (!slots.contains(unit)) throw new IllegalArgumentException("Unknown slot " + unit);
        if (player.equals(seats.get(unit))) return;
        if (seats.containsKey(unit)) throw new IllegalArgumentException("Slot is already taken");
        seats.values().remove(player);
        seats.put(unit, player);
    }

    void leave(UUID player) {
        requireLobby();
        if (!seats.values().remove(player)) throw new IllegalArgumentException("You have no seat in this lobby");
    }

    void kick(UUID player, UUID target) {
        requireHost(player);
        requireLobby();
        if (host.equals(target)) throw new IllegalArgumentException("The host cannot leave before closing the lobby");
        if (!seats.values().remove(target)) throw new IllegalArgumentException("That player has no seat in this lobby");
    }

    void start(UUID player, long now) {
        requireHost(player);
        requireLobby();
        if (seats.size() != slots.size() || !seats.containsValue(host))
            throw new IllegalStateException("All slots, including one for the host, must be filled");
        Set<String> teams = new HashSet<>();
        snapshot().units().forEach(unit -> {
            if (seats.containsKey(unit.id())) teams.add(unit.teamId());
        });
        if (teams.size() < 2) throw new IllegalStateException("Each team must have at least one player");
        started = true;
        deadline = now + TURN_NANOS;
    }

    boolean submit(UUID player, long expectedTick, Action action, long now) {
        if (!started || finished()) throw new IllegalStateException("Match is not accepting actions");
        String unit = unitFor(player);
        if (unit == null) throw new SecurityException("Spectators cannot submit match actions");
        if (expectedTick != world.tick()) throw new IllegalStateException("Current match tick is " + world.tick());
        if (due(now)) throw new IllegalStateException("The current turn's deadline has elapsed");
        world.validateAction(unit, action);
        Action previous = pending.get(unit);
        if (previous != null) {
            if (previous == action) return false;
            throw new IllegalStateException("An action is already locked for your slot and tick");
        }
        pending.put(unit, action);
        if (pending.size() == seats.size()) advance(now);
        return true;
    }

    boolean due(long now) {
        return started && now - deadline >= 0 && !finished();
    }

    void expire(long now) {
        if (!due(now)) throw new IllegalStateException("Turn deadline has not elapsed");
        advance(now);
    }

    private void advance(long now) {
        Map<String, Action> actions = new TreeMap<>();
        for (String unit : slots) actions.put(unit, pending.getOrDefault(unit, Action.WAIT));
        world.stepContested(actions);
        pending.clear();
        deadline = now + TURN_NANOS;
    }

    void requireHost(UUID player) {
        if (!host.equals(player)) throw new SecurityException("Only the match host can do this");
    }

    private void requireLobby() {
        if (started) throw new IllegalStateException("The roster is locked after the match starts");
    }

    boolean includes(UUID player) { return host.equals(player) || seats.containsValue(player); }
    Set<UUID> participants() {
        Set<UUID> participants = new HashSet<>(seats.values());
        participants.add(host);
        return Set.copyOf(participants);
    }
    boolean started() { return started; }
    boolean finished() { return world.snapshot().outcome() != Outcome.ACTIVE; }
    UUID id() { return id; }
    UUID host() { return host; }
    ArenaRuleset ruleset() { return ruleset; }
    DesignDependency release() { return release; }
    Snapshot snapshot() { return world.snapshot(); }
    Set<String> slots() { return slots; }
    Set<String> occupied() { return Set.copyOf(seats.keySet()); }
    Map<String, UUID> seats() { return Map.copyOf(seats); }
    int submittedCount() { return pending.size(); }
    long tick() { return world.tick(); }

    String unitFor(UUID player) {
        return seats.entrySet().stream().filter(entry -> entry.getValue().equals(player))
                .map(Map.Entry::getKey).findFirst().orElse(null);
    }

    Data checkpoint() { return new Data(1, id, host, release, seats, pending, started, world.checkpoint()); }

    static DesignDependency reference(ArenaRuleset ruleset) {
        var metadata = ruleset.metadata();
        return new DesignDependency(metadata.id(), metadata.version(), metadata.hash());
    }

    record Data(int format, UUID id, UUID host, DesignDependency release, Map<String, UUID> seats,
                Map<String, Action> pending, boolean started, ArenaMatchWorld.Checkpoint world) {
        Data {
            if (format != 1) throw new IllegalArgumentException("Unsupported arena room format");
            Objects.requireNonNull(id, "match id");
            Objects.requireNonNull(host, "host");
            Objects.requireNonNull(release, "release");
            Objects.requireNonNull(world, "world");
            seats = Map.copyOf(seats);
            pending = Map.copyOf(pending);
        }
    }
}
