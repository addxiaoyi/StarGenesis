package dev.stargenesis.integrations;

import java.util.Objects;
import java.util.UUID;

/** Trusted adapter evidence for a specific request and both participants. */
public record SettlementPermit(String policyId, String evidenceId, UUID actor, UUID buyer, UUID seller,
                               String requestHash, long issuedAtTick, long expiresAtTick) {
    public SettlementPermit {
        policyId = InstalledPlugin.requireText(policyId, "policyId");
        evidenceId = InstalledPlugin.requireText(evidenceId, "evidenceId");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(buyer, "buyer");
        Objects.requireNonNull(seller, "seller");
        if (requestHash == null || !requestHash.matches("[0-9a-f]{64}") || issuedAtTick < 0
                || expiresAtTick <= issuedAtTick || !actor.equals(buyer) || buyer.equals(seller))
            throw new IllegalArgumentException("Invalid settlement authorization evidence");
    }
}
