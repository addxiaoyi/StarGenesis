package dev.stargenesis.sim;

import dev.stargenesis.api.sim.EvidenceId;
import dev.stargenesis.api.sim.EventRecord;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/** Four-seat organization factory with a finite auction, escrowed orders and replayable compensation. */
public final class FactoryWorld implements AutoCloseable {
    public static final String LEGACY_ENGINE_VERSION = "genesis-factory/1";
    public static final String ENGINE_VERSION = "genesis-factory/2";
    public static final int PLAYER_COUNT = 4;
    public static final int MAX_EVENTS_PER_TICK = 256;
    public static final String CREDITS = "credits";
    public static final String ORE = "ore";
    public static final String PARTS = "parts";
    private static final String ZERO_HASH = "0".repeat(64);
    private static final Comparator<UUID> UUID_ORDER = Comparator.comparing(UUID::toString);
    private static final List<String> ORGANIZATIONS = List.of("org_cedar", "org_ember");
    private final OwnerThread owner = new OwnerThread();
    private final String matchId;
    private final String runId;
    private final String engineVersion;
    private final List<UUID> participants;
    private final Map<UUID, String> organizationByPlayer = new HashMap<>();
    private final long seed;
    private final FactoryConfig config;
    private final ConservingResourceLedger ledger;
    private final TreeMap<String, Bid> bids = new TreeMap<>();
    private final TreeMap<String, OrderState> orders = new TreeMap<>();
    private final List<FactoryReplay.Frame> history = new ArrayList<>();
    private final String initialStateHash;
    private AuctionState auctionState = AuctionState.OPEN;
    private String auctionWinner = "";
    private long winningBid;
    private long tick;
    private long eventSequence;
    private int completedOrders;
    private int recoveredOrders;
    private long producedParts;
    private boolean terminal;
    private boolean closed;
    private String reason = "RUNNING";
    private String ledgerHistoryHash = ZERO_HASH;
    private String eventHistoryHash = ZERO_HASH;
    private int evidenceSequence;
    private List<EventRecord> stepEvents = new ArrayList<>();

    public FactoryWorld(String matchId, List<UUID> participants, long seed, FactoryConfig config) {
        this(matchId, null, participants, seed, config);
    }

    /** Pins a new run identity to the ordered two-organization roster. */
    public FactoryWorld(UUID runId, List<UUID> participants, long seed, FactoryConfig config) {
        this(matchIdFor(participants, runId), runId.toString(), participants, seed, config);
    }

    private FactoryWorld(String matchId, String runId, List<UUID> participants, long seed, FactoryConfig config) {
        new EvidenceId(matchId, 0, 0);
        this.matchId = matchId;
        this.runId = runId;
        this.engineVersion = runId == null ? LEGACY_ENGINE_VERSION : ENGINE_VERSION;
        this.participants = validateParticipants(participants);
        this.seed = seed;
        this.config = Objects.requireNonNull(config, "config");
        for (int i = 0; i < this.participants.size(); i++)
            organizationByPlayer.put(this.participants.get(i), ORGANIZATIONS.get(i / 2));

        Map<String, Map<String, Long>> initial = new TreeMap<>();
        initial.put("treasury", Map.of(CREDITS, 0L));
        initial.put("auction_pool", Map.of(ORE, config.auctionOre()));
        initial.put("factory_stock", Map.of(PARTS, config.initialFactoryParts()));
        initial.put("consumed_ore", Map.of(ORE, 0L));
        for (String organization : ORGANIZATIONS) {
            initial.put(organization, Map.of(CREDITS, config.initialCreditsPerOrganization(), ORE, 0L, PARTS, 0L));
            initial.put(bidAccount(organization), Map.of(CREDITS, 0L));
        }
        ledger = new ConservingResourceLedger(initial, config.maxLedgerAttempts());
        initialStateHash = stateHash();
    }

    public static String matchIdFor(List<UUID> participants) {
        List<UUID> checked = validateParticipants(participants);
        StringBuilder key = new StringBuilder();
        checked.forEach(player -> token(key, player.toString()));
        return "factory-" + sha256(key.toString()).substring(0, 32);
    }

