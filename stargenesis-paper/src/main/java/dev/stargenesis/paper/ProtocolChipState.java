package dev.stargenesis.paper;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

record ProtocolChipState(UUID ownerId, Set<String> unlockedProtocolIds, String equippedProtocolId) {
    private static final String PROTOCOL_ID = "[a-z][a-z0-9_-]{0,31}:[a-z0-9][a-z0-9_./-]{0,94}";
    static final String STARTER_PROTOCOL_ID = "genesis:protocol";
    static final int MAX_UNLOCKED_PROTOCOLS = 64;

    ProtocolChipState {
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(unlockedProtocolIds, "unlockedProtocolIds");
        if (unlockedProtocolIds.size() > MAX_UNLOCKED_PROTOCOLS
                || unlockedProtocolIds.stream().anyMatch(id -> id == null || !id.matches(PROTOCOL_ID)))
            throw new IllegalArgumentException("Invalid unlocked protocol chips");
        unlockedProtocolIds = Set.copyOf(unlockedProtocolIds);
        Objects.requireNonNull(equippedProtocolId, "equippedProtocolId");
        if (!equippedProtocolId.isEmpty() && (!equippedProtocolId.matches(PROTOCOL_ID)
                || !unlockedProtocolIds.contains(equippedProtocolId)))
            throw new IllegalArgumentException("Equipped protocol chip is not unlocked");
    }

    static ProtocolChipState empty(UUID ownerId) {
        return new ProtocolChipState(ownerId, Set.of(), "");
    }

    boolean isUnlocked(String protocolId) {
        return unlockedProtocolIds.contains(protocolId);
    }

    ProtocolChipState unlock(String protocolId) {
        if (protocolId == null || !protocolId.matches(PROTOCOL_ID))
            throw new IllegalArgumentException("Invalid protocol chip identifier");
        if (unlockedProtocolIds.contains(protocolId)) return this;
        if (unlockedProtocolIds.size() >= MAX_UNLOCKED_PROTOCOLS)
            throw new IllegalStateException("Protocol chip storage is full");
        java.util.HashSet<String> unlocked = new java.util.HashSet<>(unlockedProtocolIds);
        unlocked.add(protocolId);
        return new ProtocolChipState(ownerId, unlocked, equippedProtocolId);
    }

    ProtocolChipState equip(String protocolId) {
        if (!unlockedProtocolIds.contains(protocolId))
            throw new IllegalArgumentException("Protocol chip is not unlocked");
        return new ProtocolChipState(ownerId, unlockedProtocolIds, protocolId);
    }

    ProtocolChipState unequip() {
        return new ProtocolChipState(ownerId, unlockedProtocolIds, "");
    }
}
