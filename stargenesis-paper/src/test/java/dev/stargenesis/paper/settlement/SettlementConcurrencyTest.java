package dev.stargenesis.paper.settlement;

import dev.stargenesis.integrations.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SettlementConcurrencyTest {
    private static final UUID BUYER = new UUID(0, 1001);
    private static final UUID SELLER = new UUID(0, 1002);
    private static final SettlementAuthorityBinding BINDING = new SettlementAuthorityBinding(
            new SettlementAuthorityBinding.AuthorityDomain("fixture:credits", "fixture:credit-history"),
            new SettlementAuthorityBinding.AuthorityDomain("fixture:items", "fixture:item-history"));
    private static final SettlementAuthorizer AUTHORIZER = new SettlementAuthorizer() {
        @Override public SettlementPermit authorize(SettlementRequest request, long tick) {
            return new SettlementPermit("fixture", "fixture:concurrency", request.actor(), request.buyer(), request.seller(),
                    SettlementRequestCodec.hash(request), tick, request.expiresAtTick());
        }
        @Override public boolean verify(SettlementRequest request, SettlementPermit permit, long tick) {
            return permit.requestHash().equals(SettlementRequestCodec.hash(request))
                    && tick >= permit.issuedAtTick() && tick < permit.expiresAtTick();
        }
    };

    @Test void expiredRecoveryWaitsForInFlightPaymentThenCompensates(@TempDir Path directory) throws Exception {
        runExpiryRace(directory, SettlementOperation.Asset.CREDITS);
    }

    @Test void expiredRecoveryCannotRefundAnInFlightDelivery(@TempDir Path directory) throws Exception {
        runExpiryRace(directory, SettlementOperation.Asset.ITEM);
    }

    @Test void concurrentBeginReturnsTheSameSagaEvenWhenTheRetryHasExpired(@TempDir Path directory) throws Exception {
        var request = new SettlementRequest(new UUID(0, 1013), BUYER, BUYER, SELLER, 10, "test:ore", 2, 100);
        var authorizing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var authorizer = new SettlementAuthorizer() {
            @Override public SettlementPermit authorize(SettlementRequest value, long tick) {
                calls.incrementAndGet();
                authorizing.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Authorization fixture timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return AUTHORIZER.authorize(value, tick);
            }
            @Override public boolean verify(SettlementRequest value, SettlementPermit permit, long tick) {
                return AUTHORIZER.verify(value, permit, tick);
            }
        };
        var port = new BlockingPort();
        var first = new SettlementSagaCoordinator(authorizer, port, new FileSettlementSagaRepository(directory));
        var second = new SettlementSagaCoordinator(authorizer, port, new FileSettlementSagaRepository(directory));
        var executor = Executors.newFixedThreadPool(2);
        try {
            var creation = executor.submit(() -> first.begin(request, 0));
            assertTrue(authorizing.await(10, TimeUnit.SECONDS));
            var retry = executor.submit(() -> second.begin(request, 100));
            try { assertThrows(TimeoutException.class, () -> retry.get(250, TimeUnit.MILLISECONDS)); }
            finally { release.countDown(); }
            assertEquals(creation.get(10, TimeUnit.SECONDS), retry.get(10, TimeUnit.SECONDS));
            assertEquals(1, calls.get());
            assertTrue(port.operations.isEmpty());
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test void differentSagaCanAdvanceThroughTheSameRepositoryWhileOnePortIsBlocked(@TempDir Path directory) throws Exception {
        var repository = new FileSettlementSagaRepository(directory);
        var blockedPort = new BlockingPort();
        var otherPort = new BlockingPort();
        var first = new SettlementSagaCoordinator(AUTHORIZER, blockedPort, repository);
        var second = new SettlementSagaCoordinator(AUTHORIZER, otherPort, repository);
        var firstRequest = new SettlementRequest(new UUID(0, 1023), BUYER, BUYER, SELLER, 10, "test:ore", 2, 100);
        var secondRequest = new SettlementRequest(new UUID(0, 1024), BUYER, BUYER, SELLER, 10, "test:ore", 2, 100);
        first.begin(firstRequest, 0);
        second.begin(secondRequest, 0);
        blockedPort.blockedAsset = SettlementOperation.Asset.CREDITS;
        var executor = Executors.newFixedThreadPool(2);
        try {
            var applying = executor.submit(() -> first.advance(firstRequest.sagaId(), 1));
            assertTrue(blockedPort.applying.await(10, TimeUnit.SECONDS));
            var independent = executor.submit(() -> second.advance(secondRequest.sagaId(), 1));
            assertEquals(SettlementSaga.State.PAYMENT_APPLIED, independent.get(10, TimeUnit.SECONDS).state());
            blockedPort.release.countDown();
            assertEquals(SettlementSaga.State.PAYMENT_APPLIED, applying.get(10, TimeUnit.SECONDS).state());
        } finally {
            blockedPort.release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test void paymentWriteFailureRecoversTheAppliedKeyAfterRestartAndExpiry(@TempDir Path directory) throws Exception {
        recoverFailedWrite(directory, 0);
    }

    @Test void deliveryWriteFailureNeverRefundsDeliveredItems(@TempDir Path directory) throws Exception {
        recoverFailedWrite(directory, 1);
    }

    @Test void compensationWriteFailureDoesNotRefundTwice(@TempDir Path directory) throws Exception {
        recoverFailedWrite(directory, 2);
    }

    private void recoverFailedWrite(Path directory, int leg) throws Exception {
        var request = new SettlementRequest(new UUID(0, 1033 + leg), BUYER, BUYER, SELLER, 10, "test:ore", 2, 100);
        var port = new BlockingPort();
        var first = new SettlementSagaCoordinator(AUTHORIZER, port, new FileSettlementSagaRepository(directory));
        first.begin(request, 0);
        if (leg >= 1) first.advance(request.sagaId(), 1);
        if (leg == 2) {
            port.rejectItems = true;
            first.advance(request.sagaId(), 2);
        }
        Path file = directory.resolve(request.sagaId() + ".settlement.json");
        byte[] before = Files.readAllBytes(file);
        Path blocked = file.resolveSibling(file.getFileName() + ".tmp");
        Files.createDirectory(blocked);
        assertThrows(FileSettlementSagaRepository.SagaStorageException.class, () -> first.advance(request.sagaId(), 3));
        assertArrayEquals(before, Files.readAllBytes(file));
        Files.delete(blocked);
        var restarted = new SettlementSagaCoordinator(AUTHORIZER, port, new FileSettlementSagaRepository(directory));
        var recovered = restarted.advance(request.sagaId(), 100);
        if (leg == 0) {
            assertEquals(SettlementSaga.State.PAYMENT_APPLIED, recovered.state());
            assertEquals(SettlementSaga.State.COMPENSATION_REQUIRED, restarted.advance(request.sagaId(), 101).state());
            assertEquals(SettlementSaga.State.COMPENSATED, restarted.advance(request.sagaId(), 102).state());
        } else {
            assertEquals(leg == 1 ? SettlementSaga.State.COMPLETED : SettlementSaga.State.COMPENSATED, recovered.state());
            assertEquals(recovered, restarted.advance(request.sagaId(), 101));
        }
        assertEquals(1, port.payments.get());
        assertEquals(leg == 1 ? 1 : 0, port.deliveries.get());
        assertEquals(leg == 1 ? 0 : 1, port.refunds.get());
    }

    private void runExpiryRace(Path directory, SettlementOperation.Asset blockedAsset) throws Exception {
        var request = new SettlementRequest(new UUID(0, 1003), BUYER, BUYER, SELLER, 10, "test:ore", 2, 100);
        var port = new BlockingPort();
        var first = new SettlementSagaCoordinator(AUTHORIZER, port, new FileSettlementSagaRepository(directory));
        var second = new SettlementSagaCoordinator(AUTHORIZER, port, new FileSettlementSagaRepository(directory));
        first.begin(request, 0);
        if (blockedAsset == SettlementOperation.Asset.ITEM) first.advance(request.sagaId(), 1);
        port.blockedAsset = blockedAsset;
        var executor = Executors.newFixedThreadPool(2);
        try {
            var applying = executor.submit(() -> first.advance(request.sagaId(), 2));
            assertTrue(port.applying.await(10, TimeUnit.SECONDS));
            var recovering = executor.submit(() -> second.advance(request.sagaId(), 100));
            try {
                assertThrows(TimeoutException.class, () -> recovering.get(250, TimeUnit.MILLISECONDS),
                        "Recovery must not publish an expiry outcome while the external operation is in flight");
            } finally {
                port.release.countDown();
            }
            assertEquals(blockedAsset == SettlementOperation.Asset.CREDITS ? SettlementSaga.State.PAYMENT_APPLIED
                    : SettlementSaga.State.COMPLETED, applying.get(10, TimeUnit.SECONDS).state());
            var recovered = recovering.get(10, TimeUnit.SECONDS);
            if (blockedAsset == SettlementOperation.Asset.CREDITS) {
                assertEquals(SettlementSaga.State.COMPENSATION_REQUIRED, recovered.state());
                assertEquals(SettlementSaga.State.COMPENSATED, second.advance(request.sagaId(), 101).state());
                assertEquals(0, port.deliveries.get());
                assertEquals(1, port.refunds.get());
            } else {
                assertEquals(SettlementSaga.State.COMPLETED, recovered.state());
                assertEquals(recovered, second.advance(request.sagaId(), 101));
                assertEquals(1, port.deliveries.get());
                assertEquals(0, port.refunds.get());
            }
            assertEquals(1, port.payments.get());
        } finally {
            port.release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private static final class BlockingPort implements IdempotentSettlementPort {
        final Map<String, SettlementOperation> operations = new ConcurrentHashMap<>();
        final CountDownLatch applying = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);
        final AtomicInteger payments = new AtomicInteger();
        final AtomicInteger deliveries = new AtomicInteger();
        final AtomicInteger refunds = new AtomicInteger();
        volatile SettlementOperation.Asset blockedAsset;
        boolean rejectItems;

        @Override public Capability capability() { return new Capability(true, true, "fixture:concurrent-port"); }
        @Override public RecoveryDescriptor recoveryDescriptor() { return new RecoveryDescriptor(capability(), BINDING); }
        @Override public Observation inspect(SettlementOperation operation) {
            return operations.containsKey(operation.idempotencyKey()) ? Observation.APPLIED : Observation.NOT_APPLIED;
        }
        @Override public Outcome apply(SettlementOperation operation) {
            if (rejectItems && operation.asset() == SettlementOperation.Asset.ITEM) return Outcome.NO_EFFECT;
            if (operation.asset() == blockedAsset && (operation.source().equals(BUYER)
                    || blockedAsset == SettlementOperation.Asset.ITEM)) {
                applying.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Fixture release timed out");
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }
            if (operations.putIfAbsent(operation.idempotencyKey(), operation) == null) {
                if (operation.asset() == SettlementOperation.Asset.ITEM) deliveries.incrementAndGet();
                else if (operation.source().equals(BUYER)) payments.incrementAndGet();
                else refunds.incrementAndGet();
            }
            return Outcome.APPLIED;
        }
    }
}