    public static String matchIdFor(List<UUID> participants, UUID runId) {
        Objects.requireNonNull(runId, "runId");
        StringBuilder key = new StringBuilder();
        token(key, ENGINE_VERSION);
        token(key, runId.toString());
        token(key, matchIdFor(participants));
        return "factory-" + sha256(key.toString()).substring(0, 32);
    }

    static FactoryWorld initialWorld(FactoryReplay replay) {
        return new FactoryWorld(replay.matchId(), replay.runId(), replay.participants(), replay.seed(), replay.config());
    }

    public static FactoryWorld restore(FactoryReplay replay) {
        Objects.requireNonNull(replay, "replay");
        FactoryWorld world = initialWorld(replay);
        try {
            if (!world.initialStateHash.equals(replay.initialStateHash()))
                throw new IllegalArgumentException("Factory initial-state commitment mismatch");
            for (FactoryReplay.Frame frame : replay.frames()) {
                FactoryStepResult result = world.step(frame.input());
                if (!result.stateHash().equals(frame.stateHash()) || !result.eventHash().equals(frame.eventHash())
                        || !result.events().equals(frame.events()))
                    throw new IllegalArgumentException("Factory replay commitment mismatch at tick " + frame.input().tick());
            }
            return world;
        } catch (RuntimeException failure) {
            world.close();
            throw failure;
        }
    }

    public FactoryStepResult step(FactoryInput input) {
        owner.check();
        requireOpen();
        Objects.requireNonNull(input, "input");
        if (terminal) throw new IllegalStateException("Factory match ended: " + reason);
        if (input.tick() != tick) throw new IllegalArgumentException("Expected factory tick " + tick);
        validateAction(input.action());

        evidenceSequence = 0;
        stepEvents = new ArrayList<>();
        apply(input.action());
        long nextTick = Math.addExact(tick, 1);
        settleAuctionIfDue(nextTick);
        completeOrdersIfDue(nextTick);
        if (nextTick >= config.maxTicks()) {
            compensateOutstanding("MATCH_DURATION_BUDGET");
            finish("MATCH_DURATION_BUDGET");
        }
        tick = nextTick;
        validateConservation();
        String hash = stateHash();
        history.add(new FactoryReplay.Frame(input, hash, stepEvents, eventHistoryHash));
        return new FactoryStepResult(tick, stepEvents, hash, eventHistoryHash, terminal, reason);
    }

    private void validateAction(FactoryAction action) {
        String organization = organizationByPlayer.get(action.actor());
        if (organization == null) throw new SecurityException("Factory action actor is not a match participant");
        switch (action.type()) {
            case PASS -> { }
            case BID -> {
                if (auctionState != AuctionState.OPEN || tick >= config.auctionCloseTick())
                    throw new IllegalStateException("Ore auction is closed");
                Bid previous = bids.get(organization);
                long available = Math.addExact(ledger.balance(organization, CREDITS),
                        previous == null ? 0 : previous.amount);
                if (action.amount() > available) throw new IllegalArgumentException("Organization cannot cover this bid");
            }
            case PLACE_ORDER -> {
                if (auctionState != AuctionState.SETTLED || !organization.equals(auctionWinner))
                    throw new IllegalStateException("Only the winning organization can place factory orders");
                if (orders.size() >= config.maxOrders()) throw new IllegalStateException("Factory order budget exhausted");
                if (action.reference().length() > 40) throw new IllegalArgumentException("Order ID exceeds its bound");
                if (orders.containsKey(orderKey(organization, action.reference())))
                    throw new IllegalArgumentException("Order ID already exists in this organization");
                if (tick + config.orderDurationTicks() > config.maxTicks())
                    throw new IllegalStateException("Order would exceed the match duration budget");
                long ore = Math.multiplyExact(action.amount(), 2);
                long credits = Math.multiplyExact(action.amount(), 3);
                if (ledger.balance(organization, ORE) < ore || ledger.balance(organization, CREDITS) < credits
                        || ledger.balance("factory_stock", PARTS) < action.amount())
                    throw new IllegalArgumentException("Organization or factory lacks order inputs");
            }
        }
    }

