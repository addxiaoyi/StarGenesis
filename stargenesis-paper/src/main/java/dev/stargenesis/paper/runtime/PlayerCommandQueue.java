package dev.stargenesis.paper.runtime;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** All service state and cleanup stay on one worker; connection events never wait for its disk calls. */
public final class PlayerCommandQueue implements AutoCloseable {
    private final PersistentWorker worker;
    private final Consumer<UUID> retire;
    private final Consumer<Set<UUID>> retainOnline;
    private final AtomicBoolean sweepQueued = new AtomicBoolean();
    private volatile Map<UUID, Connection> connections = Map.of();
    // Worker-owned snapshot; neither snapshot retains a history of disconnected identities.
    private Map<UUID, Connection> applied = Map.of();
    private boolean closed;

    public PlayerCommandQueue(PersistentWorker worker, Consumer<UUID> retire, Consumer<Set<UUID>> retainOnline) {
        this.worker = Objects.requireNonNull(worker);
        this.retire = Objects.requireNonNull(retire);
        this.retainOnline = Objects.requireNonNull(retainOnline);
    }

    public synchronized Connection connected(UUID player) {
        if (closed) throw new RejectedExecutionException("Player command admission has stopped");
        Connection connection = new Connection(Objects.requireNonNull(player));
        var updated = new HashMap<>(connections);
        Connection previous = updated.put(player, connection);
        connections = Map.copyOf(updated);
        if (previous != null) requestSweep();
        return connection;
    }

    public Connection connection(UUID player) { return connections.get(player); }

    public boolean isCurrent(Connection connection) {
        return connection != null && connections.get(connection.player()) == connection;
    }

    private boolean areCurrent(Connection first, Connection second) {
        Map<UUID, Connection> current = connections;
        return first != null && second != null
                && current.get(first.player()) == first && current.get(second.player()) == second;
    }

    public synchronized void disconnected(UUID player) {
        var updated = new HashMap<>(connections);
        updated.remove(player);
        connections = Map.copyOf(updated);
        requestSweep();
    }

    public void execute(Connection connection, Runnable command) { execute(connection, connection, command); }

    public void execute(Connection first, Connection second, Runnable command) {
        Objects.requireNonNull(command);
        if (!areCurrent(first, second)) throw new RejectedExecutionException("Player connection has retired");
        worker.execute(() -> {
            synchronizeConnections();
            try {
                // A queued command belongs to the captured connection, even if this UUID reconnects.
                if (areCurrent(first, second)) command.run();
            } finally { synchronizeConnections(); }
        });
    }

    private void requestSweep() {
        if (!sweepQueued.compareAndSet(false, true)) return;
        try {
            worker.execute(() -> {
                sweepQueued.set(false);
                synchronizeConnections();
            });
        } catch (RejectedExecutionException fullOrStopped) {
            sweepQueued.set(false);
            // A full queue already contains wrapped commands which will apply the latest snapshot.
            // On shutdown, the last running command applies it in its finally block.
        }
    }

    private void synchronizeConnections() {
        Map<UUID, Connection> current = connections;
        applied.forEach((player, previous) -> {
            if (current.get(player) != previous) retire.accept(player);
        });
        // Operations such as start can select a match for other players while they disconnect.
        retainOnline.accept(current.keySet());
        applied = current;
    }

    @Override public synchronized void close() {
        closed = true;
        connections = Map.of();
        requestSweep();
    }

    public static final class Connection {
        private final UUID player;
        private Connection(UUID player) { this.player = player; }
        public UUID player() { return player; }
    }
}
