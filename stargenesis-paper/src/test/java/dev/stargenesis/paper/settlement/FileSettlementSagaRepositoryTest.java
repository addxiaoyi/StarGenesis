package dev.stargenesis.paper.settlement;

import dev.stargenesis.integrations.SettlementPermit;
import dev.stargenesis.integrations.SettlementRequest;
import dev.stargenesis.integrations.SettlementRequestCodec;
import dev.stargenesis.integrations.SettlementSaga;
import dev.stargenesis.integrations.SettlementSagaCoordinator;
import dev.stargenesis.integrations.SettlementSagaRepository;
import dev.stargenesis.integrations.SettlementAuthorizer;
import dev.stargenesis.integrations.IdempotentSettlementPort;
import dev.stargenesis.integrations.SettlementOperation;
import dev.stargenesis.integrations.SettlementAuthorityBinding;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

class FileSettlementSagaRepositoryTest {
    private static final SettlementAuthorityBinding BINDING = new SettlementAuthorityBinding(
            new SettlementAuthorityBinding.AuthorityDomain("fixture:credits", "fixture:credit-history"),
            new SettlementAuthorityBinding.AuthorityDomain("fixture:items", "fixture:item-history"));
    @Test void sagaStateAndRevisionSurviveRepositoryRestart(@TempDir Path directory) throws Exception {
        UUID sagaId = new UUID(0, 901);
        UUID buyer = new UUID(0, 902);
        UUID seller = new UUID(0, 903);
        SettlementRequest request = new SettlementRequest(sagaId, buyer, buyer, seller, 25,
                "test:ore", 3, 100);
        SettlementPermit permit = new SettlementPermit("fixture.policy", "fixture:permit", buyer, buyer,
                seller, SettlementRequestCodec.hash(request), 1, 100);
        SettlementSaga prepared = new SettlementSaga(1, request, permit, SettlementSaga.State.PREPARED, 0, "");
        FileSettlementSagaRepository first = new FileSettlementSagaRepository(directory);

        first.create(prepared);
        SettlementSaga paymentApplied = new SettlementSaga(1, request, permit,
                SettlementSaga.State.PAYMENT_APPLIED, 1, "");
        first.replace(paymentApplied, 0);

        FileSettlementSagaRepository restarted = new FileSettlementSagaRepository(directory);
        assertEquals(paymentApplied, restarted.load(sagaId).orElseThrow());
        assertThrows(FileSettlementSagaRepository.SagaStorageException.class,
                () -> restarted.replace(new SettlementSaga(1, request, permit,
                        SettlementSaga.State.COMPLETED, 2, ""), 0));
        assertEquals(paymentApplied, restarted.load(sagaId).orElseThrow());
    }

    @Test void malformedOrUnknownSagaFieldsAreRejected(@TempDir Path directory) throws Exception {
        UUID sagaId = new UUID(0, 911);
        UUID buyer = new UUID(0, 912);
        UUID seller = new UUID(0, 913);
        SettlementRequest request = new SettlementRequest(sagaId, buyer, buyer, seller, 5, "test:ore", 1, 10);
        SettlementPermit permit = new SettlementPermit("fixture.policy", "fixture:permit", buyer, buyer,
                seller, SettlementRequestCodec.hash(request), 1, 10);
        FileSettlementSagaRepository repository = new FileSettlementSagaRepository(directory);
        repository.create(new SettlementSaga(1, request, permit, SettlementSaga.State.PREPARED, 0, ""));
        Path file = directory.resolve(sagaId + ".settlement.json");
        Files.writeString(file, Files.readString(file).replaceFirst("\\{", "{\"futureField\":true,"));

        assertThrows(FileSettlementSagaRepository.SagaStorageException.class, () -> repository.load(sagaId));
    }