    private void apply(FactoryAction action) {
        String organization = organizationByPlayer.get(action.actor());
        switch (action.type()) {
            case PASS -> emit("factory.seat_passed", Map.of("actor", action.actor().toString(), "organization", organization));
            case BID -> placeBid(action, organization);
            case PLACE_ORDER -> placeOrder(action, organization);
        }
    }

    private void placeBid(FactoryAction action, String organization) {
        Bid old = bids.get(organization);
        if (old != null) transfer(bidAccount(organization), organization, CREDITS, old.amount,
                "factory.bid_released", Map.of("organization", organization, "bid", Long.toString(old.amount)));
        transfer(organization, bidAccount(organization), CREDITS, action.amount(), "factory.bid_escrowed",
                Map.of("organization", organization, "bid", Long.toString(action.amount()), "auction", "ore_1"));
        bids.put(organization, new Bid(organization, action.amount(), action.actor()));
    }

    private void placeOrder(FactoryAction action, String organization) {
        String key = orderKey(organization, action.reference());
        String escrow = orderAccount(organization, action.reference());
        long ore = Math.multiplyExact(action.amount(), 2);
        long credits = Math.multiplyExact(action.amount(), 3);
        transfer(organization, escrow, ORE, ore, "factory.order_ore_reserved",
                Map.of("order", key, "organization", organization, "amount", Long.toString(ore)));
        transfer(organization, escrow, CREDITS, credits, "factory.order_credits_reserved",
                Map.of("order", key, "organization", organization, "amount", Long.toString(credits)));
        transfer("factory_stock", escrow, PARTS, action.amount(), "factory.order_parts_reserved",
                Map.of("order", key, "organization", organization, "amount", Long.toString(action.amount())));
        long dueAt = Math.addExact(tick, config.orderDurationTicks());
        orders.put(key, new OrderState(key, action.reference(), organization, action.amount(), tick, dueAt));
        emit("factory.order_started", Map.of("order", key, "organization", organization,
                "quantity", Long.toString(action.amount()), "due_at", Long.toString(dueAt)));
    }

    private void settleAuctionIfDue(long settlementTick) {
        if (auctionState != AuctionState.OPEN || settlementTick < config.auctionCloseTick()) return;
        Bid winner = bids.values().stream().max(Comparator.comparingLong((Bid bid) -> bid.amount)
                .thenComparing(bid -> bid.organization, Comparator.reverseOrder())).orElse(null);
        if (winner == null) {
            for (Bid bid : bids.values()) {
                transfer(bidAccount(bid.organization), bid.organization, CREDITS, bid.amount,
                        "factory.no_sale_bid_refunded", Map.of("organization", bid.organization, "auction", "ore_1"));
            }
            auctionState = AuctionState.NO_SALE;
            emit("factory.auction_no_sale", Map.of("auction", "ore_1"));
            return;
        }
        for (Bid bid : bids.values()) {
            if (bid == winner) continue;
            transfer(bidAccount(bid.organization), bid.organization, CREDITS, bid.amount,
                    "factory.bid_refunded", Map.of("organization", bid.organization, "auction", "ore_1"));
        }
        transfer(bidAccount(winner.organization), "treasury", CREDITS, winner.amount,
                "factory.auction_paid", Map.of("organization", winner.organization, "auction", "ore_1"));
        transfer("auction_pool", winner.organization, ORE, config.auctionOre(),
                "factory.auction_delivered", Map.of("organization", winner.organization, "auction", "ore_1"));
        auctionState = AuctionState.SETTLED;
        auctionWinner = winner.organization;
        winningBid = winner.amount;
        emit("factory.auction_settled", Map.of("winner", winner.organization,
                "bid", Long.toString(winner.amount), "ore", Long.toString(config.auctionOre())));
    }

    private void completeOrdersIfDue(long completionTick) {
        for (OrderState order : orders.values()) {
            if (order.state != OrderStatus.RUNNING || order.completesAt > completionTick) continue;
            if (fault(order)) failDuringProduction(order);
            else complete(order);
        }
    }

