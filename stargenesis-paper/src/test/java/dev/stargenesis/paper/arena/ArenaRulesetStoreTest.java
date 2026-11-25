package dev.stargenesis.paper.arena;

import dev.stargenesis.api.design.DesignDependency;
import dev.stargenesis.api.design.DesignId;
import dev.stargenesis.design.ArenaRuleset;
import dev.stargenesis.design.ArenaRulesetWorkspace;
import dev.stargenesis.design.DesignCodec;
import dev.stargenesis.design.Protocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ArenaRulesetStoreTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-000000000031");
    private static final UUID BOB = UUID.fromString("00000000-0000-0000-0000-000000000032");
    private final DesignCodec codec = new DesignCodec();

    @Test void draftRequiresPublicLicenseAndIndependentApprovalPublishesImmutably(@TempDir Path directory) throws Exception {
        ArenaRulesetStore store = new ArenaRulesetStore(directory);
        ArenaRuleset draft = store.create(ALICE, new DesignId("player:arena_alpha"));
        ArenaRulesetWorkspace workspace = new ArenaRulesetWorkspace(draft);
        workspace.resize(32, 24);
        workspace.duration(2400);
        workspace.publicRead(true);
        workspace.allowClone(true);
        ArenaRuleset saved = store.saveDraft(ALICE, workspace.current(), draft.metadata().hash());

        assertEquals(32, saved.width());
        var submitted = store.submitForReview(ALICE, saved.metadata().id(), saved.metadata().hash());
        assertEquals(ArenaRulesetStore.ReviewStatus.PENDING, submitted.status());
        assertTrue(store.catalog().stream().noneMatch(item -> item.metadata().id().equals(saved.metadata().id())));
        assertThrows(SecurityException.class, () -> store.approveReview(ALICE,
                saved.metadata().id(), saved.metadata().version()));
        var approved = store.approveReview(BOB, saved.metadata().id(), saved.metadata().version());
        assertEquals(ArenaRulesetStore.ReviewStatus.APPROVED, approved.status());
        assertEquals(saved, store.loadPublished(saved.metadata().id(), saved.metadata().version()).orElseThrow());
        ArenaRulesetStore restarted = new ArenaRulesetStore(directory);
        assertEquals(saved, restarted.loadPublished(saved.metadata().id(), saved.metadata().version()).orElseThrow());
        assertTrue(restarted.pendingReviews().isEmpty());
        assertEquals(saved, store.latestPublished(saved.metadata().id()).orElseThrow());
        assertEquals(saved, store.catalog().stream().filter(item -> item.metadata().id().equals(saved.metadata().id()))
                .findFirst().orElseThrow());
        assertThrows(IOException.class, () -> store.submitForReview(ALICE, saved.metadata().id(), saved.metadata().hash()));
        assertTrue(store.loadDraft(ALICE, saved.metadata().id()).isPresent());
        assertThrows(SecurityException.class, () -> store.loadDraft(BOB, saved.metadata().id()));
    }

    @Test void onlyExactResolvedDependenciesWithReusableLicensesCanBeSubmittedForReview(@TempDir Path directory) throws Exception {
        ArenaRulesetStore store = new ArenaRulesetStore(directory);
        ArenaRuleset baseDraft = store.create(ALICE, new DesignId("player:arena_base"));
        ArenaRulesetWorkspace baseWorkspace = new ArenaRulesetWorkspace(baseDraft);
        baseWorkspace.publicRead(true);
        baseWorkspace.allowClone(false);
        ArenaRuleset privateBase = store.saveDraft(ALICE, baseWorkspace.current(), baseDraft.metadata().hash());
        submitAndApprove(store, privateBase, BOB);

        ArenaRuleset dependentDraft = store.create(ALICE, new DesignId("player:arena_dependent"));
        ArenaRulesetWorkspace dependentWorkspace = new ArenaRulesetWorkspace(dependentDraft);
        dependentWorkspace.publicRead(true);
        dependentWorkspace.allowClone(true);
        dependentWorkspace.addDependency(new DesignDependency(privateBase.metadata().id(),
                privateBase.metadata().version(), privateBase.metadata().hash()));
        ArenaRuleset dependent = store.saveDraft(ALICE, dependentWorkspace.current(), dependentDraft.metadata().hash());
        assertThrows(SecurityException.class,
                () -> store.submitForReview(ALICE, dependent.metadata().id(), dependent.metadata().hash()));

        Protocol protocol = serviceProtocol();
        ArenaRuleset unresolvedDraft = store.create(ALICE, new DesignId("player:arena_unresolved"));
        ArenaRulesetWorkspace unresolvedWorkspace = new ArenaRulesetWorkspace(unresolvedDraft);
        unresolvedWorkspace.publicRead(true);
        unresolvedWorkspace.addDependency(new DesignDependency(protocol.metadata().id(),
                protocol.metadata().version(), "0".repeat(64)));
        ArenaRuleset unresolved = store.saveDraft(ALICE, unresolvedWorkspace.current(), unresolvedDraft.metadata().hash());
        assertThrows(IOException.class,
                () -> store.submitForReview(ALICE, unresolved.metadata().id(), unresolved.metadata().hash()));
    }

    @Test void editableAuthorsAndDeclaredEditorsCanSaveDrafts(@TempDir Path directory) throws Exception {
        ArenaRulesetStore store = new ArenaRulesetStore(directory);
        ArenaRuleset draft = store.create(ALICE, new DesignId("player:arena_shared"));
        ArenaRulesetWorkspace workspace = new ArenaRulesetWorkspace(draft);
        workspace.editor(BOB, true);
        ArenaRuleset withEditor = store.saveDraft(ALICE, workspace.current(), draft.metadata().hash());
        ArenaRuleset opened = store.loadDraft(BOB, withEditor.metadata().id()).orElseThrow();
        ArenaRulesetWorkspace bobWorkspace = new ArenaRulesetWorkspace(opened);
        bobWorkspace.unitBudget(12);

        ArenaRuleset edited = store.saveDraft(BOB, bobWorkspace.current(), withEditor.metadata().hash());

        assertEquals(12, edited.unitBudget());
        assertEquals(ALICE, edited.metadata().author());
        assertThrows(SecurityException.class, () -> store.saveDraft(
                UUID.fromString("00000000-0000-0000-0000-000000000033"), edited, edited.metadata().hash()));
    }

    @Test void publishedCloneRequiresAllowClone(@TempDir Path directory) throws Exception {
        ArenaRulesetStore store = new ArenaRulesetStore(directory);
        ArenaRuleset starter = store.latestPublished(new DesignId("genesis:arena_ruleset")).orElseThrow();
        assertTrue(starter.metadata().permissions().allowClone());

        ArenaRuleset cloned = store.clonePublished(BOB, starter.metadata().id(), new DesignId("player:starter_copy"));

        assertEquals(BOB, cloned.metadata().author());
        assertEquals(starter.metadata().dependencies(), cloned.metadata().dependencies());

        ArenaRuleset privateDraft = store.create(ALICE, new DesignId("player:private_source"));
        ArenaRulesetWorkspace privateWorkspace = new ArenaRulesetWorkspace(privateDraft);
        privateWorkspace.publicRead(true);
        ArenaRuleset privatePublished = store.saveDraft(ALICE, privateWorkspace.current(), privateDraft.metadata().hash());
        submitAndApprove(store, privatePublished, BOB);
        assertThrows(SecurityException.class, () -> store.clonePublished(BOB,
                privatePublished.metadata().id(), new DesignId("player:private_copy")));
    }

    @Test void reviewDecisionPersistsAndRejectRequiresReason(@TempDir Path directory) throws Exception {
        ArenaRulesetStore store = new ArenaRulesetStore(directory);
        ArenaRuleset draft = publicDraft(store, "player:arena_reviewed");

        var submitted = store.submitForReview(ALICE, draft.metadata().id(), draft.metadata().hash());
        assertEquals(1, store.pendingReviews().size());
        assertThrows(IllegalArgumentException.class, () -> store.rejectReview(BOB,
                draft.metadata().id(), draft.metadata().version(), " "));
        var rejected = store.rejectReview(BOB, draft.metadata().id(), draft.metadata().version(), "Reduce unit budget");
        assertEquals(ArenaRulesetStore.ReviewStatus.REJECTED, rejected.status());
        assertEquals("Reduce unit budget", rejected.reason());
        assertTrue(new ArenaRulesetStore(directory).pendingReviews().isEmpty());
        assertTrue(new ArenaRulesetStore(directory).loadPublished(draft.metadata().id(), draft.metadata().version()).isEmpty());
        assertThrows(IOException.class, () -> store.submitForReview(ALICE, draft.metadata().id(), draft.metadata().hash()));
        assertEquals(submitted.contentHash(), rejected.contentHash());
    }

    @Test void changedDraftCannotBeApprovedAgainstAnOlderSubmissionHash(@TempDir Path directory) throws Exception {
        ArenaRulesetStore store = new ArenaRulesetStore(directory);
        ArenaRuleset draft = publicDraft(store, "player:arena_review_hash");
        store.submitForReview(ALICE, draft.metadata().id(), draft.metadata().hash());
        ArenaRulesetWorkspace changed = new ArenaRulesetWorkspace(draft);
        changed.unitBudget(9);
        store.saveDraft(ALICE, changed.current(), draft.metadata().hash());

        assertThrows(IOException.class, () -> store.approveReview(BOB, draft.metadata().id(), draft.metadata().version()));
        assertTrue(store.loadPublished(draft.metadata().id(), draft.metadata().version()).isEmpty());
        assertEquals(1, store.pendingReviews().size());
    }

    @Test void approvedDecisionCanResumeIfPublicationFileWasNotCommitted(@TempDir Path directory) throws Exception {
        ArenaRulesetStore store = new ArenaRulesetStore(directory);
        ArenaRuleset draft = publicDraft(store, "player:arena_review_recovery");
        store.submitForReview(ALICE, draft.metadata().id(), draft.metadata().hash());
        store.approveReview(BOB, draft.metadata().id(), draft.metadata().version());
        String fileHash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest((draft.metadata().id().value() + "@" + draft.metadata().version()).getBytes(StandardCharsets.UTF_8)));
        Files.delete(directory.resolve("published").resolve(fileHash + ".json"));
        ArenaRulesetStore restarted = new ArenaRulesetStore(directory);

        assertTrue(restarted.loadPublished(draft.metadata().id(), draft.metadata().version()).isEmpty());
        assertEquals(ArenaRulesetStore.ReviewStatus.APPROVED,
                restarted.approveReview(BOB, draft.metadata().id(), draft.metadata().version()).status());
        assertEquals(draft, restarted.loadPublished(draft.metadata().id(), draft.metadata().version()).orElseThrow());
        assertTrue(restarted.pendingReviews().isEmpty());
    }

    @Test void pendingReviewHidesAnOrphanPublishedFileUntilApproval(@TempDir Path directory) throws Exception {
        ArenaRulesetStore store = new ArenaRulesetStore(directory);
        ArenaRuleset draft = publicDraft(store, "player:arena_orphan_publication");
        store.submitForReview(ALICE, draft.metadata().id(), draft.metadata().hash());
        String fileHash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest((draft.metadata().id().value() + "@" + draft.metadata().version()).getBytes(StandardCharsets.UTF_8)));
        Path orphan = directory.resolve("published").resolve(fileHash + ".json");
        Files.writeString(orphan, codec.write(draft));
        ArenaRulesetStore restarted = new ArenaRulesetStore(directory);

        assertTrue(restarted.loadPublished(draft.metadata().id(), draft.metadata().version()).isEmpty());
        assertTrue(restarted.catalog().stream().noneMatch(item -> item.metadata().id().equals(draft.metadata().id())));
        restarted.approveReview(BOB, draft.metadata().id(), draft.metadata().version());
        assertEquals(draft, restarted.loadPublished(draft.metadata().id(), draft.metadata().version()).orElseThrow());
    }

    @Test void preReviewPublishedVersionsRemainAvailable(@TempDir Path directory) throws Exception {
        ArenaRulesetStore store = new ArenaRulesetStore(directory);
        ArenaRuleset legacyPublished = publicDraft(store, "player:arena_legacy_published");
        Path publishedDirectory = directory.resolve("published");
        Files.createDirectories(publishedDirectory);
        String name = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest((legacyPublished.metadata().id().value() + "@" + legacyPublished.metadata().version())
                        .getBytes(StandardCharsets.UTF_8))) + ".json";
        Files.writeString(publishedDirectory.resolve(name), codec.write(legacyPublished));

        ArenaRulesetStore restarted = new ArenaRulesetStore(directory);

        assertEquals(legacyPublished, restarted.loadPublished(
                legacyPublished.metadata().id(), legacyPublished.metadata().version()).orElseThrow());
        assertTrue(restarted.catalog().stream().anyMatch(item -> item.metadata().id().equals(legacyPublished.metadata().id())));
    }

    private ArenaRuleset publicDraft(ArenaRulesetStore store, String id) throws Exception {
        ArenaRuleset draft = store.create(ALICE, new DesignId(id));
        ArenaRulesetWorkspace workspace = new ArenaRulesetWorkspace(draft);
        workspace.publicRead(true);
        workspace.allowClone(true);
        return store.saveDraft(ALICE, workspace.current(), draft.metadata().hash());
    }

    private void submitAndApprove(ArenaRulesetStore store, ArenaRuleset draft, UUID reviewer) throws Exception {
        store.submitForReview(ALICE, draft.metadata().id(), draft.metadata().hash());
        store.approveReview(reviewer, draft.metadata().id(), draft.metadata().version());
    }

    private Protocol serviceProtocol() throws Exception {
        try (var input = getClass().getResourceAsStream("/examples/protocol.json")) {
            assertNotNull(input);
            return (Protocol) codec.load(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
