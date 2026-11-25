package dev.stargenesis.integrations;

import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.List;
import java.util.EnumSet;
import java.util.EnumMap;
import java.util.Collections;

import static dev.stargenesis.integrations.IdempotentSettlementPort.Observation.*;
import static dev.stargenesis.integrations.SettlementOperation.Phase.*;

/** Immutable reconciliation evidence stored atomically with its resulting saga snapshot. */
public record SettlementReview(int schemaVersion, SettlementReviewRequest request,
                               SettlementReviewAuthorizer.Authorization authorization, String providerEvidence,
                               long logicalTick, SettlementSaga before,
                               Set<SettlementOperation.Phase> previouslyApplied,
                               Map<SettlementOperation.Phase, IdempotentSettlementPort.Observation> observations,
                               SettlementSaga after) {
    public static final int MAX_REVIEWS = 16;

    public SettlementReview {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(authorization, "authorization");
        providerEvidence = InstalledPlugin.requireText(providerEvidence, "providerEvidence");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        var ordered = new EnumMap<SettlementOperation.Phase, IdempotentSettlementPort.Observation>(SettlementOperation.Phase.class);
        ordered.putAll(Map.copyOf(observations));
        observations = Collections.unmodifiableMap(ordered);
        var confirmed = EnumSet.noneOf(SettlementOperation.Phase.class);
        confirmed.addAll(Set.copyOf(previouslyApplied));
        previouslyApplied = Collections.unmodifiableSet(confirmed);
        request.requireIndependentReviewer(before.request());
        if (schemaVersion != 1 || logicalTick < 0 || before.state() != SettlementSaga.State.MANUAL_REVIEW
                || request.expectedRevision() != before.revision() || before.schemaVersion() != after.schemaVersion()
                || !before.request().equals(after.request()) || !before.permit().equals(after.permit())
                || after.revision() != Math.addExact(before.revision(), 1)
                || after.state() != resolution(observations, previouslyApplied) || !after.reason().equals(reasonFor(after.state())))
            throw new IllegalArgumentException("Invalid settlement review evidence or transition");
    }

    public static SettlementSaga.State resolution(Map<SettlementOperation.Phase, IdempotentSettlementPort.Observation> observations,
                                                  Set<SettlementOperation.Phase> previouslyApplied) {
        if (observations == null || observations.size() != SettlementOperation.Phase.values().length
                || java.util.Arrays.stream(SettlementOperation.Phase.values()).anyMatch(phase -> observations.get(phase) == null))
            throw new IllegalArgumentException("Settlement review must inspect all three operations");
        if (previouslyApplied.stream().anyMatch(phase -> observations.get(phase) != APPLIED))
            return SettlementSaga.State.MANUAL_REVIEW;
        var payment = observations.get(PAYMENT);
        var delivery = observations.get(DELIVERY);
        var refund = observations.get(COMPENSATION);
        if (payment == UNKNOWN || delivery == UNKNOWN || refund == UNKNOWN) return SettlementSaga.State.MANUAL_REVIEW;
        if (payment == NOT_APPLIED)
            return delivery == NOT_APPLIED && refund == NOT_APPLIED ? SettlementSaga.State.FAILED : SettlementSaga.State.MANUAL_REVIEW;
        if (delivery == APPLIED)
            return refund == NOT_APPLIED ? SettlementSaga.State.COMPLETED : SettlementSaga.State.MANUAL_REVIEW;
        return refund == APPLIED ? SettlementSaga.State.COMPENSATED : SettlementSaga.State.COMPENSATION_REQUIRED;
    }

    public static String reasonFor(SettlementSaga.State state) {
        return state == SettlementSaga.State.MANUAL_REVIEW ? "REVIEW_UNRESOLVED" : "RECONCILED_BY_REVIEW";
    }

    /** Legacy v1 can reach review only after these three paths. Unrecognized history needs separate investigation. */
    public static Set<SettlementOperation.Phase> previouslyApplied(SettlementSaga before, List<SettlementReview> history) {
        SettlementSaga origin = history.isEmpty() ? before : history.getFirst().before();
        boolean knownOrigin = origin.state() == SettlementSaga.State.MANUAL_REVIEW
                && ((origin.revision() == 1 || origin.revision() == 2) && origin.reason().equals("EXTERNAL_OUTCOME_UNKNOWN")
                || origin.revision() == 3 && (origin.reason().equals("COMPENSATION_OUTCOME_UNKNOWN") || origin.reason().equals("COMPENSATION_REJECTED")));
        if (!knownOrigin) throw new IllegalArgumentException("Settlement review origin cannot be established");
        var applied = EnumSet.noneOf(SettlementOperation.Phase.class);
        if (origin.revision() > 1) applied.add(PAYMENT);
        for (var review : history) review.observations().forEach((phase, observation) -> {
            if (observation == APPLIED) applied.add(phase);
        });
        return Set.copyOf(applied);
    }
}
