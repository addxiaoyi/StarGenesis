package dev.stargenesis.paper;

import dev.stargenesis.design.ArenaRuleset;
import dev.stargenesis.design.ArenaRulesetEditorSession;
import dev.stargenesis.design.ArenaReviewSignature;
import dev.stargenesis.design.DesignCodec;
import dev.stargenesis.design.DesignExchange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ArenaReviewQueueTest {
    private static final UUID AUTHOR = UUID.fromString("00000000-0000-0000-0000-000000000031");
    private static final UUID REVIEWER = UUID.fromString("00000000-0000-0000-0000-000000000032");
    private static final UUID EDITOR = UUID.fromString("00000000-0000-0000-0000-000000000033");
    private static final KeyPair REVIEWER_KEYS = DesignExchange.newSigningKey();
    private final DesignCodec codec = new DesignCodec();

    @Test
    void submissionIsValidatedVisibleAndOnlyPublishedAfterOperatorApproval(@TempDir Path directory) throws Exception {
        PublishedDesignStore releases = releases(directory);
        ArenaRuleset starter = starter();
        ArenaRuleset candidate = candidate(starter);
        ArenaReviewQueue queue = reviewQueue(directory, releases);

        assertEquals(candidate, queue.submit(candidate, AUTHOR));
        assertEquals(candidate, queue.submit(candidate, AUTHOR));
        ArenaReviewQueue.SubmissionView pending = queue.listPending().getFirst();
        assertEquals(candidate.metadata().hash(), pending.hash());
        assertEquals(AUTHOR, pending.submitter());
        assertEquals(1, releases.list(starter.kind()).size());

        String hash = candidate.metadata().hash();
        ArenaRuleset approved = queue.approve(hash, REVIEWER, approveSignature(hash, REVIEWER, REVIEWER_KEYS));
        assertEquals(candidate.metadata().hash(), approved.metadata().hash());
        assertEquals(approved, queue.approve(hash, REVIEWER, approveSignature(hash, REVIEWER, REVIEWER_KEYS)));
        assertEquals(2, releases.list(starter.kind()).size());
        assertTrue(reviewQueue(directory, releases).listPending().isEmpty());
    }

    @Test
    void revokedEditorsCannotSubmitAndRejectionsAreDurableAndImmutable(@TempDir Path directory) throws Exception {
        PublishedDesignStore releases = releases(directory);
        ArenaRuleset starter = starter();
        ArenaRuleset candidate = candidate(starter);
        ArenaReviewQueue queue = reviewQueue(directory, releases);
        assertThrows(SecurityException.class, () -> queue.submit(candidate, REVIEWER));

        queue.submit(candidate, AUTHOR);
        String reason = "Please adjust the objective layout.";
        queue.reject(candidate.metadata().hash(), REVIEWER, reason,
                rejectSignature(candidate.metadata().hash(), REVIEWER, reason, REVIEWER_KEYS));
        assertTrue(queue.listPending().isEmpty());
        assertThrows(java.io.IOException.class, () -> queue.submit(candidate, AUTHOR));
        assertThrows(java.io.IOException.class,
                () -> queue.approve(candidate.metadata().hash(), UUID.randomUUID(), "invalid"));
    }

    @Test
    void corruptSubmissionFailsClosed(@TempDir Path directory) throws Exception {
        PublishedDesignStore releases = releases(directory);
        ArenaRuleset candidate = candidate(starter());
        ArenaReviewQueue queue = reviewQueue(directory, releases);
        queue.submit(candidate, AUTHOR);
        Path submission;
        try (var files = Files.walk(directory.resolve("arena-reviews").resolve("pending"))) {
            submission = files.filter(path -> path.getFileName().toString().endsWith(".submission"))
                    .findFirst().orElseThrow();
        }
        byte[] damaged = Files.readAllBytes(submission);
        damaged[10] ^= 1;
        Files.write(submission, damaged);
        assertThrows(java.io.IOException.class, queue::listPending);
    }

    @Test
    void verifiedAuthorSignatureIsRecordedInReviewAndImmutableDecision(@TempDir Path directory) throws Exception {
        PublishedDesignStore releases = releases(directory);
        ArenaRuleset candidate = candidate(starter());
        var keyPair = DesignExchange.newSigningKey();
        DesignExchange exchange = new DesignExchange(codec);
        byte[] artifact = exchange.sign(codec.write(candidate), keyPair).encode();
        var verified = exchange.verify(artifact, author -> author.equals(AUTHOR)
                ? Optional.of(keyPair.getPublic().getEncoded()) : Optional.empty());
        ArenaReviewQueue queue = reviewQueue(directory, releases);
        queue.submitSigned((ArenaRuleset) verified.document(), AUTHOR, verified.keyFingerprint());

        ArenaReviewQueue.SubmissionView pending = queue.listPending().getFirst();
        assertEquals(verified.keyFingerprint(), pending.keyFingerprint());
        assertThrows(SecurityException.class, () -> queue.approve(candidate.metadata().hash(), AUTHOR,
                approveSignature(candidate.metadata().hash(), AUTHOR, REVIEWER_KEYS)));
        queue.approve(candidate.metadata().hash(), REVIEWER,
                approveSignature(candidate.metadata().hash(), REVIEWER, REVIEWER_KEYS));

        Path decision = directory.resolve("arena-reviews").resolve("decisions")
                .resolve(candidate.metadata().hash() + ".decision");
        String evidence = new String(Files.readAllBytes(decision), StandardCharsets.ISO_8859_1);
        assertTrue(evidence.contains(verified.keyFingerprint()));
    }

    @Test
    void decisionStorageFailureNeverExposesAnUnapprovedRelease(@TempDir Path directory) throws Exception {
        PublishedDesignStore releases = releases(directory);
        ArenaRuleset candidate = candidate(starter());
        ArenaReviewQueue queue = reviewQueue(directory, releases);
        queue.submit(candidate, AUTHOR);
        Path blockedArchive = directory.resolve("arena-reviews").resolve("decisions");
        Files.writeString(blockedArchive, "A regular file blocks the decision archive.", StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> queue.approve(candidate.metadata().hash(), REVIEWER,
                approveSignature(candidate.metadata().hash(), REVIEWER, REVIEWER_KEYS)));

        assertTrue(releases.findArenaByHash(candidate.metadata().hash()).isEmpty(),
                "A failed approval write must not make the candidate available for matches");
        assertEquals(1, queue.listPending().size());
        assertEquals(candidate.metadata().hash(), queue.listPending().getFirst().hash());
        assertEquals("A regular file blocks the decision archive.", Files.readString(blockedArchive));
    }

    @Test
    void invalidDecisionSignatureNeverCreatesARecordOrRelease(@TempDir Path directory) throws Exception {
        PublishedDesignStore releases = releases(directory);
        ArenaRuleset candidate = candidate(starter());
        ArenaReviewQueue queue = reviewQueue(directory, releases);
        queue.submit(candidate, AUTHOR);
        String wrongDecisionSignature = rejectSignature(candidate.metadata().hash(), REVIEWER,
                "Please adjust the objective layout.", REVIEWER_KEYS);

        assertThrows(SecurityException.class,
                () -> queue.approve(candidate.metadata().hash(), REVIEWER, wrongDecisionSignature));

        assertFalse(Files.exists(decisionPath(directory, candidate)));
        assertTrue(releases.findArenaByHash(candidate.metadata().hash()).isEmpty());
        assertEquals(1, queue.listPending().size());
    }

    @Test
    void legacyV2ApprovalRemainsRecoverableWithoutBecomingAWritePath(@TempDir Path directory) throws Exception {
        PublishedDesignStore releases = releases(directory);
        ArenaRuleset candidate = candidate(starter());
        ArenaReviewQueue queue = reviewQueue(directory, releases);
        queue.submit(candidate, AUTHOR);
        byte[] legacyDecision = legacyV2Approval(candidate.metadata().hash(), AUTHOR, REVIEWER);
        Files.createDirectories(decisionPath(directory, candidate).getParent());
        Files.write(decisionPath(directory, candidate), legacyDecision);

        ArenaReviewQueue.DecisionInspection inspection = queue.inspectDecision(candidate.metadata().hash()).orElseThrow();
        assertFalse(inspection.decision().signed());
        assertEquals(ArenaReviewQueue.PublicationState.APPROVED_AWAITING_PUBLICATION, inspection.publication());
        assertFalse(queue.listDecisions(1).decisions().getFirst().signed());

        ArenaRuleset recovered = queue.approve(candidate.metadata().hash(), REVIEWER);

        assertEquals(candidate, recovered);
        assertArrayEquals(legacyDecision, Files.readAllBytes(decisionPath(directory, candidate)));
        assertTrue(queue.listPending().isEmpty());
    }

    @Test
    void persistedApprovalRecoversPublicationWithoutRewritingTheDecision(@TempDir Path directory) throws Exception {
        ArenaRuleset candidate = candidate(starter());
        byte[] approved = approvalImage(directory.resolve("reference"), candidate, "");
        Path recoveredDirectory = directory.resolve("recovered");
        PublishedDesignStore releases = releases(recoveredDirectory);
        ArenaReviewQueue queue = reviewQueue(recoveredDirectory, releases);
        queue.submit(candidate, AUTHOR);
        installApprovalImage(recoveredDirectory, candidate, approved);
        assertTrue(releases.findArenaByHash(candidate.metadata().hash()).isEmpty());

        assertThrows(IOException.class, () -> queue.approve(candidate.metadata().hash(), EDITOR, "invalid"));
        assertArrayEquals(approved, Files.readAllBytes(decisionPath(recoveredDirectory, candidate)));
        assertEquals(1, queue.listPending().size());

        ArenaReviewQueue restarted = reviewQueue(recoveredDirectory,
                new PublishedDesignStore(recoveredDirectory.resolve("published-designs"), codec));
        assertEquals(candidate, restarted.approve(candidate.metadata().hash(), REVIEWER,
                approveSignature(candidate.metadata().hash(), REVIEWER, REVIEWER_KEYS)));
        assertEquals(candidate, releases.findArenaByHash(candidate.metadata().hash()).orElseThrow());
        assertArrayEquals(approved, Files.readAllBytes(decisionPath(recoveredDirectory, candidate)));
        assertTrue(restarted.listPending().isEmpty());
        assertEquals(candidate, restarted.approve(candidate.metadata().hash(), REVIEWER,
                approveSignature(candidate.metadata().hash(), REVIEWER, REVIEWER_KEYS)));
        assertEquals(2, releases.list(candidate.kind()).size(), "Recovery must not add duplicate releases");
    }

    @Test
    void persistedApprovalCannotPublishPendingContentWithDifferentProvenance(@TempDir Path directory) throws Exception {
        ArenaRuleset candidate = candidate(starter());
        byte[] approved = approvalImage(directory.resolve("reference"), candidate, "a".repeat(64));
        Path recoveredDirectory = directory.resolve("recovered");
        PublishedDesignStore releases = releases(recoveredDirectory);
        ArenaReviewQueue queue = reviewQueue(recoveredDirectory, releases);
        queue.submitSigned(candidate, AUTHOR, "b".repeat(64));
        installApprovalImage(recoveredDirectory, candidate, approved);

        IOException conflict = assertThrows(IOException.class,
                () -> queue.approve(candidate.metadata().hash(), REVIEWER,
                        approveSignature(candidate.metadata().hash(), REVIEWER, REVIEWER_KEYS)));

        assertTrue(conflict.getMessage().contains("provenance"));
        assertThrows(IOException.class, () -> queue.recoverSignedApproval(candidate.metadata().hash(), REVIEWER,
                approveSignature(candidate.metadata().hash(), REVIEWER, REVIEWER_KEYS)));
        assertTrue(releases.findArenaByHash(candidate.metadata().hash()).isEmpty());
        assertEquals(1, queue.listPending().size());
        assertEquals("b".repeat(64), queue.listPending().getFirst().keyFingerprint());
        assertArrayEquals(approved, Files.readAllBytes(decisionPath(recoveredDirectory, candidate)));
    }

    @Test
    void rejectionRetryDoesNotRequireTheDeletedPendingRecord(@TempDir Path directory) throws Exception {
        PublishedDesignStore releases = releases(directory);
        ArenaRuleset candidate = candidate(starter());
        ArenaReviewQueue queue = reviewQueue(directory, releases);
        queue.submit(candidate, AUTHOR);
        String reason = "Please adjust the objective layout.";
        String signature = rejectSignature(candidate.metadata().hash(), REVIEWER, reason, REVIEWER_KEYS);
        queue.reject(candidate.metadata().hash(), REVIEWER, reason, signature);
        byte[] decision = Files.readAllBytes(decisionPath(directory, candidate));
        assertTrue(queue.listPending().isEmpty());

        ArenaReviewQueue restarted = reviewQueue(directory, releases);
        assertDoesNotThrow(() -> restarted.reject(candidate.metadata().hash(), REVIEWER, reason, signature));
        assertThrows(IOException.class, () -> restarted.reject(candidate.metadata().hash(), EDITOR, reason, signature));
        assertThrows(IOException.class,
                () -> restarted.reject(candidate.metadata().hash(), REVIEWER, "A different decision reason.", signature));
        assertThrows(IOException.class, () -> restarted.approve(candidate.metadata().hash(), REVIEWER,
                approveSignature(candidate.metadata().hash(), REVIEWER, REVIEWER_KEYS)));
        assertArrayEquals(decision, Files.readAllBytes(decisionPath(directory, candidate)));
        assertTrue(releases.findArenaByHash(candidate.metadata().hash()).isEmpty());
    }

    @Test
    void invalidPublicationVersionDoesNotCreateAnApprovalDecision(@TempDir Path directory) throws Exception {
        PublishedDesignStore releases = releases(directory);
        ArenaRuleset first = candidate(starter());
        releases.publish(first, AUTHOR);
        ArenaRulesetEditorSession changed = ArenaRulesetEditorSession.create(AUTHOR, starter());
        changed.adjustWidth(1);
        changed.preview();
        ArenaRuleset sameVersion = (ArenaRuleset) codec.load(changed.save(codec));
        assertEquals(first.metadata().version(), sameVersion.metadata().version());
        assertNotEquals(first.metadata().hash(), sameVersion.metadata().hash());
        ArenaReviewQueue queue = reviewQueue(directory, releases);
        queue.submit(sameVersion, AUTHOR);

        assertThrows(IOException.class, () -> queue.approve(sameVersion.metadata().hash(), REVIEWER,
                approveSignature(sameVersion.metadata().hash(), REVIEWER, REVIEWER_KEYS)));

        assertFalse(Files.exists(decisionPath(directory, sameVersion)));
        assertTrue(releases.findArenaByHash(sameVersion.metadata().hash()).isEmpty());
        assertEquals(1, queue.listPending().size());
    }

    @Test
    void approvalRecoveryRechecksTheLatestEditorGrant(@TempDir Path directory) throws Exception {
        ArenaRuleset first = candidate(starter());
        ArenaRulesetEditorSession grant = ArenaRulesetEditorSession.edit(AUTHOR, first);
        grant.grantEditor(EDITOR);
        grant.preview();
        ArenaRuleset shared = (ArenaRuleset) codec.load(grant.save(codec));
        ArenaRulesetEditorSession editing = ArenaRulesetEditorSession.edit(EDITOR, shared);
        editing.adjustWidth(1);
        editing.bumpPatchVersion();
        editing.preview();
        ArenaRuleset candidate = (ArenaRuleset) codec.load(editing.save(codec));

        Path referenceDirectory = directory.resolve("reference");
        PublishedDesignStore referenceReleases = releases(referenceDirectory);
        referenceReleases.publish(first, AUTHOR);
        referenceReleases.publish(shared, AUTHOR);
        ArenaReviewQueue reference = reviewQueue(referenceDirectory, referenceReleases);
        reference.submit(candidate, EDITOR);
        reference.approve(candidate.metadata().hash(), REVIEWER,
                approveSignature(candidate.metadata().hash(), REVIEWER, REVIEWER_KEYS));
        byte[] approved = Files.readAllBytes(decisionPath(referenceDirectory, candidate));

        Path recoveredDirectory = directory.resolve("recovered");
        PublishedDesignStore releases = releases(recoveredDirectory);
        releases.publish(first, AUTHOR);
        releases.publish(shared, AUTHOR);
        ArenaReviewQueue queue = reviewQueue(recoveredDirectory, releases);
        queue.submit(candidate, EDITOR);
        installApprovalImage(recoveredDirectory, candidate, approved);
        ArenaRulesetEditorSession revoke = ArenaRulesetEditorSession.edit(AUTHOR, shared);
        revoke.revokeEditor(EDITOR);
        revoke.preview();
        releases.publish((ArenaRuleset) codec.load(revoke.save(codec)), AUTHOR);

        assertThrows(SecurityException.class,
                () -> reviewQueue(recoveredDirectory, releases)
                        .approve(candidate.metadata().hash(), REVIEWER,
                                approveSignature(candidate.metadata().hash(), REVIEWER, REVIEWER_KEYS)));
        assertThrows(SecurityException.class,
                () -> queue.recoverSignedApproval(candidate.metadata().hash(), REVIEWER,
                        approveSignature(candidate.metadata().hash(), REVIEWER, REVIEWER_KEYS)));
        assertTrue(releases.findArenaByHash(candidate.metadata().hash()).isEmpty());
        assertEquals(1, queue.listPending().size());
        assertArrayEquals(approved, Files.readAllBytes(decisionPath(recoveredDirectory, candidate)));
    }

    @Test
    void maximumLengthChineseRejectionRemainsReadableAfterRestart(@TempDir Path directory) throws Exception {
        PublishedDesignStore releases = releases(directory);
        ArenaRuleset candidate = candidate(starter());
        ArenaReviewQueue queue = reviewQueue(directory, releases);
        queue.submitSigned(candidate, AUTHOR, "a".repeat(64));
        String reason = "界".repeat(240);
        String signature = rejectSignature(candidate.metadata().hash(), REVIEWER, reason, REVIEWER_KEYS);
        queue.reject(candidate.metadata().hash(), REVIEWER, reason, signature);
        byte[] decision = Files.readAllBytes(decisionPath(directory, candidate));
        assertTrue(decision.length > 1024, "The valid multilingual record exceeds the former read limit");

        ArenaReviewQueue restarted = reviewQueue(directory, releases);
        assertDoesNotThrow(() -> restarted.reject(candidate.metadata().hash(), REVIEWER, reason, signature));
        assertEquals(reason, restarted.inspectDecision(candidate.metadata().hash()).orElseThrow().decision().reason());
        assertArrayEquals(decision, Files.readAllBytes(decisionPath(directory, candidate)));
    }

    @Test
    void explicitRecoveryCannotCreateAnApprovalOrReverseARejection(@TempDir Path directory) throws Exception {
        PublishedDesignStore releases = releases(directory);
        ArenaRuleset candidate = candidate(starter());
        ArenaReviewQueue queue = reviewQueue(directory, releases);
        queue.submit(candidate, AUTHOR);
        String hash = candidate.metadata().hash();
        String signature = approveSignature(hash, REVIEWER, REVIEWER_KEYS);

        assertThrows(IOException.class, () -> queue.recoverSignedApproval(hash, REVIEWER, signature));
        assertFalse(Files.exists(decisionPath(directory, candidate)));
        assertEquals(1, queue.listPending().size());
        String reason = "The map needs changes.";
        queue.reject(hash, REVIEWER, reason, rejectSignature(hash, REVIEWER, reason, REVIEWER_KEYS));
        byte[] rejected = Files.readAllBytes(decisionPath(directory, candidate));
        assertThrows(IOException.class, () -> queue.recoverSignedApproval(hash, REVIEWER, signature));
        assertTrue(releases.findArenaByHash(hash).isEmpty());
        assertArrayEquals(rejected, Files.readAllBytes(decisionPath(directory, candidate)));
    }

    @Test
    void explicitRecoveryRequiresTheOriginalReviewerAndExactSignedProof(@TempDir Path directory) throws Exception {
        ArenaRuleset candidate = candidate(starter());
        byte[] approved = approvalImage(directory.resolve("reference"), candidate, "");
        Path recovering = directory.resolve("recovering");
        PublishedDesignStore releases = releases(recovering);
        ArenaReviewQueue queue = reviewQueue(recovering, releases);
        queue.submit(candidate, AUTHOR);
        installApprovalImage(recovering, candidate, approved);
        String hash = candidate.metadata().hash();
        String signature = approveSignature(hash, REVIEWER, REVIEWER_KEYS);

        assertThrows(SecurityException.class, () -> queue.recoverSignedApproval(hash, EDITOR, signature));
        assertThrows(SecurityException.class, () -> queue.recoverSignedApproval(hash, REVIEWER,
                approveSignature(hash, REVIEWER, DesignExchange.newSigningKey())));
        assertThrows(SecurityException.class, () -> queue.recoverSignedApproval(hash, REVIEWER,
                rejectSignature(hash, REVIEWER, "A rejection is not an approval.", REVIEWER_KEYS)));
        assertThrows(IllegalArgumentException.class,
                () -> queue.recoverSignedApproval(hash, REVIEWER, signature.substring(0, signature.length() - 2)));
        assertTrue(releases.findArenaByHash(hash).isEmpty());
        assertEquals(1, queue.listPending().size());
        assertArrayEquals(approved, Files.readAllBytes(decisionPath(recovering, candidate)));
    }

    @Test
    void explicitRecoveryRefusesUnsignedLegacyApprovals(@TempDir Path directory) throws Exception {
        PublishedDesignStore releases = releases(directory);
        ArenaRuleset candidate = candidate(starter());
        ArenaReviewQueue queue = reviewQueue(directory, releases);
        queue.submit(candidate, AUTHOR);
        byte[] legacy = legacyV2Approval(candidate.metadata().hash(), AUTHOR, REVIEWER);
        installApprovalImage(directory, candidate, legacy);

        assertThrows(SecurityException.class, () -> queue.recoverSignedApproval(candidate.metadata().hash(), REVIEWER,
                approveSignature(candidate.metadata().hash(), REVIEWER, REVIEWER_KEYS)));
        assertTrue(releases.findArenaByHash(candidate.metadata().hash()).isEmpty());
        assertEquals(1, queue.listPending().size());
        assertArrayEquals(legacy, Files.readAllBytes(decisionPath(directory, candidate)));
    }

    @Test
    void explicitRecoverySurvivesRestartAndReviewerRevocationWithoutRewritingDecision(@TempDir Path directory) throws Exception {
        ArenaRuleset candidate = candidate(starter());
        byte[] approved = approvalImage(directory.resolve("reference"), candidate, "a".repeat(64));
        Path recovering = directory.resolve("recovering");
        PublishedDesignStore releases = releases(recovering);
        ArenaReviewQueue queue = reviewQueue(recovering, releases);
        queue.submitSigned(candidate, AUTHOR, "a".repeat(64));
        installApprovalImage(recovering, candidate, approved);
        ArenaReviewTrustFileStore trust = new ArenaReviewTrustFileStore(recovering);
        trust.revoke(REVIEWER);
        ArenaReviewQueue restarted = new ArenaReviewQueue(recovering, codec,
                new PublishedDesignStore(recovering.resolve("published-designs"), codec), trust);
        String signature = approveSignature(candidate.metadata().hash(), REVIEWER, REVIEWER_KEYS);

        assertEquals(candidate, restarted.recoverSignedApproval(candidate.metadata().hash(), REVIEWER, signature));
        assertEquals(candidate, restarted.recoverSignedApproval(candidate.metadata().hash(), REVIEWER, signature));
        assertTrue(restarted.listPending().isEmpty());
        assertEquals(2, releases.list(candidate.kind()).size());
        assertTrue(trust.publicKey(REVIEWER).isEmpty());
        assertArrayEquals(approved, Files.readAllBytes(decisionPath(recovering, candidate)));
    }

    @Test
    void recoveryWriteFailurePreservesApprovalAndCandidateForRetry(@TempDir Path directory) throws Exception {
        ArenaRuleset candidate = candidate(starter());
        byte[] approved = approvalImage(directory.resolve("reference"), candidate, "");
        Path recovering = directory.resolve("recovering");
        PublishedDesignStore releases = releases(recovering);
        ArenaReviewQueue queue = reviewQueue(recovering, releases);
        queue.submit(candidate, AUTHOR);
        installApprovalImage(recovering, candidate, approved);
        String identity = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(candidate.metadata().id().value().getBytes(StandardCharsets.UTF_8)));
        Path blocked = recovering.resolve("published-designs").resolve(identity);
        Files.writeString(blocked, "Publication directory is unavailable.");
        String signature = approveSignature(candidate.metadata().hash(), REVIEWER, REVIEWER_KEYS);

        assertThrows(IOException.class, () -> queue.recoverSignedApproval(candidate.metadata().hash(), REVIEWER, signature));
        assertEquals("Publication directory is unavailable.", Files.readString(blocked));
        assertEquals(1, queue.listPending().size());
        assertArrayEquals(approved, Files.readAllBytes(decisionPath(recovering, candidate)));
        Files.delete(blocked);
        assertEquals(candidate, queue.recoverSignedApproval(candidate.metadata().hash(), REVIEWER, signature));
        assertTrue(queue.listPending().isEmpty());
        assertArrayEquals(approved, Files.readAllBytes(decisionPath(recovering, candidate)));
    }

    @Test
    void explicitRecoveryRechecksTheExactPublishedDependency(@TempDir Path directory) throws Exception {
        ArenaRuleset starter = starter();
        ArenaRuleset candidate = candidate(starter);
        byte[] approved = approvalImage(directory.resolve("reference"), candidate, "");
        Path recovering = directory.resolve("recovering");
        PublishedDesignStore releases = releases(recovering);
        ArenaReviewQueue queue = reviewQueue(recovering, releases);
        queue.submit(candidate, AUTHOR);
        installApprovalImage(recovering, candidate, approved);
        Path dependency;
        try (var files = Files.walk(recovering.resolve("published-designs"))) {
            dependency = files.filter(path -> path.getFileName().toString().endsWith(starter.metadata().hash() + ".json"))
                    .findFirst().orElseThrow();
        }
        byte[] original = Files.readAllBytes(dependency);
        Files.delete(dependency);
        String signature = approveSignature(candidate.metadata().hash(), REVIEWER, REVIEWER_KEYS);

        assertThrows(IllegalArgumentException.class,
                () -> queue.recoverSignedApproval(candidate.metadata().hash(), REVIEWER, signature));
        assertTrue(releases.findArenaByHash(candidate.metadata().hash()).isEmpty());
        assertEquals(1, queue.listPending().size());
        assertArrayEquals(approved, Files.readAllBytes(decisionPath(recovering, candidate)));
        Files.write(dependency, original);
        assertEquals(candidate, queue.recoverSignedApproval(candidate.metadata().hash(), REVIEWER, signature));
    }

    /** Reconstruct only the durable approval side of an interrupted approval/publication pair. */
    private byte[] approvalImage(Path directory, ArenaRuleset candidate, String keyFingerprint) throws Exception {
        ArenaReviewQueue queue = reviewQueue(directory, releases(directory));
        if (keyFingerprint.isEmpty()) queue.submit(candidate, AUTHOR);
        else queue.submitSigned(candidate, AUTHOR, keyFingerprint);
        queue.approve(candidate.metadata().hash(), REVIEWER,
                approveSignature(candidate.metadata().hash(), REVIEWER, REVIEWER_KEYS));
        return Files.readAllBytes(decisionPath(directory, candidate));
    }

    private ArenaReviewQueue reviewQueue(Path directory, PublishedDesignStore releases) throws Exception {
        ArenaReviewTrustFileStore trust = new ArenaReviewTrustFileStore(directory);
        trust.trust(REVIEWER, REVIEWER_KEYS.getPublic().getEncoded());
        return new ArenaReviewQueue(directory, codec, releases, trust);
    }

    private static String approveSignature(String hash, UUID reviewer, KeyPair keys) {
        return Base64.getEncoder().encodeToString(ArenaReviewSignature.sign(hash, reviewer,
                ArenaReviewSignature.Outcome.APPROVE, "", keys));
    }

    private static String rejectSignature(String hash, UUID reviewer, String reason, KeyPair keys) {
        return Base64.getEncoder().encodeToString(ArenaReviewSignature.sign(hash, reviewer,
                ArenaReviewSignature.Outcome.REJECT, reason, keys));
    }

    private static byte[] legacyV2Approval(String hash, UUID submitter, UUID reviewer) throws Exception {
        ByteArrayOutputStream payloadBytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(payloadBytes)) {
            output.writeInt(0x53475231);
            output.writeInt(2);
            output.write(HexFormat.of().parseHex(hash));
            output.writeBoolean(true);
            output.writeLong(submitter.getMostSignificantBits());
            output.writeLong(submitter.getLeastSignificantBits());
            output.writeLong(reviewer.getMostSignificantBits());
            output.writeLong(reviewer.getLeastSignificantBits());
            output.writeLong(System.currentTimeMillis());
            output.writeUTF("");
            output.writeUTF("");
        }
        byte[] payload = payloadBytes.toByteArray();
        ByteArrayOutputStream encoded = new ByteArrayOutputStream();
        encoded.write(payload);
        encoded.write(MessageDigest.getInstance("SHA-256").digest(payload));
        return encoded.toByteArray();
    }

    private void installApprovalImage(Path directory, ArenaRuleset candidate, byte[] approval) throws Exception {
        Path decision = decisionPath(directory, candidate);
        Files.createDirectories(decision.getParent());
        Files.write(decision, approval);
    }

    private Path decisionPath(Path directory, ArenaRuleset candidate) {
        return directory.resolve("arena-reviews").resolve("decisions")
                .resolve(candidate.metadata().hash() + ".decision");
    }

    private PublishedDesignStore releases(Path directory) throws Exception {
        PublishedDesignStore store = new PublishedDesignStore(directory.resolve("published-designs"), codec);
        store.seedBundled(starter());
        return store;
    }

    private ArenaRuleset starter() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/examples/arena_ruleset-v2.json")) {
            assertNotNull(input);
            return (ArenaRuleset) codec.load(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private ArenaRuleset candidate(ArenaRuleset source) {
        ArenaRulesetEditorSession editor = ArenaRulesetEditorSession.create(AUTHOR, source);
        editor.preview();
        return (ArenaRuleset) codec.load(editor.save(codec));
    }
}
