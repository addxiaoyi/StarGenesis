package dev.stargenesis.integrations;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

import static dev.stargenesis.integrations.NationResourceSettlementGateway.*;
import static dev.stargenesis.integrations.SettlementCheckpoint.State.*;
import static dev.stargenesis.integrations.SettlementGateway.Observation.*;
import static dev.stargenesis.integrations.SettlementGateway.Operation.*;
import static dev.stargenesis.integrations.SettlementGateway.Result;
import static org.junit.jupiter.api.Assertions.*;

class NationResourceSettlementGatewayTest {
    private static final String DOMAIN = "nation-ledger:test-1";
    private static final String KEY = "test:apply:0";
    private static final String IRON = "ore:iron";
    private static final String COPPER = "ore:copper";
    private static final UUID SOURCE = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID TARGET = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID THIRD = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000004");
    private static final UUID SAGA = UUID.fromString("00000000-0000-0000-0000-000000000005");
    private static final SettlementTransfer TRANSFER = transfer(SOURCE, TARGET, 10);

    @Test
    void exactReceiptsMakeApplyAndInverseCompensationIdempotent() throws Exception {
        LedgerState state = new LedgerState();
        Ledger provider = new Ledger(DOMAIN, state);
        NationResourceSettlementGateway gateway = gateway(provider);

        assertEquals(NOT_APPLIED, gateway.observe(KEY, TRANSFER, APPLY));
        assertEquals(Result.APPLIED, gateway.transfer(KEY, TRANSFER));
        assertEquals(Result.APPLIED, gateway.transfer(KEY, TRANSFER));
        assertEquals(1, state.writes);
        assertEquals(90, state.balance(SOURCE));
        assertEquals(10, state.balance(TARGET));
        assertEquals(APPLIED, gateway.observe(KEY, TRANSFER, APPLY));
        assertEquals(UNKNOWN, gateway.observe(KEY, TRANSFER, COMPENSATE));
        assertEquals(Result.UNKNOWN, gateway.transfer(KEY, transfer(SOURCE, TARGET, 11)));
        assertEquals(1, state.writes, "A conflicting key must not reach the provider write");

        String inverseKey = "test:compensate:0";
        assertEquals(Result.APPLIED, gateway.compensate(inverseKey, TRANSFER));
        assertEquals(Result.APPLIED, gateway.compensate(inverseKey, TRANSFER));
        assertEquals(APPLIED, gateway.observe(inverseKey, TRANSFER, COMPENSATE));
        assertEquals(UNKNOWN, gateway.observe(inverseKey, TRANSFER, APPLY));
        Request inverse = state.requests.get(inverseKey);
        assertEquals(TARGET, inverse.sourceNation());
        assertEquals(SOURCE, inverse.targetNation());
        assertEquals(10, inverse.amount());
        assertEquals(2, state.writes);
        assertEquals(100, state.balance(SOURCE));
        assertEquals(0, state.balance(TARGET));
    }

    @Test
    void everyReceiptFieldIsBoundToTheExactExpectedRequest() throws Exception {
        StubProvider provider = new StubProvider();
        NationResourceSettlementGateway gateway = gateway(provider);
        NationResourceReceipt valid = receipt(request());
        provider.answer = applied(valid);
        assertEquals(APPLIED, gateway.observe(KEY, TRANSFER, APPLY));

        String[] fields = {"key", "source", "target", "resource", "amount", "status", "category", "description", "domain"};
        Object[] mismatches = {"other-key", THIRD, THIRD, COPPER, 11L,
                NationResourceResult.ALREADY_APPLIED, "TRADE", "different description", "another-ledger"};
        for (int index = 0; index < fields.length; index++) {
            String field = fields[index];
            provider.answer = applied(receiptWith(valid, field, mismatches[index]));
            assertEquals(UNKNOWN, gateway.observe(KEY, TRANSFER, APPLY), field);
            assertEquals(Result.UNKNOWN, gateway.transfer(KEY, TRANSFER), field);
            if (!field.equals("amount")) {
                provider.answer = applied(receiptWith(valid, field, null));
                assertEquals(UNKNOWN, gateway.observe(KEY, TRANSFER, APPLY), "null " + field);
                assertEquals(Result.UNKNOWN, gateway.transfer(KEY, TRANSFER), "null " + field);
            }
        }
        for (long amount : new long[]{0, -1, SettlementTransfer.MAX_AMOUNT + 1}) {
            provider.answer = applied(receiptWith(valid, "amount", amount));
            assertEquals(UNKNOWN, gateway.observe(KEY, TRANSFER, APPLY));
        }
        assertEquals(0, provider.writes);
    }

