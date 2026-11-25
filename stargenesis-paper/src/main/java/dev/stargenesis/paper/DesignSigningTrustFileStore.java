package dev.stargenesis.paper;

import dev.stargenesis.design.DesignExchange;
import dev.stargenesis.design.DesignSigningTrust;

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

/** Server-root-owned publisher-key pins. The embedded key of a design import is never a trust anchor. */
final class DesignSigningTrustFileStore implements DesignSigningTrust {
    static final int MAX_TRUSTED_AUTHORS = 256;
    private static final int MAGIC = 0x53475431;
    private static final int FORMAT = 1;
    private static final int MAX_KEY_BYTES = 128;
    private static final int CHECKSUM_BYTES = 32;
    private final Path directory;

    DesignSigningTrustFileStore(Path dataDirectory) {
        directory = dataDirectory.resolve("design-trust").toAbsolutePath().normalize();
    }

    synchronized int count() throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return 0;
        requireDirectory();
        return listFiles().size();
    }

    synchronized List<TrustedKey> loadAll() throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
        requireDirectory();
        List<TrustedKey> result = new ArrayList<>();
        for (Path file : listFiles()) {
            UUID author = parseFileName(file);
            byte[] encodedKey = read(file, author);
            try {
                DesignExchange.parsePublicKey(encodedKey);
            } catch (IllegalArgumentException failure) {
                throw new IOException("Trusted publisher key is invalid", failure);
            }
            result.add(new TrustedKey(author, DesignExchange.fingerprint(encodedKey)));
        }
        return List.copyOf(result);
    }

    @Override
    public synchronized Optional<byte[]> publicKey(UUID author) {
        try {
            if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
            requireDirectory();
            Path file = path(author);
            if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
            return Optional.of(read(file, author));
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read trusted publisher key", failure);
        }
    }

    synchronized void trust(UUID author, byte[] publicKey) throws IOException {
        if (author == null || publicKey == null || publicKey.length > MAX_KEY_BYTES)
            throw new IllegalArgumentException("Publisher identity or public key is invalid");
        try {
            DesignExchange.parsePublicKey(publicKey);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Publisher key must be a valid Ed25519 X.509 public key", failure);
        }
        Files.createDirectories(directory);
        requireDirectory();
        Path target = path(author);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS) && listFiles().size() >= MAX_TRUSTED_AUTHORS)
            throw new IOException("Trusted publisher list is full");
        byte[] encoded = encode(author, publicKey);
        Path temporary = Files.createTempFile(directory, "publisher-", ".tmp");
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
                throw new IOException("Publisher trust directory does not support atomic replacement", failure);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    synchronized boolean revoke(UUID author) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return false;
        requireDirectory();
        Path target = path(author);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return false;
        if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Trusted publisher path is not a regular file");
        return Files.deleteIfExists(target);
    }

    private List<Path> listFiles() throws IOException {
        try (Stream<Path> entries = Files.list(directory)) {
            List<Path> files = entries.filter(entry -> entry.getFileName().toString().endsWith(".key"))
                    .limit(MAX_TRUSTED_AUTHORS + 1L).sorted().toList();
            if (files.size() > MAX_TRUSTED_AUTHORS)
                throw new IOException("Trusted publisher list exceeds its finite record limit");
            return files;
        }
    }

    private void requireDirectory() throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Publisher trust path is not a directory");
    }

    private Path path(UUID author) {
        return directory.resolve(author + ".key");
    }

    private static UUID parseFileName(Path path) throws IOException {
        String name = path.getFileName().toString();
        if (!name.endsWith(".key")) throw new IOException("Unknown publisher trust record format");
        try {
            UUID id = UUID.fromString(name.substring(0, name.length() - 4));
            if (!id.toString().equals(name.substring(0, name.length() - 4))) throw new IllegalArgumentException();
            return id;
        } catch (IllegalArgumentException failure) {
            throw new IOException("Trusted publisher record has an invalid identity", failure);
        }
    }

    private static byte[] encode(UUID author, byte[] publicKey) throws IOException {
        ByteArrayOutputStream payloadBytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(payloadBytes)) {
            output.writeInt(MAGIC);
            output.writeInt(FORMAT);
            output.writeLong(author.getMostSignificantBits());
            output.writeLong(author.getLeastSignificantBits());
            output.writeInt(publicKey.length);
            output.write(publicKey);
        }
        byte[] payload = payloadBytes.toByteArray();
        byte[] checksum = sha256(payload);
        ByteArrayOutputStream encoded = new ByteArrayOutputStream(payload.length + checksum.length);
        encoded.write(payload);
        encoded.write(checksum);
        return encoded.toByteArray();
    }

    private static byte[] read(Path path, UUID expectedAuthor) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Trusted publisher key is not a regular file");
        try (var stream = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] encoded = stream.readNBytes(8 + 16 + 4 + MAX_KEY_BYTES + CHECKSUM_BYTES + 1);
            if (encoded.length < 8 + 16 + 4 + 32 + CHECKSUM_BYTES
                    || encoded.length > 8 + 16 + 4 + MAX_KEY_BYTES + CHECKSUM_BYTES)
                throw new IOException("Trusted publisher key size is outside its bounds");
            int payloadLength = encoded.length - CHECKSUM_BYTES;
            byte[] payload = java.util.Arrays.copyOf(encoded, payloadLength);
            byte[] checksum = java.util.Arrays.copyOfRange(encoded, payloadLength, encoded.length);
            if (!MessageDigest.isEqual(checksum, sha256(payload)))
                throw new IOException("Trusted publisher record checksum mismatch");
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
                if (input.readInt() != MAGIC || input.readInt() != FORMAT)
                    throw new IOException("Unknown trusted publisher record format");
                UUID author = new UUID(input.readLong(), input.readLong());
                int length = input.readInt();
                if (!expectedAuthor.equals(author) || length < 32 || length > MAX_KEY_BYTES || length != input.available())
                    throw new IOException("Trusted publisher record identity or key size is invalid");
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

    record TrustedKey(UUID author, String fingerprint) { }
}