    private void failDuringProduction(OrderState order) {
        String escrow = orderAccount(order.organization, order.localId);
        long partiallyConsumed = Math.max(1, order.quantity);
        transfer(escrow, "consumed_ore", ORE, partiallyConsumed, "factory.order_partial_ore_consumed",
                Map.of("order", order.id, "organization", order.organization,
                        "amount", Long.toString(partiallyConsumed)));
        emit("factory.order_partial_production", Map.of("order", order.id, "stage", "matter_processing"));
        compensate(order, "DETERMINISTIC_FACTORY_FAULT", partiallyConsumed);
    }

    private void complete(OrderState order) {
        String escrow = orderAccount(order.organization, order.id.substring(order.organization.length() + 1));
        long ore = Math.multiplyExact(order.quantity, 2);
        long credits = Math.multiplyExact(order.quantity, 3);
        transfer(escrow, "consumed_ore", ORE, ore, "factory.order_ore_consumed",
                Map.of("order", order.id, "organization", order.organization));
        transfer(escrow, "treasury", CREDITS, credits, "factory.order_credits_paid",
                Map.of("order", order.id, "organization", order.organization));
        transfer(escrow, order.organization, PARTS, order.quantity, "factory.order_parts_delivered",
                Map.of("order", order.id, "organization", order.organization));
        order.state = OrderStatus.COMPLETED;
        completedOrders++;
        producedParts = Math.addExact(producedParts, order.quantity);
        emit("factory.order_completed", Map.of("order", order.id, "organization", order.organization,
                "quantity", Long.toString(order.quantity)));
    }

    private void compensate(OrderState order, String failure) {
        compensate(order, failure, 0);
    }

    private void compensate(OrderState order, String failure, long partiallyConsumedOre) {
        order.state = OrderStatus.COMPENSATING;
        order.failure = failure;
        String escrow = orderAccount(order.organization, order.id.substring(order.organization.length() + 1));
        emit("factory.order_fault_detected", Map.of("order", order.id, "reason", failure));
        emit("factory.compensation_started", Map.of("order", order.id, "organization", order.organization));
        long ore = Math.multiplyExact(order.quantity, 2);
        long credits = Math.multiplyExact(order.quantity, 3);
        if (partiallyConsumedOre > 0) {
            transfer("consumed_ore", order.organization, ORE, partiallyConsumedOre,
                    "factory.compensation_consumed_ore_refunded",
                    Map.of("order", order.id, "organization", order.organization));
        }
        long oreRemainingInEscrow = ore - partiallyConsumedOre;
        if (oreRemainingInEscrow > 0) {
            transfer(escrow, order.organization, ORE, oreRemainingInEscrow, "factory.compensation_ore_refunded",
                    Map.of("order", order.id, "organization", order.organization));
        }
        transfer(escrow, order.organization, CREDITS, credits, "factory.compensation_credits_refunded",
                Map.of("order", order.id, "organization", order.organization));
        transfer(escrow, "factory_stock", PARTS, order.quantity, "factory.compensation_parts_released",
                Map.of("order", order.id, "organization", order.organization));
        order.state = OrderStatus.FAILED_RECOVERED;
        recoveredOrders++;
        emit("factory.compensation_completed", Map.of("order", order.id, "reason", failure));
    }

    private void compensateOutstanding(String failure) {
        for (OrderState order : orders.values())
            if (order.state == OrderStatus.RUNNING) compensate(order, failure);
    }

    private boolean fault(OrderState order) {
        long value = mix64(seed ^ order.createdAt ^ order.completesAt ^ order.id.hashCode());
        return Long.remainderUnsigned(value, 1_000) < config.faultPermille();
    }

