package dev.stargenesis.paper;

import dev.stargenesis.design.ArenaRuleset;
import dev.stargenesis.design.ArenaReviewSignature;
import dev.stargenesis.design.DesignCodec;
import dev.stargenesis.design.DesignExchange;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/** Durable operator review queue for immutable, author-attributed arena candidates. */
final class ArenaReviewQueue {
    private static final int MAGIC = 0x53475231;
    private static final int FORMAT = 3;
    private static final int HASH_BYTES = 32;
    private static final int MAX_PENDING = 512;
    static final int MAX_DECISIONS = 8_192;
    static final int DECISION_PAGE_SIZE = 20;
    private static final int MAX_DECISION_BYTES = 2_048;
    private static final int MAX_FILE_BYTES = DesignCodec.MAX_DOCUMENT_BYTES + 512;

    private final Path pendingDirectory;
    private final Path decisionDirectory;
    private final DesignCodec codec;
    private final PublishedDesignStore releases;
    private final ArenaReviewTrustFileStore reviewerTrust;

    ArenaReviewQueue(Path dataDirectory, DesignCodec codec, PublishedDesignStore releases) {
        this(dataDirectory, codec, releases, new ArenaReviewTrustFileStore(dataDirectory));
    }

    ArenaReviewQueue(Path dataDirectory, DesignCodec codec, PublishedDesignStore releases,
                     ArenaReviewTrustFileStore reviewerTrust) {
        Path root = dataDirectory.resolve("arena-reviews").toAbsolutePath().normalize();
        pendingDirectory = root.resolve("pending");
        decisionDirectory = root.resolve("decisions");
        this.codec = codec;
        this.releases = releases;
        this.reviewerTrust = reviewerTrust;
    }

    synchronized ArenaRuleset submit(ArenaRuleset candidate, UUID submitter) throws IOException {
        return submit(candidate, submitter, "");
    }

