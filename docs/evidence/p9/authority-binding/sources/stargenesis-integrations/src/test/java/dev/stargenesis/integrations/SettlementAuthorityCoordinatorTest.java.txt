package dev.stargenesis.integrations;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static dev.stargenesis.integrations.IdempotentSettlementPort.Observation.*;
import static dev.stargenesis.integrations.SettlementOperation.Phase.*;
import static org.junit.jupiter.api.Assertions.*;

/** Continuity checks use persisted domains rather than a reconstructed coordinator's current provider. */
class SettlementAuthorityCoordinatorTest {
    private static final SettlementAuthorityBinding ORIGINAL = binding("credits:primary", "credits:history",
            "items:primary", "items:history");

    @Test void newSagaAtomicallyStoresTheOriginalAuthorityAndHistoryDomains() {
        var fixture = new Fixture();
        var prepared = fixture.coordinator().begin(fixture.request, 0);
        assertEquals(prepared, fixture.repository.saga);
        assertEquals(ORIGINAL, fixture.repository.binding);
        assertEquals(1, fixture.repository.boundCreates);
        assertEquals(0, fixture.port.reads + fixture.port.effects);
    }

    @Test void reconstructedCoordinatorRejectsAnyChangedDomainBeforeExternalQueriesOrEffects() {
        for (var changed : changedBindings()) for (var phase : SettlementOperation.Phase.values()) {
            var fixture = new Fixture();
            var before = fixture.prepare(phase);
            fixture.port.binding = changed;
            int reads = fixture.port.reads, effects = fixture.port.effects;
            var reconstructed = fixture.coordinator();
            assertThrows(IllegalStateException.class, () -> reconstructed.advance(fixture.request.sagaId(), 4));
            assertEquals(before, fixture.repository.saga);
            assertEquals(ORIGINAL, fixture.repository.binding);
            assertEquals(reads, fixture.port.reads);
            assertEquals(effects, fixture.port.effects);
        }
    }

    @Test void newEvidenceForTheSamePersistedDomainsRequiresANewAutomaticCoordinator() {
        var fixture = new Fixture();
        var original = fixture.coordinator();
        var before = original.begin(fixture.request, 0);
        fixture.port.evidence = "audit:replacement";
        assertThrows(IllegalStateException.class, () -> original.advance(fixture.request.sagaId(), 1));
        assertEquals(before, fixture.repository.saga);
        assertEquals(0, fixture.port.reads + fixture.port.effects);
        var paid = fixture.coordinator().advance(fixture.request.sagaId(), 1);
        assertEquals(SettlementSaga.State.PAYMENT_APPLIED, paid.state());
        assertEquals(ORIGINAL, fixture.repository.binding);
        assertEquals(1, fixture.port.effects);
    }

    @Test void legacyUnboundSagaCannotBeAutomaticallyAdoptedOrReconciled() {
        var fixture = new Fixture();
        var prepared = fixture.coordinator().begin(fixture.request, 0);
        fixture.repository.binding = null;
        assertThrows(IllegalStateException.class, () -> fixture.coordinator().advance(fixture.request.sagaId(), 1));
        assertEquals(prepared, fixture.repository.saga);
        assertEquals(0, fixture.port.reads + fixture.port.effects);
        fixture.repository.saga = next(prepared, SettlementSaga.State.MANUAL_REVIEW, "EXTERNAL_OUTCOME_UNKNOWN");
        int authorizations = fixture.reviewAuthorizations;
        assertThrows(IllegalStateException.class, () -> fixture.reviewer().reconcile(fixture.reviewRequest(1), 2));
        assertEquals(authorizations, fixture.reviewAuthorizations);
        assertTrue(fixture.repository.history.isEmpty());
        assertNull(fixture.repository.binding);
        assertEquals(0, fixture.port.reads + fixture.port.effects);
    }

    @Test void eitherAuthorizationCallbackCannotBindASagaAcrossAChangedProvider() {
        for (boolean verification : List.of(false, true)) for (var changed : changedBindings()) {
            var fixture = new Fixture();
            Runnable change = () -> fixture.port.binding = changed;
            if (verification) fixture.afterVerify = change; else fixture.afterAuthorize = change;
            var coordinator = fixture.coordinator();
            assertThrows(IllegalStateException.class, () -> coordinator.begin(fixture.request, 0));
            assertNull(fixture.repository.saga);
            assertNull(fixture.repository.binding);
            assertEquals(0, fixture.repository.boundCreates);
            assertEquals(0, fixture.port.reads + fixture.port.effects);
        }
    }

