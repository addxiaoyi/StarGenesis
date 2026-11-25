package dev.stargenesis.sim;

import dev.stargenesis.api.design.DesignId;
import dev.stargenesis.api.sim.AgentId;
import dev.stargenesis.api.sim.EvidenceId;
import dev.stargenesis.api.sim.ResourceLedger.TransferResult;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Deterministic four-player contract auction and virtual component delivery. */
public final class FactoryWorld implements AutoCloseable {
    public static final String COMPONENT_RESOURCE = "factory:component";
    public static final String CREDIT_RESOURCE = "factory:credit";
    public static final String ORE_RESOURCE = "factory:ore";
    public static final String POLYMER_RESOURCE = "factory:polymer";
    public static final String TREASURY_ACCOUNT = "factory:treasury";
    public static final String CUSTOMER_ACCOUNT = "factory:customer";
    public static final long STARTING_ORE_PER_MEMBER = 8;
    public static final long STARTING_POLYMER_PER_MEMBER = 16;
    public static final int MAX_BID_ACTIONS = FactorySettings.MAX_ORDERS * FactorySettings.MEMBER_COUNT;
    public static final int MAX_PRODUCTION_ACTIONS = 4_096;
    public static final int MAX_LEDGER_ATTEMPTS = 4_096;
    public static final long MAX_ACTION_SEQUENCE = 250_000_000L;
    static final int INTERNAL_SEQUENCE_BASE = 1_500_000_000;
    private static final long MAX_INTERNAL_SEQUENCE = INTERNAL_SEQUENCE_BASE + MAX_LEDGER_ATTEMPTS;

    private final OwnerThread owner = new OwnerThread();
    private final FactorySettings settings;
    private static final List<FactoryRecipe> RECIPES = List.of(
            new FactoryRecipe(new DesignId("factory:frame"), 1, 2, 4, 2, 8),
            new FactoryRecipe(new DesignId("factory:plate"), 1, 1, 3, 1, 8));

    private ConservingResourceLedger ledger;
    private final TreeMap<DesignId, MutableOrder> orders = new TreeMap<>();
    private final Map<EvidenceId, BidAction> bidActions = new LinkedHashMap<>();
    private final TreeMap<AgentId, StationJob> stationJobs = new TreeMap<>();
    private final Map<EvidenceId, ProductionAction> productionActions = new LinkedHashMap<>();
    private boolean productionMaterialsSeeded;
    private int formatVersion;
    private long tick;
    private int nextAutomaticSequence;
    private boolean closed;

    public FactoryWorld(FactorySettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        productionMaterialsSeeded = true;
        formatVersion = 2;
        ledger = new ConservingResourceLedger(initialBalances(settings, true), MAX_LEDGER_ATTEMPTS);
        nextAutomaticSequence = INTERNAL_SEQUENCE_BASE;
    }

    private FactoryWorld(FactoryCheckpoint checkpoint) {
        settings = checkpoint.settings();
        tick = checkpoint.tick();
        productionMaterialsSeeded = checkpoint.productionMaterialsSeeded();
        formatVersion = checkpoint.formatVersion();
        ledger = ConservingResourceLedger.restore(checkpoint.ledger());
        nextAutomaticSequence = checkpoint.nextAutomaticSequence();
        for (Order order : checkpoint.orders()) {
            if (orders.putIfAbsent(order.id(), new MutableOrder(order)) != null) {
                throw new IllegalArgumentException("Duplicate factory order ID");
            }
        }
        for (BidAction action : checkpoint.bidActions()) {
            if (bidActions.putIfAbsent(action.evidenceId(), action) != null) {
                throw new IllegalArgumentException("Duplicate factory bid evidence");
            }
        }
        for (StationJob job : checkpoint.stationJobs()) {
            if (stationJobs.putIfAbsent(job.owner(), job) != null) {
                throw new IllegalArgumentException("Duplicate factory workstation job");
            }
        }
        for (ProductionAction action : checkpoint.productionActions()) {
            if (productionActions.putIfAbsent(action.evidenceId(), action) != null) {
                throw new IllegalArgumentException("Duplicate factory production evidence");
            }
        }
        validateState();
        String expectedHash = formatVersion == 1 ? legacyStateHashInternal() : stateHashInternal();
        if (!checkpoint.stateHash().equals(expectedHash)) {
            throw new IllegalArgumentException("Factory checkpoint hash mismatch");
        }
        formatVersion = 2;
    }

    public static FactoryWorld restore(FactoryCheckpoint checkpoint) {
        return new FactoryWorld(Objects.requireNonNull(checkpoint, "checkpoint"));
    }

    public long tick() {
        requireOpen();
        return tick;
    }

    public Order createOrder(AgentId creator, DesignId orderId, long requiredComponents,
                             long rewardCredits, int auctionDuration, int deliveryWindow) {
        requireOpen();
        requireMember(creator);
        Objects.requireNonNull(orderId, "orderId");
        if (tick >= settings.maxSteps()) throw new IllegalStateException("Factory step budget exhausted");
        if (orderId.value().length() > 115 || orders.containsKey(orderId)
                || orders.size() >= settings.maxOrders()) {
            throw new IllegalArgumentException("Factory order ID or order count is outside the bounds");
        }
        if (requiredComponents < 1 || requiredComponents > totalAvailableMaterialMass()
                || rewardCredits < 1 || rewardCredits > settings.initialCredits()
                || auctionDuration < 1 || auctionDuration > 10_000
                || deliveryWindow < 1 || deliveryWindow > 10_000) {
            throw new IllegalArgumentException("Factory order exceeds the finite bounds");
        }
        long auctionClosesAt = Math.addExact(tick, auctionDuration);
        long latestDelivery = Math.addExact(auctionClosesAt, deliveryWindow);
        if (latestDelivery > settings.maxSteps()) {
            throw new IllegalArgumentException("Factory order deadlines exceed the scenario duration");
        }
        if (ledger.balance(TREASURY_ACCOUNT, CREDIT_RESOURCE) < rewardCredits) {
            throw new IllegalStateException("Factory treasury cannot escrow this reward");
        }
        MutableOrder order = new MutableOrder(orderId, creator, requiredComponents, rewardCredits,
                auctionClosesAt, deliveryWindow);
        automaticTransfer(TREASURY_ACCOUNT, escrowAccount(orderId), CREDIT_RESOURCE, rewardCredits);
        orders.put(orderId, order);
        return order.snapshot();
    }

