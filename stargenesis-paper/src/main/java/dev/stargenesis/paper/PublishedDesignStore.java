package dev.stargenesis.paper;

import dev.stargenesis.api.design.DesignDependency;
import dev.stargenesis.api.design.DesignDocument;
import dev.stargenesis.api.design.DesignKind;
import dev.stargenesis.design.ArenaRuleset;
import dev.stargenesis.design.ArenaRulesetValidator;
import dev.stargenesis.design.DesignCodec;

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
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/** Immutable, content-verified releases for player-authored designs. */
final class PublishedDesignStore {
    private static final int MAX_RELEASES = 10_000;
    private static final int MAX_VERSIONS_PER_DESIGN = 256;

    private final Path root;
    private final DesignCodec codec;

    PublishedDesignStore(Path directory, DesignCodec codec) {
        root = directory.toAbsolutePath().normalize();
        this.codec = codec;
    }

    synchronized DesignDocument publish(DesignDocument candidate, UUID actor) throws IOException {
        Publication publication = preparePublication(candidate, actor);
        return publication.existing() ? publication.document()
                : writeImmutable(publication.document(), publication.json());
    }

    /** Performs the same identity, version, ACL and dependency checks without publishing. */
    synchronized void validatePublication(ArenaRuleset candidate, UUID actor) throws IOException {
        preparePublication(candidate, actor);
    }

    private Publication preparePublication(DesignDocument candidate, UUID actor) throws IOException {
        if (!(candidate instanceof ArenaRuleset arena))
            throw new IllegalArgumentException("This release store currently accepts arena rulesets only");
        new ArenaRulesetValidator().requireValid(arena);
        Optional<ArenaRuleset> latest = latestArenaRelease(candidate.metadata().id().value());
        if (latest.isEmpty()) {
            if (!candidate.metadata().author().equals(actor))
                throw new SecurityException("A new design must be published by its declared author");
        } else {
            ArenaRuleset current = latest.get();
            if (!candidate.metadata().author().equals(current.metadata().author()))
                throw new SecurityException("A published design cannot change its author");
            if (!current.metadata().permissions().mayEdit(actor, current.metadata().author()))
                throw new SecurityException("Actor is not authorized by the latest published arena release");
            if (!actor.equals(current.metadata().author())
                    && !candidate.metadata().permissions().equals(current.metadata().permissions()))
                throw new SecurityException("Only the design author can change published editor permissions");
            if (candidate.metadata().version().equals(current.metadata().version())
                    && candidate.metadata().hash().equals(current.metadata().hash())) {
                verifyDependencies(current);
                return new Publication(current, "", true);
            }
            if (compareVersions(candidate.metadata().version(), current.metadata().version()) <= 0)
                throw new IOException("A changed arena release must use a version newer than the latest published version");
        }
        String encoded = codec.writeEdited(candidate, actor);
        DesignDocument sealed = codec.load(encoded);
        verifyDependencies(sealed);
        return new Publication(sealed, encoded, false);
    }

    synchronized void seedBundled(ArenaRuleset ruleset) throws IOException {
        if (!ruleset.metadata().dependencies().isEmpty())
            throw new IllegalArgumentException("Bundled arena seed cannot have dependencies");
        new ArenaRulesetValidator().requireValid(ruleset);
        String encoded = codec.write(ruleset);
        writeImmutable(ruleset, encoded);
    }

    synchronized Optional<DesignDocument> findExact(DesignDependency dependency) throws IOException {
        Path path = releasePath(dependency.id().value(), dependency.version(), dependency.hash());
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        DesignDocument document = read(path);
        if (!document.metadata().id().equals(dependency.id())
                || !document.metadata().version().equals(dependency.version())
                || !document.metadata().hash().equals(dependency.hash()))
            throw new IOException("Published release identity does not match its path");
        return Optional.of(document);
    }

    synchronized Optional<ArenaRuleset> findArenaByHash(String hash) throws IOException {
        for (DesignDocument document : list(DesignKind.ARENA_RULESET)) {
            if (document instanceof ArenaRuleset arena && arena.metadata().hash().equals(hash))
                return Optional.of(arena);
        }
        return Optional.empty();
    }

    synchronized void requireCanEdit(ArenaRuleset candidate, UUID actor) throws IOException {
        Optional<ArenaRuleset> latest = latestArenaRelease(candidate.metadata().id().value());
        if (latest.isEmpty()) {
            if (!candidate.metadata().author().equals(actor))
                throw new SecurityException("An unpublished design can only be edited by its declared author");
            return;
        }
        ArenaRuleset current = latest.get();
        if (!current.metadata().author().equals(candidate.metadata().author())
                || !current.metadata().permissions().mayEdit(actor, current.metadata().author()))
            throw new SecurityException("Actor is not authorized by the latest published arena release");
    }

    synchronized void validateSubmission(ArenaRuleset candidate, UUID actor) throws IOException {
        new ArenaRulesetValidator().requireValid(candidate);
        requireCanEdit(candidate, actor);
        verifyDependencies(candidate);
    }

