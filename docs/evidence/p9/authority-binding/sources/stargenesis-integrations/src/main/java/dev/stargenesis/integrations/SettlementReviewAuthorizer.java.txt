package dev.stargenesis.integrations;

/** Trusted operator-policy adapter. There is no default authorization or player permission mapping. */
@FunctionalInterface
public interface SettlementReviewAuthorizer {
    /** Reject unauthorized requests; returned evidence must bind this operator, case, saga and revision. */
    Authorization authorize(SettlementSaga saga, SettlementReviewRequest request, long logicalTick);

    record Authorization(String policyId, String evidenceId) {
        public Authorization {
            policyId = SettlementReviewRequest.requireText(policyId, "review policyId");
            evidenceId = SettlementReviewRequest.requireText(evidenceId, "review authorization evidenceId");
        }
    }
}