    public Order placeBid(AgentId bidder, DesignId orderId, long actionSequence, long priceCredits) {
        requireOpen();
        int memberIndex = requireMember(bidder);
        MutableOrder order = requireOrder(orderId);
        EvidenceId evidenceId = playerEvidence(actionSequence, memberIndex);
        String fingerprint = orderId.value() + "|" + priceCredits;
        BidAction previousAction = bidActions.get(evidenceId);
        if (previousAction != null) {
            if (!previousAction.fingerprint().equals(fingerprint) || !previousAction.bidder().equals(bidder)) {
                throw new IllegalArgumentException("Factory action sequence was reused with different input");
            }
            return order.snapshot();
        }
        requireUnusedPlayerEvidence(evidenceId);
        if (order.status != OrderStatus.OPEN || tick >= order.auctionClosesAt
                || priceCredits < 0 || priceCredits > order.rewardCredits) {
            throw new IllegalArgumentException("Bid is not valid for this factory order");
        }
        Bid oldBid = order.bids.get(bidder);
        if (oldBid != null && oldBid.priceCredits() != priceCredits) {
            throw new IllegalArgumentException("A member may submit only one price per order");
        }
        if (oldBid != null) return order.snapshot();
        if (bidActions.size() >= MAX_BID_ACTIONS) {
            throw new IllegalStateException("Factory bid evidence budget exhausted");
        }
        bidActions.put(evidenceId, new BidAction(evidenceId, bidder, fingerprint));
        order.bids.put(bidder, new Bid(bidder, priceCredits, tick));
        if (order.bids.size() == FactorySettings.MEMBER_COUNT) resolveAuction(order);
        return order.snapshot();
    }

    public TransferResult contribute(AgentId contributor, DesignId orderId,
                                     long actionSequence, long amount) {
        requireOpen();
        int memberIndex = requireMember(contributor);
        MutableOrder order = requireOrder(orderId);
        if (order.status != OrderStatus.AWARDED || amount < 1 || amount > FactorySettings.MAX_BALANCE) {
            throw new IllegalArgumentException("Contribution is not valid for this factory order");
        }
        EvidenceId evidenceId = playerEvidence(actionSequence, memberIndex);
        if (productionActions.containsKey(evidenceId) || bidActions.containsKey(evidenceId)) {
            throw new IllegalArgumentException("Factory action sequence was already used for another action");
        }
        TransferResult result = ledger.transfer(evidenceId, playerAccount(contributor), bufferAccount(orderId),
                COMPONENT_RESOURCE, amount);
        if (result == TransferResult.APPLIED) {
            order.contributions.merge(contributor, amount, Math::addExact);
        }
        return result;
    }

    public static List<FactoryRecipe> recipes() {
        return RECIPES;
    }

    public WorkstationSnapshot workstation(AgentId member) {
        requireOpen();
        requireMember(member);
        String account = workstationAccount(member);
        return new WorkstationSnapshot(member, ledger.balance(account, ORE_RESOURCE),
                ledger.balance(account, POLYMER_RESOURCE), ledger.balance(account, COMPONENT_RESOURCE),
                stationJobs.get(member));
    }

    public ProductionActionResult queueProduction(AgentId member, long actionSequence,
                                                  DesignId recipeId, int batches) {
        requireOpen();
        int memberIndex = requireMember(member);
        Objects.requireNonNull(recipeId, "recipeId");
        EvidenceId evidenceId = playerEvidence(actionSequence, memberIndex);
        FactoryRecipe recipe = requireRecipe(recipeId);
        long ore = recipe.oreFor(batches);
        long polymer = recipe.polymerFor(batches);
        long completesAt = Math.addExact(tick, recipe.durationFor(batches));
        String fingerprint = "queue|" + recipeId.value() + "|" + batches;
        ProductionAction previousAction = productionActions.get(evidenceId);
        if (previousAction != null) {
            requireSameAction(previousAction, member, fingerprint);
            return previousAction.result();
        }
        requireUnusedPlayerEvidence(evidenceId);
        if (tick >= settings.maxSteps() || completesAt > settings.maxSteps()) {
            throw new IllegalStateException("Factory production would exceed the scenario duration");
        }
        ensureProductionActionCapacity();
        if (stationJobs.containsKey(member)) {
            return recordProductionAction(evidenceId, member, fingerprint, ProductionActionResult.STATION_BUSY);
        }
        String inventory = playerAccount(member);
        if (ledger.balance(inventory, ORE_RESOURCE) < ore
                || ledger.balance(inventory, POLYMER_RESOURCE) < polymer) {
            return recordProductionAction(evidenceId, member, fingerprint,
                    ProductionActionResult.INSUFFICIENT_MATERIALS);
        }
        String workstation = workstationAccount(member);
        long output = recipe.componentsFor(batches);
        if (Math.addExact(ledger.balance(workstation, COMPONENT_RESOURCE), output)
                > FactorySettings.MAX_BALANCE) {
            throw new IllegalStateException("Workstation output buffer is full");
        }
        int transfers = (ore > 0 ? 1 : 0) + (polymer > 0 ? 1 : 0);
        ensureAutomaticCapacity(transfers);
        if (ore > 0) automaticTransfer(inventory, workstation, ORE_RESOURCE, ore);
        if (polymer > 0) automaticTransfer(inventory, workstation, POLYMER_RESOURCE, polymer);
        stationJobs.put(member, new StationJob(member, recipeId, batches, tick, completesAt));
        return recordProductionAction(evidenceId, member, fingerprint, ProductionActionResult.QUEUED);
    }

