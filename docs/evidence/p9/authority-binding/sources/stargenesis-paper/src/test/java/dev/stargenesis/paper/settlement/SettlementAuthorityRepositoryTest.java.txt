package dev.stargenesis.paper.settlement;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static dev.stargenesis.integrations.IdempotentSettlementPort.Observation.*;
import static dev.stargenesis.integrations.SettlementOperation.Phase.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real-file persistence of immutable authority/history domains; external assets remain test doubles. */
class SettlementAuthorityRepositoryTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final UUID SAGA = new UUID(0, 6101), BUYER = new UUID(0, 6102),
            SELLER = new UUID(0, 6103), REVIEWER = new UUID(0, 6104);

    @Test void boundInitialStateAndExactAuthoritySurviveReopenWithoutReadRewrites(@TempDir Path directory) throws Exception {
        var repository = new FileSettlementSagaRepository(directory);
        var prepared = prepared(false);
        var binding = binding("primary");
        assertTrue(repository.authorityBinding(SAGA).isEmpty());
        assertEquals(prepared, repository.createBound(prepared, binding));
        byte[] bytes = Files.readAllBytes(file(directory));
        var envelope = JSON.readTree(bytes);
        assertEquals(3, envelope.path("schemaVersion").intValue());
        assertEquals(0, envelope.path("reviewCount").intValue());
        assertTrue(envelope.path("reviews").isEmpty());
        assertEquals(binding, JSON.treeToValue(envelope.path("authorityBinding"), SettlementAuthorityBinding.class));

        var reopened = new FileSettlementSagaRepository(directory);
        assertEquals(prepared, reopened.load(SAGA).orElseThrow());
        assertEquals(binding, reopened.authorityBinding(SAGA).orElseThrow());
        assertEquals(List.of(), reopened.reviews(SAGA));
        assertArrayEquals(bytes, Files.readAllBytes(file(directory)));
    }

    @Test void committedPaymentAcrossAuthorityChangeAndReopenRecoversOnlyFromOriginalHistory(@TempDir Path directory) throws Exception {
        var repository = new FileSettlementSagaRepository(directory);
        var port = new RetainedHistoryPort();
        SettlementAuthorizer authorizer = new SettlementAuthorizer() {
            @Override public SettlementPermit authorize(SettlementRequest request, long tick) {
                return prepared(false).permit();
            }
            @Override public boolean verify(SettlementRequest request, SettlementPermit permit, long tick) { return true; }
        };
        var coordinator = new SettlementSagaCoordinator(authorizer, port, repository);
        var prepared = coordinator.begin(prepared(false).request(), 0);
        byte[] original = Files.readAllBytes(file(directory));
        port.afterApply = () -> port.binding = binding("replacement-history");

        assertThrows(IllegalStateException.class, () -> coordinator.advance(SAGA, 1));
        assertEquals(1, port.effects);
        assertEquals(prepared, repository.load(SAGA).orElseThrow());
        assertEquals(binding("primary"), repository.authorityBinding(SAGA).orElseThrow());
        assertArrayEquals(original, Files.readAllBytes(file(directory)));

        var reopened = new FileSettlementSagaRepository(directory);
        int queries = port.queries;
        var wrongHistory = new SettlementSagaCoordinator(authorizer, port, reopened);
        assertThrows(IllegalStateException.class, () -> wrongHistory.advance(SAGA, 2));
        assertEquals(queries, port.queries, "Reject a different authority/history before external lookup");
        assertEquals(1, port.effects);
        assertArrayEquals(original, Files.readAllBytes(file(directory)));

        port.binding = binding("primary");
        port.evidence = "fixture:new-audit-after-restart";
        port.afterApply = () -> {};
        var recovered = new SettlementSagaCoordinator(authorizer, port, reopened).advance(SAGA, 3);
        assertEquals(SettlementSaga.State.PAYMENT_APPLIED, recovered.state());
        assertEquals(1, recovered.revision());
        assertEquals(queries + 1, port.queries);
        assertEquals(1, port.effects, "Original durable operation lookup must not repeat payment");
        var afterRecovery = new FileSettlementSagaRepository(directory);
        assertEquals(recovered, afterRecovery.load(SAGA).orElseThrow());
        assertEquals(binding("primary"), afterRecovery.authorityBinding(SAGA).orElseThrow());
        assertEquals(3, JSON.readTree(Files.readAllBytes(file(directory))).path("schemaVersion").intValue());
    }

    @Test void ordinaryTransitionsReviewsAndRefundRetainOriginalBinding(@TempDir Path directory) throws Exception {
        var repository = new FileSettlementSagaRepository(directory);
        var binding = binding("primary");
        var prepared = repository.createBound(prepared(false), binding);
        var paid = next(prepared, SettlementSaga.State.PAYMENT_APPLIED, "");
        repository.replace(paid, prepared.revision());
        var pending = next(paid, SettlementSaga.State.MANUAL_REVIEW, "EXTERNAL_OUTCOME_UNKNOWN");
        repository.replace(pending, paid.revision());
        var review = review(pending, 1, Set.of(PAYMENT), APPLIED, NOT_APPLIED, NOT_APPLIED);
        assertEquals(SettlementSaga.State.COMPENSATION_REQUIRED, repository.commitReview(review).after().state());

        var reopened = new FileSettlementSagaRepository(directory);
        assertEquals(binding, reopened.authorityBinding(SAGA).orElseThrow());
        assertEquals(List.of(review), reopened.reviews(SAGA));
        var refunded = next(review.after(), SettlementSaga.State.COMPENSATED, "CURRENCY_REFUNDED");
        reopened.replace(refunded, review.after().revision());
        var afterRefund = new FileSettlementSagaRepository(directory);
        assertEquals(refunded, afterRefund.load(SAGA).orElseThrow());
        assertEquals(binding, afterRefund.authorityBinding(SAGA).orElseThrow());
        assertEquals(List.of(review), afterRefund.reviews(SAGA));
        assertEquals(3, JSON.readTree(Files.readAllBytes(file(directory))).path("schemaVersion").intValue());
        assertEquals(1, JSON.readTree(Files.readAllBytes(file(directory))).path("reviewCount").intValue());
        byte[] bytes = Files.readAllBytes(file(directory));
        assertEquals(review, afterRefund.commitReview(review));
        assertArrayEquals(bytes, Files.readAllBytes(file(directory)));
    }

    @Test void existingBoundSagaCannotBeRelabeledOrRecreatedUnbound(@TempDir Path directory) throws Exception {
        var repository = new FileSettlementSagaRepository(directory);
        var prepared = repository.createBound(prepared(false), binding("primary"));
        byte[] bytes = Files.readAllBytes(file(directory));
        assertThrows(FileSettlementSagaRepository.SagaStorageException.class,
                () -> repository.createBound(prepared, binding("different")));
        assertThrows(FileSettlementSagaRepository.SagaStorageException.class,
                () -> repository.createBound(prepared, binding("primary")));
        assertThrows(FileSettlementSagaRepository.SagaStorageException.class, () -> repository.create(prepared));
        assertEquals(binding("primary"), repository.authorityBinding(SAGA).orElseThrow());
        assertArrayEquals(bytes, Files.readAllBytes(file(directory)));
    }

    @Test void legacyV1AndV2RemainReadableUnboundAndCannotBeAdopted(@TempDir Path directory) throws Exception {
        var prepared = prepared(false);
        var repository = new FileSettlementSagaRepository(directory);
        repository.create(prepared);
        byte[] v1 = Files.readAllBytes(file(directory));
        assertEquals(1, JSON.readTree(v1).path("schemaVersion").intValue());
        var reopened = new FileSettlementSagaRepository(directory);
        assertEquals(prepared, reopened.load(SAGA).orElseThrow());
        assertTrue(reopened.authorityBinding(SAGA).isEmpty());
        assertTrue(reopened.reviews(SAGA).isEmpty());
        assertThrows(FileSettlementSagaRepository.SagaStorageException.class,
                () -> reopened.createBound(prepared, binding("primary")));
        assertArrayEquals(v1, Files.readAllBytes(file(directory)));

        var pending = next(prepared, SettlementSaga.State.MANUAL_REVIEW, "EXTERNAL_OUTCOME_UNKNOWN");
        reopened.replace(pending, 0);
        var review = review(pending, 1, Set.of(), UNKNOWN, UNKNOWN, UNKNOWN);
        reopened.commitReview(review);
        byte[] v2 = Files.readAllBytes(file(directory));
        assertEquals(2, JSON.readTree(v2).path("schemaVersion").intValue());
        assertFalse(JSON.readTree(v2).has("authorityBinding"));
        var afterReview = new FileSettlementSagaRepository(directory);
        assertEquals(review.after(), afterReview.load(SAGA).orElseThrow());
        assertEquals(List.of(review), afterReview.reviews(SAGA));
        assertTrue(afterReview.authorityBinding(SAGA).isEmpty());
        assertThrows(FileSettlementSagaRepository.SagaStorageException.class,
                () -> afterReview.createBound(prepared, binding("primary")));
        assertArrayEquals(v2, Files.readAllBytes(file(directory)));
    }

    @Test void concurrentInitialCreatorsPublishOnlyOneCompleteBinding(@TempDir Path directory) throws Exception {
        var start = new CountDownLatch(1);
        var ready = new CountDownLatch(2);
        var executor = Executors.newFixedThreadPool(2);
        var attempts = new ArrayList<java.util.concurrent.Future<Attempt>>();
        try {
            for (String suffix : List.of("first", "second")) {
                var binding = binding(suffix);
                attempts.add(executor.submit(() -> {
                    var repository = new FileSettlementSagaRepository(directory);
                    ready.countDown();
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Creator start timed out");
                    try {
                        repository.createBound(prepared(false), binding);
                        return new Attempt(true, binding);
                    } catch (FileSettlementSagaRepository.SagaStorageException conflict) {
                        if (!conflict.getMessage().equals("Settlement saga already exists")) throw conflict;
                        return new Attempt(false, binding);
                    }
                }));
            }
            assertTrue(ready.await(10, TimeUnit.SECONDS));
            start.countDown();
            var results = List.of(attempts.get(0).get(10, TimeUnit.SECONDS), attempts.get(1).get(10, TimeUnit.SECONDS));
            assertEquals(1, results.stream().filter(Attempt::created).count());
            var winner = results.stream().filter(Attempt::created).findFirst().orElseThrow();
            var reopened = new FileSettlementSagaRepository(directory);
            assertEquals(prepared(false), reopened.load(SAGA).orElseThrow());
            assertEquals(winner.binding(), reopened.authorityBinding(SAGA).orElseThrow());
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test void failedInitialPublishLeavesNoSagaOrBindingAndCanRetry(@TempDir Path directory) throws Exception {
        var repository = new FileSettlementSagaRepository(directory);
        Path temporary = temporary(directory);
        Files.createDirectory(temporary);
        assertThrows(FileSettlementSagaRepository.SagaStorageException.class,
                () -> repository.createBound(prepared(false), binding("primary")));
        assertFalse(Files.exists(file(directory)));
        assertTrue(repository.load(SAGA).isEmpty());
        assertTrue(repository.authorityBinding(SAGA).isEmpty());
        Files.delete(temporary);
        repository.createBound(prepared(false), binding("primary"));
        assertEquals(binding("primary"), new FileSettlementSagaRepository(directory).authorityBinding(SAGA).orElseThrow());
    }

    @Test void failedTransitionAndReviewPublishPreserveOriginalFileAndBinding(@TempDir Path directory) throws Exception {
        var repository = new FileSettlementSagaRepository(directory);
        var prepared = repository.createBound(prepared(false), binding("primary"));
        var paid = next(prepared, SettlementSaga.State.PAYMENT_APPLIED, "");
        byte[] before = Files.readAllBytes(file(directory));
        Files.createDirectory(temporary(directory));
        assertThrows(FileSettlementSagaRepository.SagaStorageException.class, () -> repository.replace(paid, 0));
        assertArrayEquals(before, Files.readAllBytes(file(directory)));
        assertEquals(binding("primary"), repository.authorityBinding(SAGA).orElseThrow());
        Files.delete(temporary(directory));
        repository.replace(paid, 0);
        var pending = next(paid, SettlementSaga.State.MANUAL_REVIEW, "EXTERNAL_OUTCOME_UNKNOWN");
        repository.replace(pending, 1);
        var review = review(pending, 1, Set.of(PAYMENT), APPLIED, NOT_APPLIED, NOT_APPLIED);
        byte[] beforeReview = Files.readAllBytes(file(directory));
        Files.createDirectory(temporary(directory));
        assertThrows(FileSettlementSagaRepository.SagaStorageException.class, () -> repository.commitReview(review));
        assertArrayEquals(beforeReview, Files.readAllBytes(file(directory)));
        assertTrue(repository.reviews(SAGA).isEmpty());
        assertEquals(pending, repository.load(SAGA).orElseThrow());
        assertEquals(binding("primary"), repository.authorityBinding(SAGA).orElseThrow());
        Files.delete(temporary(directory));
        repository.commitReview(review);
        assertEquals(List.of(review), new FileSettlementSagaRepository(directory).reviews(SAGA));
    }

    @Test void malformedSchemaBindingTypesAndReviewCountsRejectWithoutRewrite(@TempDir Path directory) throws Exception {
        var repository = new FileSettlementSagaRepository(directory);
        repository.createBound(prepared(false), binding("primary"));
        byte[] original = Files.readAllBytes(file(directory));
        List<Consumer<ObjectNode>> corruptions = List.of(
                root -> root.remove("authorityBinding"), root -> root.putNull("authorityBinding"),
                root -> root.put("authorityBinding", "not-an-object"),
                root -> ((ObjectNode) root.path("authorityBinding")).remove("credits"),
                root -> ((ObjectNode) root.path("authorityBinding")).putNull("item"),
                root -> ((ObjectNode) root.path("authorityBinding")).put("extra", true),
                root -> domain(root, "credits").remove("idempotencyHistoryId"),
                root -> domain(root, "item").put("assetAuthorityId", 7),
                root -> domain(root, "item").put("idempotencyHistoryId", false),
                root -> domain(root, "credits").put("assetAuthorityId", " "),
                root -> domain(root, "credits").put("assetAuthorityId", "不应规范化"),
                root -> domain(root, "item").put("idempotencyHistoryId", "a".repeat(129)),
                root -> root.put("schemaVersion", "3"), root -> root.put("schemaVersion", 3.0),
                root -> root.put("schemaVersion", 4), root -> root.put("unknownField", true),
                root -> root.remove("reviews"), root -> root.putNull("reviews"),
                root -> root.remove("reviewCount"), root -> root.putNull("reviewCount"),
                root -> root.put("reviewCount", "0"), root -> root.put("reviewCount", false),
                root -> root.put("reviewCount", 0.0), root -> root.put("reviewCount", -1),
                root -> root.put("reviewCount", 1), root -> root.put("reviewCount", 17),
                root -> ((ObjectNode) root.path("saga")).put("reason", "REVIEW_UNRESOLVED"));
        var allCorruptions = new ArrayList<>(corruptions);
        var nodes = JSON.getNodeFactory();
        List<JsonNode> invalidIdentifierTypes = List.of(nodes.numberNode(7), nodes.booleanNode(false),
                nodes.nullNode(), nodes.arrayNode(), nodes.objectNode());
        for (String asset : List.of("credits", "item")) {
            for (String identifier : List.of("assetAuthorityId", "idempotencyHistoryId")) {
                for (JsonNode invalidType : invalidIdentifierTypes) {
                    allCorruptions.add(root -> domain(root, asset).set(identifier, invalidType.deepCopy()));
                }
            }
        }
        for (int index = 0; index < allCorruptions.size(); index++) {
            ObjectNode root = (ObjectNode) JSON.readTree(original);
            allCorruptions.get(index).accept(root);
            byte[] altered = JSON.writeValueAsBytes(root);
            Files.write(file(directory), altered);
            String diagnostic = "Corruption " + index + ": " + root;
            assertThrows(FileSettlementSagaRepository.SagaStorageException.class, () -> repository.load(SAGA), diagnostic);
            assertThrows(FileSettlementSagaRepository.SagaStorageException.class, () -> repository.authorityBinding(SAGA), diagnostic);
            assertThrows(FileSettlementSagaRepository.SagaStorageException.class, () -> repository.reviews(SAGA), diagnostic);
            assertArrayEquals(altered, Files.readAllBytes(file(directory)));
        }
        Files.write(file(directory), original);
        assertEquals(binding("primary"), repository.authorityBinding(SAGA).orElseThrow());
    }

    @Test void erasedReviewArrayCannotSilentlyTurnBoundHistoryIntoAnInitialFile(@TempDir Path directory) throws Exception {
        var repository = new FileSettlementSagaRepository(directory);
        var prepared = repository.createBound(prepared(false), binding("primary"));
        var pending = next(prepared, SettlementSaga.State.MANUAL_REVIEW, "EXTERNAL_OUTCOME_UNKNOWN");
        repository.replace(pending, 0);
        repository.commitReview(review(pending, 1, Set.of(), UNKNOWN, UNKNOWN, UNKNOWN));
        ObjectNode root = (ObjectNode) JSON.readTree(Files.readAllBytes(file(directory)));
        root.withArray("reviews").removeAll();
        Files.write(file(directory), JSON.writeValueAsBytes(root));
        assertThrows(FileSettlementSagaRepository.SagaStorageException.class, () -> repository.load(SAGA));
        root.put("reviewCount", 0);
        Files.write(file(directory), JSON.writeValueAsBytes(root));
        assertThrows(FileSettlementSagaRepository.SagaStorageException.class, () -> repository.load(SAGA));
    }

    @Test void boundedReadAndReviewWritesRetainLastCompleteBoundSnapshot(@TempDir Path directory) throws Exception {
        var repository = new FileSettlementSagaRepository(directory);
        var prepared = repository.createBound(prepared(true), binding("primary"));
        var pending = next(prepared, SettlementSaga.State.MANUAL_REVIEW, "EXTERNAL_OUTCOME_UNKNOWN");
        repository.replace(pending, 0);
        var committed = new ArrayList<SettlementReview>();
        boolean rejectedAtByteBudget = false;
        for (int index = 1; index <= SettlementReview.MAX_REVIEWS; index++) {
            var candidate = review(pending, index, Set.of(), UNKNOWN, UNKNOWN, UNKNOWN);
            byte[] before = Files.readAllBytes(file(directory));
            try {
                repository.commitReview(candidate);
                committed.add(candidate);
                pending = candidate.after();
            } catch (FileSettlementSagaRepository.SagaStorageException expected) {
                rejectedAtByteBudget = true;
                assertNotNull(expected.getCause());
                assertTrue(expected.getCause().getMessage().contains("64 KiB"));
                assertArrayEquals(before, Files.readAllBytes(file(directory)));
                assertEquals(committed, repository.reviews(SAGA));
                assertEquals(binding("primary"), repository.authorityBinding(SAGA).orElseThrow());
                break;
            }
        }
        assertTrue(rejectedAtByteBudget, "Large legitimate original permits exhaust bytes before history is truncated");
        assertFalse(committed.isEmpty());
        assertTrue(Files.size(file(directory)) <= FileSettlementSagaRepository.MAX_FILE_BYTES);
        byte[] oversized = new byte[(int) FileSettlementSagaRepository.MAX_FILE_BYTES + 1];
        Files.write(file(directory), oversized);
        assertThrows(FileSettlementSagaRepository.SagaStorageException.class, () -> repository.load(SAGA));
        assertThrows(FileSettlementSagaRepository.SagaStorageException.class, () -> repository.authorityBinding(SAGA));
        assertArrayEquals(oversized, Files.readAllBytes(file(directory)));
    }

    private static SettlementSaga prepared(boolean largePermit) {
        var request = new SettlementRequest(SAGA, BUYER, BUYER, SELLER, 25, "test:ore", 2, 100);
        String evidence = largePermit ? String.valueOf((char) 2).repeat(1024) : "fixture:authority";
        var permit = new SettlementPermit(evidence, evidence, BUYER, BUYER, SELLER,
                SettlementRequestCodec.hash(request), 0, 100);
        return new SettlementSaga(1, request, permit, SettlementSaga.State.PREPARED, 0, "");
    }

    private static SettlementSaga next(SettlementSaga before, SettlementSaga.State state, String reason) {
        return new SettlementSaga(1, before.request(), before.permit(), state, before.revision() + 1, reason);
    }

    private static SettlementAuthorityBinding binding(String suffix) {
        return new SettlementAuthorityBinding(
                new SettlementAuthorityBinding.AuthorityDomain("credits:" + suffix, "credits-history:" + suffix),
                new SettlementAuthorityBinding.AuthorityDomain("items:" + suffix, "item-history:" + suffix));
    }

    private static SettlementReview review(SettlementSaga before, int serial, Set<SettlementOperation.Phase> applied,
                                            IdempotentSettlementPort.Observation payment,
                                            IdempotentSettlementPort.Observation delivery,
                                            IdempotentSettlementPort.Observation compensation) {
        var observations = new EnumMap<SettlementOperation.Phase, IdempotentSettlementPort.Observation>(SettlementOperation.Phase.class);
        observations.put(PAYMENT, payment);
        observations.put(DELIVERY, delivery);
        observations.put(COMPENSATION, compensation);
        var state = SettlementReview.resolution(observations, applied);
        var after = next(before, state, SettlementReview.reasonFor(state));
        var request = new SettlementReviewRequest(new UUID(1, serial), SAGA, REVIEWER, before.revision(), "case-" + serial);
        return new SettlementReview(1, request, new SettlementReviewAuthorizer.Authorization("fixture.policy", "fixture:review"),
                "fixture:provider", 200 + serial, before, applied, observations, after);
    }

    private static ObjectNode domain(ObjectNode root, String asset) {
        return (ObjectNode) root.path("authorityBinding").path(asset);
    }

    private static Path file(Path directory) { return directory.resolve(SAGA + ".settlement.json"); }
    private static Path temporary(Path directory) { return directory.resolve(SAGA + ".settlement.json.tmp"); }
    private record Attempt(boolean created, SettlementAuthorityBinding binding) { }

    /** Retains test history across repository reopen; this is not an external provider process restart. */
    private static final class RetainedHistoryPort implements IdempotentSettlementPort {
        SettlementAuthorityBinding binding = binding("primary");
        String evidence = "fixture:initial-audit";
        final Map<String, SettlementOperation> applied = new HashMap<>();
        int queries, effects;
        Runnable afterApply = () -> {};
        @Override public Capability capability() { return new Capability(true, true, evidence); }
        @Override public RecoveryDescriptor recoveryDescriptor() { return new RecoveryDescriptor(capability(), binding); }
        @Override public Observation inspect(SettlementOperation operation) {
            queries++;
            var known = applied.get(operation.idempotencyKey());
            if (known == null) return NOT_APPLIED;
            assertEquals(operation, known, "Operation key must retain the exact original payload");
            return APPLIED;
        }
        @Override public Outcome apply(SettlementOperation operation) {
            assertNull(applied.putIfAbsent(operation.idempotencyKey(), operation), "Duplicate external effect");
            effects++;
            afterApply.run();
            return Outcome.APPLIED;
        }
    }
}