    @Test
    void aSuccessResponseRequiresAnExactDurableReceipt() throws Exception {
        for (NationResourceResult response : List.of(NationResourceResult.APPLIED, NationResourceResult.ALREADY_APPLIED)) {
            for (boolean conflicting : List.of(false, true)) {
                StubProvider provider = new StubProvider();
                provider.response = response;
                provider.onWrite = request -> provider.answer = conflicting
                        ? applied(receiptWith(receipt(request), "amount", request.amount() + 1))
                        : new NationResourceObservation(UNKNOWN, null);
                assertEquals(Result.UNKNOWN, gateway(provider).transfer(KEY, TRANSFER),
                        response + ": " + (conflicting ? "conflicting receipt" : "no receipt proof"));
                assertEquals(1, provider.writes);
                assertEquals(2, provider.queries, "The successful write must be verified with a fresh query");
            }
        }
    }

    @Test
    void unknownOrMissingEvidenceNeverAuthorizesAWrite() throws Exception {
        for (boolean missingResponse : List.of(false, true)) {
            StubProvider provider = new StubProvider();
            provider.answer = missingResponse ? null : new NationResourceObservation(UNKNOWN, null);
            NationResourceSettlementGateway gateway = gateway(provider);
            assertEquals(UNKNOWN, gateway.observe(KEY, TRANSFER, APPLY));
            assertEquals(Result.UNKNOWN, gateway.transfer(KEY, TRANSFER));
            assertEquals(Result.UNKNOWN, gateway.compensate(KEY, TRANSFER));
            assertEquals(0, provider.writes);
        }

        StubProvider unavailable = new StubProvider();
        unavailable.queryFailure = new IOException("Ledger evidence is unavailable");
        MemoryStore store = new MemoryStore();
        NationResourceSettlementGateway gateway = gateway(unavailable);
        SettlementSagaCoordinator coordinator = new SettlementSagaCoordinator(store, gateway);
        SettlementPlan plan = plan(gateway, false);
        coordinator.begin(plan);
        coordinator.advance(SAGA);
        assertEquals(APPLY_REVIEW, coordinator.advance(SAGA).state());
        assertEquals(APPLY_REVIEW, coordinator.reconcile(SAGA).state());
        assertEquals(0, unavailable.writes);
    }

    @Test
    void rejectionAndUncertainTransportResultsRemainDistinct() throws Exception {
        for (NationResourceResult response : NationResourceResult.values()) {
            if (response == NationResourceResult.APPLIED || response == NationResourceResult.ALREADY_APPLIED) continue;
            StubProvider provider = new StubProvider();
            provider.response = response;
            provider.onWrite = request -> { };
            Result expected = response == NationResourceResult.UNAVAILABLE ? Result.UNKNOWN : Result.REJECTED;
            assertEquals(expected, gateway(provider).transfer(KEY, TRANSFER), response.toString());
            assertEquals(1, provider.writes);
        }
        StubProvider missingResult = new StubProvider();
        missingResult.response = null;
        missingResult.onWrite = request -> { };
        assertEquals(Result.UNKNOWN, gateway(missingResult).transfer(KEY, TRANSFER));
    }