    public ProductionActionResult cancelProduction(AgentId member, long actionSequence) {
        requireOpen();
        int memberIndex = requireMember(member);
        EvidenceId evidenceId = playerEvidence(actionSequence, memberIndex);
        String fingerprint = "cancel";
        ProductionAction previousAction = productionActions.get(evidenceId);
        if (previousAction != null) {
            requireSameAction(previousAction, member, fingerprint);
            return previousAction.result();
        }
        requireUnusedPlayerEvidence(evidenceId);
        ensureProductionActionCapacity();
        StationJob job = stationJobs.get(member);
        if (job == null) {
            return recordProductionAction(evidenceId, member, fingerprint, ProductionActionResult.NO_JOB);
        }
        FactoryRecipe recipe = requireRecipe(job.recipeId());
        int transfers = (recipe.oreFor(job.batches()) > 0 ? 1 : 0)
                + (recipe.polymerFor(job.batches()) > 0 ? 1 : 0);
        ensureAutomaticCapacity(transfers);
        String workstation = workstationAccount(member);
        String inventory = playerAccount(member);
        long ore = recipe.oreFor(job.batches());
        long polymer = recipe.polymerFor(job.batches());
        if (ore > 0) automaticTransfer(workstation, inventory, ORE_RESOURCE, ore);
        if (polymer > 0) automaticTransfer(workstation, inventory, POLYMER_RESOURCE, polymer);
        stationJobs.remove(member);
        return recordProductionAction(evidenceId, member, fingerprint, ProductionActionResult.CANCELLED);
    }

    public TransferResult collectProduction(AgentId member, long actionSequence, long amount) {
        requireOpen();
        int memberIndex = requireMember(member);
        if (amount < 1 || amount > FactorySettings.MAX_BALANCE) {
            throw new IllegalArgumentException("Collection amount is outside the finite bounds");
        }
        EvidenceId evidenceId = playerEvidence(actionSequence, memberIndex);
        if (productionActions.containsKey(evidenceId) || bidActions.containsKey(evidenceId)) {
            throw new IllegalArgumentException("Factory action sequence was already used for another action");
        }
        return ledger.transfer(evidenceId, workstationAccount(member), playerAccount(member),
                COMPONENT_RESOURCE, amount);
    }

    public boolean fulfill(AgentId fulfiller, DesignId orderId) {
        requireOpen();
        requireMember(fulfiller);
        MutableOrder order = requireOrder(orderId);
        if (order.status == OrderStatus.DELIVERED && fulfiller.equals(order.winner)) return true;
        if (order.status != OrderStatus.AWARDED || !fulfiller.equals(order.winner)) {
            throw new IllegalArgumentException("Only the awarded member may fulfill this order");
        }
        if (ledger.balance(bufferAccount(orderId), COMPONENT_RESOURCE) < order.requiredComponents) return false;

        ensureAutomaticCapacity(fulfillmentTransferCount(order));

        automaticTransfer(bufferAccount(orderId), CUSTOMER_ACCOUNT, COMPONENT_RESOURCE, order.requiredComponents);
        returnExcessComponents(order, order.requiredComponents);
        Bid winningBid = order.bids.get(order.winner);
        if (winningBid.priceCredits() > 0) {
            automaticTransfer(escrowAccount(orderId), playerAccount(order.winner), CREDIT_RESOURCE,
                    winningBid.priceCredits());
        }
        long remainder = order.rewardCredits - winningBid.priceCredits();
        if (remainder > 0) automaticTransfer(escrowAccount(orderId), TREASURY_ACCOUNT, CREDIT_RESOURCE, remainder);
        order.deliveredComponents = order.requiredComponents;
        order.status = OrderStatus.DELIVERED;
        return true;
    }

    public FactorySnapshot step() {
        requireOpen();
        if (tick >= settings.maxSteps()) throw new IllegalStateException("Factory step budget exhausted");
        long nextTick = tick + 1;
        int automaticTransfers = 0;
        for (MutableOrder order : orders.values()) {
            if (order.status == OrderStatus.OPEN && nextTick >= order.auctionClosesAt && order.bids.isEmpty()) {
                automaticTransfers++;
            } else if (order.status == OrderStatus.AWARDED && nextTick >= order.deliveryDeadline) {
                automaticTransfers += 1 + order.contributions.size();
            }
        }
        ensureAutomaticCapacity(automaticTransfers);
        tick++;
        for (MutableOrder order : orders.values()) {
            if (order.status == OrderStatus.OPEN && tick >= order.auctionClosesAt) resolveAuction(order);
            if (order.status == OrderStatus.AWARDED && tick >= order.deliveryDeadline) failOrder(order);
        }
        completeStationJobs();
        return snapshot();
    }

    public FactorySnapshot snapshot() {
        requireOpen();
        return new FactorySnapshot(settings.worldId(), tick, orderSnapshots(), ledger.snapshot(), stateHashInternal());
    }

    public FactoryCheckpoint checkpoint() {
        requireOpen();
        return new FactoryCheckpoint(settings, tick, orderSnapshots(), ledger.checkpoint(),
                List.copyOf(bidActions.values()), List.copyOf(stationJobs.values()),
                List.copyOf(productionActions.values()), productionMaterialsSeeded, 2,
                nextAutomaticSequence, stateHashInternal());
    }

    public long balance(String account, String resource) {
        requireOpen();
        return ledger.balance(account, resource);
    }

    public static String playerAccount(AgentId member) {
        return "player:" + Objects.requireNonNull(member, "member").value().toString().replace("-", "");
    }

    public static String workstationAccount(AgentId member) {
        return "workstation:" + Objects.requireNonNull(member, "member").value().toString().replace("-", "");
    }

    @Override public void close() {
        owner.check();
        closed = true;
    }

