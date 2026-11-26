package dev.stargenesis.paper.settlement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.stargenesis.integrations.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static dev.stargenesis.integrations.IdempotentSettlementPort.Observation.*;
import static dev.stargenesis.integrations.SettlementOperation.Phase.*;
import static org.junit.jupiter.api.Assertions.*;

class SettlementReviewRecoveryTest {
    private static final UUID BUYER = new UUID(0, 1501), SELLER = new UUID(0, 1502), REVIEWER = new UUID(0, 1504);

    @Test void deliveredTradeReconcilesAfterExpiryAndRetryRetainsOriginalEvidence(@TempDir Path directory) throws Exception {
        var fixture = new Fixture(directory);
        var pending = fixture.pending(DELIVERY);
        fixture.port.apply(SettlementOperation.forPhase(pending.request(), DELIVERY));
        int effects = fixture.port.effects;
        var request = reviewRequest(pending, 1);
        var review = fixture.review().reconcile(request, 200);
        assertEquals(SettlementSaga.State.COMPLETED, review.after().state());
        assertEquals(pending, review.before());
        assertEquals(pending.permit(), review.after().permit());
        assertEquals(java.util.Set.of(PAYMENT), review.previouslyApplied());
        assertEquals(effects, fixture.port.effects);
        assertEquals(List.of(PAYMENT, DELIVERY, COMPENSATION), fixture.port.queries);
        byte[] committed = Files.readAllBytes(fixture.file());
        var restarted = new FileSettlementSagaRepository(directory);
        assertEquals(List.of(review), restarted.reviews(pending.request().sagaId()));
        assertEquals(review.after(), restarted.load(pending.request().sagaId()).orElseThrow());
        assertEquals(review, new SettlementReviewCoordinator(fixture.authorizer, fixture.port, restarted).reconcile(request, 300));
        assertEquals(3, fixture.port.queries.size());
        assertArrayEquals(committed, Files.readAllBytes(fixture.file()));
        assertEquals(review.after(), fixture.automatic.advance(pending.request().sagaId(), 301));
        assertEquals(effects, fixture.port.effects);
    }

    @Test void paidUndeliveredTradeSchedulesOneRefundAndKeepsAuditAcrossAutomaticRecovery(@TempDir Path directory) throws Exception {
        var fixture = new Fixture(directory);
        var pending = fixture.pending(DELIVERY);
        var review = fixture.review().reconcile(reviewRequest(pending, 1), 200);
        assertEquals(SettlementSaga.State.COMPENSATION_REQUIRED, review.after().state());
        assertEquals(1, fixture.port.effects);
        byte[] beforeRefund = Files.readAllBytes(fixture.file());
        Path temporary = fixture.file().resolveSibling(fixture.file().getFileName() + ".tmp");
        Files.createDirectory(temporary);
        assertThrows(FileSettlementSagaRepository.SagaStorageException.class,
                () -> fixture.automatic.advance(pending.request().sagaId(), 201));
        assertEquals(2, fixture.port.effects);
        assertArrayEquals(beforeRefund, Files.readAllBytes(fixture.file()));
        Files.delete(temporary);
        var refunded = fixture.automatic.advance(pending.request().sagaId(), 201);
        assertEquals(SettlementSaga.State.COMPENSATED, refunded.state());
        assertEquals(2, fixture.port.effects);
        assertEquals(0, fixture.port.itemDeliveries);
        var restarted = new FileSettlementSagaRepository(directory);
        assertEquals(List.of(review), restarted.reviews(pending.request().sagaId()));
        assertEquals(refunded, restarted.load(pending.request().sagaId()).orElseThrow());
        assertEquals(refunded, fixture.automatic.advance(pending.request().sagaId(), 202));
        assertEquals(2, fixture.port.effects);
    }

    @Test void aRejectedRefundCanBeReviewedAgainWithoutLosingTheEarlierDecision(@TempDir Path directory) throws Exception {
        var fixture = new Fixture(directory);
        var pending = fixture.pending(DELIVERY);
        var first = fixture.review().reconcile(reviewRequest(pending, 1), 200);
        fixture.port.rejectRefund = true;
        var rejected = fixture.automatic.advance(pending.request().sagaId(), 201);
        assertEquals(SettlementSaga.State.MANUAL_REVIEW, rejected.state());
        assertEquals("COMPENSATION_REJECTED", rejected.reason());
        fixture.port.rejectRefund = false;
        var second = fixture.review().reconcile(reviewRequest(rejected, 2), 202);
        assertEquals(SettlementSaga.State.COMPENSATION_REQUIRED, second.after().state());
        assertEquals(List.of(first, second), new FileSettlementSagaRepository(directory).reviews(pending.request().sagaId()));
        assertEquals(SettlementSaga.State.COMPENSATED, fixture.automatic.advance(pending.request().sagaId(), 203).state());
        assertEquals(List.of(first, second), fixture.repository.reviews(pending.request().sagaId()));
        assertEquals(2, fixture.port.effects);
    }

