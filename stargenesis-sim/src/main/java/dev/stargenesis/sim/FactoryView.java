package dev.stargenesis.sim;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Immutable organization, auction, order and match statistics. */
public record FactoryView(String matchId, long seed, long tick, Map<String, Organization> organizations,
                          Auction auction, List<Order> orders, int completedOrders,
                          int recoveredOrders, long producedParts, String stateHash,
                          boolean terminal, String reason) {
    public FactoryView {
        organizations = Collections.unmodifiableMap(new TreeMap<>(organizations));
        orders = List.copyOf(orders);
    }

    public record Organization(String id, List<String> members, long credits, long ore, long parts) {
        public Organization { members = List.copyOf(members); }
    }

    public record Auction(String id, long closesAt, String state, List<Bid> bids, String winner,
                          long winningBid, long lotOre) {
        public Auction { bids = List.copyOf(bids); }
    }

    public record Bid(String organization, long amount) { }

    public record Order(String id, String organization, long quantity, long createdAt,
                        long completesAt, String state, String failure) { }
}
