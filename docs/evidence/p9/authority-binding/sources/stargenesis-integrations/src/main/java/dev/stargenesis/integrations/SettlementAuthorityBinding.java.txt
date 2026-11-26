package dev.stargenesis.integrations;

import java.util.Objects;

/**
 * Declared asset authorities and idempotency-history domains for one currency-for-item exchange.
 * These exact identifiers are not authenticated proof, provider operation leases or persisted history.
 * A durable repository must retain the original binding atomically with the saga; an adapter declaration
 * alone does not prove that either authority still owns the original assets or operation records.
 */
public record SettlementAuthorityBinding(AuthorityDomain credits, AuthorityDomain item) {
    public SettlementAuthorityBinding {
        Objects.requireNonNull(credits, "credits");
        Objects.requireNonNull(item, "item");
    }

    /** Stable authority and history identities, not deployment versions, audit evidence or instance IDs. */
    public record AuthorityDomain(String assetAuthorityId, String idempotencyHistoryId) {
        public AuthorityDomain {
            assetAuthorityId = requireId(assetAuthorityId, "assetAuthorityId");
            idempotencyHistoryId = requireId(idempotencyHistoryId, "idempotencyHistoryId");
        }

        private static String requireId(String value, String name) {
            Objects.requireNonNull(value, name);
            if (!value.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}"))
                throw new IllegalArgumentException(name + " must be an exact bounded canonical ASCII identifier");
            return value;
        }
    }
}
