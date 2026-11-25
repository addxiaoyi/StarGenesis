package dev.stargenesis.paper;

import dev.stargenesis.design.DesignExchange;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
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
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/** Server-root-owned Ed25519 key pins for arena reviewers. */
final class ArenaReviewTrustFileStore {
    static final int MAX_REVIEWERS = 256;
    private static final int MAGIC = 0x53475254;
    private static final int FORMAT = 1;
    private static final int MAX_KEY_BYTES = 128;
    private static final int CHECKSUM_BYTES = 32;
    private final Path directory;

    ArenaReviewTrustFileStore(Path dataDirectory) {
        directory = dataDirectory.resolve("arena-review-trust").toAbsolutePath().normalize();
    }

    synchronized int count() throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return 0;
        requireDirectory();
        return listFiles().size();
    }

    synchronized List<TrustedReviewer> loadAll() throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
        requireDirectory();
        List<TrustedReviewer> result = new ArrayList<>();
        for (Path file : listFiles()) {
            UUID reviewer = parseFileName(file);
            byte[] key = read(file, reviewer);
            try {
                DesignExchange.parsePublicKey(key);
            } catch (IllegalArgumentException failure) {
                throw new IOException("Trusted arena reviewer key is invalid", failure);
            }
            result.add(new TrustedReviewer(reviewer, DesignExchange.fingerprint(key)));
        }
        return List.copyOf(result);
    }

    synchronized Optional<byte[]> publicKey(UUID reviewer) {
        try {
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
            requireDirectory();
            Path file = path(reviewer);
            return Files.exists(file, LinkOption.NOFOLLOW_LINKS)
                    ? Optional.of(read(file, reviewer)) : Optional.empty();
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read trusted arena reviewer key", failure);
        }
    }

    synchronized void trust(UUID reviewer, byte[] publicKey) throws IOException {
        if (reviewer == null || publicKey == null || publicKey.length < 32 || publicKey.length > MAX_KEY_BYTES)
            throw new IllegalArgumentException("Reviewer identity or public key is invalid");
        try {
            DesignExchange.parsePublicKey(publicKey);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Reviewer key must be a valid Ed25519 X.509 public key", failure);
        }
        Files.createDirectories(directory);
        requireDirectory();
        Path target = path(reviewer);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS) && listFiles().size() >= MAX_REVIEWERS)
            throw new IOException("Trusted arena reviewer list is full");
        byte[] encoded = encode(reviewer, publicKey);
        Path temporary = Files.createTempFile(directory, "reviewer-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(encoded);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException failure) {
                throw new IOException("Arena reviewer trust directory does not support atomic replacement", failure);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    synchronized boolean revoke(UUID reviewer) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return false;
        requireDirectory();
        Path target = path(reviewer);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return false;
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Trusted arena reviewer path is not a regular file");
        return Files.deleteIfExists(target);
    }

    private List<Path> listFiles() throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            List<Path> files = entries.filter(entry -> entry.getFileName().toString().endsWith(".key"))
                    .limit(MAX_REVIEWERS + 1L).sorted().toList();
            if (files.size() > MAX_REVIEWERS)
                throw new IOException("Trusted arena reviewer list exceeds its finite record limit");
            return files;
        }
    }

    private void requireDirectory() throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Arena reviewer trust path is not a directory");
    }

    private Path path(UUID reviewer) { return directory.resolve(reviewer + ".key"); }

    private static UUID parseFileName(Path path) throws IOException {
        String name = path.getFileName().toString();
        try {
            UUID id = UUID.fromString(name.substring(0, name.length() - 4));
            if (!id.toString().equals(name.substring(0, name.length() - 4))) throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException failure) {
            throw new IOException("Trusted arena reviewer record has an invalid identity", failure);
        }
    }

    private static byte[] encode(UUID reviewer, byte[] publicKey) throws IOException {
        ByteArrayOutputStream payloadBytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(payloadBytes)) {
            output.writeInt(MAGIC);
            output.writeInt(FORMAT);
            output.writeLong(reviewer.getMostSignificantBits());
            output.writeLong(reviewer.getLeastSignificantBits());
            output.writeInt(publicKey.length);
            output.write(publicKey);
        }
        byte[] payload = payloadBytes.toByteArray();
        ByteArrayOutputStream encoded = new ByteArrayOutputStream(payload.length + CHECKSUM_BYTES);
        encoded.write(payload);
        encoded.write(sha256(payload));
        return encoded.toByteArray();
    }

    private static byte[] read(Path path, UUID expectedReviewer) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Trusted arena reviewer key is not a regular file");
        try (var stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] encoded = stream.readNBytes(8 + 16 + 4 + MAX_KEY_BYTES + CHECKSUM_BYTES + 1);
            if (encoded.length < 8 + 16 + 4 + 32 + CHECKSUM_BYTES
                    || encoded.length > 8 + 16 + 4 + MAX_KEY_BYTES + CHECKSUM_BYTES)
                throw new IOException("Trusted arena reviewer key size is outside its bounds");
            int payloadLength = encoded.length - CHECKSUM_BYTES;
            byte[] payload = java.util.Arrays.copyOf(encoded, payloadLength);
            byte[] checksum = java.util.Arrays.copyOfRange(encoded, payloadLength, encoded.length);
            if (!MessageDigest.isEqual(checksum, sha256(payload)))
                throw new IOException("Trusted arena reviewer record checksum mismatch");
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
                if (input.readInt() != MAGIC || input.readInt() != FORMAT)
                    throw new IOException("Unknown trusted arena reviewer record format");
                UUID reviewer = new UUID(input.readLong(), input.readLong());
                int length = input.readInt();
                if (!expectedReviewer.equals(reviewer) || length < 32 || length > MAX_KEY_BYTES
                        || length != input.available())
                    throw new IOException("Trusted arena reviewer record identity or key size is invalid");
                return input.readNBytes(length);
            }
        }
    }

    private static byte[] sha256(byte[] payload) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(payload);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    record TrustedReviewer(UUID reviewer, String fingerprint) { }
}
