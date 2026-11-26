package dev.stargenesis.integrations;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** A mutable test provider exercises withdrawal during real coordinator callbacks. */
class SettlementRecoveryLifecycleTest {
    @Test void withdrawnRecoveryRejectsNewBeginsBeforeAuthorizationOrPersistence() {
        for (int failure = 1; failure <= 5; failure++) {
            var fixture = new Fixture();
            fixture.port.failure = failure;
            assertThrows(IllegalStateException.class, () -> fixture.coordinator.begin(fixture.request, 0));
            assertEquals(0, fixture.authorizations);
            assertTrue(fixture.repository.sagas.isEmpty());
            assertEquals(0, fixture.port.reads + fixture.port.calls);
        }
    }

    @Test void withdrawalDuringAuthorizationCannotCreateAPreparedSaga() {
        for (boolean replace : List.of(false, true)) {
            var fixture = new Fixture();
            fixture.afterAuthorization = () -> fixture.port.withdraw(replace);
            assertThrows(IllegalStateException.class, () -> fixture.coordinator.begin(fixture.request, 0));
            assertTrue(fixture.repository.sagas.isEmpty());
            assertEquals(0, fixture.port.reads + fixture.port.calls);
        }
    }

    @Test void withdrawnRecoveryBlocksEveryNonterminalPhaseWithoutChangingState() {
        for (var phase : SettlementOperation.Phase.values()) for (int failure = 1; failure <= 5; failure++) {
            var fixture = new Fixture();
            var before = fixture.prepare(phase);
            int calls = fixture.port.calls;
            int reads = fixture.port.reads;
            fixture.port.failure = failure;
            assertThrows(IllegalStateException.class, () -> fixture.coordinator.advance(fixture.request.sagaId(), 3));
            assertEquals(before, fixture.current());
            assertEquals(calls, fixture.port.calls);
            assertEquals(reads, fixture.port.reads);
        }
    }

    @Test void lookupCannotAuthorizeAnApplyOrCommitAcrossCapabilityWithdrawalOrReplacement() {
        for (boolean replace : List.of(false, true)) for (boolean applied : List.of(false, true)) {
            var fixture = new Fixture();
            var before = fixture.prepare(SettlementOperation.Phase.PAYMENT);
            if (applied) fixture.port.applied.put(SettlementOperation.forPhase(fixture.request, SettlementOperation.Phase.PAYMENT).idempotencyKey(),
                    SettlementOperation.forPhase(fixture.request, SettlementOperation.Phase.PAYMENT));
            fixture.port.afterRead = () -> fixture.port.withdraw(replace);
            assertThrows(IllegalStateException.class, () -> fixture.coordinator.advance(fixture.request.sagaId(), 1));
            assertEquals(before, fixture.current());
            assertEquals(0, fixture.port.calls);
        }
    }

    @Test void authorizationRecheckCannotApplyOrCloseUsingAWithdrawnProvider() {
        for (boolean allowed : List.of(false, true)) {
            var fixture = new Fixture();
            var before = fixture.prepare(SettlementOperation.Phase.PAYMENT);
            fixture.verifyAllowed = allowed;
            fixture.afterVerification = () -> fixture.port.withdraw(false);
            assertThrows(IllegalStateException.class, () -> fixture.coordinator.advance(fixture.request.sagaId(), 1));
            assertEquals(before, fixture.current());
            assertEquals(0, fixture.port.calls);
        }
    }

    @Test void withdrawalAfterEachExternalCommitKeepsOriginalStateAndRecoversByLookup() {
        for (var phase : SettlementOperation.Phase.values()) for (boolean replace : List.of(false, true)) {
            var fixture = new Fixture();
            var before = fixture.prepare(phase);
            int calls = fixture.port.calls;
            fixture.port.afterApply = () -> fixture.port.withdraw(replace);
            assertThrows(IllegalStateException.class, () -> fixture.coordinator.advance(fixture.request.sagaId(), 3));
            assertEquals(before, fixture.current());
            assertEquals(calls + 1, fixture.port.calls);
            assertTrue(fixture.port.applied.containsKey(SettlementOperation.forPhase(fixture.request, phase).idempotencyKey()));
            fixture.port.failure = 0;
            fixture.port.evidence = "test:provider-1";
            fixture.port.afterApply = () -> {};
            var recovered = fixture.coordinator.advance(fixture.request.sagaId(), 4);
            assertEquals(switch (phase) {
                case PAYMENT -> SettlementSaga.State.PAYMENT_APPLIED;
                case DELIVERY -> SettlementSaga.State.COMPLETED;
                case COMPENSATION -> SettlementSaga.State.COMPENSATED;
            }, recovered.state());
            assertEquals(calls + 1, fixture.port.calls, "Recovery only looks up the durable result");
        }
    }

