package dev.stargenesis.integrations;

import java.util.Objects;
import java.util.UUID;

/** An operator requests fresh reconciliation, never supplies the desired asset outcome. */
public record SettlementReviewRequest(UUID reviewId, UUID sagaId, UUID reviewer,
                                      long expectedRevision, String caseReference) {
    public SettlementReviewRequest {
        Objects.requireNonNull(reviewId, "reviewId");
        Objects.requireNonNull(sagaId, "sagaId");
        Objects.requireNonNull(reviewer, "reviewer");
        caseReference = requireText(caseReference, "caseReference");
        if (expectedRevision < 0) throw new IllegalArgumentException("Negative settlement review revision");
    }

    static String requireText(String text, String field) {
        if (text == null || text.isBlank() || text.length() > 256 || text.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException(field + " must contain 1-256 non-control characters");
        return text;
    }

    public void requireIndependentReviewer(SettlementRequest request) {
        if (!sagaId.equals(request.sagaId()) || reviewer.equals(request.actor())
                || reviewer.equals(request.buyer()) || reviewer.equals(request.seller()))
            throw new SecurityException("Settlement reviewer must be independent of the trade participants");
    }
}
