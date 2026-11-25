package dev.stargenesis.integrations;

import java.util.Optional;
import java.util.UUID;

/** Durable implementations must atomically compare saga revision and replace one snapshot. */
public interface SettlementSagaRepository {
    Optional<SettlementSaga> load(UUID sagaId);

    SettlementSaga create(SettlementSaga initial);

    SettlementSaga replace(SettlementSaga updated, long expectedRevision);
}