    synchronized ArenaRuleset submitSigned(ArenaRuleset candidate, UUID signer, String keyFingerprint) throws IOException {
        if (!candidate.metadata().author().equals(signer) || keyFingerprint == null
                || !keyFingerprint.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Signed arena submission identity or key fingerprint is invalid");
        return submit(candidate, signer, keyFingerprint);
    }

    private ArenaRuleset submit(ArenaRuleset candidate, UUID submitter, String keyFingerprint) throws IOException {
        releases.validateSubmission(candidate, submitter);
        String json = codec.write(candidate);
        Path target = pendingPath(candidate.metadata().hash());
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            Submission existing = readSubmission(target);
            if (!existing.submitter().equals(submitter)
                    || !existing.ruleset().metadata().hash().equals(candidate.metadata().hash()))
                throw new IOException("An arena review submission already uses this content hash");
            if (!existing.keyFingerprint().equals(keyFingerprint))
                throw new IOException("An arena review submission already uses different signing provenance");
            return existing.ruleset();
        }
        if (Files.exists(decisionPath(candidate.metadata().hash()), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("This arena candidate already has a review decision");
        ensureRoomForPending();
        writeAtomically(target, encodeSubmission(submitter, json, keyFingerprint));
        return candidate;
    }

    synchronized List<SubmissionView> listPending() throws IOException {
        List<Path> paths = listFiles(pendingDirectory, MAX_PENDING, ".submission");
        List<SubmissionView> result = new ArrayList<>(paths.size());
        for (Path path : paths) {
            Submission submission = readSubmission(path);
            ArenaRuleset ruleset = submission.ruleset();
            result.add(new SubmissionView(ruleset.metadata().hash(), ruleset.metadata().id().value(),
                    ruleset.metadata().version(), ruleset.metadata().author(), submission.submitter(),
                    submission.submittedAtMillis(), submission.keyFingerprint()));
        }
        return result.stream().sorted(Comparator.comparingLong(SubmissionView::submittedAtMillis)
                .thenComparing(SubmissionView::hash)).toList();
    }

    synchronized ArenaRuleset approve(String hash, UUID reviewer) throws IOException {
        requireHash(hash);
        Decision previous = readDecisionIfPresent(hash);
        if (previous == null || previous.reviewerSignature().length != 0)
            throw new SecurityException("A new arena approval requires a trusted Ed25519 reviewer signature");
        if (!previous.approved() || !previous.reviewer().equals(reviewer))
            throw new IOException("This arena candidate already has a review decision");
        return recoverApproval(hash, previous);
    }

    synchronized DecisionPage listDecisions(int page) throws IOException {
        if (page < 1 || page > Math.ceilDiv(MAX_DECISIONS, DECISION_PAGE_SIZE))
            throw new IllegalArgumentException("Arena decision page is outside the archive limit");
        List<Path> paths = listFiles(decisionDirectory, MAX_DECISIONS, ".decision");
        int pages = Math.max(1, Math.ceilDiv(paths.size(), DECISION_PAGE_SIZE));
        if (page > pages) throw new IllegalArgumentException("Arena decision page must be within 1.." + pages);
        // Hash order permits bounded page reads without decoding/verifying the entire archive on a server thread.
        for (Path path : paths) {
            if (!path.getFileName().toString().matches("[0-9a-f]{64}\\.decision"))
                throw new IOException("Arena review decision filename is invalid");
        }
        int first = (page - 1) * DECISION_PAGE_SIZE;
        List<DecisionView> decisions = new ArrayList<>(DECISION_PAGE_SIZE);
        for (Path path : paths.subList(first, Math.min(first + DECISION_PAGE_SIZE, paths.size()))) {
            String hash = path.getFileName().toString().substring(0, 64);
            Decision decision = readDecisionIfPresent(hash);
            if (decision == null) throw new IOException("Arena review decision disappeared during the query");
            decisions.add(decisionView(hash, decision));
        }
        return new DecisionPage(page, pages, paths.size(), List.copyOf(decisions));
    }

    synchronized Optional<DecisionInspection> inspectDecision(String hash) throws IOException {
        requireHash(hash);
        Decision decision = readDecisionIfPresent(hash);
        if (decision == null) return Optional.empty();
        PublicationState publication = releases.findArenaByHash(hash).isPresent() ? PublicationState.PUBLISHED
                : decision.approved() ? PublicationState.APPROVED_AWAITING_PUBLICATION : PublicationState.NOT_PUBLISHED;
        return Optional.of(new DecisionInspection(decisionView(hash, decision), publication));
    }

    private static DecisionView decisionView(String hash, Decision decision) {
        return new DecisionView(hash, decision.approved(), decision.submitter(), decision.reviewer(),
                decision.decidedAtMillis(), decision.reason(), decision.keyFingerprint(), decision.reviewerFingerprint(),
                Base64.getEncoder().encodeToString(decision.reviewerPublicKey()),
                Base64.getEncoder().encodeToString(decision.reviewerSignature()));
    }

    synchronized ArenaRuleset approve(String hash, UUID reviewer, String signatureBase64) throws IOException {
        requireHash(hash);
        Decision previous = readDecisionIfPresent(hash);
        if (previous != null) {
            if (!previous.approved() || !previous.reviewer().equals(reviewer))
                throw new IOException("This arena candidate already has a review decision");
            if (previous.reviewerSignature().length != 0)
                requireSameDecisionSignature(hash, reviewer, ArenaReviewSignature.Outcome.APPROVE, "",
                        signatureBase64, previous);
            return recoverApproval(hash, previous);
        }
        ReviewerProof proof = trustedProof(hash, reviewer, ArenaReviewSignature.Outcome.APPROVE, "", signatureBase64);
        Path target = pendingPath(hash);
        Submission submission = readSubmission(target);
        if (submission.ruleset().metadata().author().equals(reviewer))
            throw new SecurityException("A design author cannot approve their own arena release");
        releases.validatePublication(submission.ruleset(), submission.submitter());
        writeDecision(hash, new Decision(true, submission.submitter(), reviewer, System.currentTimeMillis(),
                "", submission.keyFingerprint(), proof.fingerprint(), proof.publicKey(), proof.signature()));
        ArenaRuleset published = (ArenaRuleset) releases.publish(submission.ruleset(), submission.submitter());
        Files.deleteIfExists(target);
        return published;
    }

    synchronized void reject(String hash, UUID reviewer, String reason) throws IOException {
        requireHash(hash);
        ArenaReviewSignature.validateReason(ArenaReviewSignature.Outcome.REJECT, reason);
        Path target = pendingPath(hash);
        Decision previous = readDecisionIfPresent(hash);
        if (previous != null) {
            if (!previous.approved() && previous.reviewer().equals(reviewer) && previous.reason().equals(reason)
                    && previous.reviewerSignature().length == 0) {
                Files.deleteIfExists(target);
                return;
            }
            throw new IOException("This arena candidate already has a review decision");
        }
        throw new SecurityException("A new arena rejection requires a trusted Ed25519 reviewer signature");
    }

    synchronized void reject(String hash, UUID reviewer, String reason, String signatureBase64) throws IOException {
        requireHash(hash);
        ArenaReviewSignature.validateReason(ArenaReviewSignature.Outcome.REJECT, reason);
        Path target = pendingPath(hash);
        Decision previous = readDecisionIfPresent(hash);
        if (previous != null) {
            if (!previous.approved() && previous.reviewer().equals(reviewer) && previous.reason().equals(reason)) {
                if (previous.reviewerSignature().length != 0)
                    requireSameDecisionSignature(hash, reviewer, ArenaReviewSignature.Outcome.REJECT, reason,
                            signatureBase64, previous);
                Files.deleteIfExists(target);
                return;
            }
            throw new IOException("This arena candidate already has a review decision");
        }
        ReviewerProof proof = trustedProof(hash, reviewer, ArenaReviewSignature.Outcome.REJECT, reason, signatureBase64);
        Submission submission = readSubmission(target);
        writeDecision(hash, new Decision(false, submission.submitter(), reviewer, System.currentTimeMillis(),
                reason, submission.keyFingerprint(), proof.fingerprint(), proof.publicKey(), proof.signature()));
        Files.deleteIfExists(target);
    }

    /** Replays only an existing signed approval; unlike approve, this cannot create a decision. */
    synchronized ArenaRuleset recoverSignedApproval(String hash, UUID reviewer, String signatureBase64) throws IOException {
        requireHash(hash);
        Decision previous = readDecisionIfPresent(hash);
        if (previous == null || !previous.approved())
            throw new IOException("No persisted arena approval exists for this candidate");
        if (previous.reviewerSignature().length == 0)
            throw new SecurityException("Unsigned legacy approvals require the original reviewer's in-game recovery command");
        if (!previous.reviewer().equals(reviewer))
            throw new SecurityException("Recovery reviewer does not match the immutable arena approval");
        requireSameDecisionSignature(hash, reviewer, ArenaReviewSignature.Outcome.APPROVE, "", signatureBase64, previous);
        return recoverApproval(hash, previous);
    }

    private ArenaRuleset recoverApproval(String hash, Decision previous) throws IOException {
        ArenaRuleset published = releases.findArenaByHash(hash).orElse(null);
        if (published == null) {
            Submission submission = readSubmission(pendingPath(hash));
            if (!submission.submitter().equals(previous.submitter())
                    || !submission.keyFingerprint().equals(previous.keyFingerprint()))
                throw new IOException("Pending arena provenance conflicts with its approval");
            published = (ArenaRuleset) releases.publish(submission.ruleset(), submission.submitter());
        }
        Files.deleteIfExists(pendingPath(hash));
        return published;
    }

    private ReviewerProof trustedProof(String hash, UUID reviewer, ArenaReviewSignature.Outcome outcome,
                                       String reason, String signatureBase64) {
        byte[] publicKey = reviewerTrust.publicKey(reviewer)
                .orElseThrow(() -> new SecurityException("No trusted Ed25519 key is pinned for this reviewer"));
        byte[] signature = decodeSignature(signatureBase64);
        if (!ArenaReviewSignature.verify(hash, reviewer, outcome, reason, publicKey, signature))
            throw new SecurityException("Arena review signature is invalid for this candidate and decision");
        return new ReviewerProof(DesignExchange.fingerprint(publicKey), publicKey, signature);
    }

    private static void requireSameDecisionSignature(String hash, UUID reviewer, ArenaReviewSignature.Outcome outcome,
                                                     String reason, String signatureBase64, Decision decision) {
        byte[] signature = decodeSignature(signatureBase64);
        if (!Arrays.equals(signature, decision.reviewerSignature())
                || !ArenaReviewSignature.verify(hash, reviewer, outcome, reason,
                decision.reviewerPublicKey(), signature))
            throw new SecurityException("Retry signature does not match the immutable arena review decision");
    }

    private static byte[] decodeSignature(String encoded) {
        try {
            byte[] signature = Base64.getDecoder().decode(encoded);
            if (signature.length != 64 || !Base64.getEncoder().encodeToString(signature).equals(encoded))
                throw new IllegalArgumentException("Reviewer signature must be canonical Base64 Ed25519 data");
            return signature;
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Reviewer signature must be canonical Base64 Ed25519 data", failure);
        }
    }

    private byte[] encodeSubmission(UUID submitter, String json, String keyFingerprint) {
        try {
            byte[] document = json.getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream payloadBytes = new ByteArrayOutputStream(document.length + 64);
            try (DataOutputStream output = new DataOutputStream(payloadBytes)) {
                output.writeInt(MAGIC);
                output.writeInt(FORMAT);
                writeUuid(output, submitter);
                output.writeUTF(keyFingerprint);
                output.writeLong(System.currentTimeMillis());
                output.writeInt(document.length);
                output.write(document);
            }
            return withChecksum(payloadBytes.toByteArray());
        } catch (IOException impossible) {
            throw new IllegalStateException("Cannot encode arena review submission", impossible);
        }
    }

    private Submission readSubmission(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Arena review submission is missing or not a regular file");
        long size = Files.size(path);
        if (size < 64 || size > MAX_FILE_BYTES) throw new IOException("Arena review submission exceeds its byte budget");
        byte[] encoded = Files.readAllBytes(path);
        byte[] payload = verifyChecksum(encoded);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            int magic = input.readInt();
            int format = input.readInt();
            if (magic != MAGIC || (format < 1 || format > FORMAT))
                throw new IOException("Unsupported arena review submission format");
            UUID submitter = readUuid(input);
            String keyFingerprint = format == 1 ? "" : input.readUTF();
            if (!keyFingerprint.isEmpty() && !keyFingerprint.matches("[0-9a-f]{64}"))
                throw new IOException("Arena review signing provenance is invalid");
            long submittedAt = input.readLong();
            int length = input.readInt();
            if (length < 2 || length > DesignCodec.MAX_DOCUMENT_BYTES || length != input.available())
                throw new IOException("Invalid arena review document size");
            byte[] documentBytes = input.readNBytes(length);
            String json = new String(documentBytes, StandardCharsets.UTF_8);
            if (!java.util.Arrays.equals(documentBytes, json.getBytes(StandardCharsets.UTF_8)))
                throw new IOException("Arena review document contains invalid UTF-8");
            var document = codec.load(json);
            if (!(document instanceof ArenaRuleset ruleset) || submittedAt < 0
                    || !path.getFileName().toString().equals(ruleset.metadata().hash() + ".submission")
                    || !ruleset.metadata().permissions().mayEdit(submitter, ruleset.metadata().author()))
                throw new IOException("Arena review identity does not match the submission");
            return new Submission(ruleset, submitter, submittedAt, keyFingerprint);
        } catch (IllegalArgumentException failure) {
            throw new IOException("Arena review submission failed design validation", failure);
        }
    }

    private void writeDecision(String hash, Decision decision) throws IOException {
        List<Path> files = listFiles(decisionDirectory, MAX_DECISIONS, ".decision");
        Path target = decisionPath(hash);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Review decision is immutable");
        if (files.size() >= MAX_DECISIONS) throw new IOException("Arena review decision archive is full");
        byte[] encoded;
        try {
            ByteArrayOutputStream payloadBytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(payloadBytes)) {
                output.writeInt(MAGIC);
                output.writeInt(FORMAT);
                output.write(HexFormat.of().parseHex(hash));
                output.writeBoolean(decision.approved());
                writeUuid(output, decision.submitter());
                writeUuid(output, decision.reviewer());
                output.writeLong(decision.decidedAtMillis());
                output.writeUTF(decision.reason());
                output.writeUTF(decision.keyFingerprint());
                output.writeUTF(decision.reviewerFingerprint());
                output.writeShort(decision.reviewerPublicKey().length);
                output.write(decision.reviewerPublicKey());
                output.writeShort(decision.reviewerSignature().length);
                output.write(decision.reviewerSignature());
            }
            encoded = withChecksum(payloadBytes.toByteArray());
        } catch (IOException impossible) {
            throw new IllegalStateException("Cannot encode arena review decision", impossible);
        }
        if (encoded.length > MAX_DECISION_BYTES)
            throw new IOException("Arena review decision exceeds its byte budget");
        writeAtomically(target, encoded);
    }