    synchronized List<DesignDocument> list(DesignKind kind) throws IOException {
        if (!Files.exists(root)) return List.of();
        List<Path> paths;
        try (Stream<Path> files = Files.walk(root, 2)) {
            paths = files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .limit(MAX_RELEASES + 1L).sorted(Comparator.comparing(Path::toString)).toList();
        }
        if (paths.size() > MAX_RELEASES) throw new IOException("Published release catalog exceeds its finite file budget");
        List<DesignDocument> result = new ArrayList<>();
        for (Path path : paths) {
            DesignDocument document = read(path);
            if (document.kind() == kind) result.add(document);
        }
        return List.copyOf(result);
    }

    private DesignDocument writeImmutable(DesignDocument document, String encoded) throws IOException {
        Path identityDirectory = identityDirectory(document.metadata().id().value());
        if (Files.exists(identityDirectory)) {
            if (!Files.isDirectory(identityDirectory, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Published design identity path is not a directory");
            List<Path> versions;
            try (Stream<Path> files = Files.list(identityDirectory)) {
                versions = files.filter(path -> path.getFileName().toString().endsWith(".json"))
                        .limit(MAX_VERSIONS_PER_DESIGN + 1L).toList();
            }
            if (versions.size() >= MAX_VERSIONS_PER_DESIGN)
                throw new IOException("Published design has reached its version limit");
            for (Path versionPath : versions) {
                DesignDocument existing = read(versionPath);
                if (!existing.metadata().version().equals(document.metadata().version())) continue;
                if (existing.metadata().hash().equals(document.metadata().hash())) return existing;
                throw new IOException("Published versions are immutable; increment the patch version before publishing changes");
            }
        }

        Path target = releasePath(document.metadata().id().value(), document.metadata().version(), document.metadata().hash());
        Files.createDirectories(target.getParent());
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            DesignDocument existing = read(target);
            if (existing.metadata().hash().equals(document.metadata().hash())) return existing;
            throw new IOException("Published release path is already occupied");
        }
        Path temporary = Files.createTempFile(target.getParent(), "release-", ".tmp");
        try {
            byte[] bytes = encoded.getBytes(StandardCharsets.UTF_8);
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException failure) {
                throw new IOException("Release filesystem does not support atomic publication", failure);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return document;
    }

    private void verifyDependencies(DesignDocument document) throws IOException {
        for (DesignDependency dependency : document.metadata().dependencies()) {
            DesignDocument target = findExact(dependency)
                    .orElseThrow(() -> new IllegalArgumentException("Missing exact published dependency " + dependency.id().value()
                            + "@" + dependency.version()));
            if (!target.metadata().permissions().publicRead() || !target.metadata().permissions().allowClone())
                throw new SecurityException("Dependency does not permit public use and cloning: " + dependency.id().value());
        }
    }

    private Optional<ArenaRuleset> latestArenaRelease(String id) throws IOException {
        Path identityDirectory = identityDirectory(id);
        if (!Files.exists(identityDirectory, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        if (!Files.isDirectory(identityDirectory, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Published design identity path is not a directory");
        List<Path> versions;
        try (Stream<Path> files = Files.list(identityDirectory)) {
            versions = files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .limit(MAX_VERSIONS_PER_DESIGN + 1L).toList();
        }
        if (versions.size() > MAX_VERSIONS_PER_DESIGN)
            throw new IOException("Published design has exceeded its finite version budget");
        ArenaRuleset latest = null;
        for (Path path : versions) {
            DesignDocument document = read(path);
            if (!(document instanceof ArenaRuleset arena) || !arena.metadata().id().value().equals(id))
                throw new IOException("Published design directory contains an unexpected document");
            if (latest == null || compareVersions(arena.metadata().version(), latest.metadata().version()) > 0)
                latest = arena;
        }
        return Optional.ofNullable(latest);
    }

    private static int compareVersions(String first, String second) {
        String[] left = first.split("\\.");
        String[] right = second.split("\\.");
        for (int index = 0; index < 3; index++) {
            int comparison = Integer.compare(Integer.parseInt(left[index]), Integer.parseInt(right[index]));
            if (comparison != 0) return comparison;
        }
        return 0;
    }

    private DesignDocument read(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Published release is not a regular file");
        long size = Files.size(path);
        if (size < 2 || size > DesignCodec.MAX_DOCUMENT_BYTES)
            throw new IOException("Published release exceeds its finite byte budget");
        byte[] bytes = Files.readAllBytes(path);
        String json = new String(bytes, StandardCharsets.UTF_8);
        if (!java.util.Arrays.equals(bytes, json.getBytes(StandardCharsets.UTF_8)))
            throw new IOException("Published release contains invalid UTF-8");
        try {
            return codec.load(json);
        } catch (IllegalArgumentException failure) {
            throw new IOException("Published release failed design validation", failure);
        }
    }

    private Path identityDirectory(String id) {
        return root.resolve(sha256(id));
    }

    private Path releasePath(String id, String version, String hash) {
        return identityDirectory(id).resolve(version + "-" + hash + ".json");
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private record Publication(DesignDocument document, String json, boolean existing) { }
}