    @Test
    void authorityDomainIsRecheckedBeforeAndAfterProviderCalls() throws Exception {
        StubProvider changed = new StubProvider();
        NationResourceSettlementGateway gateway = gateway(changed);
        changed.domain = "another-ledger";
        assertEquals(UNKNOWN, gateway.observe(KEY, TRANSFER, APPLY));
        assertEquals(Result.UNKNOWN, gateway.transfer(KEY, TRANSFER));
        assertEquals(Result.UNKNOWN, gateway.compensate(KEY, TRANSFER));
        assertEquals(0, changed.queries);
        assertEquals(0, changed.writes);

        StubProvider changesDuringQuery = new StubProvider();
        NationResourceSettlementGateway queryGateway = gateway(changesDuringQuery);
        changesDuringQuery.onQuery = () -> changesDuringQuery.domain = "another-ledger";
        assertEquals(Result.UNKNOWN, queryGateway.transfer(KEY, TRANSFER));
        assertEquals(0, changesDuringQuery.writes);

        StubProvider changesDuringWrite = new StubProvider();
        NationResourceSettlementGateway writeGateway = gateway(changesDuringWrite);
        changesDuringWrite.onWrite = request -> {
            changesDuringWrite.answer = applied(receipt(request));
            changesDuringWrite.domain = "another-ledger";
        };
        assertEquals(Result.UNKNOWN, writeGateway.transfer(KEY, TRANSFER));
        assertEquals(1, changesDuringWrite.writes);
    }

    @Test
    void persistedProviderIdentityUsesTheFullKnownDomainDigestAndRejectsAnotherLedger() throws Exception {
        LedgerState state = new LedgerState();
        NationResourceSettlementGateway original = gateway(new Ledger(DOMAIN, state));
        // Independently computed SHA-256, RFC 4648 base32, lowercase and without padding.
        assertEquals("sc.nr.v1.73ncswy7jxf6bt5ekun3oei2wyct62gympjpinfd7gw4auotrzca", original.providerId());
        assertEquals(61, original.providerId().length());
        SettlementPlan plan = plan(original, false);
        assertTrue(plan.applyKey(0).length() <= 128);
        assertTrue(plan.compensationKey(0).length() <= 128);
        assertEquals(original.providerId(), gateway(new Ledger(DOMAIN, state)).providerId());

        MemoryStore store = new MemoryStore();
        new SettlementSagaCoordinator(store, original).begin(plan);
        NationResourceSettlementGateway another = new NationResourceSettlementGateway(
                new Ledger("another-ledger", new LedgerState()), "another-ledger", Set.of(IRON));
        assertNotEquals(original.providerId(), another.providerId());
        SettlementSagaCoordinator restarted = new SettlementSagaCoordinator(store, another);
        assertThrows(IllegalArgumentException.class, () -> restarted.begin(plan));
        assertThrows(IllegalArgumentException.class, () -> restarted.advance(SAGA));
        assertThrows(IllegalArgumentException.class, () -> restarted.reconcile(SAGA));
        assertEquals(0, state.writes);
    }

    @Test
    void restartAfterAppliedEffectAndFailedJournalWriteDoesNotTransferTwice() throws Exception {
        LedgerState state = new LedgerState();
        NationResourceSettlementGateway gateway = gateway(new Ledger(DOMAIN, state));
        MemoryStore store = new MemoryStore();
        SettlementSagaCoordinator coordinator = new SettlementSagaCoordinator(store, gateway);
        coordinator.begin(plan(gateway, false));
        SettlementCheckpoint previous = coordinator.advance(SAGA);
        store.failNextSave = true;
        assertThrows(IOException.class, () -> coordinator.advance(SAGA));
        assertEquals(previous, store.find(SAGA).orElseThrow());
        assertEquals(90, state.balance(SOURCE));
        assertEquals(10, state.balance(TARGET));

        SettlementSagaCoordinator restarted = new SettlementSagaCoordinator(store, gateway(new Ledger(DOMAIN, state)));
        assertEquals(1, restarted.advance(SAGA).cursor());
        assertEquals(COMMITTED, restarted.advance(SAGA).state());
        assertEquals(1, state.writes);
        assertEquals(90, state.balance(SOURCE));
        assertEquals(10, state.balance(TARGET));
    }