    @Test void alreadyRefundedTradeClosesWithoutASecondRefund(@TempDir Path directory) throws Exception {
        var fixture = new Fixture(directory);
        var pending = fixture.pending(COMPENSATION);
        fixture.port.apply(SettlementOperation.forPhase(pending.request(), COMPENSATION));
        var review = fixture.review().reconcile(reviewRequest(pending, 1), 200);
        assertEquals(SettlementSaga.State.COMPENSATED, review.after().state());
        assertEquals(2, fixture.port.effects);
        assertEquals(0, fixture.port.itemDeliveries);
    }

    @Test void threeAuthoritativeAbsentResultsCloseAnUnpaidTradeWithoutSideEffects(@TempDir Path directory) throws Exception {
        var fixture = new Fixture(directory);
        var pending = fixture.pending(PAYMENT);
        var review = fixture.review().reconcile(reviewRequest(pending, 1), 200);
        assertEquals(SettlementSaga.State.FAILED, review.after().state());
        assertEquals(0, fixture.port.effects);
        assertTrue(review.previouslyApplied().isEmpty());
    }

    @Test void unknownNullExceptionAndLinkageFailureStayInReviewWithTheirEvidence(@TempDir Path directory) throws Exception {
        for (String failure : List.of("unknown", "null", "exception", "linkage")) {
            var fixture = new Fixture(directory.resolve(failure));
            var pending = fixture.pending(PAYMENT);
            fixture.port.failure = failure;
            var review = fixture.review().reconcile(reviewRequest(pending, 1), 200);
            assertEquals(SettlementSaga.State.MANUAL_REVIEW, review.after().state());
            assertEquals("REVIEW_UNRESOLVED", review.after().reason());
            assertEquals(UNKNOWN, review.observations().get(DELIVERY));
            assertEquals(0, fixture.port.effects);
            assertEquals(List.of(review), new FileSettlementSagaRepository(fixture.directory).reviews(pending.request().sagaId()));
        }
    }

    @Test void aPreviouslyConfirmedPaymentCannotBeErasedByAConflictingLookup(@TempDir Path directory) throws Exception {
        var fixture = new Fixture(directory);
        var pending = fixture.pending(DELIVERY);
        fixture.port.forced.put(PAYMENT, NOT_APPLIED);
        var review = fixture.review().reconcile(reviewRequest(pending, 1), 200);
        assertEquals(SettlementSaga.State.MANUAL_REVIEW, review.after().state());
        fixture.port.forced.clear();
        var resolved = fixture.review().reconcile(reviewRequest(review.after(), 2), 201);
        assertEquals(SettlementSaga.State.COMPENSATION_REQUIRED, resolved.after().state());
        assertEquals(1, fixture.port.effects);
        assertEquals(List.of(review, resolved), fixture.repository.reviews(pending.request().sagaId()));
    }

    @Test void contradictoryDeliveryAndRefundResultsRemainQuarantinedEvenIfLaterLookupDropsOne(@TempDir Path directory) throws Exception {
        var fixture = new Fixture(directory);
        var pending = fixture.pending(COMPENSATION);
        fixture.port.rejectDelivery = false;
        fixture.port.apply(SettlementOperation.forPhase(pending.request(), DELIVERY));
        fixture.port.apply(SettlementOperation.forPhase(pending.request(), COMPENSATION));
        var review = fixture.review().reconcile(reviewRequest(pending, 1), 200);
        assertEquals(SettlementSaga.State.MANUAL_REVIEW, review.after().state());
        fixture.port.forced.put(DELIVERY, NOT_APPLIED);
        var second = fixture.review().reconcile(reviewRequest(review.after(), 2), 201);
        assertEquals(SettlementSaga.State.MANUAL_REVIEW, second.after().state());
        assertEquals(java.util.Set.of(PAYMENT, DELIVERY, COMPENSATION), second.previouslyApplied());
        assertEquals(3, fixture.port.effects);
    }

