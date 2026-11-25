package dev.stargenesis.paper.arena;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
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

import static org.junit.jupiter.api.Assertions.*;

class ArenaPublicationIntegrityTest {
    private static final UUID AUTHOR = new UUID(0, 121), REVIEWER = new UUID(0, 122), OTHER = new UUID(0, 123);
    @TempDir Path directory;

    @Test void cloningPinsTheSourceAuthorVersionAndHashWithoutAddingARuntimeDependency() throws Exception {
        var store = new ArenaRulesetStore(directory);
        var starter = store.latestPublished(new DesignId("genesis:arena_ruleset")).orElseThrow();
        var copy = store.clonePublished(AUTHOR, starter.metadata().id(), new DesignId("player:pinned-copy"));
        var old = starter.metadata();
        assertEquals("clone:" + old.author() + ":" + old.id().value() + "@" + old.version() + "#" + old.hash(),
                copy.metadata().source());
        assertEquals(old.dependencies(), copy.metadata().dependencies());
        assertFalse(copy.metadata().permissions().publicRead());
        assertFalse(copy.metadata().permissions().allowClone());
    }

    @Test void draftLoadRejectsAValidDocumentMovedToAnotherIdsPath() throws Exception {
        var store = new ArenaRulesetStore(directory);
        var draft = publicDraft(store, "player:draft-identity");
        var wrongId = new DesignId("player:wrong-identity");
        Path original = draftPath(draft), wrong = original.resolveSibling(hash(wrongId.value()) + ".json");
        Files.move(original, wrong);
        assertThrows(IOException.class, () -> store.loadDraft(AUTHOR, wrongId));
        assertThrows(IOException.class, () -> store.listDrafts(AUTHOR));
    }

    @Test void catalogRejectsAValidPublicationMovedToAnotherFileName() throws Exception {
        var store = new ArenaRulesetStore(directory);
        var draft = publicDraft(store, "player:publication-identity");
        approve(store, draft);
        Path original = publicationPath(draft), wrong = original.resolveSibling("wrong-id.json");
        Files.move(original, wrong);
        assertThrows(IOException.class, store::catalog);
        assertThrows(IOException.class, () -> store.loadPublished(draft.metadata().id(), draft.metadata().version()));
    }

    @Test void publishedReviewOwnerMustMatchTheReviewedDocumentAuthor() throws Exception {
        var store = new ArenaRulesetStore(directory);
        var draft = publicDraft(store, "player:review-author");
        approve(store, draft);
        Path file = reviewPath(draft);
        ObjectNode record = (ObjectNode) new ObjectMapper().readTree(Files.readString(file));
        record.put("author", OTHER.toString());
        Files.writeString(file, record.toString());
        assertThrows(IOException.class, () -> new ArenaRulesetStore(directory).catalog());
        assertThrows(IOException.class, () -> store.approveReview(REVIEWER, draft.metadata().id(), draft.metadata().version()));
    }

    @Test void nullReviewIsRejectedAsInvalidEvidence() throws Exception {
        var store = new ArenaRulesetStore(directory);
        var draft = publicDraft(store, "player:null-review");
        store.submitForReview(AUTHOR, draft.metadata().id(), draft.metadata().hash());
        Files.writeString(reviewPath(draft), "null");
        assertThrows(IOException.class, () -> store.reviewRecord(draft.metadata().id(), draft.metadata().version()));
        assertThrows(IOException.class, store::pendingReviews);
    }

    @Test void failedSubmissionAndDecisionPreservePreviousBytesAndPermitRetry() throws Exception {
        var store = new ArenaRulesetStore(directory);
        var draft = publicDraft(store, "player:review-write-failure");
        Path file = reviewPath(draft), blocker = Files.createDirectory(file.resolveSibling(file.getFileName() + ".tmp"));
        assertThrows(IOException.class, () -> store.submitForReview(AUTHOR, draft.metadata().id(), draft.metadata().hash()));
        assertTrue(store.reviewRecord(draft.metadata().id(), draft.metadata().version()).isEmpty());
        assertTrue(store.loadPublished(draft.metadata().id(), draft.metadata().version()).isEmpty());
        Files.delete(blocker);
        store.submitForReview(AUTHOR, draft.metadata().id(), draft.metadata().hash());
        byte[] pending = Files.readAllBytes(file);
        Files.createDirectory(blocker);
        assertThrows(IOException.class, () -> store.approveReview(REVIEWER, draft.metadata().id(), draft.metadata().version()));
        assertArrayEquals(pending, Files.readAllBytes(file));
        assertTrue(store.loadPublished(draft.metadata().id(), draft.metadata().version()).isEmpty());
        Files.delete(blocker);
        var reopened = new ArenaRulesetStore(directory);
        reopened.approveReview(REVIEWER, draft.metadata().id(), draft.metadata().version());
        byte[] approved = Files.readAllBytes(file), published = Files.readAllBytes(publicationPath(draft));
        reopened.approveReview(REVIEWER, draft.metadata().id(), draft.metadata().version());
        assertArrayEquals(approved, Files.readAllBytes(file));
        assertArrayEquals(published, Files.readAllBytes(publicationPath(draft)));
    }

