package dev.stargenesis.integrations;

/** External side-effect adapter must durably inspect every idempotency key before retry. */
public interface IdempotentSettlementPort {
    /** Current availability and audit evidence; withdraw recovery support while the provider is unavailable.
     * Automatic coordination pins this value for its lifetime; review pins it for one reconciliation.
     * Evidence is a declaration, not a persisted asset-authority identity.
     * Implementations must still hold their own operation lease; before/after checks cannot make a concurrent reload atomic.
     */
    Capability capability();

    Observation inspect(SettlementOperation operation);

    Outcome apply(SettlementOperation operation);

    enum Observation { APPLIED, NOT_APPLIED, UNKNOWN }
    enum Outcome { APPLIED, NO_EFFECT, INDETERMINATE }

    record Capability(boolean durableIdempotencyKeys, boolean lookupByIdempotencyKey,
                      String evidenceId) {
        public Capability {
            evidenceId = InstalledPlugin.requireText(evidenceId, "evidenceId");
        }

        public boolean supportsRecovery() {
            return durableIdempotencyKeys && lookupByIdempotencyKey;
        }
    }
}
