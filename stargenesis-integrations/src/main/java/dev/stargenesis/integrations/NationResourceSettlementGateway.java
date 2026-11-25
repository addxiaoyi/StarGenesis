package dev.stargenesis.integrations;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Fail-closed bridge contract for a single authoritative nation stockpile ledger.
 * This is not a registered StarCore integration: a pinned upstream adapter must supply
 * durable, atomic, parameter-bound idempotency and the query guarantees below.
 */
public final class NationResourceSettlementGateway implements SettlementGateway {
    public static final String PROVIDER_TYPE = "sc.nr.v1";
    private static final String CATEGORY = "OTHER";

    private final NationResourceProvider provider;
    private final String authorityDomainId;
    private final Set<String> allowedResources;
    private final String providerId;

    public NationResourceSettlementGateway(NationResourceProvider provider,
                                           String authorityDomainId,
                                           Set<String> allowedResources) {
        this.provider = Objects.requireNonNull(provider, "provider");
        this.authorityDomainId = requireText(authorityDomainId, "authority domain");
        this.allowedResources = Set.copyOf(allowedResources);
        if (this.allowedResources.isEmpty() || this.allowedResources.size() > 64
                || this.allowedResources.stream().anyMatch(resource -> !validResource(resource))) {
            throw new IllegalArgumentException("Allowed resources must contain 1..64 valid IDs");
        }
        if (!domainMatches()) {
            throw new IllegalArgumentException("Provider authority domain does not match its pin");
        }
        this.providerId = PROVIDER_TYPE + "." + domainDigest(authorityDomainId);
    }

    /** The full domain digest is part of the provider ID persisted in every saga plan. */
    @Override public String providerId() { return providerId; }

    @Override
    public Observation observe(String key, SettlementTransfer transfer, Operation operation) throws Exception {
        return observeRequest(request(key, transfer, operation));
    }

    @Override
    public Result transfer(String key, SettlementTransfer transfer) throws Exception {
        return execute(request(key, transfer, Operation.APPLY));
    }

    @Override
    public Result compensate(String key, SettlementTransfer transfer) throws Exception {
        return execute(request(key, transfer, Operation.COMPENSATE));
    }

    private Result execute(Request request) throws Exception {
        // Also protect direct callers: unknown or conflicting receipts never cause a write.
        Observation before = observeRequest(request);
        if (before == Observation.APPLIED) return Result.APPLIED;
        if (before != Observation.NOT_APPLIED || !domainMatches()) return Result.UNKNOWN;
        NationResourceResult result = provider.transfer(request);
        if (!domainMatches() || result == null) return Result.UNKNOWN;
        return switch (result) {
            // A transport success alone is not durable evidence of this exact request.
            case APPLIED, ALREADY_APPLIED -> observeRequest(request) == Observation.APPLIED
                    ? Result.APPLIED : Result.UNKNOWN;
            case REJECTED, INSUFFICIENT_RESOURCES -> Result.REJECTED;
            case UNAVAILABLE -> Result.UNKNOWN;
        };
    }

    private Observation observeRequest(Request expected) throws Exception {
        if (!domainMatches()) return Observation.UNKNOWN;
        NationResourceObservation observation = provider.query(expected.idempotencyKey());
        if (!domainMatches() || observation == null) return Observation.UNKNOWN;
        if (observation.status() != Observation.APPLIED) return observation.status();
        return matches(observation.receipt(), expected) ? Observation.APPLIED : Observation.UNKNOWN;
    }

    private Request request(String key, SettlementTransfer transfer, Operation operation) {
        Objects.requireNonNull(transfer, "transfer");
        boolean inverse = Objects.requireNonNull(operation, "operation") == Operation.COMPENSATE;
        UUID source = nationId(inverse ? transfer.targetAccount() : transfer.sourceAccount());
        UUID target = nationId(inverse ? transfer.sourceAccount() : transfer.targetAccount());
        if (!allowedResources.contains(transfer.assetId()))
            throw new IllegalArgumentException("Resource is not allow-listed");
        return new Request(key, source, target, transfer.assetId(), transfer.amount(),
                CATEGORY, description(key), authorityDomainId);
    }