    @Test void failedPublicationRetainsAnApprovedDecisionAndOnlyItsReviewerCanResume() throws Exception {
        var store = new ArenaRulesetStore(directory);
        var draft = publicDraft(store, "player:publication-retry");
        store.submitForReview(AUTHOR, draft.metadata().id(), draft.metadata().hash());
        Path file = publicationPath(draft), blocker = Files.createDirectory(file.resolveSibling(file.getFileName() + ".tmp"));
        assertThrows(IOException.class, () -> store.approveReview(REVIEWER, draft.metadata().id(), draft.metadata().version()));
        assertEquals(ArenaRulesetStore.ReviewStatus.APPROVED, store.reviewRecord(draft.metadata().id(), draft.metadata().version()).orElseThrow().status());
        byte[] approved = Files.readAllBytes(reviewPath(draft));
        var restarted = new ArenaRulesetStore(directory);
        assertTrue(restarted.loadPublished(draft.metadata().id(), draft.metadata().version()).isEmpty());
        assertThrows(SecurityException.class, () -> restarted.approveReview(OTHER, draft.metadata().id(), draft.metadata().version()));
        Files.delete(blocker);
        restarted.approveReview(REVIEWER, draft.metadata().id(), draft.metadata().version());
        assertEquals(draft, restarted.loadPublished(draft.metadata().id(), draft.metadata().version()).orElseThrow());
        assertArrayEquals(approved, Files.readAllBytes(reviewPath(draft)));
    }

    @Test void rejectionWriteFailureKeepsTheSubmissionPendingAndRetryHidesIt() throws Exception {
        var store = new ArenaRulesetStore(directory);
        var draft = publicDraft(store, "player:rejection-retry");
        store.submitForReview(AUTHOR, draft.metadata().id(), draft.metadata().hash());
        Path file = reviewPath(draft); byte[] pending = Files.readAllBytes(file);
        Path blocker = Files.createDirectory(file.resolveSibling(file.getFileName() + ".tmp"));
        assertThrows(IOException.class, () -> store.rejectReview(REVIEWER, draft.metadata().id(), draft.metadata().version(), "Revise duration"));
        assertArrayEquals(pending, Files.readAllBytes(file));
        assertEquals(1, store.pendingReviews().size());
        Files.delete(blocker);
        store.rejectReview(REVIEWER, draft.metadata().id(), draft.metadata().version(), "Revise duration");
        var reopened = new ArenaRulesetStore(directory);
        assertTrue(reopened.pendingReviews().isEmpty());
        assertTrue(reopened.loadPublished(draft.metadata().id(), draft.metadata().version()).isEmpty());
    }

