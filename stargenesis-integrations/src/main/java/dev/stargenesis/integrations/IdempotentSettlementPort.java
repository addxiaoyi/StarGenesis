package dev.stargenesis.integrations;

/** External side-effect adapter must durably inspect every idempotency key before retry. */
public interface IdempotentSettlementPort {
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