    @Test void terminalResultsAndIdenticalBeginRemainReadableWhileProviderIsUnavailable() {
        var fixture = new Fixture();
        fixture.prepare(SettlementOperation.Phase.DELIVERY);
        var completed = fixture.coordinator.advance(fixture.request.sagaId(), 2);
        fixture.port.failure = 4;
        assertEquals(completed, fixture.coordinator.advance(fixture.request.sagaId(), 100));
        assertEquals(completed, fixture.coordinator.begin(fixture.request, 100));
    }

    @Test void withdrawnNoEffectResponseCannotScheduleCompensationFromAnUntrustedResult() {
        var fixture = new Fixture();
        var before = fixture.prepare(SettlementOperation.Phase.DELIVERY);
        fixture.port.rejectDelivery = true;
        fixture.port.afterApply = () -> fixture.port.withdraw(false);
        assertThrows(IllegalStateException.class, () -> fixture.coordinator.advance(fixture.request.sagaId(), 2));
        assertEquals(before, fixture.current());
        assertEquals(1, fixture.port.applied.size());
        fixture.port.failure = 0;
        fixture.port.afterApply = () -> {};
        assertEquals(SettlementSaga.State.COMPENSATION_REQUIRED,
                fixture.coordinator.advance(fixture.request.sagaId(), 3).state());
        assertEquals(1, fixture.port.applied.size());
    }

    @Test void reviewRejectsMixedCapabilityEvidenceWithoutSavingPartialHistory() {
        for (boolean replace : List.of(false, true)) for (int withdrawalRead = 1; withdrawalRead <= 3; withdrawalRead++) {
            var fixture = new Fixture();
            var before = fixture.awaitReview();
            int boundary = fixture.port.reads + withdrawalRead;
            fixture.port.afterRead = () -> { if (fixture.port.reads == boundary) fixture.port.withdraw(replace); };
            assertThrows(IllegalStateException.class, () -> fixture.reviewer.reconcile(fixture.reviewRequest(), 4));
            assertEquals(before, fixture.current());
            assertTrue(fixture.repository.history.isEmpty());
            assertEquals(boundary, fixture.port.reads);
            assertEquals(0, fixture.port.calls);
        }
    }

    @Test void reviewRetriesAfterRecoveryAndStoredReviewDoesNotRequireAnOnlineProvider() {
        var fixture = new Fixture();
        fixture.awaitReview();
        var request = fixture.reviewRequest();
        fixture.port.failure = 1;
        assertThrows(IllegalStateException.class, () -> fixture.reviewer.reconcile(request, 4));
        fixture.port.failure = 0;
        var review = fixture.reviewer.reconcile(request, 5);
        assertEquals(SettlementSaga.State.FAILED, review.after().state());
        fixture.port.failure = 4;
        assertEquals(review, fixture.reviewer.reconcile(request, 6));
        assertEquals(1, fixture.repository.history.size());
        assertEquals(0, fixture.port.calls);
    }

    private static final class Fixture {
        final TestPort port = new TestPort();
        final Repository repository = new Repository();
        final SettlementRequest request = new SettlementRequest(new UUID(0, 11), new UUID(0, 1),
                new UUID(0, 1), new UUID(0, 2), 10, "test:ore", 2, 100);
        int authorizations;
        boolean verifyAllowed = true;
        Runnable afterAuthorization = () -> {};
        Runnable afterVerification = () -> {};
        final SettlementSagaCoordinator coordinator = new SettlementSagaCoordinator(new SettlementAuthorizer() {
            @Override public SettlementPermit authorize(SettlementRequest input, long tick) {
                authorizations++;
                afterAuthorization.run();
                return new SettlementPermit("test.policy", "test.permit", input.actor(), input.buyer(), input.seller(),
                        SettlementRequestCodec.hash(input), tick, input.expiresAtTick());
            }
            @Override public boolean verify(SettlementRequest input, SettlementPermit permit, long tick) {
                afterVerification.run();
                return verifyAllowed && tick >= permit.issuedAtTick() && tick < permit.expiresAtTick();
            }
        }, port, repository);
        final SettlementReviewCoordinator reviewer = new SettlementReviewCoordinator(
                (saga, input, tick) -> new SettlementReviewAuthorizer.Authorization("test.review", "test.review-authorization"),
                port, repository);

