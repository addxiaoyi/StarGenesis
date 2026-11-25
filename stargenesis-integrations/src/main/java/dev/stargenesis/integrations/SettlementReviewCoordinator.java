package dev.stargenesis.integrations;

import java.util.EnumMap;
import java.util.Objects;

/** Explicit, authorized reconciliation. It only queries external operations; it never applies one. */
public final class SettlementReviewCoordinator {
    private final SettlementReviewAuthorizer authorizer;
    private final IdempotentSettlementPort port;
    private final SettlementSagaRepository repository;

    public SettlementReviewCoordinator(SettlementReviewAuthorizer authorizer, IdempotentSettlementPort port,
                                       SettlementSagaRepository repository) {
        this.authorizer = Objects.requireNonNull(authorizer, "authorizer");
        this.port = Objects.requireNonNull(port, "port");
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public SettlementReview reconcile(SettlementReviewRequest request, long logicalTick) {
        Objects.requireNonNull(request, "request");
        if (logicalTick < 0) throw new IllegalArgumentException("Negative settlement review tick");
        return repository.withExclusiveSaga(request.sagaId(), () -> {
            var saga = repository.load(request.sagaId())
                    .orElseThrow(() -> new IllegalArgumentException("Settlement saga does not exist"));
            request.requireIndependentReviewer(saga.request());
            var history = repository.reviews(request.sagaId());
            for (var review : history) {
                if (!review.request().reviewId().equals(request.reviewId())) continue;
                if (!review.request().equals(request)) throw new IllegalArgumentException("Review ID reused with a different request");
                Objects.requireNonNull(authorizer.authorize(review.before(), request, logicalTick), "review authorization");
                return review;
            }
            if (saga.state() != SettlementSaga.State.MANUAL_REVIEW || saga.revision() != request.expectedRevision())
                throw new IllegalStateException("Settlement is not awaiting review at the expected revision");
            if (history.size() >= SettlementReview.MAX_REVIEWS) throw new IllegalStateException("Settlement review history budget exhausted");
            if (!history.isEmpty() && logicalTick < history.getLast().logicalTick())
                throw new IllegalArgumentException("Settlement review clock moved backwards");
            var previouslyApplied = SettlementReview.previouslyApplied(saga, history);
            var authorization = Objects.requireNonNull(authorizer.authorize(saga, request, logicalTick), "review authorization");
            var capability = Objects.requireNonNull(port.capability(), "settlement capability");
            if (!capability.supportsRecovery()) throw new IllegalStateException("Settlement provider recovery capability unavailable");
            var observations = new EnumMap<SettlementOperation.Phase, IdempotentSettlementPort.Observation>(SettlementOperation.Phase.class);
            for (var phase : SettlementOperation.Phase.values()) observations.put(phase, inspect(SettlementOperation.forPhase(saga.request(), phase)));
            var state = SettlementReview.resolution(observations, previouslyApplied);
            var after = new SettlementSaga(saga.schemaVersion(), saga.request(), saga.permit(), state,
                    Math.addExact(saga.revision(), 1), SettlementReview.reasonFor(state));
            var review = new SettlementReview(1, request, authorization, capability.evidenceId(), logicalTick, saga, previouslyApplied, observations, after);
            return repository.commitReview(review);
        });
    }

    private IdempotentSettlementPort.Observation inspect(SettlementOperation operation) {
        try { return Objects.requireNonNull(port.inspect(operation), "settlement observation"); }
        catch (RuntimeException | LinkageError unavailable) { return IdempotentSettlementPort.Observation.UNKNOWN; }
    }
}
