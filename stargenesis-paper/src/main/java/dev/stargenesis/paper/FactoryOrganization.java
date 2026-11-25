package dev.stargenesis.paper;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeSet;
import java.util.UUID;

/** Immutable, server-owned roster for a factory before its simulation starts. */
record FactoryOrganization(String name, UUID owner, List<UUID> members) {
    static final int MAX_MEMBERS = 4;

    FactoryOrganization {
        if (name == null || !name.matches("[a-z][a-z0-9_-]{0,31}")) {
            throw new IllegalArgumentException("Organization name must be a lowercase ID of at most 32 characters");
        }
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(members, "members");
        TreeSet<UUID> orderedMembers = new TreeSet<>();
        for (UUID member : members) orderedMembers.add(Objects.requireNonNull(member, "member"));
        if (orderedMembers.isEmpty() || orderedMembers.size() > MAX_MEMBERS
                || orderedMembers.size() != members.size() || !orderedMembers.contains(owner)) {
            throw new IllegalArgumentException("Organization must contain its owner and at most four distinct members");
        }
        members = List.copyOf(orderedMembers);
    }

    FactoryOrganization addMember(UUID member) {
        Objects.requireNonNull(member, "member");
        if (members.contains(member)) throw new IllegalArgumentException("Player is already an organization member");
        if (members.size() == MAX_MEMBERS) throw new IllegalStateException("Organization already has four members");
        List<UUID> updated = new ArrayList<>(members);
        updated.add(member);
        return new FactoryOrganization(name, owner, updated);
    }

    FactoryOrganization removeMember(UUID member) {
        Objects.requireNonNull(member, "member");
        if (owner.equals(member)) throw new IllegalArgumentException("The organization owner cannot be removed");
        if (!members.contains(member)) throw new IllegalArgumentException("Player is not an organization member");
        List<UUID> updated = new ArrayList<>(members);
        updated.remove(member);
        return new FactoryOrganization(name, owner, updated);
    }
}
