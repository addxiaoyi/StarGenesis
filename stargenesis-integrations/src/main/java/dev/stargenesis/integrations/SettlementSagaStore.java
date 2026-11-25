package dev.stargenesis.integrations;

import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Durable checkpoint boundary used by the saga coordinator. */
public interface SettlementSagaStore {
    Optional<SettlementCheckpoint> find(UUID id) throws IOException;

    List<SettlementCheckpoint> loadAll() throws IOException;

    void save(SettlementCheckpoint checkpoint) throws IOException;
}
