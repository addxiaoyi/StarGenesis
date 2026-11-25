package dev.stargenesis.paper.arena;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.stargenesis.api.design.DesignDependency;
import dev.stargenesis.api.design.DesignDocument;
import dev.stargenesis.api.design.DesignId;
import dev.stargenesis.api.design.DesignKind;
import dev.stargenesis.api.design.DesignMetadata;
import dev.stargenesis.api.design.DesignPermissions;
import dev.stargenesis.design.ArenaRuleset;
import dev.stargenesis.design.DesignCodec;
import dev.stargenesis.design.DesignValidationException;

import java.io.IOException;
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
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.time.Instant;
import java.util.Objects;

/** Author-scoped drafts and an immutable, dependency-licensed UGC arena catalog. */
public final class ArenaRulesetStore {
    public static final int MAX_DRAFTS_PER_AUTHOR = 32;
    public static final int MAX_DRAFT_AUTHORS = 64;
    public static final int MAX_PUBLISHED_ARENAS = 256;
    public static final int MAX_REVIEW_RECORDS = 512;
    public static final int MAX_REVIEW_REASON_LENGTH = 256;
    public static final long MAX_FILE_BYTES = 262_144;
    private static final int MAX_DEPENDENCY_DEPTH = 16;
    private static final DesignId STARTER_ARENA = new DesignId("genesis:arena_ruleset");
    private final Path drafts;
    private final Path published;
    private final Path reviews;
    private final DesignCodec codec = new DesignCodec();
    private final ObjectMapper reviewMapper;

