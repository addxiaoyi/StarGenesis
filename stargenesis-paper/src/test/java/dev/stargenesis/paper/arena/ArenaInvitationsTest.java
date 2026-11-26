package dev.stargenesis.paper.arena;

import dev.stargenesis.paper.runtime.WorkspaceRace;
import dev.stargenesis.paper.runtime.PersistentWorker;
import dev.stargenesis.paper.runtime.PlayerCommandQueue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ArenaInvitationsTest {
    private static final UUID ALICE = new UUID(0, 401), BOB = new UUID(0, 402), CAROL = new UUID(0, 403);
    @TempDir Path directory;

    @Test void invitationCapacityIsBoundedBeforeAnyTeamWrite() {
        var invitations = new ArenaInvitations();
        for (int index = 0; index < 64; index++)
            invitations.invite(new UUID(1, 2L * index), new UUID(1, 2L * index + 1), "cedar");
        assertThrows(IllegalStateException.class, () -> invitations.invite(ALICE, BOB, "cedar"));
    }

    @Test void aPlayerCannotTakeBothInvitationRolesOrJoinAnInvitationChain() {
        var invitations = new ArenaInvitations();
        invitations.invite(ALICE, BOB, "cedar");
        assertThrows(IllegalStateException.class, () -> invitations.invite(BOB, CAROL, "cedar"));
        assertThrows(IllegalStateException.class, () -> invitations.invite(CAROL, ALICE, "cedar"));
    }

    @Test void disconnectedInviterIsNotRestoredByAnAlreadyAdmittedFailedSave() throws Exception {
        cancelledFailedSave(false);
    }

    @Test void closedInvitationsAreNotRestoredByAnAlreadyAdmittedFailedSave() throws Exception {
        cancelledFailedSave(true);
    }

    @Test void failedRosterWriteKeepsTheOriginalInvitationForAnExactRetry() throws Exception {
        var persistence = new ArenaMatchPersistence(directory.resolve("matches"));
        var rulesets = new ArenaRulesetStore(directory.resolve("rulesets"));
        var service = new ArenaMatchService(rulesets, persistence);
        var invitations = new ArenaInvitations();
        invitations.invite(ALICE, BOB, "cedar");
        Path blocker = Files.createDirectory(directory.resolve("matches/teams.json.tmp"));
        assertThrows(IOException.class, () -> invitations.accept(BOB, service::joinTeam));
        assertTrue(service.roster().isEmpty());
        assertTrue(persistence.loadTeamRoster().isEmpty());
        assertThrows(IllegalStateException.class, () -> invitations.invite(CAROL, BOB, "ember"));
        assertThrows(IllegalStateException.class, () -> invitations.invite(ALICE, CAROL, "ember"));
        Files.delete(blocker);
        var accepted = invitations.accept(BOB, service::joinTeam);
        assertEquals(new ArenaInvitations.Invitation(ALICE, "cedar"), accepted);
        assertEquals(java.util.Map.of(ALICE, "cedar", BOB, "cedar"), new ArenaMatchService(rulesets, persistence).roster());
        assertThrows(IllegalStateException.class, () -> invitations.accept(BOB, service::joinTeam));
    }

    @Test void inFlightAcceptanceKeepsBothPlayersAndItsCapacitySlotReserved() throws Exception {
        var invitations = new ArenaInvitations();
        invitations.invite(ALICE, BOB, "cedar");
        for (int index = 0; index < 63; index++)
            invitations.invite(new UUID(1, 2L * index), new UUID(1, 2L * index + 1), "cedar");
        var accepted = invitations.accept(BOB, (inviter, invited, team) -> {
            assertThrows(IllegalStateException.class, () -> invitations.accept(BOB, (a, b, c) -> fail("Duplicate acceptance")));
            assertThrows(IllegalStateException.class, () -> invitations.invite(ALICE, CAROL, "cedar"));
            assertThrows(IllegalStateException.class, () -> invitations.invite(CAROL, BOB, "cedar"));
            assertThrows(IllegalStateException.class, () -> invitations.invite(CAROL, new UUID(2, 0), "cedar"));
        });
        assertEquals(ALICE, accepted.inviter());
        invitations.invite(CAROL, new UUID(2, 0), "cedar");
        assertThrows(IllegalStateException.class, () -> invitations.invite(ALICE, BOB, "cedar"));
    }

    @Test void concurrentDuplicateAcceptanceCannotEnterTheRosterStoreTwice() throws Exception {
        var persistence = new ArenaMatchPersistence(directory.resolve("matches"));
        var service = new ArenaMatchService(new ArenaRulesetStore(directory.resolve("rulesets")), persistence);
        var invitations = new ArenaInvitations();
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        ArenaInvitations.TeamJoiner join = (inviter, invited, team) -> {
            calls.incrementAndGet();
            service.joinTeam(inviter, invited, team);
        };
        invitations.invite(ALICE, BOB, "cedar");
        WorkspaceRace.duringStoreCall(persistence, () -> invitations.accept(BOB, join), invitations,
                () -> assertThrows(IllegalStateException.class, () -> invitations.accept(BOB, join)));
        assertEquals(1, calls.get());
        assertEquals(java.util.Map.of(ALICE, "cedar", BOB, "cedar"), persistence.loadTeamRoster());
    }

    @Test void olderCompletionCannotRemoveOrReplaceANewerInvitationForTheSameRecipient() throws Exception {
        for (boolean failed : new boolean[]{false, true}) {
            var invitations = new ArenaInvitations();
            invitations.invite(ALICE, BOB, "cedar");
            ArenaInvitations.TeamJoiner old = (inviter, invited, team) -> {
                invitations.cancel(BOB);
                invitations.invite(CAROL, BOB, "ember");
                if (failed) throw new IOException("Old persistence attempt failed");
            };
            if (failed) assertThrows(IOException.class, () -> invitations.accept(BOB, old));
            else assertEquals(ALICE, invitations.accept(BOB, old).inviter());
            var replacement = invitations.accept(BOB, (inviter, invited, team) -> {
                assertEquals(CAROL, inviter);
                assertEquals(BOB, invited);
                assertEquals("ember", team);
            });
            assertEquals(new ArenaInvitations.Invitation(CAROL, "ember"), replacement);
        }
    }

    @Test void shutdownDoesNotUndoAnAlreadyAdmittedSuccessfulRosterCommit() throws Exception {
        var persistence = new ArenaMatchPersistence(directory.resolve("matches"));
        var rulesets = new ArenaRulesetStore(directory.resolve("rulesets"));
        var service = new ArenaMatchService(rulesets, persistence);
        var invitations = new ArenaInvitations();
        invitations.invite(ALICE, BOB, "cedar");
        WorkspaceRace.duringStoreCall(persistence, () -> invitations.accept(BOB, service::joinTeam), invitations, invitations::close);
        byte[] saved = Files.readAllBytes(directory.resolve("matches/teams.json"));
        assertEquals(java.util.Map.of(ALICE, "cedar", BOB, "cedar"), new ArenaMatchService(rulesets, persistence).roster());
        assertThrows(IllegalStateException.class, () -> invitations.invite(CAROL, BOB, "ember"));
        assertThrows(IllegalStateException.class, () -> invitations.accept(BOB, service::joinTeam));
        invitations.close();
        assertArrayEquals(saved, Files.readAllBytes(directory.resolve("matches/teams.json")));
    }

    @Test void cancellationReclaimsCapacityFromEitherRoleAndCloseRejectsAllNewAdmission() {
        var invitations = new ArenaInvitations();
        for (int index = 0; index < 64; index++)
            invitations.invite(new UUID(1, 2L * index), new UUID(1, 2L * index + 1), "cedar");
        invitations.cancel(new UUID(1, 0));
        invitations.invite(ALICE, BOB, "cedar");
        invitations.cancel(BOB);
        invitations.invite(CAROL, ALICE, "ember");
        invitations.close();
        invitations.cancel(CAROL);
        assertThrows(IllegalStateException.class, () -> invitations.accept(ALICE, (a, b, c) -> fail("Closed admission")));
        assertThrows(IllegalStateException.class, () -> invitations.invite(ALICE, BOB, "cedar"));
        assertThrows(IllegalStateException.class, () -> new ArenaInvitations().accept(ALICE, (a, b, c) -> fail("Restored transient invite")));
    }

    @Test void rejectedMembershipDiscardsTheInvitationAndInvalidInputDoesNotOccupyPlayers() throws Exception {
        var invitations = new ArenaInvitations();
        assertThrows(IllegalArgumentException.class, () -> invitations.invite(ALICE, ALICE, "cedar"));
        assertThrows(IllegalArgumentException.class, () -> invitations.invite(ALICE, BOB, "Invalid Team!"));
        invitations.invite(ALICE, BOB, "cedar");
        assertThrows(IllegalStateException.class, () -> invitations.accept(BOB, (a, b, c) -> {
            throw new IllegalStateException("A party already joined another team");
        }));
        invitations.invite(CAROL, BOB, "ember");
        assertEquals(CAROL, invitations.accept(BOB, (a, b, c) -> {}).inviter());
    }

    @Test void commandCompletionCannotRetainALateInvitationAfterEitherConnectionRetires() throws Exception {
        for (UUID leaving : java.util.List.of(ALICE, BOB)) {
            var invitations = new ArenaInvitations();
            var entered = new java.util.concurrent.CountDownLatch(1);
            var release = new java.util.concurrent.CountDownLatch(1);
            var added = new java.util.concurrent.CompletableFuture<Void>();
            try (var worker = PersistentWorker.commands("arena-late-invitation", directory.resolve(leaving.toString()), 1)) {
                var commands = new PlayerCommandQueue(worker, invitations::cancel, invitations::retainOnline);
                var alice = commands.connected(ALICE);
                var bob = commands.connected(BOB);
                commands.execute(alice, bob, () -> {
                    try {
                        entered.countDown();
                        assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
                        invitations.invite(ALICE, BOB, "cedar");
                        added.complete(null);
                    } catch (Throwable failure) { added.completeExceptionally(failure); }
                });
                assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
                commands.disconnected(leaving);
                commands.connected(leaving);
                release.countDown();
                added.get(5, java.util.concurrent.TimeUnit.SECONDS);
                assertTrue(worker.shutdown(java.time.Duration.ofSeconds(5), java.time.Duration.ofSeconds(5)).complete());
            } finally { release.countDown(); }
            assertThrows(IllegalStateException.class, () -> invitations.accept(BOB, (a, b, c) -> fail("Retired invitation")));
            invitations.invite(ALICE, BOB, "cedar");
            assertEquals(ALICE, invitations.accept(BOB, (a, b, c) -> {}).inviter());
        }
    }

    private void cancelledFailedSave(boolean close) throws Exception {
        var persistence = new ArenaMatchPersistence(directory.resolve("matches"));
        var service = new ArenaMatchService(new ArenaRulesetStore(directory.resolve("rulesets")), persistence);
        var invitations = new ArenaInvitations();
        invitations.invite(ALICE, BOB, "cedar");
        Files.createDirectory(directory.resolve("matches/teams.json.tmp"));
        WorkspaceRace.duringStoreCall(persistence, () -> {
            assertThrows(IOException.class, () -> invitations.accept(BOB, service::joinTeam));
            return null;
        }, invitations, () -> { if (close) invitations.close(); else invitations.cancel(ALICE); });
        assertTrue(service.roster().isEmpty());
        assertFalse(Files.exists(directory.resolve("matches/teams.json")));
        assertThrows(IllegalStateException.class, () -> invitations.accept(BOB, service::joinTeam));
    }
}
