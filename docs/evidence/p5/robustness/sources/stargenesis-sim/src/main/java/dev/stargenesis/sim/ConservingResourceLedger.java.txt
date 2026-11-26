package dev.stargenesis.sim;

import dev.stargenesis.api.sim.EvidenceId;
import dev.stargenesis.api.sim.ResourceLedger;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Bounded owner-thread reference ledger for simulation assets only. Initial balances are scenario
 * inputs, never Minecraft inventory. Failed attempts reserve their evidence ID: funding an account
 * later does not change an earlier failure; retry with a new evidence ID. No history is evicted.
 */
public final class ConservingResourceLedger implements ResourceLedger {
    public static final int DEFAULT_MAX_ATTEMPTS = 16_384;
    private final OwnerThread owner = new OwnerThread();
    private final Map<String, Map<String, Long>> balances = new TreeMap<>();
    private final Map<EvidenceId, Attempt> attempts = new LinkedHashMap<>();
    private final int maxAttempts;

    public ConservingResourceLedger(Map<String, Map<String, Long>> initial) {
        this(initial, DEFAULT_MAX_ATTEMPTS);
    }

    public ConservingResourceLedger(Map<String, Map<String, Long>> initial, int maxAttempts) {
        this.maxAttempts = checkedCapacity(maxAttempts);
        balances.putAll(immutableBalances(initial));
        balances.replaceAll((account, resources) -> new TreeMap<>(resources));
    }

    private ConservingResourceLedger(Checkpoint checkpoint) {
        this(checkpoint.balances(), checkpoint.maxAttempts());
        for (Attempt attempt : checkpoint.attempts()) {
            if (attempts.putIfAbsent(attempt.key(), attempt) != null) {
                throw new IllegalArgumentException("Duplicate checkpoint evidence");
            }
        }
    }

    @Override public long balance(String account, String resource) {
        owner.check(); validateName(account); validateName(resource);
        return balances.getOrDefault(account, Map.of()).getOrDefault(resource, 0L);
    }

    @Override public TransferResult transfer(EvidenceId key, String source, String target,
                                             String resource, long amount) {
        owner.check();
        Objects.requireNonNull(key, "evidence ID");
        validateRequest(source, target, resource, amount);
        Attempt previous = attempts.get(key);
        if (previous != null) {
            if (!previous.sameRequest(source, target, resource, amount)) {
                throw new IllegalArgumentException("Evidence ID reused with a different transfer");
            }
            return previous.result() == TransferResult.APPLIED
                    ? TransferResult.ALREADY_APPLIED : previous.result();
        }
        if (attempts.size() >= maxAttempts) {
            throw new IllegalStateException("Ledger evidence budget exhausted; checkpoint and end scenario");
        }
        long available = balance(source, resource);
        if (available < amount) {
            attempts.put(key, new Attempt(key, source, target, resource, amount, TransferResult.INSUFFICIENT_FUNDS));
            return TransferResult.INSUFFICIENT_FUNDS;
        }
        long receiving = Math.addExact(balance(target, resource), amount);
        // Validate every operation before changing either side.
        if (!balances.containsKey(target) && balances.size() >= 4096) {
            throw new IllegalStateException("Ledger account budget exhausted");
        }
        if (!balances.getOrDefault(target, Map.of()).containsKey(resource)
                && balances.values().stream().mapToInt(Map::size).sum() >= 16_384) {
            throw new IllegalStateException("Ledger resource-entry budget exhausted");
        }
        balances.get(source).put(resource, available - amount);
        balances.computeIfAbsent(target, ignored -> new TreeMap<>()).put(resource, receiving);
        attempts.put(key, new Attempt(key, source, target, resource, amount, TransferResult.APPLIED));
        return TransferResult.APPLIED;
    }

    @Override public Map<String, Map<String, Long>> snapshot() {
        owner.check(); return immutableBalances(balances);
    }

    /** Full in-memory restart value, including failed and successful idempotency history. */
    public Checkpoint checkpoint() {
        owner.check(); return new Checkpoint(snapshot(), List.copyOf(attempts.values()), maxAttempts);
    }

    /** The restored instance belongs to the thread executing this method. No file IO is performed. */
    public static ConservingResourceLedger restore(Checkpoint checkpoint) {
        return new ConservingResourceLedger(Objects.requireNonNull(checkpoint));
    }

    public record Checkpoint(Map<String, Map<String, Long>> balances, List<Attempt> attempts, int maxAttempts) {
        public Checkpoint {
            checkedCapacity(maxAttempts);
            balances = immutableBalances(balances);
            attempts = List.copyOf(attempts);
            if (attempts.size() > maxAttempts || attempts.stream().map(Attempt::key).distinct().count() != attempts.size()) {
                throw new IllegalArgumentException("Invalid checkpoint evidence history");
            }
        }
    }

    public record Attempt(EvidenceId key, String source, String target, String resource, long amount, TransferResult result) {
        public Attempt {
            Objects.requireNonNull(key); Objects.requireNonNull(result);
            validateRequest(source, target, resource, amount);
            if (result == TransferResult.ALREADY_APPLIED) {
                throw new IllegalArgumentException("Checkpoint must contain original transfer outcomes");
            }
        }
        boolean sameRequest(String source, String target, String resource, long amount) {
            return this.source.equals(source) && this.target.equals(target) && this.resource.equals(resource) && this.amount == amount;
        }
    }

    private static int checkedCapacity(int capacity) {
        if (capacity < 1 || capacity > 100_000) throw new IllegalArgumentException("Invalid evidence budget");
        return capacity;
    }

    private static void validateRequest(String source, String target, String resource, long amount) {
        validateName(source); validateName(target); validateName(resource);
        if (amount <= 0 || source.equals(target)) throw new IllegalArgumentException("Positive amount and distinct accounts required");
    }

    private static void validateName(String value) {
        if (value == null || !value.matches("[a-z0-9][a-z0-9_.:/-]{0,127}")) {
            throw new IllegalArgumentException("Invalid bounded account or resource ID");
        }
    }

    private static Map<String, Map<String, Long>> immutableBalances(Map<String, Map<String, Long>> source) {
        Objects.requireNonNull(source);
        if (source.size() > 4096) throw new IllegalArgumentException("Too many ledger accounts");
        Map<String, Map<String, Long>> copy = new TreeMap<>();
        int entries = 0;
        for (var account : source.entrySet()) {
            validateName(account.getKey());
            Map<String, Long> resources = new TreeMap<>();
            for (var item : Objects.requireNonNull(account.getValue()).entrySet()) {
                validateName(item.getKey());
                if (item.getValue() == null || item.getValue() < 0) throw new IllegalArgumentException("Negative or null resource balance");
                resources.put(item.getKey(), item.getValue());
                if (++entries > 16_384) throw new IllegalArgumentException("Too many ledger resource entries");
            }
            copy.put(account.getKey(), Collections.unmodifiableMap(resources));
        }
        return Collections.unmodifiableMap(copy);
    }
}