    private boolean transfer(String source, String target, String resource, long amount,
                             String eventType, Map<String, String> data) {
        if (amount < 1 || ledger.balance(source, resource) < amount)
            throw new IllegalStateException("Factory escrow invariant failed for " + resource);
        EvidenceId evidence = new EvidenceId(matchId, tick, evidenceSequence++);
        ConservingResourceLedger.TransferResult result = ledger.transfer(evidence, source, target, resource, amount);
        if (result != ConservingResourceLedger.TransferResult.APPLIED)
            throw new IllegalStateException("Factory ledger transfer was not applied");
        ledgerHistoryHash = chain(ledgerHistoryHash, evidence.toString(), source, target, resource,
                Long.toString(amount), result.name());
        emit(evidence, eventType, data);
        return true;
    }

    private void emit(String type, Map<String, String> data) {
        emit(new EvidenceId(matchId, tick, evidenceSequence++), type, data);
    }

    private void emit(EvidenceId evidence, String type, Map<String, String> data) {
        if (stepEvents.size() >= MAX_EVENTS_PER_TICK) throw new IllegalStateException("Factory event budget exhausted");
        EventRecord event = new EventRecord(evidence, type, data);
        stepEvents.add(event);
        StringBuilder encoded = new StringBuilder();
        token(encoded, evidence.toString());
        token(encoded, type);
        new TreeMap<>(data).forEach((key, value) -> { token(encoded, key); token(encoded, value); });
        eventHistoryHash = sha256(eventHistoryHash + encoded);
    }

    private void validateConservation() {
        Map<String, Map<String, Long>> balances = ledger.snapshot();
        long credits = total(balances, CREDITS);
        long ore = total(balances, ORE);
        long parts = total(balances, PARTS);
        if (credits != Math.multiplyExact(config.initialCreditsPerOrganization(), 2)
                || ore != config.auctionOre() || parts != config.initialFactoryParts())
            throw new IllegalStateException("Factory simulation assets were created or lost");
        if (orders.size() > config.maxOrders() || bids.size() > ORGANIZATIONS.size())
            throw new IllegalStateException("Factory state exceeds its bounded record count");
        for (OrderState order : orders.values()) {
            if (order.state == OrderStatus.RUNNING) {
                String escrow = orderAccount(order.organization, order.localId);
                if (ledger.balance(escrow, ORE) != Math.multiplyExact(order.quantity, 2)
                        || ledger.balance(escrow, CREDITS) != Math.multiplyExact(order.quantity, 3)
                        || ledger.balance(escrow, PARTS) != order.quantity)
                    throw new IllegalStateException("Running factory order escrow is incomplete");
            }
        }
    }

    private long total(Map<String, Map<String, Long>> balances, String resource) {
        long total = 0;
        for (Map<String, Long> resources : balances.values())
            total = Math.addExact(total, resources.getOrDefault(resource, 0L));
        return total;
    }

    public FactoryView view() {
        owner.check();
        Map<String, FactoryView.Organization> organizationViews = new TreeMap<>();
        for (String organization : ORGANIZATIONS) {
            List<String> members = participants.stream().filter(player -> organizationByPlayer.get(player).equals(organization))
                    .map(UUID::toString).toList();
            organizationViews.put(organization, new FactoryView.Organization(organization, members,
                    ledger.balance(organization, CREDITS), ledger.balance(organization, ORE),
                    ledger.balance(organization, PARTS)));
        }
        List<FactoryView.Bid> bidViews = bids.values().stream()
                .map(bid -> new FactoryView.Bid(bid.organization, bid.amount)).toList();
        FactoryView.Auction auction = new FactoryView.Auction("ore_1", config.auctionCloseTick(), auctionState.name(),
                bidViews, auctionWinner, winningBid, config.auctionOre());
        List<FactoryView.Order> orderViews = orders.values().stream().map(order -> new FactoryView.Order(
                order.id, order.organization, order.quantity, order.createdAt, order.completesAt,
                order.state.name(), order.failure)).toList();
        return new FactoryView(matchId, seed, tick, organizationViews, auction, orderViews,
                completedOrders, recoveredOrders, producedParts, stateHash(), terminal, reason);
    }

    public FactoryReplay replay() {
        owner.check();
        return new FactoryReplay(runId == null ? 1 : 2, engineVersion, matchId, participants, seed, config,
                initialStateHash, history, runId);
    }