    @Test void participantsDeniedAuthorizationAndNullEvidenceCannotReadProviderOrWrite(@TempDir Path directory) throws Exception {
        var fixture = new Fixture(directory);
        var pending = fixture.pending(PAYMENT);
        byte[] original = Files.readAllBytes(fixture.file());
        for (var participant : List.of(BUYER, SELLER)) {
            var request = new SettlementReviewRequest(UUID.randomUUID(), pending.request().sagaId(), participant, pending.revision(), "case-1");
            assertThrows(SecurityException.class, () -> fixture.review().reconcile(request, 200));
        }
        SettlementReviewAuthorizer denied = (saga, request, tick) -> { throw new SecurityException("Review permission revoked"); };
        assertThrows(SecurityException.class, () -> new SettlementReviewCoordinator(denied, fixture.port, fixture.repository)
                .reconcile(reviewRequest(pending, 1), 200));
        assertThrows(NullPointerException.class, () -> new SettlementReviewCoordinator((saga, request, tick) -> null,
                fixture.port, fixture.repository).reconcile(reviewRequest(pending, 1), 200));
        assertArrayEquals(original, Files.readAllBytes(fixture.file()));
        assertTrue(fixture.port.queries.isEmpty());
        assertTrue(fixture.repository.reviews(pending.request().sagaId()).isEmpty());
    }

    @Test void staleUnknownAndReusedReviewRequestsFailWithoutChangingEvidence(@TempDir Path directory) throws Exception {
        var fixture = new Fixture(directory);
        var pending = fixture.pending(PAYMENT);
        var valid = reviewRequest(pending, 1);
        var stale = new SettlementReviewRequest(valid.reviewId(), valid.sagaId(), REVIEWER, pending.revision() + 1, "case-1");
        assertThrows(IllegalStateException.class, () -> fixture.review().reconcile(stale, 200));
        assertThrows(IllegalArgumentException.class, () -> fixture.review().reconcile(new SettlementReviewRequest(
                valid.reviewId(), UUID.randomUUID(), REVIEWER, 1, "case-1"), 200));
        assertTrue(fixture.port.queries.isEmpty());
        fixture.review().reconcile(valid, 200);
        byte[] original = Files.readAllBytes(fixture.file());
        for (var changed : List.of(
                new SettlementReviewRequest(valid.reviewId(), valid.sagaId(), REVIEWER, valid.expectedRevision(), "different-case"),
                new SettlementReviewRequest(valid.reviewId(), valid.sagaId(), new UUID(0, 1599), valid.expectedRevision(), valid.caseReference())))
            assertThrows(IllegalArgumentException.class, () -> fixture.review().reconcile(changed, 201));
        assertThrows(SecurityException.class, () -> new SettlementReviewCoordinator((saga, request, tick) -> {
            throw new SecurityException("Revoked since original review");
        }, fixture.port, fixture.repository).reconcile(valid, 201));
        assertEquals(3, fixture.port.queries.size());
        assertArrayEquals(original, Files.readAllBytes(fixture.file()));
    }

    @Test void oldRepositoryAndOrdinaryReplaceCannotBypassReviewEvidence(@TempDir Path directory) throws Exception {
        var fixture = new Fixture(directory);
        var pending = fixture.pending(PAYMENT);
        var oldRepository = new SettlementSagaRepository() {
            @Override public <T> T withExclusiveSaga(UUID id, Supplier<T> action) { return fixture.repository.withExclusiveSaga(id, action); }
            @Override public Optional<SettlementSaga> load(UUID id) { return fixture.repository.load(id); }
            @Override public SettlementSaga create(SettlementSaga saga) { throw new AssertionError(); }
            @Override public SettlementSaga replace(SettlementSaga saga, long revision) { throw new AssertionError(); }
        };
        assertThrows(UnsupportedOperationException.class, () -> new SettlementReviewCoordinator(fixture.authorizer, fixture.port, oldRepository)
                .reconcile(reviewRequest(pending, 1), 200));
        var forced = new SettlementSaga(1, pending.request(), pending.permit(), SettlementSaga.State.FAILED, pending.revision() + 1, "forced");
        assertThrows(IllegalArgumentException.class, () -> fixture.repository.replace(forced, pending.revision()));
        assertEquals(pending, fixture.repository.load(pending.request().sagaId()).orElseThrow());
        assertTrue(fixture.port.queries.isEmpty());
    }

