package dev.stargenesis.integrations;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Idempotent transaction identity and its finite ordered transfers. */
public record SettlementPlan(UUID id, UUID initiator, String providerId, List<SettlementTransfer> transfers) {
    public static final int MAX_TRANSFERS = 16;

    public SettlementPlan {
        Objects.requireNonNull(id, "saga ID");
        Objects.requireNonNull(initiator, "initiator");
        if (providerId == null || !providerId.matches("[a-z0-9][a-z0-9._-]{0,63}"))
            throw new IllegalArgumentException("Invalid settlement provider ID");
        transfers = List.copyOf(transfers);
        if (transfers.isEmpty() || transfers.size() > MAX_TRANSFERS || transfers.stream().anyMatch(Objects::isNull))
            throw new IllegalArgumentException("Settlement plan must contain 1..16 transfers");
    }

    public String applyKey(int transferIndex) {
        Objects.checkIndex(transferIndex, transfers.size());
        return providerId + ":" + id + ":apply:" + transferIndex;
    }

    public String compensationKey(int transferIndex) {
        Objects.checkIndex(transferIndex, transfers.size());
        return providerId + ":" + id + ":compensate:" + transferIndex;
    }
}
