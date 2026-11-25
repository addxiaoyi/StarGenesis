package dev.stargenesis.paper;

import dev.stargenesis.sim.EcologyCheckpoint;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

record EcologyGroupRecord(UUID id, UUID ownerId, List<UUID> members, EcologyCheckpoint checkpoint) {
    static final int MAX_MEMBERS = 8;

    EcologyGroupRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(ownerId, "ownerId");
        Objects.requireNonNull(members, "members");
        Objects.requireNonNull(checkpoint, "checkpoint");
        if (!checkpoint.settings().worldId().equals(EcologySession.sharedWorldId(id))
                || checkpoint.settings().seed() != (id.getMostSignificantBits() ^ id.getLeastSignificantBits()))
            throw new IllegalArgumentException("Ecology group checkpoint belongs to another group");
        List<UUID> copiedMembers = new ArrayList<>(members);
        if (copiedMembers.isEmpty() || copiedMembers.size() > MAX_MEMBERS
                || copiedMembers.stream().anyMatch(Objects::isNull)
                || copiedMembers.stream().distinct().count() != copiedMembers.size()
                || !copiedMembers.contains(ownerId))
            throw new IllegalArgumentException("Ecology group member roster is invalid");
        copiedMembers.sort(Comparator.comparing(UUID::toString));
        members = List.copyOf(copiedMembers);
    }

    boolean contains(UUID playerId) {
        return members.contains(playerId);
    }

    EcologyGroupRecord addMember(UUID actorId, UUID memberId) {
        requireOwner(actorId);
        Objects.requireNonNull(memberId, "memberId");
        if (members.contains(memberId)) return this;
        if (members.size() >= MAX_MEMBERS) throw new IllegalStateException("Ecology group is full");
        List<UUID> updated = new ArrayList<>(members);
        updated.add(memberId);
        return new EcologyGroupRecord(id, ownerId, updated, checkpoint);
    }

    EcologyGroupRecord removeMember(UUID actorId, UUID memberId) {
        requireOwner(actorId);
        Objects.requireNonNull(memberId, "memberId");
        if (ownerId.equals(memberId)) throw new SecurityException("Ecology group owner cannot be removed");
        if (!members.contains(memberId)) throw new IllegalArgumentException("Player is not an ecology group member");
        return new EcologyGroupRecord(id, ownerId,
                members.stream().filter(existing -> !existing.equals(memberId)).toList(), checkpoint);
    }

    EcologyGroupRecord leave(UUID memberId) {
        Objects.requireNonNull(memberId, "memberId");
        if (ownerId.equals(memberId)) throw new SecurityException("Ecology group owner cannot leave");
        if (!members.contains(memberId)) throw new IllegalArgumentException("Player is not an ecology group member");
        return new EcologyGroupRecord(id, ownerId,
                members.stream().filter(existing -> !existing.equals(memberId)).toList(), checkpoint);
    }

    EcologyGroupRecord withCheckpoint(EcologyCheckpoint updatedCheckpoint) {
        return new EcologyGroupRecord(id, ownerId, members, updatedCheckpoint);
    }

    void requireOwner(UUID actorId) {
        if (!ownerId.equals(actorId)) throw new SecurityException("Only the ecology group owner can change its roster");
    }
}