    public ArenaRulesetStore(Path root) throws IOException {
        Path directory = root.toAbsolutePath().normalize();
        drafts = directory.resolve("drafts");
        published = directory.resolve("published");
        reviews = directory.resolve("reviews");
        Files.createDirectories(drafts);
        Files.createDirectories(published);
        Files.createDirectories(reviews);
        rejectSymlink(directory);
        rejectSymlink(drafts);
        rejectSymlink(published);
        rejectSymlink(reviews);
        reviewMapper = new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(12)
                        .maxStringLength(MAX_REVIEW_REASON_LENGTH + 256).maxNumberLength(12).build()).build());
        reviewMapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS);
        reviewMapper.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        reviewMapper.disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    }

    public synchronized ArenaRuleset create(UUID owner, DesignId id) throws IOException {
        if (owner == null || id == null) throw new IllegalArgumentException("Missing arena owner or ID");
        if (loadDraft(owner, id).isPresent()) throw new IOException("Arena draft already exists; open it before editing");
        Path directory = ownerDirectory(owner);
        Files.createDirectories(directory);
        rejectSymlink(directory);
        if (countDrafts(directory) >= MAX_DRAFTS_PER_AUTHOR) throw new IOException("Arena draft limit reached");
        DesignMetadata metadata = new DesignMetadata(id, DesignCodec.SCHEMA_VERSION, "1.0.0", owner,
                "genesis:player-arena", List.of(), "", new DesignPermissions(false, false, Set.of()));
        ArenaRuleset initial = (ArenaRuleset) codec.load(codec.seal(new ArenaRuleset(metadata, 16, 16, 1200, 4)));
        write(path(owner, id), codec.write(initial));
        return initial;
    }

    public synchronized ArenaRuleset clonePublished(UUID owner, DesignId sourceId, DesignId targetId) throws IOException {
        ArenaRuleset source = latestPublished(sourceId)
                .orElseThrow(() -> new IOException("Published arena does not exist"));
        if (!source.metadata().permissions().publicRead() || !source.metadata().permissions().allowClone())
            throw new SecurityException("Arena does not allow public cloning");
        if (sourceId.equals(targetId)) throw new IllegalArgumentException("Clone must use a new design ID");
        Path target = path(owner, targetId);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Arena draft already exists");
        Path directory = ownerDirectory(owner);
        Files.createDirectories(directory);
        rejectSymlink(directory);
        if (countDrafts(directory) >= MAX_DRAFTS_PER_AUTHOR) throw new IOException("Arena draft limit reached");
        DesignMetadata old = source.metadata();
        DesignMetadata metadata = new DesignMetadata(targetId, old.schemaVersion(), "1.0.0", owner,
                "genesis:arena-clone:" + sourceId.value(), old.dependencies(), "",
                new DesignPermissions(false, false, Set.of()));
        ArenaRuleset copy = new ArenaRuleset(metadata, source.width(), source.height(),
                source.durationSteps(), source.unitBudget());
        ArenaRuleset sealed = (ArenaRuleset) codec.load(codec.seal(copy));
        write(target, codec.write(sealed));
        return sealed;
    }

    public synchronized Optional<ArenaRuleset> loadDraft(UUID editor, DesignId id) throws IOException {
        if (editor == null || id == null) throw new IllegalArgumentException("Missing arena editor or ID");
        Path own = path(editor, id);
        if (Files.exists(own, LinkOption.NOFOLLOW_LINKS)) {
            ArenaRuleset document = read(own);
            authorize(editor, document);
            return Optional.of(document);
        }
        Path authorDirectory = findDraftAuthor(id);
        if (authorDirectory == null) return Optional.empty();
        ArenaRuleset document = read(authorDirectory.resolve(fileName(id)));
        authorize(editor, document);
        return Optional.of(document);
    }

    public synchronized List<ArenaRuleset> listDrafts(UUID owner) throws IOException {
        Path directory = ownerDirectory(owner);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
        rejectSymlink(directory);
        List<ArenaRuleset> result = new ArrayList<>();
        try (var files = Files.newDirectoryStream(directory, "*.json")) {
            for (Path file : files) {
                if (result.size() >= MAX_DRAFTS_PER_AUTHOR) throw new IOException("Arena draft directory exceeds its count budget");
                ArenaRuleset ruleset = read(file);
                authorize(owner, ruleset);
                result.add(ruleset);
            }
        }
        return result.stream().sorted(documentOrder()).toList();
    }

    public synchronized ArenaRuleset saveDraft(UUID editor, ArenaRuleset candidate, String expectedHash) throws IOException {
        authorize(editor, candidate);
        Path target = path(candidate.metadata().author(), candidate.metadata().id());
        Path directory = ownerDirectory(candidate.metadata().author());
        Files.createDirectories(directory);
        rejectSymlink(directory);
        verifyExpectedVersion(target, expectedHash);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS) && countDrafts(directory) >= MAX_DRAFTS_PER_AUTHOR)
            throw new IOException("Arena draft limit reached");
        ArenaRuleset signed = seal(candidate);
        write(target, codec.write(signed));
        return signed;
    }

    public synchronized ReviewRecord submitForReview(UUID actor, DesignId id, String expectedHash) throws IOException {
        ArenaRuleset draft = loadDraft(actor, id).orElseThrow(() -> new IOException("Arena draft does not exist"));
        authorize(actor, draft);
        validatePublicationCandidate(draft, expectedHash);
        Path target = reviewPath(id, draft.metadata().version());
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            ReviewRecord previous = readReview(target);
            if (previous.contentHash().equals(draft.metadata().hash())) {
                if (previous.status() == ReviewStatus.PENDING) throw new IOException("Arena submission is already pending review");
                if (previous.status() == ReviewStatus.APPROVED) throw new IOException("Arena version has already been approved");
                throw new IOException("Rejected Arena versions are immutable; submit a higher version");
            }
            throw new IOException("An Arena review record already exists for this version");
        }
        if (countReviewRecords() >= MAX_REVIEW_RECORDS) throw new IOException("Arena review record limit reached");
        ReviewRecord submission = new ReviewRecord(1, draft.metadata().author(), id, draft.metadata().version(),
                draft.metadata().hash(), ReviewStatus.PENDING, Instant.now().toString(), null, "", "");
        writeReview(target, submission);
        return submission;
    }

    public synchronized List<ReviewRecord> pendingReviews() throws IOException {
        rejectSymlink(reviews);
        List<ReviewRecord> result = new ArrayList<>();
        int records = 0;
        try (var files = Files.newDirectoryStream(reviews, "*.json")) {
            for (Path file : files) {
                if (++records > MAX_REVIEW_RECORDS) throw new IOException("Arena review queue exceeds its count budget");
                ReviewRecord record = readReview(file);
                if (record.status() == ReviewStatus.PENDING) result.add(record);
            }
        }
        return result.stream().sorted(Comparator.comparing(ReviewRecord::submittedAt)
                .thenComparing(record -> record.id().value()).thenComparing(ReviewRecord::version)).toList();
    }

    public synchronized Optional<ReviewRecord> reviewRecord(DesignId id, String version) throws IOException {
        Path file = reviewPath(id, version);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        return Optional.of(readReview(file));
    }

    public synchronized ReviewRecord approveReview(UUID reviewer, DesignId id, String version) throws IOException {
        if (reviewer == null) throw new IllegalArgumentException("Missing Arena reviewer");
        ReviewRecord submission = reviewRecord(id, version)
                .orElseThrow(() -> new IOException("Arena review submission does not exist"));
        if (submission.status() == ReviewStatus.REJECTED)
            throw new IOException("Rejected Arena review cannot be approved");
        if (submission.status() == ReviewStatus.PENDING) requireIndependentReviewer(reviewer, submission);
        else if (!reviewer.equals(submission.reviewer()))
            throw new SecurityException("Only the approving reviewer may resume publication");

        ArenaRuleset publishedVersion = loadPublished(id, version).orElse(null);
        if (publishedVersion == null) {
            ArenaRuleset draft = loadDraft(submission.author(), id)
                    .orElseThrow(() -> new IOException("Submitted Arena draft no longer exists"));
            if (!draft.metadata().hash().equals(submission.contentHash()))
                throw new IOException("Submitted Arena draft changed; submit the new hash for review");
            validatePublicationCandidate(draft, submission.contentHash());
            if (submission.status() == ReviewStatus.PENDING) {
                submission = new ReviewRecord(1, submission.author(), id, version, submission.contentHash(),
                        ReviewStatus.APPROVED, submission.submittedAt(), reviewer, "", Instant.now().toString());
                writeReview(reviewPath(id, version), submission);
            }
            publishDocument(draft);
        } else if (!publishedVersion.metadata().hash().equals(submission.contentHash())) {
            throw new IOException("Published Arena version does not match the review submission");
        } else if (submission.status() == ReviewStatus.PENDING) {
            submission = new ReviewRecord(1, submission.author(), id, version, submission.contentHash(),
                    ReviewStatus.APPROVED, submission.submittedAt(), reviewer, "", Instant.now().toString());
            writeReview(reviewPath(id, version), submission);
        }
        return submission;
    }

    public synchronized ReviewRecord rejectReview(UUID reviewer, DesignId id, String version, String reason) throws IOException {
        ReviewRecord submission = requirePendingReview(id, version);
        requireIndependentReviewer(reviewer, submission);
        if (reason == null || reason.isBlank() || reason.length() > MAX_REVIEW_REASON_LENGTH)
            throw new IllegalArgumentException("Review rejection reason must be 1-256 characters");
        ReviewRecord rejected = new ReviewRecord(1, submission.author(), id, version, submission.contentHash(),
                ReviewStatus.REJECTED, submission.submittedAt(), reviewer, reason.trim(), Instant.now().toString());
        writeReview(reviewPath(id, version), rejected);
        return rejected;
    }

    private ReviewRecord requirePendingReview(DesignId id, String version) throws IOException {
        Path file = reviewPath(id, version);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Arena review submission does not exist");
        ReviewRecord submission = readReview(file);
        if (submission.status() != ReviewStatus.PENDING) throw new IOException("Arena review submission is not pending");
        return submission;
    }

    private void requireIndependentReviewer(UUID reviewer, ReviewRecord submission) {
        if (reviewer == null) throw new IllegalArgumentException("Missing Arena reviewer");
        if (reviewer.equals(submission.author())) throw new SecurityException("Arena authors cannot review their own submissions");
    }

    private void validatePublicationCandidate(ArenaRuleset draft, String expectedHash) throws IOException {
        if (expectedHash == null || !expectedHash.equals(draft.metadata().hash()))
            throw new IOException("Arena draft changed; reopen it before submission");
        if (!draft.metadata().permissions().publicRead()) throw new SecurityException("Public catalog publication requires publicRead");
        validateDependencies(draft);
        List<ArenaRuleset> versions = publishedVersions(draft.metadata().id());
        for (ArenaRuleset version : versions)
            if (version.metadata().version().equals(draft.metadata().version()))
                throw new IOException("Arena version is already published");
        if (!versions.isEmpty() && compareVersions(draft.metadata().version(),
                versions.getLast().metadata().version()) <= 0)
            throw new IOException("Published arena versions must increase monotonically");
        Path target = publishedPath(draft.metadata().id(), draft.metadata().version());
        if (countPublished() >= MAX_PUBLISHED_ARENAS && !Files.exists(target, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Published arena catalog is full");
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                && !read(target).metadata().hash().equals(draft.metadata().hash()))
            throw new IOException("Published arena version is immutable");
    }

    private void publishDocument(ArenaRuleset draft) throws IOException {
        Path target = publishedPath(draft.metadata().id(), draft.metadata().version());
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            ArenaRuleset existing = read(target);
            if (existing.metadata().hash().equals(draft.metadata().hash())) return;
            throw new IOException("Published arena version is immutable");
        }
        write(target, codec.write(draft));
    }

    public synchronized List<ArenaRuleset> catalog() throws IOException {
        List<ArenaRuleset> result = new ArrayList<>();
        result.add(readBundledStarter());
        result.addAll(allPublished());
        return result.stream().sorted(documentOrder()).toList();
    }

    public synchronized Optional<ArenaRuleset> latestPublished(DesignId id) throws IOException {
        List<ArenaRuleset> versions = new ArrayList<>(publishedVersions(id));
        if (id.equals(STARTER_ARENA)) versions.add(readBundledStarter());
        return versions.stream().max(documentOrder());
    }

    public synchronized Optional<ArenaRuleset> loadPublished(DesignId id, String version) throws IOException {
        if (id.equals(STARTER_ARENA)) {
            ArenaRuleset starter = readBundledStarter();
            if (starter.metadata().version().equals(version)) return Optional.of(starter);
        }
        return publishedVersions(id).stream()
                .filter(document -> document.metadata().version().equals(version)).findFirst();
    }

    public synchronized void validatePublishedDependencies(ArenaRuleset ruleset) throws IOException {
        if (ruleset == null || !ruleset.metadata().permissions().publicRead())
            throw new SecurityException("Arena ruleset is not publicly playable");
        validateDependencies(ruleset);
    }

    public synchronized ArenaRuleset requirePinnedPlayable(String id, String version, String hash) throws IOException {
        ArenaRuleset ruleset = loadPublished(new DesignId(id), version)
                .orElseThrow(() -> new IOException("Pinned ArenaRuleset publication is missing"));
        if (!ruleset.metadata().hash().equals(hash)) throw new IOException("Pinned ArenaRuleset hash mismatch");
        validatePublishedDependencies(ruleset);
        return ruleset;
    }

    private void validateDependencies(ArenaRuleset root) throws IOException {
        Map<Pin, DesignDocument> registry = loadRegistry();
        Set<Pin> visiting = new HashSet<>();
        visiting.add(pin(root));
        validateDependencyClosure(root, registry, visiting, 0);
    }

    private void validateDependencyClosure(DesignDocument parent, Map<Pin, DesignDocument> registry,
                                           Set<Pin> visiting, int depth) throws IOException {
        if (depth > MAX_DEPENDENCY_DEPTH) throw new IOException("Arena dependency depth exceeds its budget");
        for (DesignDependency dependency : parent.metadata().dependencies()) {
            Pin dependencyPin = new Pin(dependency.id(), dependency.version(), dependency.hash());
            DesignDocument target = registry.get(dependencyPin);
            if (target == null) throw new IOException("Unresolved design dependency " + dependency.id().value());
            if (!target.metadata().permissions().publicRead() || !target.metadata().permissions().allowClone())
                throw new SecurityException("Dependency does not grant publicRead and allowClone: " + dependency.id().value());
            if (target instanceof ArenaRuleset arena && !visiting.add(dependencyPin))
                throw new IOException("Arena dependency cycle rejected");
            if (target instanceof ArenaRuleset arena)
                validateDependencyClosure(arena, registry, visiting, depth + 1);
            visiting.remove(dependencyPin);
        }
    }

    private Map<Pin, DesignDocument> loadRegistry() throws IOException {
        Map<Pin, DesignDocument> registry = new TreeMap<>(Comparator.comparing((Pin pin) -> pin.id().value())
                .thenComparing(Pin::version).thenComparing(Pin::hash));
        for (DesignKind kind : DesignKind.values()) {
            String resource = "/examples/" + kind.name().toLowerCase(java.util.Locale.ROOT) + ".json";
            try (var input = DesignCodec.class.getResourceAsStream(resource)) {
                if (input == null) throw new IOException("Missing bundled design " + resource);
                DesignDocument document = codec.load(new String(input.readAllBytes(), StandardCharsets.UTF_8));
                registry.put(pin(document), document);
            } catch (DesignValidationException invalid) {
                throw new IOException("Bundled design failed integrity validation", invalid);
            }
        }
        for (ArenaRuleset arena : allPublished()) registry.put(pin(arena), arena);
        return registry;
    }

    private List<ArenaRuleset> allPublished() throws IOException {
        List<ArenaRuleset> result = new ArrayList<>();
        try (var files = Files.newDirectoryStream(published, "*.json")) {
            for (Path file : files) {
                if (result.size() >= MAX_PUBLISHED_ARENAS) throw new IOException("Published arena catalog exceeds its count budget");
                ArenaRuleset arena = read(file);
                Optional<ReviewRecord> review = reviewRecord(arena.metadata().id(), arena.metadata().version());
                if (review.isPresent()) {
                    ReviewRecord decision = review.orElseThrow();
                    if (!decision.contentHash().equals(arena.metadata().hash()))
                        throw new IOException("Arena publication hash does not match its review record");
                    if (decision.status() != ReviewStatus.APPROVED) continue;
                }
                result.add(arena);
            }
        }
        return result;
    }

    private List<ArenaRuleset> publishedVersions(DesignId id) throws IOException {
        return allPublished().stream().filter(document -> document.metadata().id().equals(id))
                .sorted(documentOrder()).toList();
    }

    private ArenaRuleset readBundledStarter() throws IOException {
        try (var input = DesignCodec.class.getResourceAsStream("/examples/arena_ruleset.json")) {
            if (input == null) throw new IOException("Missing bundled arena starter");
            DesignDocument document = codec.load(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            if (!(document instanceof ArenaRuleset arena)) throw new IOException("Bundled arena starter has the wrong kind");
            return arena;
        } catch (DesignValidationException invalid) {
            throw new IOException("Bundled arena starter failed integrity validation", invalid);
        }
    }

    private ArenaRuleset seal(ArenaRuleset candidate) throws IOException {
        try { return (ArenaRuleset) codec.load(codec.seal(candidate)); }
        catch (DesignValidationException invalid) { throw new IOException("Arena ruleset validation failed", invalid); }
    }

    private ArenaRuleset read(Path file) throws IOException {
        rejectSymlink(file);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_FILE_BYTES)
            throw new IOException("Arena file is not regular or exceeds 256 KiB");
        byte[] data;
        try (var input = Files.newInputStream(file)) { data = input.readNBytes((int) MAX_FILE_BYTES + 1); }
        if (data.length > MAX_FILE_BYTES) throw new IOException("Arena document exceeds its byte budget");
        try {
            DesignDocument document = codec.load(new String(data, StandardCharsets.UTF_8));
            if (!(document instanceof ArenaRuleset arena)) throw new IOException("Stored design is not an ArenaRuleset");
            return arena;
        } catch (DesignValidationException invalid) {
            throw new IOException("Stored arena failed integrity validation", invalid);
        }
    }

    private void authorize(UUID actor, ArenaRuleset document) {
        if (actor == null || !document.metadata().permissions().mayEdit(actor, document.metadata().author()))
            throw new SecurityException("Arena draft is not editable by this player");
    }

    private void verifyExpectedVersion(Path target, String expectedHash) throws IOException {
        if (expectedHash == null || !(expectedHash.isEmpty() || expectedHash.matches("[0-9a-f]{64}")))
            throw new IllegalArgumentException("Invalid expected arena hash");
        boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        if (expectedHash.isEmpty() && exists) throw new IOException("Arena draft exists; open it before saving");
        if (!expectedHash.isEmpty() && !exists) throw new IOException("Arena draft was removed; reopen it before saving");
        if (exists && !read(target).metadata().hash().equals(expectedHash))
            throw new IOException("Arena draft changed since it was opened; reopen it before saving");
    }

    private Path findDraftAuthor(DesignId id) throws IOException {
        List<Path> authors;
        try (var directories = Files.list(drafts)) {
            authors = directories.filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)).limit(MAX_DRAFT_AUTHORS + 1).toList();
        }
        if (authors.size() > MAX_DRAFT_AUTHORS) throw new IOException("Arena author directory exceeds its count budget");
        Path found = null;
        for (Path author : authors) {
            rejectSymlink(author);
            Path candidate = author.resolve(fileName(id));
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) continue;
            if (found != null) throw new IOException("Arena ID is ambiguous across author drafts");
            found = author;
        }
        return found;
    }

    private int countDrafts(Path directory) throws IOException {
        int count = 0;
        try (var files = Files.newDirectoryStream(directory, "*.json")) {
            for (Path ignored : files) if (++count > MAX_DRAFTS_PER_AUTHOR) break;
        }
        return count;
    }

    private int countPublished() throws IOException {
        int count = 0;
        try (var files = Files.newDirectoryStream(published, "*.json")) {
            for (Path ignored : files) if (++count > MAX_PUBLISHED_ARENAS) break;
        }
        return count;
    }

    private int countReviewRecords() throws IOException {
        int count = 0;
        try (var files = Files.newDirectoryStream(reviews, "*.json")) {
            for (Path file : files) if (++count > MAX_REVIEW_RECORDS) break;
        }
        return count;
    }

    private Path reviewPath(DesignId id, String version) {
        if (id == null || version == null || !version.matches("(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})"))
            throw new IllegalArgumentException("Invalid Arena review identity");
        return reviews.resolve(hash(id.value() + "@" + version) + ".json");
    }

    private ReviewRecord readReview(Path file) throws IOException {
        rejectSymlink(file);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > 8192)
            throw new IOException("Arena review record is not regular or exceeds 8 KiB");
        byte[] data;
        try (var input = Files.newInputStream(file)) { data = input.readNBytes(8193); }
        if (data.length > 8192) throw new IOException("Arena review record exceeds 8 KiB");
        ReviewRecord record = reviewMapper.readValue(data, ReviewRecord.class);
        if (record.schemaVersion() != 1 || record.author() == null || record.id() == null
                || record.version() == null || !record.version().matches("(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})")
                || record.contentHash() == null || !record.contentHash().matches("[0-9a-f]{64}")
                || record.submittedAt() == null || record.reason() == null
                || record.reason().length() > MAX_REVIEW_REASON_LENGTH || record.reviewedAt() == null
                || record.status() == null
                || !file.getFileName().equals(reviewPath(record.id(), record.version()).getFileName()))
            throw new IOException("Arena review record identity or schema is invalid");
        try { Instant.parse(record.submittedAt()); }
        catch (RuntimeException invalid) { throw new IOException("Arena review submission timestamp is invalid", invalid); }
        if (record.status() == ReviewStatus.PENDING) {
            if (record.reviewer() != null || !record.reason().isEmpty() || !record.reviewedAt().isEmpty())
                throw new IOException("Pending Arena review record contains a decision");
        } else {
            if (record.reviewer() == null || record.reviewer().equals(record.author()) || record.reviewedAt().isEmpty())
                throw new IOException("Arena review decision identity is invalid");
            if (record.status() == ReviewStatus.APPROVED && !record.reason().isEmpty())
                throw new IOException("Approved Arena review cannot contain a rejection reason");
            if (record.status() == ReviewStatus.REJECTED && record.reason().isBlank())
                throw new IOException("Rejected Arena review requires a reason");
            try { Instant.parse(record.reviewedAt()); }
            catch (RuntimeException invalid) { throw new IOException("Arena review decision timestamp is invalid", invalid); }
        }
        return record;
    }

    private void writeReview(Path target, ReviewRecord record) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        rejectSymlink(target);
        rejectSymlink(temporary);
        byte[] data = reviewMapper.writeValueAsBytes(record);
        if (data.length > 8192) throw new IOException("Arena review record exceeds 8 KiB");
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            var buffer = java.nio.ByteBuffer.wrap(data);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException failure) {
            Files.deleteIfExists(temporary);
            throw new IOException("Atomic Arena review write unavailable; previous record preserved", failure);
        }
    }

    private Path ownerDirectory(UUID owner) {
        if (owner == null) throw new IllegalArgumentException("Missing arena owner");
        return drafts.resolve(owner.toString());
    }

    private Path path(UUID owner, DesignId id) { return ownerDirectory(owner).resolve(fileName(id)); }

    private String fileName(DesignId id) { return hash(id.value()) + ".json"; }

    private Path publishedPath(DesignId id, String version) { return published.resolve(hash(id.value() + "@" + version) + ".json"); }

    private String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }

    private void write(Path target, String encoded) throws IOException {
        byte[] data = encoded.getBytes(StandardCharsets.UTF_8);
        if (data.length > MAX_FILE_BYTES) throw new IOException("Arena document exceeds its byte budget");
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        rejectSymlink(target);
        rejectSymlink(temporary);
        try (var channel = java.nio.channels.FileChannel.open(temporary, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            var buffer = java.nio.ByteBuffer.wrap(data);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException failure) {
            Files.deleteIfExists(temporary);
            throw new IOException("Atomic arena write unavailable; previous file preserved", failure);
        }
    }

    private void rejectSymlink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("Arena symlink path rejected");
    }

    private Comparator<ArenaRuleset> documentOrder() {
        return Comparator.comparing((ArenaRuleset document) -> document.metadata().id())
                .thenComparing(document -> document.metadata().version(), ArenaRulesetStore::compareVersions);
    }

    private static int compareVersions(String first, String second) {
        String[] a = first.split("\\.");
        String[] b = second.split("\\.");
        for (int i = 0; i < 3; i++) {
            int comparison = Integer.compare(Integer.parseInt(a[i]), Integer.parseInt(b[i]));
            if (comparison != 0) return comparison;
        }
        return 0;
    }

    private Pin pin(DesignDocument document) {
        return new Pin(document.metadata().id(), document.metadata().version(), document.metadata().hash());
    }

    private record Pin(DesignId id, String version, String hash) { }

    public enum ReviewStatus { PENDING, APPROVED, REJECTED }
    public record ReviewRecord(int schemaVersion, UUID author, DesignId id, String version, String contentHash,
                               ReviewStatus status, String submittedAt, UUID reviewer, String reason, String reviewedAt) { }
}
