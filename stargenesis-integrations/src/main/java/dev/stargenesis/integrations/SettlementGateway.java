package dev.stargenesis.integrations;

/** External settlement contract. Implementations must preserve each key across retries and queries. */
public interface SettlementGateway {
    String providerId();

    /**
     * Observes the exact expected effect, including its direction. A receipt for another
     * request under the same key is UNKNOWN, never proof that this step completed.
     */
    Observation observe(String idempotencyKey, SettlementTransfer transfer, Operation operation) throws Exception;

    /** REJECTED guarantees no transfer occurred. Use UNKNOWN for an ambiguous result. */
    Result transfer(String idempotencyKey, SettlementTransfer transfer) throws Exception;

    /** Applies the exact inverse of the transfer under its own stable idempotency key. */
    Result compensate(String idempotencyKey, SettlementTransfer transfer) throws Exception;

    /** NOT_APPLIED guarantees that the observed attempt cannot complete later. */
    enum Observation { APPLIED, NOT_APPLIED, UNKNOWN }

    enum Operation { APPLY, COMPENSATE }

    /** RETRY guarantees no completed effect; UNKNOWN may already have changed external state. */
    enum Result { APPLIED, RETRY, REJECTED, UNKNOWN }
}
