package dev.stargenesis.integrations;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Durable implementations enforce initial state, immutable request/permit, legal transitions and atomic revisions. */
public interface SettlementSagaRepository {
    /**
     * Holds the saga's exclusive lock for lookup, external side effects and snapshot replacement together.
     * Durable implementations must coordinate all repository instances and processes that share storage.
     * Nested reads/writes of the same saga are permitted; nesting a different saga must reject rather than deadlock.
     * Older repositories fail closed until they implement this stronger contract.
     */
    default <T> T withExclusiveSaga(UUID sagaId, Supplier<T> action) {
        throw new UnsupportedOperationException("Repository does not support exclusive settlement execution");
    }

    Optional<SettlementSaga> load(UUID sagaId);

    SettlementSaga create(SettlementSaga initial);

    SettlementSaga replace(SettlementSaga updated, long expectedRevision);
}