    @Test
    void restartAfterCompensationAndFailedJournalWriteDoesNotReverseTwice() throws Exception {
        LedgerState state = new LedgerState();
        NationResourceSettlementGateway gateway = gateway(new Ledger(DOMAIN, state));
        SettlementPlan plan = plan(gateway, true);
        state.rejectedKeys.add(plan.applyKey(1));
        MemoryStore store = new MemoryStore();
        SettlementSagaCoordinator coordinator = new SettlementSagaCoordinator(store, gateway);
        coordinator.begin(plan);
        coordinator.advance(SAGA);
        assertEquals(1, coordinator.advance(SAGA).cursor());
        assertEquals(COMPENSATING, coordinator.advance(SAGA).state());
        SettlementCheckpoint previous = store.find(SAGA).orElseThrow();
        store.failNextSave = true;
        assertThrows(IOException.class, () -> coordinator.advance(SAGA));
        assertEquals(previous, store.find(SAGA).orElseThrow());
        assertEquals(100, state.balance(SOURCE));
        assertEquals(0, state.balance(TARGET));
        assertEquals(3, state.writes, "Apply, rejected second step, then inverse compensation");

        SettlementSagaCoordinator restarted = new SettlementSagaCoordinator(store, gateway(new Ledger(DOMAIN, state)));
        assertEquals(ABORTED, restarted.advance(SAGA).state());
        assertEquals(ABORTED, restarted.advance(SAGA).state());
        assertEquals(3, state.writes);
        assertEquals(100, state.balance(SOURCE));
        assertEquals(0, state.balance(TARGET));
    }

    @Test
    void conflictingEvidenceCannotCommitOrBeReconciledAsTheExpectedTransfer() throws Exception {
        StubProvider provider = new StubProvider();
        NationResourceSettlementGateway gateway = gateway(provider);
        SettlementPlan plan = plan(gateway, false);
        Request expected = new Request(plan.applyKey(0), SOURCE, TARGET, IRON, 10,
                "OTHER", "StarGenesis settlement " + plan.applyKey(0), DOMAIN);
        provider.answer = applied(receiptWith(receipt(expected), "target", THIRD));
        SettlementSagaCoordinator coordinator = new SettlementSagaCoordinator(new MemoryStore(), gateway);
        coordinator.begin(plan);
        coordinator.advance(SAGA);
        assertEquals(APPLY_REVIEW, coordinator.advance(SAGA).state());
        assertEquals(APPLY_REVIEW, coordinator.reconcile(SAGA).state());
        assertEquals(0, provider.writes);
    }

    @Test
    void spentResourcesLeaveCompensationInReviewUntilTheyCanBeRecovered() throws Exception {
        LedgerState state = new LedgerState();
        NationResourceSettlementGateway gateway = gateway(new Ledger(DOMAIN, state));
        SettlementPlan plan = plan(gateway, true);
        state.rejectedKeys.add(plan.applyKey(1));
        SettlementSagaCoordinator coordinator = new SettlementSagaCoordinator(new MemoryStore(), gateway);
        coordinator.begin(plan);
        coordinator.advance(SAGA);
        coordinator.advance(SAGA);
        assertEquals(COMPENSATING, coordinator.advance(SAGA).state());
        state.balances.put(TARGET, 0L);
        state.balances.put(THIRD, 10L);
        assertEquals(COMPENSATION_REVIEW, coordinator.advance(SAGA).state());
        assertEquals(COMPENSATION_REVIEW, coordinator.advance(SAGA).state());
        assertEquals(90, state.balance(SOURCE));
        state.balances.put(THIRD, 0L);
        state.balances.put(TARGET, 10L);
        assertEquals(COMPENSATING, coordinator.reconcile(SAGA).state());
        assertEquals(ABORTED, coordinator.advance(SAGA).state());
        assertEquals(100, state.balance(SOURCE));
        assertEquals(0, state.balance(TARGET));
        assertEquals(0, state.balance(THIRD));
    }