    @Test void aChangedDomainAfterLookupCannotAuthorizeAnApplyOrLocalCommit() {
        for (boolean applied : List.of(false, true)) for (var changed : changedBindings()) {
            var fixture = new Fixture();
            var coordinator = fixture.coordinator();
            var before = coordinator.begin(fixture.request, 0);
            if (applied) fixture.port.applied.put(SettlementOperation.forPhase(fixture.request, PAYMENT).idempotencyKey(),
                    SettlementOperation.forPhase(fixture.request, PAYMENT));
            fixture.port.afterRead = () -> fixture.port.binding = changed;
            assertThrows(IllegalStateException.class, () -> coordinator.advance(fixture.request.sagaId(), 1));
            assertEquals(before, fixture.repository.saga);
            assertEquals(ORIGINAL, fixture.repository.binding);
            assertEquals(0, fixture.port.effects);
        }
    }

    @Test void providerChangeDuringPermitReverificationCannotApplyOrCloseTheSaga() {
        for (boolean authorized : List.of(false, true)) {
            var fixture = new Fixture();
            var coordinator = fixture.coordinator();
            var before = coordinator.begin(fixture.request, 0);
            fixture.verifyAllowed = authorized;
            fixture.afterVerify = () -> fixture.port.binding = changedBindings().getFirst();
            assertThrows(IllegalStateException.class, () -> coordinator.advance(fixture.request.sagaId(), 1));
            assertEquals(before, fixture.repository.saga);
            assertEquals(0, fixture.port.effects);
        }
    }

    @Test void changedHistoryAfterAnyAppliedPhaseLeavesLocalStateForOriginalHistoryLookup() {
        for (var phase : SettlementOperation.Phase.values()) {
            var fixture = new Fixture();
            var before = fixture.prepare(phase);
            var coordinator = fixture.coordinator();
            int effects = fixture.port.effects;
            fixture.port.afterApply = () -> fixture.port.binding = changedBindings().get(1);
            assertThrows(IllegalStateException.class, () -> coordinator.advance(fixture.request.sagaId(), 4));
            assertEquals(before, fixture.repository.saga);
            assertEquals(effects + 1, fixture.port.effects);
            fixture.port.binding = ORIGINAL;
            fixture.port.evidence = "audit:after-restart";
            fixture.port.afterApply = () -> {};
            var recovered = fixture.coordinator().advance(fixture.request.sagaId(), 5);
            assertEquals(switch (phase) {
                case PAYMENT -> SettlementSaga.State.PAYMENT_APPLIED;
                case DELIVERY -> SettlementSaga.State.COMPLETED;
                case COMPENSATION -> SettlementSaga.State.COMPENSATED;
            }, recovered.state());
            assertEquals(effects + 1, fixture.port.effects, "Recovered by lookup without repeating the external operation");
            assertEquals(ORIGINAL, fixture.repository.binding);
        }
    }

    @Test void aRejectedExternalOperationCannotCommitAcrossChangedAuthority() {
        var fixture = new Fixture();
        var coordinator = fixture.coordinator();
        var before = coordinator.begin(fixture.request, 0);
        fixture.port.rejectPayment = true;
        fixture.port.afterApply = () -> fixture.port.binding = changedBindings().getFirst();
        assertThrows(IllegalStateException.class, () -> coordinator.advance(fixture.request.sagaId(), 1));
        assertEquals(before, fixture.repository.saga);
        assertEquals(0, fixture.port.effects);
    }

    @Test void freshReviewRejectsAnyChangedPersistedDomainBeforeAuthorizationOrQuery() {
        for (var changed : changedBindings()) {
            var fixture = new Fixture();
            var before = fixture.awaitReview();
            fixture.port.binding = changed;
            int reads = fixture.port.reads, authorizations = fixture.reviewAuthorizations;
            assertThrows(IllegalStateException.class, () -> fixture.reviewer().reconcile(fixture.reviewRequest(1), 4));
            assertEquals(before, fixture.repository.saga);
            assertEquals(authorizations, fixture.reviewAuthorizations);
            assertEquals(reads, fixture.port.reads);
            assertTrue(fixture.repository.history.isEmpty());
        }
    }