    private Decision readDecisionIfPresent(String hash) throws IOException {
        Path path = decisionPath(hash);
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return null;
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > MAX_DECISION_BYTES)
            throw new IOException("Arena review decision is invalid");
        byte[] encoded;
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            encoded = input.readNBytes(MAX_DECISION_BYTES + 1);
        }
        if (encoded.length > MAX_DECISION_BYTES)
            throw new IOException("Arena review decision exceeds its byte budget");
        byte[] payload = verifyChecksum(encoded);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            int magic = input.readInt();
            int format = input.readInt();
            if (magic != MAGIC || format < 1 || format > FORMAT || !readHash(input).equals(hash))
                throw new IOException("Arena review decision identity mismatch");
            boolean approved = input.readBoolean();
            UUID submitter = readUuid(input);
            UUID reviewer = readUuid(input);
            long decidedAt = input.readLong();
            String reason = input.readUTF();
            String keyFingerprint = format == 1 ? "" : input.readUTF();
            String reviewerFingerprint = format < 3 ? "" : input.readUTF();
            byte[] reviewerPublicKey = new byte[0];
            byte[] reviewerSignature = new byte[0];
            if (format == 3) {
                int keyLength = input.readUnsignedShort();
                if (keyLength < 32 || keyLength > 128) throw new IOException("Reviewer public key size is invalid");
                reviewerPublicKey = input.readNBytes(keyLength);
                int signatureLength = input.readUnsignedShort();
                if (signatureLength != 64) throw new IOException("Reviewer signature size is invalid");
                reviewerSignature = input.readNBytes(signatureLength);
                if (reviewerPublicKey.length != keyLength || reviewerSignature.length != signatureLength)
                    throw new IOException("Reviewer signature record is truncated");
            }
            Decision decision = new Decision(approved, submitter, reviewer, decidedAt, reason, keyFingerprint,
                    reviewerFingerprint, reviewerPublicKey, reviewerSignature);
            if (decision.decidedAtMillis() < 0 || input.available() != 0)
                throw new IOException("Malformed arena review decision");
            try {
                ArenaReviewSignature.validateReason(approved ? ArenaReviewSignature.Outcome.APPROVE
                        : ArenaReviewSignature.Outcome.REJECT, reason);
            } catch (IllegalArgumentException failure) {
                throw new IOException("Arena review decision reason is invalid", failure);
            }
            if (!decision.keyFingerprint().isEmpty() && !decision.keyFingerprint().matches("[0-9a-f]{64}"))
                throw new IOException("Arena review decision signing provenance is invalid");
            if (format == 3) {
                ArenaReviewSignature.Outcome outcome = approved
                        ? ArenaReviewSignature.Outcome.APPROVE : ArenaReviewSignature.Outcome.REJECT;
                try {
                    if (!decision.reviewerFingerprint().equals(DesignExchange.fingerprint(reviewerPublicKey))
                            || !ArenaReviewSignature.verify(hash, reviewer, outcome, reason,
                            reviewerPublicKey, reviewerSignature))
                        throw new IOException("Arena review decision signature is invalid");
                } catch (IllegalArgumentException failure) {
                    throw new IOException("Arena review decision signature is invalid", failure);
                }
            }
            return decision;
        }
    }

    private void ensureRoomForPending() throws IOException {
        if (listFiles(pendingDirectory, MAX_PENDING, ".submission").size() >= MAX_PENDING)
            throw new IOException("Arena review queue is full");
    }

    private static List<Path> listFiles(Path directory, int maximum, String suffix) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Arena review directory is not a regular directory");
        List<Path> paths;
        try (Stream<Path> files = Files.list(directory)) {
            paths = files.filter(path -> path.getFileName().toString().endsWith(suffix))
                    .limit((long) maximum + 1).sorted(Comparator.comparing(Path::toString)).toList();
        }
        if (paths.size() > maximum) throw new IOException("Arena review directory exceeds its finite file limit");
        return paths;
    }

    private static void writeAtomically(Path target, byte[] encoded) throws IOException {
        Files.createDirectories(target.getParent());
        if (!Files.isDirectory(target.getParent(), LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Arena review directory is not a regular directory");
        Path temporary = Files.createTempFile(target.getParent(), "arena-review-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(encoded);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException failure) {
                throw new IOException("Arena review filesystem does not support atomic writes", failure);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static byte[] withChecksum(byte[] payload) throws IOException {
        try {
            ByteArrayOutputStream encoded = new ByteArrayOutputStream(payload.length + HASH_BYTES);
            encoded.write(payload);
            encoded.write(MessageDigest.getInstance("SHA-256").digest(payload));
            return encoded.toByteArray();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static byte[] verifyChecksum(byte[] encoded) throws IOException {
        if (encoded.length < 64 || encoded.length > MAX_FILE_BYTES)
            throw new IOException("Arena review record exceeds its byte budget");
        int payloadLength = encoded.length - HASH_BYTES;
        byte[] payload = java.util.Arrays.copyOf(encoded, payloadLength);
        try {
            byte[] expected = MessageDigest.getInstance("SHA-256").digest(payload);
            if (!MessageDigest.isEqual(expected, java.util.Arrays.copyOfRange(encoded, payloadLength, encoded.length)))
                throw new IOException("Arena review checksum mismatch");
            return payload;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static void requireHash(String hash) {
        if (hash == null || !hash.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Review ID must be a full lowercase SHA-256 hash");
    }

    private static String readHash(DataInputStream input) throws IOException {
        byte[] bytes = input.readNBytes(HASH_BYTES);
        if (bytes.length != HASH_BYTES) throw new IOException("Truncated review decision hash");
        return HexFormat.of().formatHex(bytes);
    }

    private static UUID readUuid(DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }

    private static void writeUuid(DataOutputStream output, UUID value) throws IOException {
        output.writeLong(value.getMostSignificantBits());
        output.writeLong(value.getLeastSignificantBits());
    }

    private Path pendingPath(String hash) { return pendingDirectory.resolve(hash + ".submission"); }
    private Path decisionPath(String hash) { return decisionDirectory.resolve(hash + ".decision"); }

    record SubmissionView(String hash, String designId, String version, UUID author, UUID submitter,
                          long submittedAtMillis, String keyFingerprint) { }
    record DecisionView(String hash, boolean approved, UUID submitter, UUID reviewer, long decidedAtMillis,
                        String reason, String keyFingerprint, String reviewerFingerprint,
                        String reviewerPublicKeyBase64, String reviewerSignatureBase64) {
        boolean signed() { return !reviewerSignatureBase64.isEmpty(); }
    }
    record DecisionPage(int page, int totalPages, int totalRecords, List<DecisionView> decisions) { }
    record DecisionInspection(DecisionView decision, PublicationState publication) { }
    enum PublicationState { PUBLISHED, APPROVED_AWAITING_PUBLICATION, NOT_PUBLISHED }
    private record Submission(ArenaRuleset ruleset, UUID submitter, long submittedAtMillis, String keyFingerprint) { }
    private record Decision(boolean approved, UUID submitter, UUID reviewer, long decidedAtMillis, String reason,
                            String keyFingerprint, String reviewerFingerprint, byte[] reviewerPublicKey,
                            byte[] reviewerSignature) {
        private Decision {
            reviewerPublicKey = reviewerPublicKey.clone();
            reviewerSignature = reviewerSignature.clone();
        }
        @Override public byte[] reviewerPublicKey() { return reviewerPublicKey.clone(); }
        @Override public byte[] reviewerSignature() { return reviewerSignature.clone(); }
    }
    private record ReviewerProof(String fingerprint, byte[] publicKey, byte[] signature) { }
}