    public String matchId() { owner.check(); return matchId; }
    public List<UUID> participants() { owner.check(); return participants; }
    public long tick() { owner.check(); return tick; }
    public Map<String, Map<String, Long>> ledgerSnapshot() { owner.check(); return ledger.snapshot(); }
    public String stateHash() {
        owner.check();
        StringBuilder state = new StringBuilder();
        token(state, engineVersion); token(state, matchId); token(state, Long.toString(seed));
        token(state, Long.toString(config.initialCreditsPerOrganization()));
        token(state, Long.toString(config.auctionOre())); token(state, Long.toString(config.initialFactoryParts()));
        token(state, Integer.toString(config.auctionCloseTick())); token(state, Integer.toString(config.orderDurationTicks()));
        token(state, Integer.toString(config.maxTicks())); token(state, Integer.toString(config.maxOrders()));
        token(state, Integer.toString(config.faultPermille())); token(state, Integer.toString(config.maxLedgerAttempts()));
        token(state, Long.toString(tick)); token(state, auctionState.name()); token(state, auctionWinner);
        token(state, Long.toString(winningBid));
        token(state, Integer.toString(completedOrders)); token(state, Integer.toString(recoveredOrders));
        token(state, Long.toString(producedParts)); token(state, Boolean.toString(terminal)); token(state, reason);
        token(state, ledgerHistoryHash); token(state, eventHistoryHash);
        participants.forEach(player -> { token(state, player.toString()); token(state, organizationByPlayer.get(player)); });
        bids.forEach((organization, bid) -> {
            token(state, organization); token(state, Long.toString(bid.amount)); token(state, bid.actor.toString());
        });
        orders.forEach((key, order) -> {
            token(state, key); token(state, order.localId); token(state, order.organization);
            token(state, Long.toString(order.quantity)); token(state, Long.toString(order.createdAt));
            token(state, Long.toString(order.completesAt)); token(state, order.state.name()); token(state, order.failure);
        });
        ledger.snapshot().forEach((account, resources) -> {
            token(state, account);
            resources.forEach((resource, amount) -> { token(state, resource); token(state, Long.toString(amount)); });
        });
        return sha256(state.toString());
    }

    private void finish(String endReason) { terminal = true; reason = endReason; }

    private String orderKey(String organization, String id) { return organization + ":" + id; }
    private String orderAccount(String organization, String id) { return "order-" + organization.substring(4) + "-" + id; }
    private String bidAccount(String organization) { return "bid-" + organization.substring(4); }

    private static List<UUID> validateParticipants(List<UUID> values) {
        List<UUID> copy = List.copyOf(values);
        if (copy.size() != PLAYER_COUNT || copy.stream().anyMatch(Objects::isNull)
                || copy.stream().distinct().count() != PLAYER_COUNT)
            throw new IllegalArgumentException("A factory match requires exactly four distinct players");
        return copy;
    }

    private long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private String chain(String previous, String... fields) {
        StringBuilder encoded = new StringBuilder();
        token(encoded, previous);
        for (String field : fields) token(encoded, field);
        return sha256(encoded.toString());
    }

    private static void token(StringBuilder target, String value) { target.append(value.length()).append(':').append(value); }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }

    private void requireOpen() { if (closed) throw new IllegalStateException("Factory world is closed"); }

    @Override public void close() { owner.check(); closed = true; }

    private enum AuctionState { OPEN, SETTLED, NO_SALE }
    private enum OrderStatus { RUNNING, COMPLETED, COMPENSATING, FAILED_RECOVERED }
    private record Bid(String organization, long amount, UUID actor) { }

    private static final class OrderState {
        private final String id;
        private final String localId;
        private final String organization;
        private final long quantity;
        private final long createdAt;
        private final long completesAt;
        private OrderStatus state = OrderStatus.RUNNING;
        private String failure = "";

        private OrderState(String id, String localId, String organization, long quantity, long createdAt, long completesAt) {
            this.id = id; this.localId = localId; this.organization = organization;
            this.quantity = quantity; this.createdAt = createdAt; this.completesAt = completesAt;
        }
    }
}
