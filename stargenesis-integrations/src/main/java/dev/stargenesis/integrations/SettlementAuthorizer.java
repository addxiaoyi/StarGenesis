package dev.stargenesis.integrations;

/** Trusted deployment/land/inventory permission adapter. This interface grants no default permit. */
public interface SettlementAuthorizer {
    SettlementPermit authorize(SettlementRequest request, long logicalTick);

    boolean verify(SettlementRequest request, SettlementPermit permit, long logicalTick);
}