    @Test void reviewCannotSaveMixedAuthorityResultsFromAnyOfItsThreeQueries() {
        for (var changed : changedBindings()) for (int boundary = 1; boundary <= 3; boundary++) {
            var fixture = new Fixture();
            var before = fixture.awaitReview();
            int stop = fixture.port.reads + boundary;
            fixture.port.afterRead = () -> { if (fixture.port.reads == stop) fixture.port.binding = changed; };
            assertThrows(IllegalStateException.class, () -> fixture.reviewer().reconcile(fixture.reviewRequest(1), 4));
            assertEquals(before, fixture.repository.saga);
            assertEquals(stop, fixture.port.reads);
            assertTrue(fixture.repository.history.isEmpty());
            assertEquals(0, fixture.port.effects);
        }
    }

    @Test void reviewAuthorizationCannotChangeTheBoundProviderBeforeQueries() {
        var fixture = new Fixture();
        var before = fixture.awaitReview();
        int reads = fixture.port.reads;
        fixture.afterReviewAuthorize = () -> fixture.port.binding = changedBindings().getFirst();
        assertThrows(IllegalStateException.class, () -> fixture.reviewer().reconcile(fixture.reviewRequest(1), 4));
        assertEquals(before, fixture.repository.saga);
        assertEquals(reads, fixture.port.reads);
        assertTrue(fixture.repository.history.isEmpty());
    }

    @Test void aFreshReviewMayUseNewAuditEvidenceForTheSameOriginalDomains() {
        var fixture = new Fixture();
        fixture.awaitReview();
        fixture.port.evidence = "audit:new-review";
        var review = fixture.reviewer().reconcile(fixture.reviewRequest(1), 4);
        assertEquals("audit:new-review", review.providerEvidence());
        assertEquals(SettlementSaga.State.FAILED, review.after().state());
        assertEquals(ORIGINAL, fixture.repository.binding);
    }

    @Test void existingBeginTerminalAndReviewRetriesRemainReadOnlyWhenProviderIsOffline() {
        var fixture = new Fixture();
        var coordinator = fixture.coordinator();
        var prepared = coordinator.begin(fixture.request, 0);
        fixture.port.offline = true;
        assertEquals(prepared, coordinator.begin(fixture.request, 200));
        fixture.port.offline = false;
        fixture.port.unknown = true;
        var pending = coordinator.advance(fixture.request.sagaId(), 1);
        fixture.port.unknown = false;
        fixture.port.offline = true;
        assertEquals(pending, coordinator.advance(fixture.request.sagaId(), 2));
        fixture.port.offline = false;
        var request = fixture.reviewRequest(1);
        var review = fixture.reviewer().reconcile(request, 4);
        int reads = fixture.port.reads, effects = fixture.port.effects, reviews = fixture.repository.history.size();
        fixture.port.offline = true;
        assertEquals(review, fixture.reviewer().reconcile(request, 5));
        assertEquals(review.after(), coordinator.advance(fixture.request.sagaId(), 6));
        assertEquals(reads, fixture.port.reads);
        assertEquals(effects, fixture.port.effects);
        assertEquals(reviews, fixture.repository.history.size());
        assertEquals(2, fixture.reviewAuthorizations, "Stored reviews still require current review permission");
    }

    private static List<SettlementAuthorityBinding> changedBindings() {
        return List.of(binding("credits:replacement", "credits:history", "items:primary", "items:history"),
                binding("credits:primary", "credits:new-history", "items:primary", "items:history"),
                binding("credits:primary", "credits:history", "items:replacement", "items:history"),
                binding("credits:primary", "credits:history", "items:primary", "items:new-history"));
    }

    private static SettlementAuthorityBinding binding(String credits, String creditHistory, String items, String itemHistory) {
        return new SettlementAuthorityBinding(new SettlementAuthorityBinding.AuthorityDomain(credits, creditHistory),
                new SettlementAuthorityBinding.AuthorityDomain(items, itemHistory));
    }

    private static SettlementSaga next(SettlementSaga before, SettlementSaga.State state, String reason) {
        return new SettlementSaga(1, before.request(), before.permit(), state, before.revision() + 1, reason);
    }

    private static final class Fixture {
        final Repository repository = new Repository();
        final Port port = new Port();
        final SettlementRequest request = new SettlementRequest(new UUID(0, 610), new UUID(0, 611), new UUID(0, 611),
                new UUID(0, 612), 10, "test:ore", 2, 100);
        Runnable afterAuthorize = () -> {}, afterVerify = () -> {}, afterReviewAuthorize = () -> {};
        int reviewAuthorizations;
        boolean verifyAllowed = true;

