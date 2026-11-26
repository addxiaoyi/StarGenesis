package dev.stargenesis.integrations;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SettlementSagaCoordinatorTest {
    private static final UUID BUYER = new UUID(0, 1);
    private static final UUID SELLER = new UUID(0, 2);
    private static final SettlementAuthorityBinding BINDING = new SettlementAuthorityBinding(
            new SettlementAuthorityBinding.AuthorityDomain("fixture:credits", "fixture:credit-history"),
            new SettlementAuthorityBinding.AuthorityDomain("fixture:items", "fixture:item-history"));

    @Test void exchangeCompletesCreditAndItemLegsWithStableOperationKeys() {
        Fixture fixture = new Fixture();
        SettlementSaga created = fixture.coordinator.begin(request(101), 0);

        SettlementSaga paid = fixture.coordinator.advance(created.request().sagaId(), 1);
        SettlementSaga complete = fixture.coordinator.advance(created.request().sagaId(), 2);

        assertEquals(SettlementSaga.State.PAYMENT_APPLIED, paid.state());
        assertEquals(SettlementSaga.State.COMPLETED, complete.state());
        assertEquals(90, fixture.gateway.balance(BUYER, SettlementOperation.Asset.CREDITS, "credits"));
        assertEquals(10, fixture.gateway.balance(SELLER, SettlementOperation.Asset.CREDITS, "credits"));
        assertEquals(2, fixture.gateway.balance(BUYER, SettlementOperation.Asset.ITEM, "test:ore"));
        assertEquals(3, fixture.gateway.balance(SELLER, SettlementOperation.Asset.ITEM, "test:ore"));
        assertEquals(2, fixture.gateway.appliedKeys().size());
    }

    @Test void crashAfterExternalPaymentIsRecoveredByLookupWithoutDuplicateDebit() {
        Fixture fixture = new Fixture();
        SettlementSaga created = fixture.coordinator.begin(request(102), 0);
        fixture.gateway.indeterminateAfterApply = true;

        SettlementSaga uncertain = fixture.coordinator.advance(created.request().sagaId(), 1);
        assertEquals(SettlementSaga.State.PREPARED, uncertain.state());
        assertEquals(90, fixture.gateway.balance(BUYER, SettlementOperation.Asset.CREDITS, "credits"));

        SettlementSaga recovered = fixture.coordinator.advance(created.request().sagaId(), 2);
        assertEquals(SettlementSaga.State.PAYMENT_APPLIED, recovered.state());
        assertEquals(1, fixture.gateway.appliedKeys().size());
        assertEquals(SettlementSaga.State.COMPLETED,
                fixture.coordinator.advance(created.request().sagaId(), 3).state());
    }

    @Test void deliveryFailureRunsAnIdempotentCurrencyCompensation() {
        Fixture fixture = new Fixture();
        SettlementSaga created = fixture.coordinator.begin(request(103), 0);
        fixture.gateway.rejectItems = true;

        assertEquals(SettlementSaga.State.PAYMENT_APPLIED,
                fixture.coordinator.advance(created.request().sagaId(), 1).state());
        assertEquals(SettlementSaga.State.COMPENSATION_REQUIRED,
                fixture.coordinator.advance(created.request().sagaId(), 2).state());
        SettlementSaga compensated = fixture.coordinator.advance(created.request().sagaId(), 3);

        assertEquals(SettlementSaga.State.COMPENSATED, compensated.state());
        assertEquals(100, fixture.gateway.balance(BUYER, SettlementOperation.Asset.CREDITS, "credits"));
        assertEquals(0, fixture.gateway.balance(SELLER, SettlementOperation.Asset.CREDITS, "credits"));
        assertEquals(5, fixture.gateway.balance(SELLER, SettlementOperation.Asset.ITEM, "test:ore"));
        assertEquals(2, fixture.gateway.appliedKeys().size());
    }

    @Test void unknownExternalOutcomeStopsForManualReviewWithoutRetry() {
        Fixture fixture = new Fixture();
        SettlementSaga created = fixture.coordinator.begin(request(104), 0);
        fixture.gateway.unknownLookup = true;

        SettlementSaga review = fixture.coordinator.advance(created.request().sagaId(), 1);

        assertEquals(SettlementSaga.State.MANUAL_REVIEW, review.state());
        assertEquals("EXTERNAL_OUTCOME_UNKNOWN", review.reason());
        assertTrue(fixture.gateway.appliedKeys().isEmpty());
        assertEquals(review, fixture.coordinator.advance(created.request().sagaId(), 2));
    }

    @Test void expiresPermitBeforePaymentAndRejectsGatewayWithoutDurableLookup() {
        Fixture fixture = new Fixture();
        SettlementRequest expiring = new SettlementRequest(UUID.randomUUID(), BUYER, BUYER, SELLER,
                10, "test:ore", 2, 2);
        SettlementSaga created = fixture.coordinator.begin(expiring, 0);

        assertEquals(SettlementSaga.State.FAILED, fixture.coordinator.advance(created.request().sagaId(), 2).state());
        assertTrue(fixture.gateway.appliedKeys().isEmpty());
        assertThrows(IllegalStateException.class,
                () -> new SettlementSagaCoordinator(fixture.authorizer,
                        new FakeGateway(false, false), fixture.repository));
    }

    @Test void sameSagaIdCannotBeReusedForDifferentAssets() {
        Fixture fixture = new Fixture();
        SettlementRequest request = request(105);
        fixture.coordinator.begin(request, 0);
        SettlementRequest changed = new SettlementRequest(request.sagaId(), BUYER, BUYER, SELLER,
                11, request.itemId(), request.itemCount(), request.expiresAtTick());
        assertThrows(IllegalArgumentException.class, () -> fixture.coordinator.begin(changed, 0));
    }

    @Test void repositoriesWithoutExclusiveExecutionFailBeforeAuthorizationOrSideEffects() {
        Fixture fixture = new Fixture();
        var unsupported = new SettlementSagaRepository() {
            @Override public Optional<SettlementSaga> load(UUID id) { throw new AssertionError("No unlocked read"); }
            @Override public SettlementSaga create(SettlementSaga saga) { throw new AssertionError("No unlocked create"); }
            @Override public SettlementSaga replace(SettlementSaga saga, long revision) { throw new AssertionError("No unlocked replace"); }
        };
        var authorizer = new SettlementAuthorizer() {
            @Override public SettlementPermit authorize(SettlementRequest request, long tick) { throw new AssertionError("No authorization"); }
            @Override public boolean verify(SettlementRequest request, SettlementPermit permit, long tick) { throw new AssertionError("No verification"); }
        };
        var coordinator = new SettlementSagaCoordinator(authorizer, fixture.gateway, unsupported);
        assertThrows(UnsupportedOperationException.class, () -> coordinator.begin(request(109), 0));
        assertThrows(UnsupportedOperationException.class, () -> coordinator.advance(request(109).sagaId(), 1));
        assertTrue(fixture.gateway.appliedKeys().isEmpty());
    }

    @Test void expiryOnlyPreventsNewBeginsAndExistingRequestsRemainIdempotent() {
        Fixture fixture = new Fixture();
        var existing = fixture.coordinator.begin(request(110), 0);
        assertEquals(existing, fixture.coordinator.begin(request(110), 100));
        assertThrows(IllegalArgumentException.class, () -> fixture.coordinator.begin(request(111), 100));
        assertThrows(IllegalArgumentException.class, () -> fixture.coordinator.begin(request(110), -1));
        assertTrue(fixture.gateway.appliedKeys().isEmpty());
    }

    @Test void paymentPersistFailureAfterExpiryStillRecoversAndRefundsTheDebit() {
        Fixture fixture = new Fixture();
        SettlementRequest request = request(106);
        fixture.coordinator.begin(request, 0);
        fixture.repository.failNextReplace = true;
        assertThrows(IllegalStateException.class, () -> fixture.coordinator.advance(request.sagaId(), 1));

        SettlementSagaCoordinator restarted = new SettlementSagaCoordinator(
                fixture.authorizer, fixture.gateway, fixture.repository);
        assertEquals(SettlementSaga.State.PAYMENT_APPLIED,
                restarted.advance(request.sagaId(), 100).state());
        assertEquals(SettlementSaga.State.COMPENSATION_REQUIRED,
                restarted.advance(request.sagaId(), 101).state());
        assertEquals(SettlementSaga.State.COMPENSATED,
                restarted.advance(request.sagaId(), 102).state());
        assertEquals(100, fixture.gateway.balance(BUYER, SettlementOperation.Asset.CREDITS, "credits"));
        assertEquals(0, fixture.gateway.balance(SELLER, SettlementOperation.Asset.CREDITS, "credits"));
        assertEquals(0, fixture.gateway.balance(BUYER, SettlementOperation.Asset.ITEM, "test:ore"));
        assertEquals(2, fixture.gateway.appliedKeys().size());
    }

    @Test void deliveryPersistFailureAfterExpiryCompletesWithoutRefundingDeliveredItems() {
        Fixture fixture = new Fixture();
        SettlementRequest request = request(107);
        fixture.coordinator.begin(request, 0);
        fixture.coordinator.advance(request.sagaId(), 1);
        fixture.repository.failNextReplace = true;
        assertThrows(IllegalStateException.class, () -> fixture.coordinator.advance(request.sagaId(), 2));

        SettlementSagaCoordinator restarted = new SettlementSagaCoordinator(
                fixture.authorizer, fixture.gateway, fixture.repository);
        SettlementSaga recovered = restarted.advance(request.sagaId(), 100);
        assertEquals(SettlementSaga.State.COMPLETED, recovered.state());
        assertEquals(recovered, restarted.advance(request.sagaId(), 101));
        assertEquals(90, fixture.gateway.balance(BUYER, SettlementOperation.Asset.CREDITS, "credits"));
        assertEquals(10, fixture.gateway.balance(SELLER, SettlementOperation.Asset.CREDITS, "credits"));
        assertEquals(2, fixture.gateway.balance(BUYER, SettlementOperation.Asset.ITEM, "test:ore"));
        assertEquals(2, fixture.gateway.appliedKeys().size());
    }

    @Test void expiredUnknownPaymentAndDeliveryRequireReviewWithoutAdditionalSideEffects() {
        for (boolean paymentApplied : new boolean[]{false, true}) {
            Fixture fixture = new Fixture();
            SettlementRequest request = request(108);
            fixture.coordinator.begin(request, 0);
            if (paymentApplied) fixture.coordinator.advance(request.sagaId(), 1);
            fixture.gateway.indeterminateAfterApply = true;
            fixture.coordinator.advance(request.sagaId(), 2);
            fixture.gateway.unknownLookup = true;

            SettlementSaga review = fixture.coordinator.advance(request.sagaId(), 100);
            assertEquals(SettlementSaga.State.MANUAL_REVIEW, review.state());
            assertEquals("EXTERNAL_OUTCOME_UNKNOWN", review.reason());
            assertEquals(review, fixture.coordinator.advance(request.sagaId(), 101));
            assertEquals(90, fixture.gateway.balance(BUYER, SettlementOperation.Asset.CREDITS, "credits"));
            assertEquals(paymentApplied ? 2 : 1, fixture.gateway.appliedKeys().size());
        }
    }

    private SettlementRequest request(long id) {
        return new SettlementRequest(new UUID(0, id), BUYER, BUYER, SELLER, 10, "test:ore", 2, 100);
    }

    private static final class Fixture {
        private final FakeGateway gateway = new FakeGateway(true, true);
        private final MemoryRepository repository = new MemoryRepository();
        private final SettlementAuthorizer authorizer = new SettlementAuthorizer() {
            @Override public SettlementPermit authorize(SettlementRequest request, long logicalTick) {
                return new SettlementPermit("fixture.policy", "fixture:" + request.sagaId(), request.actor(),
                        request.buyer(), request.seller(), SettlementRequestCodec.hash(request), logicalTick,
                        request.expiresAtTick());
            }

            @Override public boolean verify(SettlementRequest request, SettlementPermit permit, long logicalTick) {
                return permit.actor().equals(request.actor()) && permit.buyer().equals(request.buyer())
                        && permit.seller().equals(request.seller())
                        && permit.requestHash().equals(SettlementRequestCodec.hash(request))
                        && logicalTick >= permit.issuedAtTick() && logicalTick < permit.expiresAtTick();
            }
        };
        private final SettlementSagaCoordinator coordinator = new SettlementSagaCoordinator(authorizer, gateway, repository);

        private Fixture() {
            gateway.setBalance(BUYER, SettlementOperation.Asset.CREDITS, "credits", 100);
            gateway.setBalance(SELLER, SettlementOperation.Asset.CREDITS, "credits", 0);
            gateway.setBalance(BUYER, SettlementOperation.Asset.ITEM, "test:ore", 0);
            gateway.setBalance(SELLER, SettlementOperation.Asset.ITEM, "test:ore", 5);
        }
    }

    private static final class MemoryRepository implements SettlementSagaRepository {
        private final Map<UUID, SettlementSaga> sagas = new HashMap<>();
        private final Map<UUID, SettlementAuthorityBinding> bindings = new HashMap<>();
        private boolean failNextReplace;
        @Override public synchronized <T> T withExclusiveSaga(UUID sagaId, java.util.function.Supplier<T> action) {
            return action.get();
        }
        @Override public synchronized Optional<SettlementSaga> load(UUID id) { return Optional.ofNullable(sagas.get(id)); }
        @Override public synchronized Optional<SettlementAuthorityBinding> authorityBinding(UUID id) { return Optional.ofNullable(bindings.get(id)); }
        @Override public synchronized SettlementSaga createBound(SettlementSaga saga, SettlementAuthorityBinding binding) {
            java.util.Objects.requireNonNull(binding);
            create(saga);
            bindings.put(saga.request().sagaId(), binding);
            return saga;
        }
        @Override public synchronized SettlementSaga create(SettlementSaga saga) {
            saga.requireInitial();
            if (sagas.putIfAbsent(saga.request().sagaId(), saga) != null)
                throw new IllegalStateException("Saga already exists");
            return saga;
        }
        @Override public synchronized SettlementSaga replace(SettlementSaga updated, long expectedRevision) {
            if (failNextReplace) {
                failNextReplace = false;
                throw new IllegalStateException("Simulated persistence failure after external side effect");
            }
            SettlementSaga current = sagas.get(updated.request().sagaId());
            if (current == null || current.revision() != expectedRevision)
                throw new IllegalStateException("Saga revision conflict");
            updated.requireSuccessorOf(current);
            sagas.put(updated.request().sagaId(), updated);
            return updated;
        }
    }

    private static final class FakeGateway implements IdempotentSettlementPort {
        private final Map<String, SettlementOperation> applied = new HashMap<>();
        private final Map<String, Long> balances = new HashMap<>();
        private final boolean durableKeys;
        private final boolean lookup;
        private boolean indeterminateAfterApply;
        private boolean rejectItems;
        private boolean unknownLookup;

        private FakeGateway(boolean durableKeys, boolean lookup) { this.durableKeys = durableKeys; this.lookup = lookup; }

        @Override public Capability capability() { return new Capability(durableKeys, lookup, "fixture:durable-ledger"); }
        @Override public RecoveryDescriptor recoveryDescriptor() { return new RecoveryDescriptor(capability(), BINDING); }

        @Override public Observation inspect(SettlementOperation operation) {
            if (unknownLookup) return Observation.UNKNOWN;
            SettlementOperation prior = applied.get(operation.idempotencyKey());
            if (prior == null) return Observation.NOT_APPLIED;
            if (!prior.equals(operation)) throw new IllegalArgumentException("Operation key collision");
            return Observation.APPLIED;
        }

        @Override public Outcome apply(SettlementOperation operation) {
            if (rejectItems && operation.asset() == SettlementOperation.Asset.ITEM) return Outcome.NO_EFFECT;
            SettlementOperation prior = applied.get(operation.idempotencyKey());
            if (prior != null) {
                if (!prior.equals(operation)) throw new IllegalArgumentException("Operation key collision");
                return Outcome.APPLIED;
            }
            String source = account(operation.source(), operation.asset(), operation.assetId());
            String target = account(operation.target(), operation.asset(), operation.assetId());
            long available = balances.getOrDefault(source, 0L);
            if (available < operation.amount()) return Outcome.NO_EFFECT;
            balances.put(source, available - operation.amount());
            balances.merge(target, operation.amount(), Math::addExact);
            applied.put(operation.idempotencyKey(), operation);
            if (indeterminateAfterApply) {
                indeterminateAfterApply = false;
                return Outcome.INDETERMINATE;
            }
            return Outcome.APPLIED;
        }

        private void setBalance(UUID player, SettlementOperation.Asset asset, String assetId, long amount) {
            balances.put(account(player, asset, assetId), amount);
        }

        private long balance(UUID player, SettlementOperation.Asset asset, String assetId) {
            return balances.getOrDefault(account(player, asset, assetId), 0L);
        }

        private java.util.Set<String> appliedKeys() { return java.util.Set.copyOf(applied.keySet()); }

        private String account(UUID player, SettlementOperation.Asset asset, String assetId) {
            return player + ":" + asset + ":" + assetId;
        }
    }
}
