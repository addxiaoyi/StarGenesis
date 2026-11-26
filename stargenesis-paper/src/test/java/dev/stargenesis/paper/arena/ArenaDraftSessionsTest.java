package dev.stargenesis.paper.arena;

import dev.stargenesis.api.design.DesignId;
import dev.stargenesis.design.ArenaRuleset;
import dev.stargenesis.design.ArenaRulesetWorkspace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ArenaDraftSessionsTest {
    private static final UUID AUTHOR = new UUID(0, 701);
    private static final DesignId ID = new DesignId("player:session-lifecycle");
    @TempDir Path directory;

    @Test void disconnectDiscardsOnlyUnsavedWorkspaceAndRejoinLoadsSavedBytes() throws Exception {
        var store = new ArenaRulesetStore(directory);
        var sessions = new ArenaDraftSessions(store);
        ArenaRuleset saved = sessions.create(AUTHOR, ID);
        var old = sessions.require(AUTHOR);
        old.workspace.resize(24, 24);
        assertTrue(old.dirty());
        sessions.disconnected(AUTHOR);
        assertEquals(0, sessions.size());
        assertThrows(IllegalArgumentException.class, () -> sessions.require(AUTHOR));
        assertEquals(saved, sessions.open(AUTHOR, ID));
        assertNotSame(old, sessions.require(AUTHOR));
        assertFalse(sessions.require(AUTHOR).dirty());
        assertEquals(0, sessions.require(AUTHOR).workspace.undoDepth());
        assertEquals(saved, new ArenaRulesetStore(directory).loadDraft(AUTHOR, ID).orElseThrow());
    }

    @Test void closedServiceCannotReopenOrCreatePersistentDrafts() throws Exception {
        var store = new ArenaRulesetStore(directory);
        var sessions = new ArenaDraftSessions(store);
        ArenaRuleset saved = sessions.create(AUTHOR, ID);
        sessions.close();
        sessions.close();
        assertThrows(IllegalArgumentException.class, () -> sessions.open(AUTHOR, ID));
        assertThrows(IllegalArgumentException.class, () -> sessions.create(AUTHOR, new DesignId("player:after-close")));
        assertThrows(IllegalArgumentException.class, () -> sessions.clonePublished(AUTHOR,
                new DesignId("genesis:arena_ruleset"), new DesignId("player:closed-copy")));
        assertThrows(IllegalArgumentException.class, () -> sessions.save(AUTHOR));
        assertThrows(IllegalArgumentException.class, () -> sessions.submit(AUTHOR));
        assertEquals(0, sessions.size());
        assertEquals(java.util.List.of(saved), store.listDrafts(AUTHOR));
    }

    @Test void sharedDraftCannotExceedServerWorkspaceBudget() throws Exception {
        var store = new ArenaRulesetStore(directory);
        ArenaRuleset shared = sharedDraft(store);
        var sessions = new ArenaDraftSessions(store);
        sessions.open(AUTHOR, ID);
        for (int i = 0; i < 63; i++) sessions.open(editor(i), ID);
        assertEquals(ArenaDraftSessions.MAX_OPEN_WORKSPACES, sessions.size());
        assertThrows(IllegalArgumentException.class, () -> sessions.open(editor(63), ID));
        assertThrows(IllegalArgumentException.class, () -> sessions.create(editor(63), new DesignId("player:no-capacity")));
        assertThrows(IllegalArgumentException.class, () -> sessions.clonePublished(editor(63),
                new DesignId("genesis:arena_ruleset"), new DesignId("player:no-copy-capacity")));
        assertTrue(store.listDrafts(editor(63)).isEmpty());
        sessions.disconnected(editor(0));
        assertEquals(shared, sessions.open(editor(63), ID));
        assertEquals(ArenaDraftSessions.MAX_OPEN_WORKSPACES, sessions.size());
    }

    @Test void dirtyCloseRequiresDiscardAndUndoRestoresTheSavedBase() throws Exception {
        var store = new ArenaRulesetStore(directory);
        var sessions = new ArenaDraftSessions(store);
        ArenaRuleset saved = sessions.create(AUTHOR, ID);
        var current = sessions.require(AUTHOR);
        current.workspace.duration(200);
        assertThrows(IllegalArgumentException.class, () -> sessions.closeSession(AUTHOR, false));
        assertSame(current, sessions.require(AUTHOR));
        assertTrue(current.workspace.undo());
        assertFalse(current.dirty());
        assertFalse(sessions.closeSession(AUTHOR, false));
        assertEquals(saved, sessions.open(AUTHOR, ID));
        sessions.require(AUTHOR).workspace.unitBudget(12);
        assertTrue(sessions.closeSession(AUTHOR, true));
        assertEquals(saved, sessions.open(AUTHOR, ID));
    }

    @Test void failedCreationReleasesAdmissionAndDoesNotReplaceAnotherPlayersWorkspace() throws Exception {
        var store = new ArenaRulesetStore(directory);
        var sessions = new ArenaDraftSessions(store);
        sessions.create(editor(0), new DesignId("player:other"));
        var other = sessions.require(editor(0));
        Path target = draftPath(AUTHOR, ID);
        Files.createDirectories(target.getParent());
        Path blocker = Files.createDirectory(temporary(target));
        assertThrows(IOException.class, () -> sessions.create(AUTHOR, ID));
        assertEquals(1, sessions.size());
        assertSame(other, sessions.require(editor(0)));
        assertFalse(Files.exists(target));
        Files.delete(blocker);
        assertEquals(ID, sessions.create(AUTHOR, ID).metadata().id());
        assertEquals(2, sessions.size());
    }

    @Test void failedCloneAndDeniedOpenDoNotOccupyAdmissionOrChangeTheSource() throws Exception {
        var store = new ArenaRulesetStore(directory);
        ArenaRuleset source = store.create(AUTHOR, ID);
        var sessions = new ArenaDraftSessions(store);
        assertThrows(SecurityException.class, () -> sessions.open(editor(0), ID));
        assertEquals(0, sessions.size());
        DesignId copyId = new DesignId("player:copy-session");
        Path target = draftPath(editor(0), copyId);
        Files.createDirectories(target.getParent());
        Path blocker = Files.createDirectory(temporary(target));
        DesignId starter = new DesignId("genesis:arena_ruleset");
        assertThrows(IOException.class, () -> sessions.clonePublished(editor(0), starter, copyId));
        assertEquals(0, sessions.size());
        Files.delete(blocker);
        ArenaRuleset copy = sessions.clonePublished(editor(0), starter, copyId);
        assertTrue(copy.metadata().source().endsWith("#" + store.latestPublished(starter).orElseThrow().metadata().hash()));
        sessions.disconnected(editor(0));
        assertEquals(copy, sessions.open(editor(0), copyId));
        assertEquals(source, store.loadDraft(AUTHOR, ID).orElseThrow());
    }

    @Test void failedSavePreservesEditedWorkspaceUndoAndHashUntilRetryCommits() throws Exception {
        var store = new ArenaRulesetStore(directory);
        var sessions = new ArenaDraftSessions(store);
        ArenaRuleset saved = sessions.create(AUTHOR, ID);
        Path target = draftPath(AUTHOR, ID);
        byte[] original = Files.readAllBytes(target);
        var current = sessions.require(AUTHOR);
        current.workspace.resize(24, 20);
        var candidate = current.workspace.current();
        Path blocker = Files.createDirectory(temporary(target));
        assertThrows(IOException.class, () -> sessions.save(AUTHOR));
        assertSame(current, sessions.require(AUTHOR));
        assertEquals(candidate, current.workspace.current());
        assertEquals(1, current.workspace.undoDepth());
        assertEquals(saved.metadata().hash(), current.baseHash);
        assertTrue(current.dirty());
        assertArrayEquals(original, Files.readAllBytes(target));
        Files.delete(blocker);
        ArenaRuleset committed = sessions.save(AUTHOR);
        assertEquals(24, committed.width());
        assertEquals(20, committed.height());
        assertFalse(sessions.require(AUTHOR).dirty());
        assertEquals(0, sessions.require(AUTHOR).workspace.undoDepth());
        assertEquals(committed.metadata().hash(), sessions.require(AUTHOR).baseHash);
        assertEquals(committed, new ArenaRulesetStore(directory).loadDraft(AUTHOR, ID).orElseThrow());
    }

    @Test void collaboratorConflictAndRevocationKeepBothSavedAndUnsavedWorkIntact() throws Exception {
        var store = new ArenaRulesetStore(directory);
        sharedDraft(store);
        var sessions = new ArenaDraftSessions(store);
        sessions.open(AUTHOR, ID);
        sessions.open(editor(0), ID);
        var editing = sessions.require(editor(0));
        editing.workspace.resize(24, 24);
        sessions.require(AUTHOR).workspace.unitBudget(12);
        sessions.save(AUTHOR);
        assertThrows(IOException.class, () -> sessions.save(editor(0)));
        assertSame(editing, sessions.require(editor(0)));
        assertTrue(editing.dirty());
        assertEquals(1, editing.workspace.undoDepth());
        sessions.require(AUTHOR).workspace.editor(editor(0), false);
        ArenaRuleset revoked = sessions.save(AUTHOR);
        byte[] bytes = Files.readAllBytes(draftPath(AUTHOR, ID));
        assertThrows(IOException.class, () -> sessions.save(editor(0)));
        assertArrayEquals(bytes, Files.readAllBytes(draftPath(AUTHOR, ID)));
        sessions.closeSession(editor(0), true);
        assertThrows(SecurityException.class, () -> sessions.open(editor(0), ID));
        assertEquals(1, sessions.size());
        assertEquals(revoked, store.loadDraft(AUTHOR, ID).orElseThrow());
    }

    @Test void submissionRequiresSavedDraftAndFailedSubmissionCanBeRetriedAfterReopen() throws Exception {
        var store = new ArenaRulesetStore(directory);
        var sessions = new ArenaDraftSessions(store);
        sessions.create(AUTHOR, ID);
        sessions.require(AUTHOR).workspace.publicRead(true);
        assertThrows(IllegalArgumentException.class, () -> sessions.submit(AUTHOR));
        assertTrue(store.reviewRecord(ID, "1.0.0").isEmpty());
        ArenaRuleset saved = sessions.save(AUTHOR);
        Path review = directory.resolve("reviews").resolve(hash(ID.value() + "@1.0.0") + ".json");
        Path blocker = Files.createDirectory(temporary(review));
        assertThrows(IOException.class, () -> sessions.submit(AUTHOR));
        assertEquals(saved.metadata().hash(), sessions.require(AUTHOR).baseHash);
        assertFalse(sessions.require(AUTHOR).dirty());
        sessions.close();
        Files.delete(blocker);
        var restarted = new ArenaDraftSessions(new ArenaRulesetStore(directory));
        assertEquals(saved, restarted.open(AUTHOR, ID));
        var pending = restarted.submit(AUTHOR);
        assertEquals(saved.metadata().hash(), pending.contentHash());
        assertEquals(ArenaRulesetStore.ReviewStatus.PENDING, pending.status());
        assertTrue(store.loadPublished(ID, "1.0.0").isEmpty());
        assertThrows(SecurityException.class, () -> store.approveReview(AUTHOR, ID, "1.0.0"));
        store.approveReview(editor(0), ID, "1.0.0");
        assertEquals(saved, new ArenaRulesetStore(directory).loadPublished(ID, "1.0.0").orElseThrow());
    }

    @Test void concurrentLastSlotAdmissionCreatesExactlyOnePersistentDraft() throws Exception {
        var store = new ArenaRulesetStore(directory);
        sharedDraft(store);
        var sessions = new ArenaDraftSessions(store);
        sessions.open(AUTHOR, ID);
        for (int i = 0; i < 62; i++) sessions.open(editor(i), ID);
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> racingCreate(sessions, editor(62), ready, start));
            var two = executor.submit(() -> racingCreate(sessions, editor(63), ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertNotEquals(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS));
        } finally { start.countDown(); }
        assertEquals(64, sessions.size());
        assertEquals(1, store.listDrafts(editor(62)).size() + store.listDrafts(editor(63)).size());
    }

    @Test void concurrentSamePlayerCreationCannotOverwriteOrLeaveAnUnopenedDraft() throws Exception {
        var store = new ArenaRulesetStore(directory);
        var sessions = new ArenaDraftSessions(store);
        CountDownLatch ready = new CountDownLatch(2), start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var one = executor.submit(() -> racingCreate(sessions, AUTHOR, ready, start));
            var two = executor.submit(() -> racingCreate(sessions, AUTHOR, ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            assertNotEquals(one.get(10, TimeUnit.SECONDS), two.get(10, TimeUnit.SECONDS));
        } finally { start.countDown(); }
        assertEquals(1, sessions.size());
        assertEquals(java.util.List.of(sessions.require(AUTHOR).saved), store.listDrafts(AUTHOR));
    }

    @Test void closeWaitsForAnAdmittedSaveAndCannotLeaveAResurrectedSession() throws Exception {
        var store = new ArenaRulesetStore(directory);
        var sessions = new ArenaDraftSessions(store);
        sessions.create(AUTHOR, ID);
        var editing = sessions.require(AUTHOR);
        editing.workspace.resize(24, 20);
        var savingThread = new AtomicReference<Thread>();
        var closingThread = new AtomicReference<Thread>();
        Future<ArenaRuleset> save;
        Future<?> close;
        try (var executor = Executors.newFixedThreadPool(2)) {
            synchronized (store) {
                save = executor.submit(() -> { savingThread.set(Thread.currentThread()); return sessions.save(AUTHOR); });
                awaitBlockedOn(savingThread, store);
                close = executor.submit(() -> { closingThread.set(Thread.currentThread()); sessions.close(); });
                awaitBlockedOn(closingThread, sessions);
                assertFalse(close.isDone());
            }
            ArenaRuleset committed = save.get(5, TimeUnit.SECONDS);
            close.get(5, TimeUnit.SECONDS);
            assertEquals(0, sessions.size());
            assertThrows(IllegalArgumentException.class, () -> sessions.save(AUTHOR));
            editing.workspace.resize(32, 32); // A stale reference cannot reinsert itself or save through this service.
            assertEquals(committed, new ArenaRulesetStore(directory).loadDraft(AUTHOR, ID).orElseThrow());
        }
    }

    @Test void disconnectWaitsForAnAdmittedCreateThenFreesTheWorkspace() throws Exception {
        var store = new ArenaRulesetStore(directory);
        var sessions = new ArenaDraftSessions(store);
        var creatingThread = new AtomicReference<Thread>();
        var leavingThread = new AtomicReference<Thread>();
        Future<ArenaRuleset> create;
        Future<?> leave;
        try (var executor = Executors.newFixedThreadPool(2)) {
            synchronized (store) {
                create = executor.submit(() -> { creatingThread.set(Thread.currentThread()); return sessions.create(AUTHOR, ID); });
                awaitBlockedOn(creatingThread, store);
                leave = executor.submit(() -> { leavingThread.set(Thread.currentThread()); sessions.disconnected(AUTHOR); });
                awaitBlockedOn(leavingThread, sessions);
            }
            ArenaRuleset saved = create.get(5, TimeUnit.SECONDS);
            leave.get(5, TimeUnit.SECONDS);
            assertEquals(0, sessions.size());
            assertEquals(saved, sessions.open(AUTHOR, ID));
        }
    }

    private void awaitBlockedOn(AtomicReference<Thread> reference, Object monitor) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        var threads = java.lang.management.ManagementFactory.getThreadMXBean();
        while (System.nanoTime() < deadline) {
            Thread thread = reference.get();
            if (thread != null) {
                var info = threads.getThreadInfo(thread.threadId());
                if (info != null && info.getThreadState() == Thread.State.BLOCKED && info.getLockInfo() != null
                        && info.getLockInfo().getIdentityHashCode() == System.identityHashCode(monitor)) return;
            }
            Thread.sleep(5);
        }
        fail("Worker did not reach the expected persistence/lifecycle monitor");
    }

    private boolean racingCreate(ArenaDraftSessions sessions, UUID owner, CountDownLatch ready,
                                 CountDownLatch start) throws Exception {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Admission race did not start");
        try {
            sessions.create(owner, new DesignId("player:concurrent-" + UUID.randomUUID()));
            return true;
        } catch (IllegalArgumentException limit) { return false; }
    }

    private ArenaRuleset sharedDraft(ArenaRulesetStore store) throws Exception {
        ArenaRuleset initial = store.create(AUTHOR, ID);
        var edit = new ArenaRulesetWorkspace(initial);
        for (int i = 0; i < 64; i++) edit.editor(editor(i), true);
        return store.saveDraft(AUTHOR, edit.current(), initial.metadata().hash());
    }

    private Path draftPath(UUID owner, DesignId id) throws Exception {
        return directory.resolve("drafts").resolve(owner.toString()).resolve(hash(id.value()) + ".json");
    }

    private Path temporary(Path target) { return target.resolveSibling(target.getFileName() + ".tmp"); }

    private String hash(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static UUID editor(int index) { return new UUID(1, 700 + index); }
}