        SettlementSagaCoordinator coordinator() {
            return new SettlementSagaCoordinator(new SettlementAuthorizer() {
                @Override public SettlementPermit authorize(SettlementRequest input, long tick) {
                    afterAuthorize.run();
                    return new SettlementPermit("fixture:policy", "fixture:permit", input.actor(), input.buyer(), input.seller(),
                            SettlementRequestCodec.hash(input), tick, input.expiresAtTick());
                }
                @Override public boolean verify(SettlementRequest input, SettlementPermit permit, long tick) {
                    afterVerify.run();
                    return verifyAllowed;
                }
            }, port, repository);
        }

        SettlementReviewCoordinator reviewer() {
            return new SettlementReviewCoordinator((saga, request, tick) -> {
                reviewAuthorizations++;
                afterReviewAuthorize.run();
                return new SettlementReviewAuthorizer.Authorization("fixture:review-policy", "fixture:review-permit");
            }, port, repository);
        }

        SettlementSaga prepare(SettlementOperation.Phase phase) {
            var coordinator = coordinator();
            coordinator.begin(request, 0);
            if (phase != PAYMENT) coordinator.advance(request.sagaId(), 1);
            if (phase == COMPENSATION) {
                port.rejectDelivery = true;
                coordinator.advance(request.sagaId(), 2);
            }
            return repository.saga;
        }

        SettlementSaga awaitReview() {
            var coordinator = coordinator();
            coordinator.begin(request, 0);
            port.unknown = true;
            var pending = coordinator.advance(request.sagaId(), 1);
            port.unknown = false;
            return pending;
        }

        SettlementReviewRequest reviewRequest(int serial) {
            return new SettlementReviewRequest(new UUID(1, serial), request.sagaId(), new UUID(0, 613),
                    repository.saga.revision(), "case-" + serial);
        }
    }

    private static final class Port implements IdempotentSettlementPort {
        SettlementAuthorityBinding binding = ORIGINAL;
        String evidence = "audit:original";
        final Map<String, SettlementOperation> applied = new HashMap<>();
        int reads, effects;
        boolean unknown, offline, rejectPayment, rejectDelivery;
        Runnable afterRead = () -> {}, afterApply = () -> {};
        @Override public Capability capability() { return new Capability(true, true, evidence); }
        @Override public RecoveryDescriptor recoveryDescriptor() {
            if (offline) throw new IllegalStateException("Provider unavailable");
            return new RecoveryDescriptor(capability(), binding);
        }
        @Override public Observation inspect(SettlementOperation operation) {
            reads++;
            var result = unknown ? UNKNOWN : applied.containsKey(operation.idempotencyKey()) ? APPLIED : NOT_APPLIED;
            afterRead.run();
            return result;
        }
        @Override public Outcome apply(SettlementOperation operation) {
            if (operation.asset() == SettlementOperation.Asset.CREDITS && rejectPayment
                    || operation.asset() == SettlementOperation.Asset.ITEM && rejectDelivery) {
                afterApply.run();
                return Outcome.NO_EFFECT;
            }
            assertNull(applied.putIfAbsent(operation.idempotencyKey(), operation), "Duplicate external operation");
            effects++;
            afterApply.run();
            return Outcome.APPLIED;
        }
    }

    private static final class Repository implements SettlementSagaRepository {
        SettlementSaga saga;
        SettlementAuthorityBinding binding;
        final List<SettlementReview> history = new ArrayList<>();
        int boundCreates;
        @Override public synchronized <T> T withExclusiveSaga(UUID id, Supplier<T> action) { return action.get(); }
        @Override public Optional<SettlementSaga> load(UUID id) { return Optional.ofNullable(saga); }
        @Override public Optional<SettlementAuthorityBinding> authorityBinding(UUID id) { return Optional.ofNullable(binding); }
        @Override public SettlementSaga create(SettlementSaga initial) { fail("Automatic coordination requires atomic bound creation"); return null; }
        @Override public SettlementSaga createBound(SettlementSaga initial, SettlementAuthorityBinding declared) {
            initial.requireInitial();
            assertNull(saga);
            assertNotNull(declared);
            saga = initial;
            binding = declared;
            boundCreates++;
            return saga;
        }
        @Override public SettlementSaga replace(SettlementSaga updated, long expectedRevision) {
            assertEquals(saga.revision(), expectedRevision);
            updated.requireSuccessorOf(saga);
            saga = updated;
            return saga;
        }
        @Override public List<SettlementReview> reviews(UUID id) { return List.copyOf(history); }
        @Override public SettlementReview commitReview(SettlementReview review) {
            assertEquals(saga, review.before());
            history.add(review);
            saga = review.after();
            return review;
        }
    }
}