    private void resolveAuction(MutableOrder order) {
        if (order.status != OrderStatus.OPEN) return;
        if (order.bids.isEmpty()) {
            automaticTransfer(escrowAccount(order.id), TREASURY_ACCOUNT, CREDIT_RESOURCE, order.rewardCredits);
            order.status = OrderStatus.CANCELLED;
            return;
        }
        Bid winner = order.bids.values().stream().min(Comparator.comparingLong(Bid::priceCredits)
                .thenComparing(Bid::bidder)).orElseThrow();
        order.winner = winner.bidder();
        order.deliveryDeadline = Math.addExact(tick, order.deliveryWindow);
        order.status = OrderStatus.AWARDED;
    }

    private void failOrder(MutableOrder order) {
        automaticTransfer(escrowAccount(order.id), TREASURY_ACCOUNT, CREDIT_RESOURCE, order.rewardCredits);
        for (Map.Entry<AgentId, Long> contribution : order.contributions.entrySet()) {
            if (contribution.getValue() > 0) {
                automaticTransfer(bufferAccount(order.id), playerAccount(contribution.getKey()), COMPONENT_RESOURCE,
                        contribution.getValue());
            }
        }
        order.status = OrderStatus.FAILED;
    }

    private void returnExcessComponents(MutableOrder order, long delivered) {
        long remaining = delivered;
        for (Map.Entry<AgentId, Long> contribution : order.contributions.entrySet()) {
            long used = Math.min(remaining, contribution.getValue());
            remaining -= used;
            long excess = contribution.getValue() - used;
            if (excess > 0) {
                automaticTransfer(bufferAccount(order.id), playerAccount(contribution.getKey()),
                        COMPONENT_RESOURCE, excess);
            }
        }
        if (remaining != 0) throw new IllegalStateException("Factory contribution accounting is inconsistent");
    }

    private void automaticTransfer(String source, String target, String resource, long amount) {
        if (amount < 1) throw new IllegalArgumentException("Factory transfer must be positive");
        ensureAutomaticCapacity(1);
        EvidenceId evidenceId = new EvidenceId(settings.worldId(), tick, nextAutomaticSequence++);
        TransferResult result = ledger.transfer(evidenceId, source, target, resource, amount);
        if (result != TransferResult.APPLIED) throw new IllegalStateException("Factory escrow transfer failed");
    }

    private void completeStationJobs() {
        List<StationJob> completed = stationJobs.values().stream()
                .filter(job -> job.completesAt() <= tick).toList();
        for (StationJob job : completed) {
            FactoryRecipe recipe = requireRecipe(job.recipeId());
            String account = workstationAccount(job.owner());
            Map<String, Map<String, Long>> balances = mutableBalances(ledger.checkpoint().balances());
            Map<String, Long> workstation = balances.get(account);
            long ore = recipe.oreFor(job.batches());
            long polymer = recipe.polymerFor(job.batches());
            if (workstation == null
                    || workstation.getOrDefault(ORE_RESOURCE, 0L) != ore
                    || workstation.getOrDefault(POLYMER_RESOURCE, 0L) != polymer) {
                throw new IllegalStateException("Factory workstation materials do not match its queued recipe");
            }
            workstation.put(ORE_RESOURCE, 0L);
            workstation.put(POLYMER_RESOURCE, 0L);
            long components = Math.addExact(workstation.getOrDefault(COMPONENT_RESOURCE, 0L),
                    recipe.componentsFor(job.batches()));
            if (components > FactorySettings.MAX_BALANCE) {
                throw new IllegalStateException("Factory workstation output buffer is full");
            }
            workstation.put(COMPONENT_RESOURCE, components);
            replaceLedgerBalances(balances);
            stationJobs.remove(job.owner());
        }
    }

    private static Map<String, Map<String, Long>> mutableBalances(
            Map<String, Map<String, Long>> balances) {
        Map<String, Map<String, Long>> mutable = new TreeMap<>();
        balances.forEach((account, resources) -> mutable.put(account, new TreeMap<>(resources)));
        return mutable;
    }

    private void replaceLedgerBalances(Map<String, Map<String, Long>> balances) {
        ConservingResourceLedger.Checkpoint current = ledger.checkpoint();
        ledger = ConservingResourceLedger.restore(new ConservingResourceLedger.Checkpoint(
                balances, current.attempts(), current.maxAttempts()));
    }

    private ProductionActionResult recordProductionAction(EvidenceId evidenceId, AgentId member,
                                                          String fingerprint,
                                                          ProductionActionResult result) {
        ensureProductionActionCapacity();
        ProductionAction action = new ProductionAction(evidenceId, member, fingerprint, result);
        if (productionActions.putIfAbsent(evidenceId, action) != null) {
            throw new IllegalStateException("Factory production evidence was recorded twice");
        }
        return result;
    }

    private void ensureProductionActionCapacity() {
        if (productionActions.size() >= MAX_PRODUCTION_ACTIONS) {
            throw new IllegalStateException("Factory production evidence budget exhausted");
        }
    }

    private void requireUnusedPlayerEvidence(EvidenceId evidenceId) {
        if (bidActions.containsKey(evidenceId) || productionActions.containsKey(evidenceId)
                || ledger.checkpoint().attempts().stream().anyMatch(attempt -> attempt.key().equals(evidenceId))) {
            throw new IllegalArgumentException("Factory action sequence was already used for another action");
        }
    }

    private static void requireSameAction(ProductionAction previousAction, AgentId member,
                                          String fingerprint) {
        if (!previousAction.member().equals(member) || !previousAction.fingerprint().equals(fingerprint)) {
            throw new IllegalArgumentException("Factory action sequence was reused with different input");
        }
    }

