package dev.stargenesis.sim;

import dev.stargenesis.api.sim.EvidenceId;

import java.util.List;
import java.util.Objects;

/** In-memory recovery value for a factory, including its ledger deduplication history. */
public record FactoryCheckpoint(FactorySettings settings, long tick, List<FactoryWorld.Order> orders,
                                ConservingResourceLedger.Checkpoint ledger,
                                List<FactoryWorld.BidAction> bidActions,
                                List<FactoryWorld.StationJob> stationJobs,
                                List<FactoryWorld.ProductionAction> productionActions,
                                boolean productionMaterialsSeeded,
                                int formatVersion, int nextAutomaticSequence, String stateHash) {
    public FactoryCheckpoint(FactorySettings settings, long tick, List<FactoryWorld.Order> orders,
                             ConservingResourceLedger.Checkpoint ledger,
                             List<FactoryWorld.BidAction> bidActions,
                             int nextAutomaticSequence, String stateHash) {
        this(settings, tick, orders, ledger, bidActions, List.of(), List.of(), true, 2,
                nextAutomaticSequence, stateHash);
    }

    public FactoryCheckpoint {
        Objects.requireNonNull(settings, "settings");
        if (tick < 0 || tick > settings.maxSteps() || (formatVersion != 1 && formatVersion != 2)
                || nextAutomaticSequence < FactoryWorld.INTERNAL_SEQUENCE_BASE
                || nextAutomaticSequence > Integer.MAX_VALUE
                || stateHash == null || !stateHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid factory checkpoint metadata");
        }
        orders = List.copyOf(Objects.requireNonNull(orders, "orders"));
        Objects.requireNonNull(ledger, "ledger");
        bidActions = List.copyOf(Objects.requireNonNull(bidActions, "bidActions"));
        stationJobs = List.copyOf(Objects.requireNonNull(stationJobs, "stationJobs"));
        productionActions = List.copyOf(Objects.requireNonNull(productionActions, "productionActions"));
        if (stationJobs.size() > FactorySettings.MEMBER_COUNT
                || productionActions.size() > FactoryWorld.MAX_PRODUCTION_ACTIONS
                || stationJobs.stream().map(FactoryWorld.StationJob::owner).distinct().count()
                != stationJobs.size()
                || productionActions.stream().map(FactoryWorld.ProductionAction::evidenceId).distinct().count()
                != productionActions.size()) {
            throw new IllegalArgumentException("Invalid factory production history");
        }
        if (formatVersion == 1 && (productionMaterialsSeeded || !stationJobs.isEmpty()
                || !productionActions.isEmpty())) {
            throw new IllegalArgumentException("Invalid legacy factory checkpoint metadata");
        }
        if (bidActions.size() > FactoryWorld.MAX_BID_ACTIONS
                || bidActions.stream().map(FactoryWorld.BidAction::evidenceId).distinct().count() != bidActions.size()) {
            throw new IllegalArgumentException("Invalid factory bid history");
        }
        for (FactoryWorld.BidAction action : bidActions) {
            EvidenceId evidenceId = action.evidenceId();
            if (!settings.worldId().equals(evidenceId.world()) || evidenceId.tick() > tick) {
                throw new IllegalArgumentException("Factory bid history belongs to another world or future tick");
            }
        }
        for (FactoryWorld.ProductionAction action : productionActions) {
            EvidenceId evidenceId = action.evidenceId();
            if (!settings.worldId().equals(evidenceId.world()) || evidenceId.tick() > tick
                    || bidActions.stream().anyMatch(bid -> bid.evidenceId().equals(evidenceId))) {
                throw new IllegalArgumentException("Factory production history has foreign, future or reused evidence");
            }
        }
    }
}