    @Test void failedFileWritePreservesLegacyBytesAndRetryCommitsStateAndAuditTogether(@TempDir Path directory) throws Exception {
        var fixture = new Fixture(directory);
        var pending = fixture.pending(DELIVERY);
        byte[] original = Files.readAllBytes(fixture.file());
        assertEquals(1, new ObjectMapper().readTree(original).path("schemaVersion").intValue());
        Path temporary = fixture.file().resolveSibling(fixture.file().getFileName() + ".tmp");
        Files.createDirectory(temporary);
        var request = reviewRequest(pending, 1);
        assertThrows(FileSettlementSagaRepository.SagaStorageException.class, () -> fixture.review().reconcile(request, 200));
        assertArrayEquals(original, Files.readAllBytes(fixture.file()));
        assertTrue(fixture.repository.reviews(pending.request().sagaId()).isEmpty());
        Files.delete(temporary);
        var review = fixture.review().reconcile(request, 201);
        assertEquals(2, new ObjectMapper().readTree(Files.readAllBytes(fixture.file())).path("schemaVersion").intValue());
        assertEquals(review.after(), fixture.repository.load(request.sagaId()).orElseThrow());
        assertEquals(List.of(review), fixture.repository.reviews(request.sagaId()));
        assertEquals(1, fixture.port.effects);
    }

    @Test void lostResponseAfterCommitRetriesTheOriginalAuditWithoutRepeatingQueries(@TempDir Path directory) throws Exception {
        var fixture = new Fixture(directory);
        var pending = fixture.pending(DELIVERY);
        AtomicBoolean fail = new AtomicBoolean(true);
        var repository = new ForwardingRepository(fixture.repository) {
            @Override public SettlementReview commitReview(SettlementReview review) {
                var result = super.commitReview(review);
                if (fail.getAndSet(false)) throw new IllegalStateException("Response lost after atomic commit");
                return result;
            }
        };
        var coordinator = new SettlementReviewCoordinator(fixture.authorizer, fixture.port, repository);
        var request = reviewRequest(pending, 1);
        assertThrows(IllegalStateException.class, () -> coordinator.reconcile(request, 200));
        byte[] original = Files.readAllBytes(fixture.file());
        var result = coordinator.reconcile(request, 300);
        assertEquals(200, result.logicalTick());
        assertEquals(3, fixture.port.queries.size());
        assertArrayEquals(original, Files.readAllBytes(fixture.file()));
        assertEquals(List.of(result), fixture.repository.reviews(request.sagaId()));
    }

