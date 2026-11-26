package dev.stargenesis.integrations;

import java.util.Objects;

/** External side-effect adapter must durably inspect every idempotency key before retry. */
public interface IdempotentSettlementPort {
    /** Availability and audit evidence declaration; also reflect withdrawal in recoveryDescriptor().
     * Recovery consumes the coherent descriptor, not this legacy declaration alone.
     * Evidence is a declaration, not a persisted asset-authority identity.
     * Implementations must still hold their own operation lease; before/after checks cannot make a concurrent reload atomic.
     */
    Capability capability();

    /**
     * Returns capability and asset/history identities from one coherent provider snapshot.
     * Older adapters cannot supply an implicit binding from audit evidence or the current deployment.
     * This declaration does not authenticate an authority, persist history or replace an operation lease.
     */
    default RecoveryDescriptor recoveryDescriptor() {
        throw new UnsupportedOperationException("Provider does not declare settlement authority continuity");
    }

    Observation inspect(SettlementOperation operation);

    Outcome apply(SettlementOperation operation);

    enum Observation { APPLIED, NOT_APPLIED, UNKNOWN }
    enum Outcome { APPLIED, NO_EFFECT, INDETERMINATE }

    /** Coherent declarations only; consumers must still verify recovery support and the persisted binding. */
    record RecoveryDescriptor(Capability capability, SettlementAuthorityBinding authorityBinding) {
        public RecoveryDescriptor {
            Objects.requireNonNull(capability, "capability");
            Objects.requireNonNull(authorityBinding, "authorityBinding");
        }
    }

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