        SettlementSaga current() { return repository.sagas.get(request.sagaId()); }
        SettlementSaga prepare(SettlementOperation.Phase phase) {
            coordinator.begin(request, 0);
            if (phase != SettlementOperation.Phase.PAYMENT) coordinator.advance(request.sagaId(), 1);
            if (phase == SettlementOperation.Phase.COMPENSATION) {
                port.rejectDelivery = true;
                coordinator.advance(request.sagaId(), 2);
            }
            return current();
        }
        SettlementSaga awaitReview() {
            prepare(SettlementOperation.Phase.PAYMENT);
            port.unknown = true;
            coordinator.advance(request.sagaId(), 1);
            port.unknown = false;
            return current();
        }
        SettlementReviewRequest reviewRequest() {
            return new SettlementReviewRequest(new UUID(0, 21), request.sagaId(), new UUID(0, 3), 1, "test-case");
        }
    }

    private static final class TestPort implements IdempotentSettlementPort {
        final Map<String, SettlementOperation> applied = new HashMap<>();
        int failure, reads, calls;
        boolean unknown, rejectDelivery;
        String evidence = "test:provider-1";
        Runnable afterRead = () -> {};
        Runnable afterApply = () -> {};
        void withdraw(boolean replace) { if (replace) evidence = "test:provider-2"; else failure = 1; }
        @Override public Capability capability() {
            return switch (failure) {
                case 1 -> new Capability(false, true, evidence);
                case 2 -> new Capability(true, false, evidence);
                case 3 -> null;
                case 4 -> throw new IllegalStateException("Provider stopping");
                case 5 -> throw new NoClassDefFoundError("Provider unloaded");
                default -> new Capability(true, true, evidence);
            };
        }
        @Override public Observation inspect(SettlementOperation operation) {
            reads++;
            Observation result = unknown ? Observation.UNKNOWN : applied.containsKey(operation.idempotencyKey()) ? Observation.APPLIED : Observation.NOT_APPLIED;
            afterRead.run();
            return result;
        }
        @Override public Outcome apply(SettlementOperation operation) {
            calls++;
            if (rejectDelivery && operation.asset() == SettlementOperation.Asset.ITEM) {
                afterApply.run();
                return Outcome.NO_EFFECT;
            }
            if (applied.putIfAbsent(operation.idempotencyKey(), operation) != null) fail("Duplicate external side effect");
            afterApply.run();
            return Outcome.APPLIED;
        }
    }

    private static final class Repository implements SettlementSagaRepository {
        final Map<UUID, SettlementSaga> sagas = new HashMap<>();
        final List<SettlementReview> history = new ArrayList<>();
        @Override public synchronized <T> T withExclusiveSaga(UUID id, java.util.function.Supplier<T> action) { return action.get(); }
        @Override public Optional<SettlementSaga> load(UUID id) { return Optional.ofNullable(sagas.get(id)); }
        @Override public List<SettlementReview> reviews(UUID id) { return List.copyOf(history); }
        @Override public SettlementSaga create(SettlementSaga saga) {
            saga.requireInitial();
            if (sagas.putIfAbsent(saga.request().sagaId(), saga) != null) throw new IllegalStateException("Already exists");
            return saga;
        }
        @Override public SettlementSaga replace(SettlementSaga saga, long revision) {
            var prior = sagas.get(saga.request().sagaId());
            assertEquals(revision, prior.revision());
            saga.requireSuccessorOf(prior);
            sagas.put(saga.request().sagaId(), saga);
            return saga;
        }
        @Override public SettlementReview commitReview(SettlementReview review) {
            assertEquals(review.before(), sagas.get(review.request().sagaId()));
            history.add(review);
            sagas.put(review.request().sagaId(), review.after());
            return review;
        }
    }
}
