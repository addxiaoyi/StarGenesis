package dev.stargenesis.sim;

import dev.stargenesis.api.sim.AgentId;

import java.util.List;
import java.util.Objects;
import java.util.TreeSet;

/** Bounded four-member virtual factory scenario. Balances are simulation assets only. */
public record FactorySettings(String worldId, List<AgentId> members, long maxSteps,
                              long startingComponents, long initialCredits, int maxOrders) {
    public static final int MEMBER_COUNT = 4;
    public static final int MAX_ORDERS = 64;
    public static final long MAX_BALANCE = 1_000_000_000L;

    public FactorySettings {
        if (worldId == null || !worldId.matches("[a-z0-9][a-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("Invalid factory world ID");
        }
        Objects.requireNonNull(members, "members");
        TreeSet<AgentId> orderedMembers = new TreeSet<>();
        for (AgentId member : members) orderedMembers.add(Objects.requireNonNull(member, "member"));
        if (members.size() != MEMBER_COUNT || orderedMembers.size() != MEMBER_COUNT) {
            throw new IllegalArgumentException("A factory requires four distinct members");
        }
        members = List.copyOf(orderedMembers);
        if (maxSteps < 1 || maxSteps > 72_000 || startingComponents < 1
                || startingComponents > MAX_BALANCE || initialCredits < 1
                || initialCredits > MAX_BALANCE || maxOrders < 1 || maxOrders > MAX_ORDERS) {
            throw new IllegalArgumentException("Factory settings exceed the finite bounds");
        }
    }
}
