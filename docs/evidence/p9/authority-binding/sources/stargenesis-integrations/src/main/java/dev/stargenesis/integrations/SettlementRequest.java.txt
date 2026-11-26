package dev.stargenesis.integrations;

import java.util.Objects;
import java.util.UUID;

/** Explicit player trade request. Amounts are integer units and never inferred from a simulation score. */
public record SettlementRequest(UUID sagaId, UUID actor, UUID buyer, UUID seller,
                                long credits, String itemId, long itemCount, long expiresAtTick) {
    public SettlementRequest {
        Objects.requireNonNull(sagaId, "sagaId");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(buyer, "buyer");
        Objects.requireNonNull(seller, "seller");
        if (!actor.equals(buyer) || buyer.equals(seller) || credits < 1 || credits > 1_000_000_000L
                || itemId == null || !itemId.matches("[a-z][a-z0-9_-]{0,31}:[a-z0-9][a-z0-9_./-]{0,94}")
                || itemId.contains("..") || itemId.endsWith("/") || itemCount < 1 || itemCount > 64
                || expiresAtTick < 1)
            throw new IllegalArgumentException("Settlement request is outside its authorized bounds");
    }
}