    private FactoryRecipe requireRecipe(DesignId recipeId) {
        return RECIPES.stream().filter(recipe -> recipe.id().equals(recipeId)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown factory production recipe"));
    }

    private long totalAvailableMaterialMass() {
        long total = 0;
        for (Map<String, Long> resources : ledger.snapshot().values()) {
            total = Math.addExact(total, resources.getOrDefault(COMPONENT_RESOURCE, 0L));
            total = Math.addExact(total, Math.multiplyExact(2L, resources.getOrDefault(ORE_RESOURCE, 0L)));
            total = Math.addExact(total, resources.getOrDefault(POLYMER_RESOURCE, 0L));
        }
        return total;
    }

    private int fulfillmentTransferCount(MutableOrder order) {
        int transfers = 1;
        long remaining = order.requiredComponents;
        for (long contribution : order.contributions.values()) {
            long used = Math.min(remaining, contribution);
            remaining -= used;
            if (contribution > used) transfers++;
        }
        Bid winningBid = order.bids.get(order.winner);
        if (winningBid.priceCredits() > 0) transfers++;
        if (order.rewardCredits > winningBid.priceCredits()) transfers++;
        if (remaining != 0) throw new IllegalStateException("Factory contribution accounting is inconsistent");
        return transfers;
    }

    private void ensureAutomaticCapacity(int transfers) {
        if (transfers < 0) throw new IllegalArgumentException("Invalid automatic transfer count");
        ConservingResourceLedger.Checkpoint ledgerCheckpoint = ledger.checkpoint();
        if ((long) ledgerCheckpoint.attempts().size() + transfers > ledgerCheckpoint.maxAttempts()
                || (long) nextAutomaticSequence + transfers > MAX_INTERNAL_SEQUENCE) {
            throw new IllegalStateException("Factory settlement evidence budget exhausted before mutation");
        }
    }

    private EvidenceId playerEvidence(long actionSequence, int memberIndex) {
        if (actionSequence < 0 || actionSequence > MAX_ACTION_SEQUENCE) {
            throw new IllegalArgumentException("Factory action sequence is outside the finite bounds");
        }
        int sequence = Math.toIntExact(Math.addExact(Math.multiplyExact(actionSequence, FactorySettings.MEMBER_COUNT), memberIndex));
        return new EvidenceId(settings.worldId(), tick, sequence);
    }

    private int requireMember(AgentId member) {
        Objects.requireNonNull(member, "member");
        int index = settings.members().indexOf(member);
        if (index < 0) throw new SecurityException("Agent is not a member of this factory");
        return index;
    }

    private MutableOrder requireOrder(DesignId orderId) {
        MutableOrder order = orders.get(Objects.requireNonNull(orderId, "orderId"));
        if (order == null) throw new IllegalArgumentException("Unknown factory order");
        return order;
    }

    private List<Order> orderSnapshots() {
        return orders.values().stream().map(MutableOrder::snapshot).toList();
    }

    private void validateState() {
        if (tick > settings.maxSteps() || orders.size() > settings.maxOrders()
                || bidActions.size() > MAX_BID_ACTIONS || productionActions.size() > MAX_PRODUCTION_ACTIONS
                || nextAutomaticSequence < INTERNAL_SEQUENCE_BASE
                || nextAutomaticSequence > MAX_INTERNAL_SEQUENCE
                || ledger.checkpoint().maxAttempts() != MAX_LEDGER_ATTEMPTS
                || ledger.checkpoint().attempts().size() > MAX_LEDGER_ATTEMPTS) {
            throw new IllegalArgumentException("Factory checkpoint exceeds its scenario bounds");
        }
        for (MutableOrder order : orders.values()) validateOrder(order);
        validateLedgerTotals();
        validateStationJobs();
        for (BidAction action : bidActions.values()) {
            if (!settings.members().contains(action.bidder()) || action.evidenceId().tick() > tick
                    || action.evidenceId().sequence() > MAX_ACTION_SEQUENCE * FactorySettings.MEMBER_COUNT + 3) {
                throw new IllegalArgumentException("Factory checkpoint contains invalid bid evidence");
            }
            int memberIndex = action.evidenceId().sequence() % FactorySettings.MEMBER_COUNT;
            if (!settings.members().get(memberIndex).equals(action.bidder())) {
                throw new IllegalArgumentException("Factory bid evidence does not match its member");
            }
            if (productionActions.containsKey(action.evidenceId())) {
                throw new IllegalArgumentException("Factory evidence is shared by bid and production actions");
            }
        }
        for (ProductionAction action : productionActions.values()) {
            EvidenceId evidence = action.evidenceId();
            if (!settings.members().contains(action.member()) || evidence.tick() > tick
                    || evidence.sequence() > MAX_ACTION_SEQUENCE * FactorySettings.MEMBER_COUNT + 3
                    || !settings.members().get(evidence.sequence() % FactorySettings.MEMBER_COUNT)
                    .equals(action.member()) || bidActions.containsKey(evidence)) {
                throw new IllegalArgumentException("Factory checkpoint contains invalid production evidence");
            }
        }
        for (ConservingResourceLedger.Attempt attempt : ledger.checkpoint().attempts()) {
            EvidenceId evidence = attempt.key();
            if (!settings.worldId().equals(evidence.world()) || evidence.tick() > tick) {
                throw new IllegalArgumentException("Factory ledger contains foreign or future evidence");
            }
            if (bidActions.containsKey(evidence) || productionActions.containsKey(evidence)) {
                throw new IllegalArgumentException("Factory ledger evidence is reused by another action");
            }
            int sequence = evidence.sequence();
            if (sequence <= MAX_ACTION_SEQUENCE * FactorySettings.MEMBER_COUNT + 3) {
                int memberIndex = sequence % FactorySettings.MEMBER_COUNT;
                AgentId member = settings.members().get(memberIndex);
                boolean knownBuffer = orders.values().stream()
                        .anyMatch(order -> bufferAccount(order.id).equals(attempt.target()));
                boolean contribution = playerAccount(member).equals(attempt.source()) && knownBuffer
                        && COMPONENT_RESOURCE.equals(attempt.resource());
                boolean collection = workstationAccount(member).equals(attempt.source())
                        && playerAccount(member).equals(attempt.target())
                        && COMPONENT_RESOURCE.equals(attempt.resource());
                if (!contribution && !collection) {
                    throw new IllegalArgumentException("Factory ledger contains invalid member evidence");
                }
            } else if (sequence < INTERNAL_SEQUENCE_BASE || sequence >= nextAutomaticSequence
                    || attempt.result() != TransferResult.APPLIED) {
                throw new IllegalArgumentException("Factory ledger contains invalid settlement evidence");
            }
        }
    }

    private void validateOrder(MutableOrder order) {
        if (!settings.members().contains(order.creator) || order.id.value().length() > 115
                || order.requiredComponents < 1 || order.requiredComponents > scenarioMaterialMass()
                || order.rewardCredits < 1 || order.rewardCredits > settings.initialCredits()
                || order.auctionClosesAt < 1 || order.auctionClosesAt > settings.maxSteps()
                || order.deliveryWindow < 1 || order.bids.size() > FactorySettings.MEMBER_COUNT) {
            throw new IllegalArgumentException("Factory checkpoint contains an invalid order");
        }
        long contributionTotal = 0;
        for (Map.Entry<AgentId, Long> contribution : order.contributions.entrySet()) {
            if (!settings.members().contains(contribution.getKey()) || contribution.getValue() < 1) {
                throw new IllegalArgumentException("Factory checkpoint contains an invalid contribution");
            }
            contributionTotal = Math.addExact(contributionTotal, contribution.getValue());
        }
        for (Bid bid : order.bids.values()) {
            if (!settings.members().contains(bid.bidder()) || bid.priceCredits() > order.rewardCredits
                    || bid.tick() > tick || bid.tick() >= order.auctionClosesAt) {
                throw new IllegalArgumentException("Factory checkpoint contains an invalid bid");
            }
        }
        long escrow = ledger.balance(escrowAccount(order.id), CREDIT_RESOURCE);
        long buffer = ledger.balance(bufferAccount(order.id), COMPONENT_RESOURCE);
        switch (order.status) {
            case OPEN -> {
                if (order.auctionClosesAt <= tick || order.winner != null || order.deliveryDeadline != 0
                        || order.deliveredComponents != 0
                        || escrow != order.rewardCredits || buffer != contributionTotal) invalidOrder();
            }
            case AWARDED -> {
                if (order.winner == null || !order.bids.containsKey(order.winner)
                        || order.deliveryDeadline <= tick || order.deliveredComponents != 0
                        || escrow != order.rewardCredits || buffer != contributionTotal) invalidOrder();
            }
            case DELIVERED -> {
                if (order.winner == null || !order.bids.containsKey(order.winner)
                        || order.deliveredComponents != order.requiredComponents || escrow != 0 || buffer != 0) invalidOrder();
            }
            case FAILED -> {
                if (order.winner == null || !order.bids.containsKey(order.winner)
                        || order.deliveredComponents != 0 || escrow != 0 || buffer != 0) invalidOrder();
            }
            case CANCELLED -> {
                if (order.winner != null || order.deliveredComponents != 0 || !order.bids.isEmpty()
                        || escrow != 0 || buffer != 0) invalidOrder();
            }
        }
    }

    private void validateLedgerTotals() {
        Map<String, Map<String, Long>> balances = ledger.snapshot();
        long materialMass = 0;
        long credits = 0;
        long expectedMass = scenarioMaterialMass();
        for (Map.Entry<String, Map<String, Long>> account : balances.entrySet()) {
            for (Map.Entry<String, Long> resource : account.getValue().entrySet()) {
                long resourceLimit = CREDIT_RESOURCE.equals(resource.getKey())
                        ? FactorySettings.MAX_BALANCE : expectedMass;
                if (resource.getValue() > resourceLimit) {
                    throw new IllegalArgumentException("Factory checkpoint exceeds a resource balance limit");
                }
                if (resource.getKey().equals(COMPONENT_RESOURCE)) materialMass = Math.addExact(materialMass, resource.getValue());
                else if (resource.getKey().equals(CREDIT_RESOURCE)) credits = Math.addExact(credits, resource.getValue());
                else if (resource.getKey().equals(ORE_RESOURCE)) {
                    materialMass = Math.addExact(materialMass, Math.multiplyExact(resource.getValue(), 2L));
                } else if (resource.getKey().equals(POLYMER_RESOURCE)) {
                    materialMass = Math.addExact(materialMass, resource.getValue());
                }
                else throw new IllegalArgumentException("Factory checkpoint contains an unknown resource");
            }
            validateAccount(account.getKey());
        }
        if (materialMass != expectedMass || credits != settings.initialCredits()) {
            throw new IllegalArgumentException("Factory checkpoint violates its resource ledger");
        }
    }

    private long scenarioMaterialMass() {
        long expectedMass = Math.multiplyExact(settings.startingComponents(), FactorySettings.MEMBER_COUNT);
        if (productionMaterialsSeeded) {
            long seededMassPerMember = Math.addExact(Math.multiplyExact(STARTING_ORE_PER_MEMBER, 2L),
                    STARTING_POLYMER_PER_MEMBER);
            expectedMass = Math.addExact(expectedMass,
                    Math.multiplyExact(seededMassPerMember, FactorySettings.MEMBER_COUNT));
        }
        return expectedMass;
    }

    private void validateStationJobs() {
        for (StationJob job : stationJobs.values()) {
            FactoryRecipe recipe = requireRecipe(job.recipeId());
            if (!settings.members().contains(job.owner()) || job.startedAt() > tick
                    || job.completesAt() <= tick
                    || job.completesAt() != Math.addExact(job.startedAt(), recipe.durationFor(job.batches()))) {
                throw new IllegalArgumentException("Factory checkpoint contains an invalid workstation job");
            }
            String account = workstationAccount(job.owner());
            if (ledger.balance(account, ORE_RESOURCE) != recipe.oreFor(job.batches())
                    || ledger.balance(account, POLYMER_RESOURCE) != recipe.polymerFor(job.batches())) {
                throw new IllegalArgumentException("Factory workstation balances do not match its job");
            }
        }
        for (AgentId member : settings.members()) {
            if (!stationJobs.containsKey(member)
                    && (ledger.balance(workstationAccount(member), ORE_RESOURCE) != 0
                    || ledger.balance(workstationAccount(member), POLYMER_RESOURCE) != 0)) {
                throw new IllegalArgumentException("Factory workstation has unassigned raw materials");
            }
        }
    }

    private void validateAccount(String account) {
        if (formatVersion == 1 && settings.members().stream()
                .map(FactoryWorld::workstationAccount).anyMatch(account::equals)) {
            throw new IllegalArgumentException("Legacy factory checkpoint contains a workstation account");
        }
        if (account.equals(TREASURY_ACCOUNT) || account.equals(CUSTOMER_ACCOUNT)
                || settings.members().stream().map(FactoryWorld::playerAccount).anyMatch(account::equals)
                || settings.members().stream().map(FactoryWorld::workstationAccount).anyMatch(account::equals)) return;
        for (MutableOrder order : orders.values()) {
            if (account.equals(bufferAccount(order.id)) || account.equals(escrowAccount(order.id))) return;
        }
        throw new IllegalArgumentException("Factory checkpoint contains an unknown ledger account");
    }

    private String stateHashInternal() {
        return stateHashInternal(true);
    }

    private String legacyStateHashInternal() {
        return stateHashInternal(false);
    }

    private String stateHashInternal(boolean includeProductionState) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                writeText(output, settings.worldId());
                output.writeLong(settings.maxSteps());
                output.writeLong(settings.startingComponents());
                output.writeLong(settings.initialCredits());
                output.writeInt(settings.maxOrders());
                output.writeInt(settings.members().size());
                for (AgentId member : settings.members()) writeText(output, member.value().toString());
                output.writeLong(tick);
                output.writeInt(nextAutomaticSequence);
                output.writeInt(orders.size());
                for (MutableOrder order : orders.values()) writeOrder(output, order.snapshot());
                ConservingResourceLedger.Checkpoint ledgerCheckpoint = ledger.checkpoint();
                writeBalances(output, ledgerCheckpoint.balances());
                output.writeInt(ledgerCheckpoint.maxAttempts());
                output.writeInt(ledgerCheckpoint.attempts().size());
                for (ConservingResourceLedger.Attempt attempt : ledgerCheckpoint.attempts()) {
                    writeText(output, attempt.key().toString());
                    writeText(output, attempt.source());
                    writeText(output, attempt.target());
                    writeText(output, attempt.resource());
                    output.writeLong(attempt.amount());
                    output.writeInt(attempt.result().ordinal());
                }
                List<BidAction> actions = bidActions.values().stream()
                        .sorted(Comparator.comparing(action -> action.evidenceId().toString())).toList();
                output.writeInt(actions.size());
                for (BidAction action : actions) {
                    writeText(output, action.evidenceId().toString());
                    writeText(output, action.bidder().value().toString());
                    writeText(output, action.fingerprint());
                }
                if (includeProductionState) {
                    output.writeBoolean(productionMaterialsSeeded);
                    output.writeInt(stationJobs.size());
                    for (StationJob job : stationJobs.values()) {
                        writeText(output, job.owner().value().toString());
                        writeText(output, job.recipeId().value());
                        output.writeInt(job.batches());
                        output.writeLong(job.startedAt());
                        output.writeLong(job.completesAt());
                    }
                    List<ProductionAction> production = productionActions.values().stream()
                            .sorted(Comparator.comparing(action -> action.evidenceId().toString())).toList();
                    output.writeInt(production.size());
                    for (ProductionAction action : production) {
                        writeText(output, action.evidenceId().toString());
                        writeText(output, action.member().value().toString());
                        writeText(output, action.fingerprint());
                        output.writeInt(action.result().ordinal());
                    }
                }
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException impossible) {
            throw new IllegalStateException("Cannot encode factory state", impossible);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void writeOrder(DataOutputStream output, Order order) throws IOException {
        writeText(output, order.id().value());
        writeText(output, order.creator().value().toString());
        output.writeLong(order.requiredComponents());
        output.writeLong(order.rewardCredits());
        output.writeLong(order.auctionClosesAt());
        output.writeInt(order.deliveryWindow());
        output.writeLong(order.deliveryDeadline());
        output.writeInt(order.status().ordinal());
        output.writeBoolean(order.winner() != null);
        if (order.winner() != null) writeText(output, order.winner().value().toString());
        output.writeLong(order.deliveredComponents());
        output.writeInt(order.bids().size());
        for (Bid bid : order.bids()) {
            writeText(output, bid.bidder().value().toString());
            output.writeLong(bid.priceCredits());
            output.writeLong(bid.tick());
        }
        output.writeInt(order.contributions().size());
        for (Map.Entry<AgentId, Long> contribution : order.contributions().entrySet()) {
            writeText(output, contribution.getKey().value().toString());
            output.writeLong(contribution.getValue());
        }
    }

    private static void writeBalances(DataOutputStream output, Map<String, Map<String, Long>> balances)
            throws IOException {
        output.writeInt(balances.size());
        for (Map.Entry<String, Map<String, Long>> account : balances.entrySet()) {
            writeText(output, account.getKey());
            output.writeInt(account.getValue().size());
            for (Map.Entry<String, Long> balance : account.getValue().entrySet()) {
                writeText(output, balance.getKey());
                output.writeLong(balance.getValue());
            }
        }
    }

    private static void writeText(DataOutputStream output, String text) throws IOException {
        byte[] value = text.getBytes(StandardCharsets.UTF_8);
        output.writeInt(value.length);
        output.write(value);
    }

    private static Map<String, Map<String, Long>> initialBalances(FactorySettings settings,
                                                                  boolean seedProductionMaterials) {
        Map<String, Map<String, Long>> balances = new TreeMap<>();
        for (AgentId member : settings.members()) {
            Map<String, Long> resources = new TreeMap<>();
            resources.put(COMPONENT_RESOURCE, settings.startingComponents());
            if (seedProductionMaterials) {
                resources.put(ORE_RESOURCE, STARTING_ORE_PER_MEMBER);
                resources.put(POLYMER_RESOURCE, STARTING_POLYMER_PER_MEMBER);
            }
            balances.put(playerAccount(member), resources);
        }
        balances.put(TREASURY_ACCOUNT, Map.of(CREDIT_RESOURCE, settings.initialCredits()));
        return balances;
    }

    private static String bufferAccount(DesignId orderId) { return "order:" + orderId.value() + ":buffer"; }
    private static String escrowAccount(DesignId orderId) { return "order:" + orderId.value() + ":escrow"; }

    private void requireOpen() {
        owner.check();
        if (closed) throw new IllegalStateException("Factory world is closed");
    }

    private static void invalidOrder() {
        throw new IllegalArgumentException("Factory checkpoint violates an order escrow invariant");
    }

    public enum OrderStatus { OPEN, AWARDED, DELIVERED, FAILED, CANCELLED }

    public enum ProductionActionResult { QUEUED, STATION_BUSY, INSUFFICIENT_MATERIALS, CANCELLED, NO_JOB }

    public record StationJob(AgentId owner, DesignId recipeId, int batches,
                             long startedAt, long completesAt) {
        public StationJob {
            Objects.requireNonNull(owner, "owner");
            Objects.requireNonNull(recipeId, "recipeId");
            if (batches < 1 || startedAt < 0 || completesAt <= startedAt) {
                throw new IllegalArgumentException("Invalid factory workstation job");
            }
        }
    }

    public record ProductionAction(EvidenceId evidenceId, AgentId member, String fingerprint,
                                   ProductionActionResult result) {
        public ProductionAction {
            Objects.requireNonNull(evidenceId, "evidenceId");
            Objects.requireNonNull(member, "member");
            Objects.requireNonNull(result, "result");
            if (fingerprint == null || fingerprint.length() > 256) {
                throw new IllegalArgumentException("Invalid factory production fingerprint");
            }
        }
    }

    public record WorkstationSnapshot(AgentId owner, long ore, long polymer, long components,
                                      StationJob job) {
        public WorkstationSnapshot {
            Objects.requireNonNull(owner, "owner");
            if (ore < 0 || polymer < 0 || components < 0) {
                throw new IllegalArgumentException("Invalid factory workstation balance");
            }
        }
    }

    public record Bid(AgentId bidder, long priceCredits, long tick) {
        public Bid {
            Objects.requireNonNull(bidder, "bidder");
            if (priceCredits < 0 || tick < 0) throw new IllegalArgumentException("Invalid factory bid");
        }
    }

    public record Order(DesignId id, AgentId creator, long requiredComponents, long rewardCredits,
                        long auctionClosesAt, int deliveryWindow, long deliveryDeadline,
                        OrderStatus status, List<Bid> bids, AgentId winner, long deliveredComponents,
                        Map<AgentId, Long> contributions) {
        public Order {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(creator, "creator");
            Objects.requireNonNull(status, "status");
            if (requiredComponents < 1 || rewardCredits < 1 || auctionClosesAt < 1 || deliveryWindow < 1
                    || deliveryDeadline < 0 || deliveredComponents < 0 || deliveredComponents > requiredComponents) {
                throw new IllegalArgumentException("Invalid factory order state");
            }
            List<Bid> orderedBids = new ArrayList<>(Objects.requireNonNull(bids, "bids"));
            orderedBids.sort(Comparator.comparing(Bid::bidder));
            if (orderedBids.stream().map(Bid::bidder).distinct().count() != orderedBids.size()) {
                throw new IllegalArgumentException("Duplicate factory bid");
            }
            bids = List.copyOf(orderedBids);
            Map<AgentId, Long> orderedContributions = new TreeMap<>();
            Objects.requireNonNull(contributions, "contributions").forEach((member, amount) -> {
                Objects.requireNonNull(member, "contributor");
                if (amount == null || amount < 1) throw new IllegalArgumentException("Invalid factory contribution");
                orderedContributions.put(member, amount);
            });
            contributions = Collections.unmodifiableMap(orderedContributions);
        }
    }

    public record BidAction(EvidenceId evidenceId, AgentId bidder, String fingerprint) {
        public BidAction {
            Objects.requireNonNull(evidenceId, "evidenceId");
            Objects.requireNonNull(bidder, "bidder");
            if (fingerprint == null || fingerprint.length() > 256) {
                throw new IllegalArgumentException("Invalid factory bid fingerprint");
            }
        }
    }

    private static final class MutableOrder {
        private final DesignId id;
        private final AgentId creator;
        private final long requiredComponents;
        private final long rewardCredits;
        private final long auctionClosesAt;
        private final int deliveryWindow;
        private final TreeMap<AgentId, Bid> bids = new TreeMap<>();
        private final TreeMap<AgentId, Long> contributions = new TreeMap<>();
        private OrderStatus status = OrderStatus.OPEN;
        private AgentId winner;
        private long deliveryDeadline;
        private long deliveredComponents;

        private MutableOrder(DesignId id, AgentId creator, long requiredComponents, long rewardCredits,
                             long auctionClosesAt, int deliveryWindow) {
            this.id = id;
            this.creator = creator;
            this.requiredComponents = requiredComponents;
            this.rewardCredits = rewardCredits;
            this.auctionClosesAt = auctionClosesAt;
            this.deliveryWindow = deliveryWindow;
        }

        private MutableOrder(Order order) {
            id = order.id();
            creator = order.creator();
            requiredComponents = order.requiredComponents();
            rewardCredits = order.rewardCredits();
            auctionClosesAt = order.auctionClosesAt();
            deliveryWindow = order.deliveryWindow();
            deliveryDeadline = order.deliveryDeadline();
            status = order.status();
            winner = order.winner();
            deliveredComponents = order.deliveredComponents();
            for (Bid bid : order.bids()) bids.put(bid.bidder(), bid);
            contributions.putAll(order.contributions());
        }

        private Order snapshot() {
            return new Order(id, creator, requiredComponents, rewardCredits, auctionClosesAt,
                    deliveryWindow, deliveryDeadline, status, List.copyOf(bids.values()), winner,
                    deliveredComponents, contributions);
        }
    }
}