    @Test
    void publicRequestAndObservationContractsRejectInvalidBoundsAndProofShapes() {
        Request valid = request();
        assertEquals(SettlementTransfer.MAX_AMOUNT,
                requestWith(valid, "amount", SettlementTransfer.MAX_AMOUNT).amount());
        String[] fields = {"key", "key", "key", "domain", "domain", "domain", "resource", "resource",
                "amount", "amount", "category", "description"};
        Object[] invalid = {"", "k\n", "k".repeat(129), "", "d\t", "d".repeat(129), "INVALID", "r".repeat(129),
                0L, SettlementTransfer.MAX_AMOUNT + 1, "TRADE", "unbound description"};
        for (int index = 0; index < fields.length; index++) {
            String field = fields[index];
            Object value = invalid[index];
            assertThrows(IllegalArgumentException.class, () -> requestWith(valid, field, value), field);
        }
        for (String field : List.of("key", "domain", "resource", "category", "description"))
            assertThrows(IllegalArgumentException.class, () -> requestWith(valid, field, null), field);
        assertThrows(IllegalArgumentException.class, () -> requestWith(valid, "target", SOURCE));
        assertThrows(NullPointerException.class, () -> requestWith(valid, "source", null));
        assertThrows(NullPointerException.class, () -> requestWith(valid, "target", null));
        assertThrows(IllegalArgumentException.class, () -> new NationResourceObservation(APPLIED, null));
        assertThrows(IllegalArgumentException.class, () -> new NationResourceObservation(NOT_APPLIED, receipt(valid)));
        assertThrows(IllegalArgumentException.class, () -> new NationResourceObservation(UNKNOWN, receipt(valid)));
        assertThrows(NullPointerException.class, () -> new NationResourceObservation(null, null));
    }

    @Test
    void malformedUtf16CannotAliasAnAuthorityOrKeyButValidUnicodeRemainsSupported() throws Exception {
        StubProvider provider = new StubProvider();
        NationResourceSettlementGateway gateway = gateway(provider);
        for (String malformed : List.of("\uD800", "\uDC00", "domain:\uD800x", "domain:\uDC00x")) {
            StubProvider malformedDomain = new StubProvider();
            malformedDomain.domain = malformed;
            assertThrows(IllegalArgumentException.class,
                    () -> new NationResourceSettlementGateway(malformedDomain, malformed, Set.of(IRON)));
            assertThrows(IllegalArgumentException.class, () -> requestWith(request(), "key", malformed));
            assertThrows(IllegalArgumentException.class, () -> gateway.transfer(malformed, TRANSFER));
        }
        assertEquals(0, provider.queries);
        assertEquals(0, provider.writes);

        String unicodeDomain = "nation:\u8d44\u6e90:\uD83D\uDE80";
        String unicodeKey = "settlement:\u8d44\u6e90:\uD83D\uDE80";
        StubProvider unicode = new StubProvider();
        unicode.domain = unicodeDomain;
        NationResourceSettlementGateway unicodeGateway = new NationResourceSettlementGateway(
                unicode, unicodeDomain, Set.of(IRON));
        assertEquals(Result.APPLIED, unicodeGateway.transfer(unicodeKey, TRANSFER));
        assertEquals(APPLIED, unicodeGateway.observe(unicodeKey, TRANSFER, APPLY));
        assertNotEquals(gateway.providerId(), unicodeGateway.providerId());
        assertEquals(1, unicode.writes);
    }

