package dev.stargenesis.sim;

import dev.stargenesis.api.sim.AgentId;
import java.util.List;
import java.util.Objects;

/** Complete state plus deduplication and replay history. Restoring validates every invariant. */
public record WorldSnapshot(String engineVersion, int schemaVersion, String worldId, long seed,
                            SandboxConfig config, long tick, List<Terrain> terrain, GridPoint base,
                            List<NodeState> nodes, List<AgentState> agents,
                            ConservingResourceLedger.Checkpoint ledger, String ledgerHistoryHash,
                            String eventHistoryHash, boolean terminal, String reason,
                            List<ReplayLog.Frame> history, String stateHash) {
    public WorldSnapshot {
        Objects.requireNonNull(config); Objects.requireNonNull(ledger);
        terrain = List.copyOf(terrain); nodes = List.copyOf(nodes); agents = List.copyOf(agents); history = List.copyOf(history);
        if (schemaVersion != 1 || tick < 0 || tick > 6000 || history.size() != tick || terrain.size() != config.width()*config.height()
                || nodes.size() != 2 || agents.size() != config.agentCount() || reason == null || reason.length() > 512
                || !validHash(stateHash) || !validHash(ledgerHistoryHash) || !validHash(eventHistoryHash))
            throw new IllegalArgumentException("Invalid snapshot envelope");
    }
    private static boolean validHash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }
    public record NodeState(String id, GridPoint position, ResourceType resource) {
        public NodeState { Objects.requireNonNull(id); Objects.requireNonNull(position); Objects.requireNonNull(resource); }
    }
    public record AgentState(AgentId id, GridPoint position, int targetNode, int preferredResource,
                             boolean paused, long lastSequence, String status) {
        public AgentState {
            Objects.requireNonNull(id); Objects.requireNonNull(position); Objects.requireNonNull(status);
            if (targetNode < -1 || targetNode > 1 || preferredResource < -1 || preferredResource > 1 || lastSequence < -1 || status.length() > 64)
                throw new IllegalArgumentException("Invalid agent snapshot");
        }
    }
}
