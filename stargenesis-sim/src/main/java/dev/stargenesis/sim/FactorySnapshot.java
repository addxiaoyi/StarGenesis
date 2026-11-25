package dev.stargenesis.sim;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record FactorySnapshot(String worldId, long tick, List<FactoryWorld.Order> orders,
                              Map<String, Map<String, Long>> balances, String stateHash) {
    public FactorySnapshot {
        if (worldId == null || !worldId.matches("[a-z0-9][a-z0-9_-]{0,63}") || tick < 0
                || stateHash == null || !stateHash.matches("[0-9a-f]{64}")) {
            throw new IllegalArgumentException("Invalid factory snapshot metadata");
        }
        orders = List.copyOf(Objects.requireNonNull(orders, "orders"));
        balances = Objects.requireNonNull(balances, "balances").entrySet().stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey,
                        entry -> Map.copyOf(entry.getValue())));
    }
}