    @Test
    void canonicalNationIdsResourceAllowlistAndDomainPinAreRequiredBeforeProviderAccess() throws Exception {
        StubProvider provider = new StubProvider();
        NationResourceSettlementGateway gateway = gateway(provider);
        for (String source : List.of("1-1-1-1-1", "nation:a", " " + SOURCE)) {
            SettlementTransfer invalid = new SettlementTransfer(source, TARGET.toString(), IRON, 10);
            assertThrows(IllegalArgumentException.class, () -> gateway.transfer(KEY, invalid));
        }
        SettlementTransfer disallowed = new SettlementTransfer(SOURCE.toString(), TARGET.toString(), "ore:gold", 10);
        assertThrows(IllegalArgumentException.class, () -> gateway.transfer(KEY, disallowed));
        assertThrows(IllegalArgumentException.class, () -> new NationResourceSettlementGateway(provider, "another-ledger", Set.of(IRON)));
        assertThrows(IllegalArgumentException.class, () -> new NationResourceSettlementGateway(provider, DOMAIN, Set.of()));
        assertThrows(IllegalArgumentException.class, () -> new NationResourceSettlementGateway(provider, DOMAIN, Set.of("INVALID")));
        Set<String> tooMany = new HashSet<>();
        for (int index = 0; index < 65; index++) tooMany.add("ore:r" + index);
        assertThrows(IllegalArgumentException.class, () -> new NationResourceSettlementGateway(provider, DOMAIN, tooMany));
        assertEquals(0, provider.queries);
        assertEquals(0, provider.writes);
    }

    private static NationResourceSettlementGateway gateway(NationResourceProvider provider) {
        return new NationResourceSettlementGateway(provider, DOMAIN, Set.of(IRON, COPPER));
    }

    private static SettlementTransfer transfer(UUID source, UUID target, long amount) {
        return new SettlementTransfer(source.toString(), target.toString(), IRON, amount);
    }

    private static SettlementPlan plan(NationResourceSettlementGateway gateway, boolean twoSteps) {
        return new SettlementPlan(SAGA, ACTOR, gateway.providerId(), twoSteps
                ? List.of(TRANSFER, transfer(TARGET, THIRD, 10)) : List.of(TRANSFER));
    }

    private static Request request() {
        return new Request(KEY, SOURCE, TARGET, IRON, 10, "OTHER", "StarGenesis settlement " + KEY, DOMAIN);
    }

    private static NationResourceReceipt receipt(Request request) {
        return new NationResourceReceipt(request.idempotencyKey(), request.sourceNation(), request.targetNation(),
                request.resourceId(), request.amount(), NationResourceResult.APPLIED, request.category(),
                request.description(), request.authorityDomainId());
    }

    private static NationResourceObservation applied(NationResourceReceipt receipt) {
        return new NationResourceObservation(APPLIED, receipt);
    }

    private static NationResourceReceipt receiptWith(NationResourceReceipt receipt, String field, Object value) {
        return new NationResourceReceipt(field.equals("key") ? (String) value : receipt.idempotencyKey(),
                field.equals("source") ? (UUID) value : receipt.sourceNation(),
                field.equals("target") ? (UUID) value : receipt.targetNation(),
                field.equals("resource") ? (String) value : receipt.resourceId(),
                field.equals("amount") ? (Long) value : receipt.amount(),
                field.equals("status") ? (NationResourceResult) value : receipt.status(),
                field.equals("category") ? (String) value : receipt.category(),
                field.equals("description") ? (String) value : receipt.description(),
                field.equals("domain") ? (String) value : receipt.authorityDomainId());
    }

    private static Request requestWith(Request request, String field, Object value) {
        return new Request(field.equals("key") ? (String) value : request.idempotencyKey(),
                field.equals("source") ? (UUID) value : request.sourceNation(),
                field.equals("target") ? (UUID) value : request.targetNation(),
                field.equals("resource") ? (String) value : request.resourceId(),
                field.equals("amount") ? (Long) value : request.amount(),
                field.equals("category") ? (String) value : request.category(),
                field.equals("description") ? (String) value : request.description(),
                field.equals("domain") ? (String) value : request.authorityDomainId());
    }

