package dev.stargenesis.paper;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FactoryOrganizationSessionTest {
    private static final List<UUID> MEMBERS = List.of(1, 2, 3, 4, 5).stream()
            .map(index -> new UUID(0, index)).toList();

    @Test
    void ownerBuildsFourMemberRosterAndOnlyThenCanStartFactory() {
        FactoryOrganizationSession.CommandResult created = FactoryOrganizationSession.create(
                MEMBERS.getFirst(), "research_team", false);
        FactoryOrganizationSession current = created.session();
        assertTrue(created.changed());
        FactoryOrganizationSession partial = current;
        assertThrows(IllegalStateException.class, () -> partial.factoryMembers(MEMBERS.getFirst()));

        for (int index = 1; index < 4; index++) {
            current = current.command(MEMBERS.getFirst(), new String[] {"add", MEMBERS.get(index).toString()}, false)
                    .session();
        }

        assertEquals(4, current.organization().members().size());
        assertEquals(current.organization().members(), current.factoryMembers(MEMBERS.getFirst()));
        FactoryOrganizationSession full = current;
        assertThrows(IllegalStateException.class, () -> full.command(MEMBERS.getFirst(),
                new String[] {"add", MEMBERS.get(4).toString()}, false));
    }

    @Test
    void ownerAuthorizationAndRosterFreezeAreEnforced() {
        FactoryOrganizationSession current = FactoryOrganizationSession.create(
                MEMBERS.getFirst(), "research_team", false).session();
        FactoryOrganizationSession initial = current;
        assertThrows(SecurityException.class, () -> initial.command(MEMBERS.get(1),
                new String[] {"add", MEMBERS.get(1).toString()}, false));

        current = current.command(MEMBERS.getFirst(), new String[] {"add", MEMBERS.get(1).toString()}, false).session();
        FactoryOrganizationSession memberAdded = current;
        assertThrows(IllegalArgumentException.class, () -> memberAdded.command(MEMBERS.getFirst(),
                new String[] {"remove", MEMBERS.getFirst().toString()}, false));
        assertThrows(IllegalStateException.class, () -> memberAdded.command(MEMBERS.getFirst(),
                new String[] {"add", MEMBERS.get(2).toString()}, true));
        assertThrows(IllegalStateException.class, () -> FactoryOrganizationSession.create(
                MEMBERS.getFirst(), "other_team", true));
    }

    @Test
    void removesMembersWithoutRemovingOwnerAndValidatesNamesAndUuids() {
        FactoryOrganizationSession current = FactoryOrganizationSession.create(
                MEMBERS.getFirst(), "research_team", false).session();
        current = current.command(MEMBERS.getFirst(), new String[] {"add", MEMBERS.get(1).toString()}, false).session();
        current = current.command(MEMBERS.getFirst(), new String[] {"remove", MEMBERS.get(1).toString()}, false).session();
        assertEquals(List.of(MEMBERS.getFirst()), current.organization().members());
        FactoryOrganizationSession ownerOnly = current;
        assertThrows(IllegalArgumentException.class, () -> ownerOnly.command(MEMBERS.getFirst(),
                new String[] {"add", "not-a-uuid"}, false));
        assertThrows(IllegalArgumentException.class, () -> FactoryOrganizationSession.create(
                MEMBERS.getFirst(), "Research Team", false));
        assertThrows(SecurityException.class, () -> FactoryOrganizationSession.create(null, "research_team", false));
    }

    @Test
    void rejectsUnexpectedStatusArguments() {
        FactoryOrganizationSession session = FactoryOrganizationSession.create(
                MEMBERS.getFirst(), "research_team", false).session();
        assertThrows(IllegalArgumentException.class, () -> session.command(MEMBERS.getFirst(),
                new String[] {"status", "unexpected"}, false));
    }
}
