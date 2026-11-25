package dev.stargenesis.paper;

import dev.stargenesis.api.design.DesignDocument;
import dev.stargenesis.api.design.DesignKind;
import dev.stargenesis.design.DesignExchange;
import dev.stargenesis.design.DesignSigningTrust;
import dev.stargenesis.design.SignedDesignArtifact;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
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
import java.util.stream.Stream;

/** Stores trusted, declarative design packages without activating or rewriting their contents. */
final class SignedDesignArchiveStore {
    static final int MAX_ARCHIVED_DESIGNS = 256;
    private static final String EXTENSION = ".sgdesign";
    private final Path directory;
    private final DesignExchange exchange;

    SignedDesignArchiveStore(Path dataDirectory, DesignExchange exchange) {
        directory = dataDirectory.resolve("design-library").toAbsolutePath().normalize();
        this.exchange = exchange;
    }

    synchronized ImportedDesign importSigned(byte[] encodedArtifact, DesignSigningTrust trust) throws IOException {
        var verified = exchange.verify(encodedArtifact, trust);
        if (verified.document().kind() == DesignKind.ARENA_RULESET)
            throw new IllegalArgumentException("Signed arena rulesets must enter the moderation queue");

        Files.createDirectories(directory);
        requireDirectory();
        String artifactHash = sha256(encodedArtifact);
        Path target = path(artifactHash);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            byte[] existing = readBytes(target);
            if (!MessageDigest.isEqual(existing, encodedArtifact))
                throw new IOException("Signed design archive hash collision");
            return read(target);
        }
        if (listPaths().size() >= MAX_ARCHIVED_DESIGNS)
            throw new IOException("Signed design archive is full");

        Path temporary = Files.createTempFile(directory, "signed-design-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(encodedArtifact);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException failure) {
                throw new IOException("Signed design archive does not support atomic replacement", failure);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return new ImportedDesign(artifactHash, verified.document(), verified.keyFingerprint());
    }

    synchronized List<ImportedDesign> list() throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
        requireDirectory();
        List<ImportedDesign> result = new ArrayList<>();
        for (Path file : listPaths()) result.add(read(file));
        return List.copyOf(result);
    }

    synchronized Optional<ImportedDesign> find(String artifactHash) throws IOException {
        if (artifactHash == null || !artifactHash.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Artifact hash must be a complete lowercase SHA-256");
        Path target = path(artifactHash);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        return Optional.of(read(target));
    }

    private List<Path> listPaths() throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            List<Path> files = entries.filter(file -> file.getFileName().toString().endsWith(EXTENSION))
                    .sorted(Comparator.comparing(file -> file.getFileName().toString()))
                    .limit(MAX_ARCHIVED_DESIGNS + 1L).toList();
            if (files.size() > MAX_ARCHIVED_DESIGNS)
                throw new IOException("Signed design archive exceeds its finite record limit");
            return files;
        }
    }

    private ImportedDesign read(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Signed design archive entry is not a regular file");
        String filename = file.getFileName().toString();
        if (!filename.matches("[0-9a-f]{64}\\.sgdesign"))
            throw new IOException("Signed design archive entry has an invalid filename");
        byte[] encoded = readBytes(file);
        String artifactHash = sha256(encoded);
        if (!filename.equals(artifactHash + EXTENSION))
            throw new IOException("Signed design archive hash does not match its filename");
        var verified = exchange.verifyPublisherSignature(encoded);
        if (verified.document().kind() == DesignKind.ARENA_RULESET)
            throw new IOException("Arena rulesets must be stored in the moderation queue");
        return new ImportedDesign(artifactHash, verified.document(), verified.keyFingerprint());
    }

    private static byte[] readBytes(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Signed design archive entry is not a regular file");
        try (var input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] encoded = input.readNBytes(SignedDesignArtifact.MAX_ARTIFACT_BYTES + 1);
            if (encoded.length < 1 || encoded.length > SignedDesignArtifact.MAX_ARTIFACT_BYTES)
                throw new IOException("Signed design archive entry exceeds its byte budget");
            return encoded;
        }
    }

    private Path path(String artifactHash) {
        return directory.resolve(artifactHash + EXTENSION);
    }

    private void requireDirectory() throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Signed design archive path is not a directory");
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    record ImportedDesign(String artifactHash, DesignDocument document, String keyFingerprint) { }
}