    private static final class StubProvider implements NationResourceProvider {
        private String domain = DOMAIN;
        private NationResourceObservation answer = new NationResourceObservation(NOT_APPLIED, null);
        private NationResourceResult response = NationResourceResult.APPLIED;
        private Consumer<Request> onWrite = request -> answer = applied(receipt(request));
        private Runnable onQuery = () -> { };
        private IOException queryFailure;
        private int writes;
        private int queries;

        @Override public String authorityDomainId() { return domain; }

        @Override public NationResourceResult transfer(Request request) {
            writes++;
            onWrite.accept(request);
            return response;
        }

        @Override public NationResourceObservation query(String key) throws IOException {
            queries++;
            if (queryFailure != null) throw queryFailure;
            onQuery.run();
            return answer;
        }
    }

    /** The state survives replacement of the fake provider instance, as a durable ledger would. */
    private static final class LedgerState {
        private final Map<UUID, Long> balances = new HashMap<>(Map.of(SOURCE, 100L, TARGET, 0L, THIRD, 0L));
        private final Map<String, Request> requests = new HashMap<>();
        private final Set<String> rejectedKeys = new HashSet<>();
        private int writes;

        private long balance(UUID nation) { return balances.getOrDefault(nation, 0L); }
    }

    /** Synchronous, single-authority test ledger; missing keys explicitly prove no in-flight work. */
    private static final class Ledger implements NationResourceProvider {
        private final String domain;
        private final LedgerState state;

        private Ledger(String domain, LedgerState state) { this.domain = domain; this.state = state; }

        @Override public String authorityDomainId() { return domain; }

        @Override public NationResourceResult transfer(Request request) {
            state.writes++;
            if (!domain.equals(request.authorityDomainId()) || !IRON.equals(request.resourceId()))
                return NationResourceResult.REJECTED;
            Request previous = state.requests.get(request.idempotencyKey());
            if (previous != null)
                return previous.equals(request) ? NationResourceResult.ALREADY_APPLIED : NationResourceResult.REJECTED;
            if (state.rejectedKeys.contains(request.idempotencyKey())) return NationResourceResult.REJECTED;
            if (state.balance(request.sourceNation()) < request.amount()) return NationResourceResult.INSUFFICIENT_RESOURCES;
            long target = Math.addExact(state.balance(request.targetNation()), request.amount());
            state.balances.put(request.sourceNation(), state.balance(request.sourceNation()) - request.amount());
            state.balances.put(request.targetNation(), target);
            state.requests.put(request.idempotencyKey(), request);
            return NationResourceResult.APPLIED;
        }

        @Override public NationResourceObservation query(String key) {
            Request request = state.requests.get(key);
            return request == null ? new NationResourceObservation(NOT_APPLIED, null) : applied(receipt(request));
        }
    }

    private static final class MemoryStore implements SettlementSagaStore {
        private final Map<UUID, SettlementCheckpoint> records = new HashMap<>();
        private boolean failNextSave;

        @Override public Optional<SettlementCheckpoint> find(UUID id) { return Optional.ofNullable(records.get(id)); }
        @Override public List<SettlementCheckpoint> loadAll() { return List.copyOf(records.values()); }

        @Override public void save(SettlementCheckpoint checkpoint) throws IOException {
            if (failNextSave) {
                failNextSave = false;
                throw new IOException("Injected journal write failure after provider side effect");
            }
            SettlementCheckpoint previous = records.get(checkpoint.plan().id());
            if (previous != null && (!previous.plan().equals(checkpoint.plan())
                    || checkpoint.revision() != previous.revision() + 1))
                throw new IOException("Invalid replacement checkpoint");
            records.put(checkpoint.plan().id(), checkpoint);
        }
    }
}