    private static boolean matches(NationResourceReceipt receipt, Request expected) {
        return receipt != null && receipt.status() == NationResourceResult.APPLIED
                && Objects.equals(receipt.idempotencyKey(), expected.idempotencyKey())
                && Objects.equals(receipt.sourceNation(), expected.sourceNation())
                && Objects.equals(receipt.targetNation(), expected.targetNation())
                && Objects.equals(receipt.resourceId(), expected.resourceId())
                && receipt.amount() == expected.amount()
                && Objects.equals(receipt.category(), expected.category())
                && Objects.equals(receipt.description(), expected.description())
                && Objects.equals(receipt.authorityDomainId(), expected.authorityDomainId());
    }

    private boolean domainMatches() { return authorityDomainId.equals(provider.authorityDomainId()); }

    private static UUID nationId(String account) {
        try {
            UUID id = UUID.fromString(account);
            if (!id.toString().equalsIgnoreCase(account)) throw new IllegalArgumentException("Non-canonical nation ID");
            return id;
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Settlement account must be a canonical nation UUID", failure);
        }
    }

    private static String description(String key) {
        return "StarGenesis settlement " + requireText(key, "settlement idempotency key");
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank() || value.length() > 128
                || value.chars().anyMatch(Character::isISOControl)
                || !StandardCharsets.UTF_8.newEncoder().canEncode(value)) {
            throw new IllegalArgumentException("Invalid " + label);
        }
        return value;
    }

    private static boolean validResource(String value) {
        return value != null && value.matches("[a-z0-9][a-z0-9._:/-]{0,127}");
    }

    private static String domainDigest(String domain) {
        try {
            // Full SHA-256 in lowercase base32 fits SettlementPlan's 64-character ID bound.
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(domain.getBytes(StandardCharsets.UTF_8));
            String alphabet = "abcdefghijklmnopqrstuvwxyz234567";
            StringBuilder value = new StringBuilder(52);
            int bits = 0;
            int buffer = 0;
            for (byte octet : hash) {
                buffer = (buffer << 8) | (octet & 0xff);
                bits += 8;
                while (bits >= 5) {
                    bits -= 5;
                    value.append(alphabet.charAt((buffer >>> bits) & 31));
                }
            }
            if (bits > 0) value.append(alphabet.charAt((buffer << (5 - bits)) & 31));
            return value.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }

    /**
     * Implement only against a pinned and reviewed upstream ledger on its owner thread.
     * A key is durable and bound to the entire Request; another request must never reuse it.
     * Transfer and receipt commit atomically and queries are authoritative/linearizable.
     * NOT_APPLIED proves no effect occurred AND no prior attempt can complete later;
     * absent receipts, unavailable storage and pending work alone prove only UNKNOWN.
     * Requests must recheck authorityDomainId atomically with the write. Caller authorization
     * is a separate required business boundary; this SPI does not authorize an initiator.
     */
    public interface NationResourceProvider {
        String authorityDomainId();
        NationResourceResult transfer(Request request) throws Exception;
        NationResourceObservation query(String idempotencyKey) throws Exception;
    }

    public enum NationResourceResult {
        APPLIED, ALREADY_APPLIED,
        /** Both rejection statuses guarantee no effect occurred or can complete later. */
        REJECTED, INSUFFICIENT_RESOURCES,
        UNAVAILABLE
    }

    /** An explicit proof state; an absent receipt cannot be passed off as APPLIED. */
    public record NationResourceObservation(Observation status, NationResourceReceipt receipt) {
        public NationResourceObservation {
            Objects.requireNonNull(status, "observation status");
            if ((status == Observation.APPLIED) != (receipt != null))
                throw new IllegalArgumentException("Only an applied observation carries a receipt");
        }
    }

    public record Request(String idempotencyKey, UUID sourceNation, UUID targetNation,
                          String resourceId, long amount, String category,
                          String description, String authorityDomainId) {
        public Request {
            requireText(idempotencyKey, "settlement idempotency key");
            Objects.requireNonNull(sourceNation, "source nation");
            Objects.requireNonNull(targetNation, "target nation");
            requireText(authorityDomainId, "authority domain");
            if (sourceNation.equals(targetNation) || amount < 1 || amount > SettlementTransfer.MAX_AMOUNT
                    || !validResource(resourceId) || !CATEGORY.equals(category)
                    || !NationResourceSettlementGateway.description(idempotencyKey).equals(description)) {
                throw new IllegalArgumentException("Invalid nation resource request");
            }
        }
    }

    /** Untrusted boundary data: malformed or mismatched fields are observed as UNKNOWN. */
    public record NationResourceReceipt(String idempotencyKey, UUID sourceNation, UUID targetNation,
                                        String resourceId, long amount, NationResourceResult status,
                                        String category, String description, String authorityDomainId) { }
}