    @Test void compareAndReplaceAllowsOnlyOneConcurrentWriterAcrossRepositoryInstances(@TempDir Path directory)
            throws Exception {
        UUID sagaId = new UUID(0, 921);
        UUID buyer = new UUID(0, 922);
        UUID seller = new UUID(0, 923);
        SettlementRequest request = new SettlementRequest(sagaId, buyer, buyer, seller, 5, "test:ore", 1, 10);
        SettlementPermit permit = new SettlementPermit("fixture.policy", "fixture:permit", buyer, buyer,
                seller, SettlementRequestCodec.hash(request), 0, 10);
        SettlementSaga prepared = new SettlementSaga(1, request, permit, SettlementSaga.State.PREPARED, 0, "");
        new FileSettlementSagaRepository(directory).create(prepared);

        int writers = 12;
        CountDownLatch ready = new CountDownLatch(writers);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(writers);
        List<java.util.concurrent.Future<Boolean>> attempts = new ArrayList<>();
        try {
            for (int index = 0; index < writers; index++) {
                String reason = "writer-" + index;
                attempts.add(executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Writer start timed out");
                    SettlementSaga updated = new SettlementSaga(1, request, permit,
                            SettlementSaga.State.PAYMENT_APPLIED, 1, reason);
                    try {
                        new FileSettlementSagaRepository(directory).replace(updated, 0);
                        return true;
                    } catch (FileSettlementSagaRepository.SagaStorageException conflict) {
                        if (!"Settlement saga revision conflict".equals(conflict.getMessage())) throw conflict;
                        return false;
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            int successfulWriters = 0;
            for (var attempt : attempts) if (attempt.get(10, TimeUnit.SECONDS)) successfulWriters++;

            assertEquals(1, successfulWriters);
            assertEquals(1, new FileSettlementSagaRepository(directory).load(sagaId).orElseThrow().revision());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test void sagaRecoveryUsesExternalLookupThenCompensatesAfterRepositoryRestart(@TempDir Path directory) throws Exception {
        UUID sagaId = new UUID(0, 931);
        UUID buyer = new UUID(0, 932);
        UUID seller = new UUID(0, 933);
        SettlementRequest request = new SettlementRequest(sagaId, buyer, buyer, seller, 10, "test:ore", 2, 100);
        SettlementAuthorizer authorizer = new SettlementAuthorizer() {
            @Override public SettlementPermit authorize(SettlementRequest value, long tick) {
                return new SettlementPermit("fixture", "fixture:p9", buyer, buyer, seller,
                        SettlementRequestCodec.hash(value), tick, value.expiresAtTick());
            }
            @Override public boolean verify(SettlementRequest value, SettlementPermit permit, long tick) {
                return tick < permit.expiresAtTick() && permit.requestHash().equals(SettlementRequestCodec.hash(value));
            }
        };
        RecoverablePort port = new RecoverablePort(buyer, seller);
        SettlementSagaRepository firstRepository = new FileSettlementSagaRepository(directory);
        SettlementSagaCoordinator first = new SettlementSagaCoordinator(authorizer, port, firstRepository);
        first.begin(request, 0);
        port.uncertainNextCredit = true;
        assertEquals(SettlementSaga.State.PREPARED, first.advance(sagaId, 1).state());

        SettlementSagaRepository restartedRepository = new FileSettlementSagaRepository(directory);
        SettlementSagaCoordinator restarted = new SettlementSagaCoordinator(authorizer, port, restartedRepository);
        assertEquals(SettlementSaga.State.PAYMENT_APPLIED, restarted.advance(sagaId, 2).state());
        port.rejectItemDelivery = true;
        assertEquals(SettlementSaga.State.COMPENSATION_REQUIRED, restarted.advance(sagaId, 3).state());
        SettlementSagaCoordinator afterSecondRestart = new SettlementSagaCoordinator(authorizer, port,
                new FileSettlementSagaRepository(directory));

        assertEquals(SettlementSaga.State.COMPENSATED, afterSecondRestart.advance(sagaId, 4).state());
        assertEquals(100L, port.creditBalances.get(buyer));
        assertEquals(0L, port.creditBalances.get(seller));
        assertEquals(0, port.itemTransfers);
    }

    private static final class RecoverablePort implements IdempotentSettlementPort {
        private final UUID buyer;
        private final UUID seller;
        private final Map<String, SettlementOperation> applied = new HashMap<>();
        private final Map<UUID, Long> creditBalances = new HashMap<>();
        private boolean uncertainNextCredit;
        private boolean rejectItemDelivery;
        private int itemTransfers;

        private RecoverablePort(UUID buyer, UUID seller) {
            this.buyer = buyer; this.seller = seller;
            creditBalances.put(buyer, 100L); creditBalances.put(seller, 0L);
        }

        @Override public Capability capability() { return new Capability(true, true, "fixture:p9-idempotent"); }
        @Override public RecoveryDescriptor recoveryDescriptor() { return new RecoveryDescriptor(capability(), BINDING); }
        @Override public Observation inspect(SettlementOperation operation) {
            return applied.containsKey(operation.idempotencyKey()) ? Observation.APPLIED : Observation.NOT_APPLIED;
        }
        @Override public Outcome apply(SettlementOperation operation) {
            if (operation.asset() == SettlementOperation.Asset.ITEM && rejectItemDelivery) return Outcome.NO_EFFECT;
            if (applied.containsKey(operation.idempotencyKey())) return Outcome.APPLIED;
            if (operation.asset() == SettlementOperation.Asset.CREDITS) {
                if (creditBalances.get(operation.source()) < operation.amount()) return Outcome.NO_EFFECT;
                creditBalances.merge(operation.source(), -operation.amount(), Math::addExact);
                creditBalances.merge(operation.target(), operation.amount(), Math::addExact);
                applied.put(operation.idempotencyKey(), operation);
                if (uncertainNextCredit) { uncertainNextCredit = false; return Outcome.INDETERMINATE; }
                return Outcome.APPLIED;
            }
            itemTransfers++;
            applied.put(operation.idempotencyKey(), operation);
            return Outcome.APPLIED;
        }
    }
}