    @Test void capturedLegacyApprovedCloneRemainsAvailableWithItsOriginalSourceAndHash() throws Exception {
        var store = new ArenaRulesetStore(directory);
        ArenaRuleset fixture;
        try (var input = getClass().getResourceAsStream("/arena-v1-published.json")) {
            assertNotNull(input);
            fixture = (ArenaRuleset) new dev.stargenesis.design.DesignCodec().load(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        assertEquals("62b245a04703d16bf97aa3c48bbef0062517aa75a9ce83749131b87100eacc9b", fixture.metadata().hash());
        for (String kind : new String[]{"published", "reviews"}) {
            try (var input = getClass().getResourceAsStream("/arena-v1-" + kind + ".json")) {
                assertNotNull(input);
                Files.copy(input, directory.resolve(kind).resolve(publicationPath(fixture).getFileName()));
            }
        }
        byte[] oldPublished = Files.readAllBytes(publicationPath(fixture)), oldReview = Files.readAllBytes(reviewPath(fixture));
        assertEquals(fixture, store.requirePinnedPlayable(fixture.metadata().id().value(), fixture.metadata().version(), fixture.metadata().hash()));
        store.approveReview(REVIEWER, fixture.metadata().id(), fixture.metadata().version());
        assertArrayEquals(oldPublished, Files.readAllBytes(publicationPath(fixture)));
        assertArrayEquals(oldReview, Files.readAllBytes(reviewPath(fixture)));
        var copy = store.clonePublished(OTHER, fixture.metadata().id(), new DesignId("player:legacy-new-copy"));
        assertTrue(copy.metadata().source().endsWith("#" + fixture.metadata().hash()));
        assertEquals("genesis:arena-clone:genesis:arena_ruleset", fixture.metadata().source());
    }

    @Test void aDifferentAuthorCannotPublishANewerVersionOfAnExistingDesignId() throws Exception {
        var store = new ArenaRulesetStore(directory);
        var original = publicDraft(store, "player:owned-publication");
        approve(store, original);
        var meta = original.metadata();
        var impostor = new ArenaRuleset(new dev.stargenesis.api.design.DesignMetadata(meta.id(), 1, "2.0.0", OTHER,
                meta.source(), meta.dependencies(), "", meta.permissions()), 16, 16, 20, 4);
        var saved = store.saveDraft(OTHER, impostor, "");
        assertThrows(SecurityException.class, () -> store.submitForReview(OTHER, saved.metadata().id(), saved.metadata().hash()));
        assertEquals(original, store.latestPublished(meta.id()).orElseThrow());
    }

    @Test void submittedIdentityCannotBeClaimedByAnotherAuthorBeforeApprovalOrAfterRejection() throws Exception {
        var store = new ArenaRulesetStore(directory);
        var original = publicDraft(store, "player:reserved-review");
        store.submitForReview(AUTHOR, original.metadata().id(), original.metadata().hash());
        var meta = original.metadata();
        var candidate = new ArenaRuleset(new dev.stargenesis.api.design.DesignMetadata(meta.id(), 1, "2.0.0", OTHER,
                meta.source(), meta.dependencies(), "", meta.permissions()), 16, 16, 20, 4);
        var saved = store.saveDraft(OTHER, candidate, "");
        assertThrows(SecurityException.class, () -> store.submitForReview(OTHER, saved.metadata().id(), saved.metadata().hash()));
        store.rejectReview(REVIEWER, meta.id(), meta.version(), "Revise duration");
        assertThrows(SecurityException.class, () -> new ArenaRulesetStore(directory).submitForReview(OTHER, saved.metadata().id(), saved.metadata().hash()));
        assertTrue(store.loadPublished(meta.id(), "2.0.0").isEmpty());
    }

    @Test void bundledDesignIdentityCannotBeShadowedByAPlayerPublication() throws Exception {
        var store = new ArenaRulesetStore(directory);
        var starter = store.latestPublished(new DesignId("genesis:arena_ruleset")).orElseThrow();
        var meta = starter.metadata();
        var impostor = new ArenaRuleset(new dev.stargenesis.api.design.DesignMetadata(meta.id(), 1, "2.0.0", AUTHOR,
                "player:shadow", java.util.List.of(), "", meta.permissions()), 16, 16, 20, 4);
        var saved = store.saveDraft(AUTHOR, impostor, "");
        assertThrows(SecurityException.class, () -> store.submitForReview(AUTHOR, saved.metadata().id(), saved.metadata().hash()));
        assertEquals(starter, store.latestPublished(meta.id()).orElseThrow());
    }

    @Test void newDraftAuthorsAreAdmittedBeforeCreatingAnUnusableSixtyFifthDirectory() throws Exception {
        var store = new ArenaRulesetStore(directory);
        for (int i = 0; i < ArenaRulesetStore.MAX_DRAFT_AUTHORS; i++) {
            UUID owner = new UUID(1, i);
            store.create(owner, new DesignId("player:author-" + i));
        }
        assertThrows(IOException.class, () -> store.saveDraft(AUTHOR,
                unsignedDraft(AUTHOR, "player:author-overflow"), ""));
        assertFalse(Files.exists(directory.resolve("drafts").resolve(AUTHOR.toString())));
        assertTrue(store.loadDraft(new UUID(1, 0), new DesignId("player:author-0")).isPresent());
    }

    private ArenaRuleset unsignedDraft(UUID author, String id) {
        return new ArenaRuleset(new dev.stargenesis.api.design.DesignMetadata(new DesignId(id), 1, "1.0.0", author,
                "player:draft", java.util.List.of(), "", new dev.stargenesis.api.design.DesignPermissions(false, false, java.util.Set.of())),
                16, 16, 20, 4);
    }

    private ArenaRuleset publicDraft(ArenaRulesetStore store, String id) throws IOException {
        var initial = store.create(AUTHOR, new DesignId(id));
        var workspace = new ArenaRulesetWorkspace(initial);
        workspace.publicRead(true); workspace.allowClone(true);
        return store.saveDraft(AUTHOR, workspace.current(), initial.metadata().hash());
    }

    private void approve(ArenaRulesetStore store, ArenaRuleset draft) throws IOException {
        store.submitForReview(AUTHOR, draft.metadata().id(), draft.metadata().hash());
        store.approveReview(REVIEWER, draft.metadata().id(), draft.metadata().version());
    }

    private Path draftPath(ArenaRuleset draft) { return directory.resolve("drafts").resolve(draft.metadata().author().toString()).resolve(hash(draft.metadata().id().value()) + ".json"); }
    private Path publicationPath(ArenaRuleset draft) { return directory.resolve("published").resolve(hash(draft.metadata().id().value() + "@" + draft.metadata().version()) + ".json"); }
    private Path reviewPath(ArenaRuleset draft) { return directory.resolve("reviews").resolve(publicationPath(draft).getFileName()); }
    private String hash(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
