package dev.stargenesis.paper.settlement;

import dev.stargenesis.integrations.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SettlementRepositoryIntegrityTest {
    private static final SettlementAuthorityBinding BINDING = new SettlementAuthorityBinding(
            new SettlementAuthorityBinding.AuthorityDomain("fixture:credits", "fixture:credit-history"),
            new SettlementAuthorityBinding.AuthorityDomain("fixture:items", "fixture:item-history"));
    @Test void createOnlyAcceptsAnInitialPreparedSaga(@TempDir Path directory) throws Exception {
        var repository = new FileSettlementSagaRepository(directory);
        var initial = initial();
        for (var invalid : new SettlementSaga[]{
                state(initial, SettlementSaga.State.PAYMENT_APPLIED, 1),
                state(initial, SettlementSaga.State.PREPARED, 2),
                new SettlementSaga(1, initial.request(), initial.permit(), SettlementSaga.State.PREPARED, 0, "invented history")}) {
            assertThrows(IllegalArgumentException.class, () -> repository.create(invalid));
            assertTrue(repository.load(initial.request().sagaId()).isEmpty());
        }
    }

    @Test void replacementCannotRewriteRequestOrAuthorization(@TempDir Path directory) throws Exception {
        var initial = initial();
        var repository = new FileSettlementSagaRepository(directory);
        repository.create(initial);
        Path file = directory.resolve(initial.request().sagaId() + ".settlement.json");
        byte[] before = Files.readAllBytes(file);
        var original = initial.request();
        var changed = new SettlementRequest(original.sagaId(), original.actor(), original.buyer(), original.seller(),
                11, original.itemId(), original.itemCount(), original.expiresAtTick());
        var permit = new SettlementPermit("changed-policy", "changed-evidence", changed.actor(), changed.buyer(), changed.seller(),
                SettlementRequestCodec.hash(changed), 0, 100);
        var rewritten = new SettlementSaga(1, changed, permit, SettlementSaga.State.PAYMENT_APPLIED, 1, "");
        assertThrows(IllegalArgumentException.class, () -> repository.replace(rewritten, 0));
        var changedPermit = new SettlementPermit("changed-policy", "changed-evidence", original.actor(), original.buyer(), original.seller(),
                SettlementRequestCodec.hash(original), 0, 100);
        var reauthorized = new SettlementSaga(1, original, changedPermit, SettlementSaga.State.PAYMENT_APPLIED, 1, "");
        assertThrows(IllegalArgumentException.class, () -> repository.replace(reauthorized, 0));
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    @Test void replacementRejectsSkippedLegsAndTerminalReopening(@TempDir Path directory) throws Exception {
        var initial = initial();
        var repository = new FileSettlementSagaRepository(directory);
        repository.create(initial);
        assertThrows(IllegalArgumentException.class,
                () -> repository.replace(state(initial, SettlementSaga.State.COMPLETED, 1), 0));
        repository.replace(state(initial, SettlementSaga.State.PAYMENT_APPLIED, 1), 0);
        var completed = repository.replace(state(initial, SettlementSaga.State.COMPLETED, 2), 1);
        assertThrows(IllegalArgumentException.class,
                () -> repository.replace(state(initial, SettlementSaga.State.PAYMENT_APPLIED, 3), 2));
        assertEquals(completed, new FileSettlementSagaRepository(directory).load(initial.request().sagaId()).orElseThrow());
    }

    @Test void exclusiveScopeHoldsTheFileLockAndReleasesItOnFailure(@TempDir Path directory) throws Exception {
        var initial = initial();
        UUID id = initial.request().sagaId();
        var first = new FileSettlementSagaRepository(directory);
        var second = new FileSettlementSagaRepository(directory);
        assertThrows(IllegalStateException.class, () -> first.withExclusiveSaga(id, () -> {
            second.create(initial);
            assertEquals(initial, second.withExclusiveSaga(id, () -> second.load(id).orElseThrow()));
            assertThrows(IllegalStateException.class,
                    () -> second.withExclusiveSaga(new UUID(0, 1204), () -> null));
            try (var channel = FileChannel.open(directory.resolve(id + ".lock"), StandardOpenOption.WRITE)) {
                assertThrows(OverlappingFileLockException.class, channel::tryLock);
            } catch (java.io.IOException failure) { throw new AssertionError(failure); }
            throw new IllegalStateException("fixture callback failed");
        }));
        try (var channel = FileChannel.open(directory.resolve(id + ".lock"), StandardOpenOption.WRITE);
             var lock = channel.tryLock()) {
            assertNotNull(lock);
        }
        assertEquals(initial, second.withExclusiveSaga(id, () -> second.load(id).orElseThrow()));
    }

    @Test void legacyPaidSagaPreservesBytesAndCannotAdoptTheCurrentProviderAfterExpiry(@TempDir Path directory) throws Exception {
        byte[] fixture;
        try (var input = getClass().getResourceAsStream("/settlement-v1-paid.json")) {
            assertNotNull(input);
            fixture = input.readAllBytes();
        }
        assertEquals("98d09f245dbf71f198d86fe18291ecb872711a7c23122bbe4ac45a9d1426c7e5",
                java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(fixture)));
        UUID id = new UUID(0, 1103);
        Path file = directory.resolve(id + ".settlement.json");
        Files.write(file, fixture);
        var repository = new FileSettlementSagaRepository(directory);
        var loaded = repository.load(id).orElseThrow();
        assertEquals(SettlementSaga.State.PAYMENT_APPLIED, loaded.state());
        assertEquals(1, loaded.revision());
        assertArrayEquals(fixture, Files.readAllBytes(file));
        AtomicInteger refunds = new AtomicInteger();
        AtomicInteger queries = new AtomicInteger();
        var port = new IdempotentSettlementPort() {
            @Override public Capability capability() { return new Capability(true, true, "fixture:legacy-refund"); }
            @Override public RecoveryDescriptor recoveryDescriptor() { return new RecoveryDescriptor(capability(), BINDING); }
            @Override public Observation inspect(SettlementOperation operation) { queries.incrementAndGet(); return Observation.NOT_APPLIED; }
            @Override public Outcome apply(SettlementOperation operation) {
                assertEquals(loaded.request().seller(), operation.source());
                assertEquals(loaded.request().buyer(), operation.target());
                assertEquals(SettlementOperation.Asset.CREDITS, operation.asset());
                refunds.incrementAndGet();
                return Outcome.APPLIED;
            }
        };
        var authorizer = new SettlementAuthorizer() {
            @Override public SettlementPermit authorize(SettlementRequest request, long tick) { throw new AssertionError("No new permit"); }
            @Override public boolean verify(SettlementRequest request, SettlementPermit permit, long tick) { return false; }
        };
        var coordinator = new SettlementSagaCoordinator(authorizer, port, repository);
        assertTrue(repository.authorityBinding(id).isEmpty());
        assertEquals(loaded, coordinator.begin(loaded.request(), 100));
        assertThrows(IllegalStateException.class, () -> coordinator.advance(id, 100));
        assertThrows(IllegalStateException.class, () -> coordinator.advance(id, 101));
        assertEquals(0, queries.get());
        assertEquals(0, refunds.get());
        assertEquals(loaded, repository.load(id).orElseThrow());
        assertArrayEquals(fixture, Files.readAllBytes(file));
        assertEquals(loaded.request(), repository.load(id).orElseThrow().request());
        assertEquals(loaded.permit(), repository.load(id).orElseThrow().permit());
    }

    private SettlementSaga initial() {
        UUID buyer = new UUID(0, 1201), seller = new UUID(0, 1202);
        var request = new SettlementRequest(new UUID(0, 1203), buyer, buyer, seller, 10, "test:ore", 2, 100);
        var permit = new SettlementPermit("fixture", "fixture:integrity", buyer, buyer, seller,
                SettlementRequestCodec.hash(request), 0, 100);
        return new SettlementSaga(1, request, permit, SettlementSaga.State.PREPARED, 0, "");
    }

    private SettlementSaga state(SettlementSaga original, SettlementSaga.State state, long revision) {
        return new SettlementSaga(1, original.request(), original.permit(), state, revision, "");
    }
}