    @Test void twoReviewersCannotCommitConflictingResolutionsForOneRevision(@TempDir Path directory) throws Exception {
        var fixture = new Fixture(directory);
        var pending = fixture.pending(PAYMENT);
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var attempts = new ArrayList<java.util.concurrent.Future<Boolean>>();
            for (int index = 1; index <= 2; index++) {
                var base = reviewRequest(pending, index);
                var request = new SettlementReviewRequest(base.reviewId(), base.sagaId(), new UUID(0, 1503 + index),
                        base.expectedRevision(), base.caseReference());
                attempts.add(executor.submit(() -> {
                    var coordinator = new SettlementReviewCoordinator(fixture.authorizer, fixture.port, new FileSettlementSagaRepository(directory));
                    ready.countDown();
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    try { coordinator.reconcile(request, 200); return true; }
                    catch (IllegalStateException conflict) { return false; }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            int successes = 0;
            for (var attempt : attempts) if (attempt.get(10, TimeUnit.SECONDS)) successes++;
            assertEquals(1, successes);
            assertEquals(1, fixture.repository.reviews(pending.request().sagaId()).size());
            assertEquals(3, fixture.port.queries.size());
        } finally { start.countDown(); }
    }

    @Test void reconciliationAndAutomaticRefundUseTheSameExclusiveScope(@TempDir Path directory) throws Exception {
        var fixture = new Fixture(directory);
        var pending = fixture.pending(DELIVERY);
        CountDownLatch inspecting = new CountDownLatch(1), release = new CountDownLatch(1), advancing = new CountDownLatch(1);
        fixture.port.beforeQuery = () -> {
            inspecting.countDown();
            try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
            catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            var review = executor.submit(() -> fixture.review().reconcile(reviewRequest(pending, 1), 200));
            assertTrue(inspecting.await(10, TimeUnit.SECONDS));
            var advance = executor.submit(() -> {
                advancing.countDown();
                return new SettlementSagaCoordinator(fixture.tradeAuthorizer, fixture.port,
                        new FileSettlementSagaRepository(directory)).advance(pending.request().sagaId(), 201);
            });
            assertTrue(advancing.await(10, TimeUnit.SECONDS));
            try {
                assertThrows(java.util.concurrent.TimeoutException.class, () -> advance.get(100, TimeUnit.MILLISECONDS));
                assertEquals(1, fixture.port.effects);
            } finally { release.countDown(); }
            assertEquals(SettlementSaga.State.COMPENSATION_REQUIRED, review.get(10, TimeUnit.SECONDS).after().state());
            assertEquals(SettlementSaga.State.COMPENSATED, advance.get(10, TimeUnit.SECONDS).state());
            assertEquals(2, fixture.port.effects);
        } finally { release.countDown(); }
    }

    @Test void malformedSnapshotsObservationsIdentitiesAndAuditOrderingAreRejected(@TempDir Path directory) throws Exception {
        var fixture = new Fixture(directory);
        var pending = fixture.pending(PAYMENT);
        fixture.port.forced.put(DELIVERY, UNKNOWN);
        var first = fixture.review().reconcile(reviewRequest(pending, 1), 200);
        fixture.port.forced.clear();
        fixture.review().reconcile(reviewRequest(first.after(), 2), 201);
        byte[] original = Files.readAllBytes(fixture.file());
        var mapper = new ObjectMapper();
        List<Consumer<ObjectNode>> corruptions = List.of(
                root -> ((ObjectNode) root.path("saga")).put("state", "COMPLETED"),
                root -> ((ObjectNode) root.path("reviews").get(0).path("after")).put("state", "FAILED"),
                root -> ((ObjectNode) root.path("reviews").get(0).path("observations")).put("DELIVERY", "NOT_APPLIED"),
                root -> ((ObjectNode) root.path("reviews").get(0).path("request")).put("reviewer", BUYER.toString()),
                root -> ((ObjectNode) root.path("reviews").get(0).path("request")).put("expectedRevision", 99),
                root -> ((ObjectNode) root.path("reviews").get(0)).putNull("authorization"),
                root -> ((ObjectNode) root.path("reviews").get(1)).put("logicalTick", 199),
                root -> ((com.fasterxml.jackson.databind.node.ArrayNode) root.path("reviews")).remove(0),
                root -> ((com.fasterxml.jackson.databind.node.ArrayNode) root.path("reviews")).add(root.path("reviews").get(0).deepCopy()),
                root -> ((com.fasterxml.jackson.databind.node.ArrayNode) root.path("reviews")).removeAll(),
                root -> root.put("schemaVersion", "2"),
                root -> root.put("schemaVersion", 99),
                root -> root.put("unrecognized", true));
        for (var corruption : corruptions) {
            ObjectNode root = (ObjectNode) mapper.readTree(original);
            corruption.accept(root);
            byte[] corrupt = mapper.writeValueAsBytes(root);
            Files.write(fixture.file(), corrupt);
            assertThrows(FileSettlementSagaRepository.SagaStorageException.class, () -> fixture.repository.load(pending.request().sagaId()));
            assertThrows(FileSettlementSagaRepository.SagaStorageException.class, () -> fixture.repository.reviews(pending.request().sagaId()));
            assertArrayEquals(corrupt, Files.readAllBytes(fixture.file()));
        }
        Files.write(fixture.file(), original);
        assertEquals(2, fixture.repository.reviews(pending.request().sagaId()).size());
    }

    @Test void boundedAuditHistoryStopsBeforeAnotherLookupAndNeverPrunesEvidence(@TempDir Path directory) throws Exception {
        var fixture = new Fixture(directory);
        var pending = fixture.pending(PAYMENT);
        fixture.port.forced.put(PAYMENT, UNKNOWN);
        for (int index = 0; index < SettlementReview.MAX_REVIEWS; index++)
            pending = fixture.review().reconcile(reviewRequest(pending, index), 200 + index).after();
        var next = reviewRequest(pending, 99);
        int queries = fixture.port.queries.size();
        byte[] original = Files.readAllBytes(fixture.file());
        assertTrue(original.length <= FileSettlementSagaRepository.MAX_FILE_BYTES);
        assertThrows(IllegalStateException.class, () -> fixture.review().reconcile(next, 300));
        assertEquals(queries, fixture.port.queries.size());
        assertEquals(SettlementReview.MAX_REVIEWS, fixture.repository.reviews(next.sagaId()).size());
        assertArrayEquals(original, Files.readAllBytes(fixture.file()));
        assertEquals(0, fixture.port.effects);
    }

    @Test void providerCapabilityLossAndUnknownLegacyReviewOriginFailClosed(@TempDir Path directory) throws Exception {
        var fixture = new Fixture(directory);
        var pending = fixture.pending(PAYMENT);
        byte[] original = Files.readAllBytes(fixture.file());
        fixture.port.supported = false;
        assertThrows(IllegalStateException.class, () -> fixture.review().reconcile(reviewRequest(pending, 1), 200));
        assertArrayEquals(original, Files.readAllBytes(fixture.file()));
        assertTrue(fixture.port.queries.isEmpty());
        fixture.port.supported = true;
        Files.writeString(fixture.file(), new String(original, java.nio.charset.StandardCharsets.UTF_8)
                .replace("EXTERNAL_OUTCOME_UNKNOWN", "unrecognized legacy stop"));
        var unsupported = fixture.repository.load(pending.request().sagaId()).orElseThrow();
        assertThrows(IllegalArgumentException.class, () -> fixture.review().reconcile(reviewRequest(unsupported, 1), 200));
        assertTrue(fixture.port.queries.isEmpty());
    }

    @Test void reviewClockAndTextBudgetsRejectBeforeQueriesOrWrites(@TempDir Path directory) throws Exception {
        var fixture = new Fixture(directory);
        var pending = fixture.pending(PAYMENT);
        for (String invalid : List.of("", " ", "x".repeat(257), "case\nforged-entry"))
            assertThrows(IllegalArgumentException.class, () -> new SettlementReviewRequest(UUID.randomUUID(),
                    pending.request().sagaId(), REVIEWER, pending.revision(), invalid));
        fixture.port.forced.put(PAYMENT, UNKNOWN);
        var first = fixture.review().reconcile(reviewRequest(pending, 1), 200);
        byte[] original = Files.readAllBytes(fixture.file());
        assertThrows(IllegalArgumentException.class, () -> fixture.review().reconcile(reviewRequest(first.after(), 2), 199));
        assertThrows(IllegalArgumentException.class, () -> fixture.review().reconcile(reviewRequest(first.after(), 2), -1));
        assertEquals(3, fixture.port.queries.size());
        assertArrayEquals(original, Files.readAllBytes(fixture.file()));
    }

    @Test void reviewByteBudgetFailsWithoutTruncatingHistoryOrOverwritingPriorSnapshot(@TempDir Path directory) throws Exception {
        var fixture = new Fixture(directory);
        var pending = fixture.pending(PAYMENT);
        fixture.port.forced.put(PAYMENT, UNKNOWN);
        // Legal legacy authorization fields can occupy their full JSON-escaped size.
        var mapper = new ObjectMapper();
        ObjectNode originalJson = (ObjectNode) mapper.readTree(Files.readAllBytes(fixture.file()));
        ObjectNode permit = (ObjectNode) originalJson.path("saga").path("permit");
        permit.put("policyId", String.valueOf((char) 1).repeat(1024));
        permit.put("evidenceId", String.valueOf((char) 2).repeat(1024));
        Files.write(fixture.file(), mapper.writeValueAsBytes(originalJson));
        pending = fixture.repository.load(pending.request().sagaId()).orElseThrow();
        boolean exhausted = false;
        for (int index = 0; index < SettlementReview.MAX_REVIEWS; index++) {
            byte[] before = Files.readAllBytes(fixture.file());
            var history = fixture.repository.reviews(pending.request().sagaId());
            try {
                pending = fixture.review().reconcile(reviewRequest(pending, index), 200 + index).after();
            } catch (FileSettlementSagaRepository.SagaStorageException capacity) {
                assertTrue(capacity.getCause().getMessage().contains("64 KiB"));
                assertArrayEquals(before, Files.readAllBytes(fixture.file()));
                assertEquals(history, fixture.repository.reviews(pending.request().sagaId()));
                assertEquals(pending, fixture.repository.load(pending.request().sagaId()).orElseThrow());
                exhausted = true;
                break;
            }
        }
        assertTrue(exhausted, "Large valid legacy fields must hit the byte limit before the count limit");
        assertEquals(0, fixture.port.effects);
    }

    @Test void legacyManualReviewFixtureLoadsUnchangedAndReconcilesWithTheOriginalPermit(@TempDir Path directory) throws Exception {
        byte[] fixture;
        try (var input = getClass().getResourceAsStream("/settlement-v1-manual.json")) {
            assertNotNull(input);
            fixture = input.readAllBytes();
        }
        assertEquals("220cf5a906cc946277866efefa32b9cb050876956ab26b8a2dcecd24d7dbcee3",
                java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(fixture)));
        var id = new UUID(0, 1103);
        var file = directory.resolve(id + ".settlement.json");
        Files.write(file, fixture);
        var repository = new FileSettlementSagaRepository(directory);
        var stopped = repository.load(id).orElseThrow();
        assertEquals(SettlementSaga.State.MANUAL_REVIEW, stopped.state());
        assertEquals(2, stopped.revision());
        assertTrue(repository.reviews(id).isEmpty());
        assertArrayEquals(fixture, Files.readAllBytes(file));
        var port = new Port();
        port.request = stopped.request();
        port.apply(SettlementOperation.forPhase(stopped.request(), PAYMENT));
        var coordinator = new SettlementReviewCoordinator((saga, request, tick) ->
                new SettlementReviewAuthorizer.Authorization("fixture:legacy-review", "fixture:legacy-case"), port, repository);
        var review = coordinator.reconcile(reviewRequest(stopped, 1), 200);
        assertEquals(SettlementSaga.State.COMPENSATION_REQUIRED, review.after().state());
        assertEquals(stopped.request(), review.after().request());
        assertEquals(stopped.permit(), review.after().permit());
        assertEquals(1, port.effects);
        assertEquals(List.of(review), new FileSettlementSagaRepository(directory).reviews(id));
    }

    @Test void capabilityWithdrawalDuringReviewPreservesExactStoredBytesAndCanRetry(@TempDir Path directory) throws Exception {
        var fixture = new Fixture(directory);
        var before = fixture.pending(PAYMENT);
        byte[] original = Files.readAllBytes(fixture.file());
        fixture.port.beforeQuery = () -> { if (fixture.port.queries.size() == 1) fixture.port.supported = false; };
        var request = reviewRequest(before, 91);
        assertThrows(IllegalStateException.class, () -> fixture.review().reconcile(request, 200));
        assertArrayEquals(original, Files.readAllBytes(fixture.file()));
        var restarted = new FileSettlementSagaRepository(directory);
        assertEquals(before, restarted.load(before.request().sagaId()).orElseThrow());
        assertTrue(restarted.reviews(before.request().sagaId()).isEmpty());
        assertEquals(2, fixture.port.queries.size());
        fixture.port.supported = true;
        fixture.port.beforeQuery = () -> {};
        var review = new SettlementReviewCoordinator(fixture.authorizer, fixture.port, restarted).reconcile(request, 201);
        assertEquals(SettlementSaga.State.FAILED, review.after().state());
        assertEquals(List.of(review), new FileSettlementSagaRepository(directory).reviews(request.sagaId()));
        assertEquals(0, fixture.port.effects);
    }

    @Test void capabilityWithdrawalAfterPaymentPreservesFileAndRestartOnlyLooksUpThePayment(@TempDir Path directory) throws Exception {
        var fixture = new Fixture(directory);
        var request = new SettlementRequest(new UUID(0, 1591), BUYER, BUYER, SELLER, 10, "test:ore", 2, 100);
        fixture.port.request = request;
        var before = fixture.automatic.begin(request, 0);
        byte[] original = Files.readAllBytes(fixture.file());
        fixture.port.afterApply = () -> fixture.port.supported = false;
        assertThrows(IllegalStateException.class, () -> fixture.automatic.advance(request.sagaId(), 1));
        assertEquals(1, fixture.port.effects);
        assertArrayEquals(original, Files.readAllBytes(fixture.file()));
        var restarted = new FileSettlementSagaRepository(directory);
        assertEquals(before, restarted.load(request.sagaId()).orElseThrow());
        fixture.port.supported = true;
        fixture.port.afterApply = () -> {};
        var recovery = new SettlementSagaCoordinator(fixture.tradeAuthorizer, fixture.port, restarted);
        assertEquals(SettlementSaga.State.PAYMENT_APPLIED, recovery.advance(request.sagaId(), 2).state());
        assertEquals(1, fixture.port.effects);
        assertEquals(SettlementSaga.State.COMPLETED, recovery.advance(request.sagaId(), 3).state());
        assertEquals(2, fixture.port.effects);
        assertEquals(1, fixture.port.itemDeliveries);
    }

    private static SettlementReviewRequest reviewRequest(SettlementSaga saga, long id) {
        return new SettlementReviewRequest(new UUID(0, 1600 + id), saga.request().sagaId(), REVIEWER, saga.revision(), "case-" + id);
    }

    private static final class Fixture {
        private final Path directory;
        private final FileSettlementSagaRepository repository;
        private final Port port = new Port();
        private final SettlementAuthorizer tradeAuthorizer = new SettlementAuthorizer() {
            @Override public SettlementPermit authorize(SettlementRequest request, long tick) {
                return new SettlementPermit("fixture", "fixture:trade", BUYER, BUYER, SELLER, SettlementRequestCodec.hash(request), tick, 100);
            }
            @Override public boolean verify(SettlementRequest request, SettlementPermit permit, long tick) { return tick < 100; }
        };
        private final SettlementReviewAuthorizer authorizer = (saga, request, tick) -> {
            assertEquals(saga.revision(), request.expectedRevision());
            return new SettlementReviewAuthorizer.Authorization("fixture:review-policy", "fixture:case:" + request.reviewId());
        };
        private final SettlementSagaCoordinator automatic;

        private Fixture(Path directory) throws Exception {
            this.directory = directory;
            repository = new FileSettlementSagaRepository(directory);
            automatic = new SettlementSagaCoordinator(tradeAuthorizer, port, repository);
        }

        private SettlementSaga pending(SettlementOperation.Phase phase) {
            var request = new SettlementRequest(new UUID(0, 1503), BUYER, BUYER, SELLER, 10, "test:ore", 2, 100);
            port.request = request;
            automatic.begin(request, 0);
            if (phase != PAYMENT) automatic.advance(request.sagaId(), 1);
            if (phase == COMPENSATION) { port.rejectDelivery = true; automatic.advance(request.sagaId(), 2); }
            port.forced.put(phase, UNKNOWN);
            var pending = automatic.advance(request.sagaId(), 3);
            assertEquals(SettlementSaga.State.MANUAL_REVIEW, pending.state());
            port.forced.clear();
            port.queries.clear();
            return pending;
        }

        private SettlementReviewCoordinator review() { return new SettlementReviewCoordinator(authorizer, port, repository); }
        private Path file() { return directory.resolve(port.request.sagaId() + ".settlement.json"); }
    }

    private static final class Port implements IdempotentSettlementPort {
        private SettlementRequest request;
        private final Map<String, SettlementOperation> applied = new HashMap<>();
        private final EnumMap<SettlementOperation.Phase, Observation> forced = new EnumMap<>(SettlementOperation.Phase.class);
        private final List<SettlementOperation.Phase> queries = new ArrayList<>();
        private String failure = "";
        private int effects, itemDeliveries;
        private boolean rejectDelivery, rejectRefund, supported = true;
        private Runnable beforeQuery = () -> { };
        private Runnable afterApply = () -> { };

        @Override public Capability capability() { return new Capability(supported, supported, "fixture:authoritative-operations"); }
        @Override public Observation inspect(SettlementOperation operation) {
            beforeQuery.run();
            var phase = phase(operation);
            queries.add(phase);
            if (phase == DELIVERY) {
                switch (failure) {
                    case "null" -> { return null; }
                    case "unknown" -> { return UNKNOWN; }
                    case "exception" -> throw new IllegalStateException("Provider unavailable");
                    case "linkage" -> throw new NoClassDefFoundError("Provider unloaded");
                }
            }
            if (forced.containsKey(phase)) return forced.get(phase);
            var previous = applied.get(operation.idempotencyKey());
            if (previous == null) return NOT_APPLIED;
            assertEquals(previous, operation);
            return APPLIED;
        }

        @Override public Outcome apply(SettlementOperation operation) {
            var phase = phase(operation);
            if (phase == DELIVERY && rejectDelivery) return Outcome.NO_EFFECT;
            if (phase == COMPENSATION && rejectRefund) return Outcome.NO_EFFECT;
            var previous = applied.putIfAbsent(operation.idempotencyKey(), operation);
            if (previous != null) assertEquals(previous, operation);
            else { effects++; if (phase == DELIVERY) itemDeliveries++; }
            afterApply.run();
            return Outcome.APPLIED;
        }

        private SettlementOperation.Phase phase(SettlementOperation operation) {
            for (var phase : SettlementOperation.Phase.values())
                if (SettlementOperation.forPhase(request, phase).equals(operation)) return phase;
            throw new AssertionError("Unexpected complete operation payload: " + operation);
        }
    }

    private static class ForwardingRepository implements SettlementSagaRepository {
        private final SettlementSagaRepository delegate;
        private ForwardingRepository(SettlementSagaRepository delegate) { this.delegate = delegate; }
        @Override public <T> T withExclusiveSaga(UUID id, Supplier<T> action) { return delegate.withExclusiveSaga(id, action); }
        @Override public Optional<SettlementSaga> load(UUID id) { return delegate.load(id); }
        @Override public SettlementSaga create(SettlementSaga saga) { return delegate.create(saga); }
        @Override public SettlementSaga replace(SettlementSaga saga, long revision) { return delegate.replace(saga, revision); }
        @Override public List<SettlementReview> reviews(UUID id) { return delegate.reviews(id); }
        @Override public SettlementReview commitReview(SettlementReview review) { return delegate.commitReview(review); }
    }
}
