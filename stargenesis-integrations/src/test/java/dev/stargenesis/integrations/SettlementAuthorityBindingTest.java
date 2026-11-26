package dev.stargenesis.integrations;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Authority declarations must stay exact and cannot silently upgrade legacy adapters or repositories. */
class SettlementAuthorityBindingTest {
    private static final SettlementAuthorityBinding.AuthorityDomain CREDITS = domain("wallet:primary", "wallet:history-v1");
    private static final SettlementAuthorityBinding.AuthorityDomain ITEM = domain("inventory:primary", "inventory:history-v1");

    @Test void exactAuthorityAndHistoryIdentifiersArePreservedWithoutNormalization() {
        var value = domain("URN:Wallet/Primary-v1.2", "History:Ledger_1/Receipts");
        assertEquals("URN:Wallet/Primary-v1.2", value.assetAuthorityId());
        assertEquals("History:Ledger_1/Receipts", value.idempotencyHistoryId());
        assertEquals(value, domain(value.assetAuthorityId(), value.idempotencyHistoryId()));
        assertNotEquals(value, domain("urn:Wallet/Primary-v1.2", value.idempotencyHistoryId()));
    }

    @Test void creditsAndItemsRemainDistinctBindingPositions() {
        var binding = new SettlementAuthorityBinding(CREDITS, ITEM);
        assertEquals(CREDITS, binding.credits());
        assertEquals(ITEM, binding.item());
        assertNotEquals(binding, new SettlementAuthorityBinding(ITEM, CREDITS));
    }

    @Test void changingEitherAuthorityOrHistoryChangesTheBinding() {
        var original = new SettlementAuthorityBinding(CREDITS, ITEM);
        assertNotEquals(original, new SettlementAuthorityBinding(domain("wallet:replacement", CREDITS.idempotencyHistoryId()), ITEM));
        assertNotEquals(original, new SettlementAuthorityBinding(domain(CREDITS.assetAuthorityId(), "wallet:new-history"), ITEM));
        assertNotEquals(original, new SettlementAuthorityBinding(CREDITS, domain("inventory:replacement", ITEM.idempotencyHistoryId())));
        assertNotEquals(original, new SettlementAuthorityBinding(CREDITS, domain(ITEM.assetAuthorityId(), "inventory:new-history")));
    }

    @Test void oneAnd128AsciiCharactersAreAcceptedAtBothIdentifierBoundaries() {
        String maximum = "A" + "a._:/-09".repeat(16).substring(0, 127);
        assertEquals(128, maximum.length());
        assertEquals("0", domain("0", "Z").assetAuthorityId());
        assertEquals("Z", domain("0", "Z").idempotencyHistoryId());
        assertEquals(maximum, domain(maximum, maximum).assetAuthorityId());
        assertEquals(maximum, domain(maximum, maximum).idempotencyHistoryId());
    }

    @Test void invalidOrOversizedIdentifiersAreRejectedRatherThanTrimmedOrRewritten() {
        for (String invalid : List.of("", " ", " leading", "trailing ", "contains space", ":prefix", "/prefix",
                ".prefix", "-prefix", "_prefix", "line\nfeed", "tab\tvalue", "wallet?query", "wallet#fragment",
                "wallet\\path", "quoted\"value", "NUL" + (char) 0 + "value", "naïve", "钱包", "a".repeat(129))) {
            assertThrows(IllegalArgumentException.class, () -> domain(invalid, "history"), invalid);
            assertThrows(IllegalArgumentException.class, () -> domain("authority", invalid), invalid);
        }
    }

    @Test void nullIdentifiersAndAssetDomainsAreRejected() {
        assertThrows(NullPointerException.class, () -> domain(null, "history"));
        assertThrows(NullPointerException.class, () -> domain("authority", null));
        assertThrows(NullPointerException.class, () -> new SettlementAuthorityBinding(null, ITEM));
        assertThrows(NullPointerException.class, () -> new SettlementAuthorityBinding(CREDITS, null));
    }

    @Test void recoveryDescriptorRetainsDeclarationsWithoutGrantingRecoverySupport() {
        var capability = new IdempotentSettlementPort.Capability(false, false, "test:unavailable");
        var binding = new SettlementAuthorityBinding(CREDITS, ITEM);
        var descriptor = new IdempotentSettlementPort.RecoveryDescriptor(capability, binding);
        assertSame(capability, descriptor.capability());
        assertSame(binding, descriptor.authorityBinding());
        assertFalse(descriptor.capability().supportsRecovery());
        assertEquals(descriptor, new IdempotentSettlementPort.RecoveryDescriptor(capability, binding));
    }

    @Test void recoveryDescriptorRequiresBothCapabilityAndAuthorityDeclarations() {
        var capability = new IdempotentSettlementPort.Capability(true, true, "test:audited");
        var binding = new SettlementAuthorityBinding(CREDITS, ITEM);
        assertThrows(NullPointerException.class, () -> new IdempotentSettlementPort.RecoveryDescriptor(null, binding));
        assertThrows(NullPointerException.class, () -> new IdempotentSettlementPort.RecoveryDescriptor(capability, null));
    }

    @Test void legacyProviderCannotInferAuthorityFromCapabilityOrInvokeEffects() {
        var legacy = new IdempotentSettlementPort() {
            @Override public Capability capability() { throw new AssertionError("No inferred continuity declaration"); }
            @Override public Observation inspect(SettlementOperation operation) { throw new AssertionError("No external query"); }
            @Override public Outcome apply(SettlementOperation operation) { throw new AssertionError("No external effect"); }
        };
        assertThrows(UnsupportedOperationException.class, legacy::recoveryDescriptor);
    }

    @Test void legacyRepositoryCannotFallBackToAnUnboundReadOrCreate() {
        var legacy = new SettlementSagaRepository() {
            @Override public Optional<SettlementSaga> load(UUID id) { throw new AssertionError("No inferred binding read"); }
            @Override public SettlementSaga create(SettlementSaga initial) { throw new AssertionError("No unbound creation"); }
            @Override public SettlementSaga replace(SettlementSaga updated, long revision) { throw new AssertionError("No state replacement"); }
        };
        var initial = initial();
        assertThrows(UnsupportedOperationException.class, () -> legacy.authorityBinding(initial.request().sagaId()));
        assertThrows(UnsupportedOperationException.class,
                () -> legacy.createBound(initial, new SettlementAuthorityBinding(CREDITS, ITEM)));
    }

    private static SettlementAuthorityBinding.AuthorityDomain domain(String authority, String history) {
        return new SettlementAuthorityBinding.AuthorityDomain(authority, history);
    }

    private static SettlementSaga initial() {
        UUID buyer = new UUID(0, 1), seller = new UUID(0, 2);
        var request = new SettlementRequest(new UUID(0, 3), buyer, buyer, seller, 10, "test:ore", 1, 100);
        var permit = new SettlementPermit("test:policy", "test:permit", buyer, buyer, seller,
                SettlementRequestCodec.hash(request), 0, 100);
        return new SettlementSaga(1, request, permit, SettlementSaga.State.PREPARED, 0, "");
    }
}
