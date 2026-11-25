package dev.stargenesis.paper;

import dev.stargenesis.sim.EcologyCheckpoint;
import dev.stargenesis.sim.EcologyWorld;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EcologyGroupRecordTest {
    private static final UUID GROUP_ID = UUID.fromString("00000000-0000-0000-0000-000000000100");
    private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");
    private static final UUID MEMBER_ID = UUID.fromString("00000000-0000-0000-0000-000000000102");

    @Test
    void ownerControlsRosterAndMembersMayLeave() {
        EcologyGroupRecord group = group();
        EcologyGroupRecord joined = group.addMember(OWNER_ID, MEMBER_ID);

        assertEquals(2, joined.members().size());
        assertTrue(joined.contains(OWNER_ID));
        assertTrue(joined.contains(MEMBER_ID));
        assertSame(joined, joined.addMember(OWNER_ID, MEMBER_ID));
        assertThrows(SecurityException.class, () -> group.addMember(MEMBER_ID, UUID.randomUUID()));
        assertThrows(SecurityException.class, () -> joined.requireOwner(MEMBER_ID));
        assertThrows(SecurityException.class, () -> joined.removeMember(MEMBER_ID, OWNER_ID));
        assertThrows(SecurityException.class, () -> joined.leave(OWNER_ID));
        assertEquals(List.of(OWNER_ID), joined.leave(MEMBER_ID).members());
    }

    @Test
    void rosterAndCheckpointAreBoundedToTheirGroup() {
        EcologyGroupRecord group = group();
        List<UUID> fullRoster = new ArrayList<>();
        for (int index = 0; index < EcologyGroupRecord.MAX_MEMBERS; index++)
            fullRoster.add(new UUID(0, index + 1));
        EcologyGroupRecord full = new EcologyGroupRecord(GROUP_ID, fullRoster.getFirst(), fullRoster,
                group.checkpoint());

        assertThrows(IllegalStateException.class, () -> full.addMember(full.ownerId(), UUID.randomUUID()));
        assertThrows(IllegalArgumentException.class, () -> new EcologyGroupRecord(UUID.randomUUID(), OWNER_ID,
                List.of(OWNER_ID), group.checkpoint()));
        assertThrows(IllegalArgumentException.class, () -> group.removeMember(OWNER_ID, UUID.randomUUID()));
    }

    private static EcologyGroupRecord group() {
        try (EcologyWorld world = new EcologyWorld(EcologySession.sharedSettings(GROUP_ID))) {
            EcologyCheckpoint checkpoint = world.checkpoint();
            return new EcologyGroupRecord(GROUP_ID, OWNER_ID, List.of(OWNER_ID), checkpoint);
        }
    }
}
