package dev.stargenesis.paper.design;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

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
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Administrator-managed Ed25519 publisher trust roots in X.509 PEM format. */
public final class PublisherKeyStore {
    public static final int MAX_KEYS = 32;
    public static final int MAX_REVOKED_KEYS = 4096;
    private static final int MAX_KEY_FILE_BYTES = 4096;
    private static final String KEY_ID_PATTERN = "[a-z0-9][a-z0-9_.:-]{0,63}";
    private static final String FILE_ALIAS_PATTERN = "[a-z0-9][a-z0-9_.-]{0,63}";
    private static final String REVOCATION_SUFFIX = ".revoked.json";
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build())
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                    DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                    DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                    DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);

    private final Path directory;
    private final Path inbox;

    public PublisherKeyStore(Path directory) throws IOException {
        this.directory = directory.toAbsolutePath().normalize();
        Path parent = this.directory.getParent();
        if (parent == null) throw new IOException("Publisher trust directory must have a parent");
        inbox = parent.resolve("publisher-inbox");
        Files.createDirectories(this.directory);
        Files.createDirectories(inbox);
        checkDirectories();
    }

    public synchronized Map<String, PublicKey> loadAll() throws IOException {
        checkDirectories();
        Map<String, RevocationRecord> revoked = loadRevocations();
        List<Path> files = listFiles("*.pub", MAX_KEYS + MAX_REVOKED_KEYS + 1);
        Map<String, PublicKey> keys = new TreeMap<>();
        java.util.Set<String> seenIds = new java.util.HashSet<>();
        for (Path file : files) {
            String filename = file.getFileName().toString();
            String keyId = keyIdFromFilename(filename.substring(0, filename.length() - ".pub".length()));
            if (!seenIds.add(keyId)) throw new IOException("Duplicate publisher key ID " + keyId);
            RevocationRecord revocation = revoked.get(keyId);
            if (revocation != null) continue;
            PublicKey key = readPublicKey(file, keyId);
            if (keys.putIfAbsent(keyId, key) != null) throw new IOException("Duplicate publisher key ID " + keyId);
            if (keys.size() > MAX_KEYS) throw new IOException("Publisher key count exceeds its 32-key budget");
        }
        return Map.copyOf(keys);
    }

    public synchronized PublisherEntry addFromInbox(String keyId, String fileAlias) throws IOException {
        requireKeyId(keyId);
        requireFileAlias(fileAlias);
        checkDirectories();
        Map<String, RevocationRecord> revoked = loadRevocations();
        if (revoked.containsKey(keyId)) throw new IOException("Revoked publisher key IDs cannot be reused");

        Path staged = inbox.resolve(fileAlias + ".pub");
        PublicKey candidate = readPublicKey(staged, keyId);
        Map<String, PublicKey> active = loadAll();
        PublicKey existing = active.get(keyId);
        if (existing != null) {
            if (!MessageDigest.isEqual(existing.getEncoded(), candidate.getEncoded()))
                throw new IOException("Publisher key ID is already trusted with different key material");
            return new PublisherEntry(keyId, true, fingerprint(existing), "", "");
        }
        if (active.size() >= MAX_KEYS) throw new IOException("Publisher key count exceeds its 32-key budget");

        Path target = directory.resolve(keyFileStem(keyId) + ".pub");
        writeNewAtomic(target, pem(candidate.getEncoded()).getBytes(StandardCharsets.US_ASCII));
        return new PublisherEntry(keyId, true, fingerprint(candidate), "", "");
    }

    public synchronized PublisherEntry revoke(String keyId, String reason) throws IOException {
        requireKeyId(keyId);
        String checkedReason = validateReason(reason);
        checkDirectories();
        Map<String, RevocationRecord> revoked = loadRevocations();
        RevocationRecord existingRevocation = revoked.get(keyId);
        if (existingRevocation != null) {
            removeKeyFiles(keyId);
            return entry(existingRevocation);
        }

        PublicKey key = loadAll().get(keyId);
        if (key == null) throw new IOException("Publisher key is not currently trusted");
        RevocationRecord record = new RevocationRecord(1, keyId, fingerprint(key), Instant.now().toString(), checkedReason);
        Path target = directory.resolve(keyFileStem(keyId) + REVOCATION_SUFFIX);
        writeNewAtomic(target, JSON.writeValueAsBytes(record));
        removeKeyFiles(keyId);
        return entry(record);
    }

    public synchronized List<PublisherEntry> listEntries() throws IOException {
        Map<String, PublicKey> active = loadAll();
        Map<String, RevocationRecord> revoked = loadRevocations();
        List<PublisherEntry> entries = new ArrayList<>();
        active.forEach((keyId, key) -> entries.add(new PublisherEntry(keyId, true, fingerprint(key), "", "")));
        revoked.values().forEach(record -> entries.add(entry(record)));
        return entries.stream().sorted(java.util.Comparator.comparing(PublisherEntry::keyId)).toList();
    }

    private Map<String, RevocationRecord> loadRevocations() throws IOException {
        List<Path> files = listFiles("*" + REVOCATION_SUFFIX, MAX_REVOKED_KEYS + 1);
        if (files.size() > MAX_REVOKED_KEYS) throw new IOException("Publisher revocation history exceeds its 4096-entry budget");
        Map<String, RevocationRecord> records = new TreeMap<>();
        for (Path file : files) {
            String filename = file.getFileName().toString();
            String stem = filename.substring(0, filename.length() - REVOCATION_SUFFIX.length());
            String fileKeyId = keyIdFromFilename(stem);
            rejectSymbolicOrLargeFile(file, MAX_KEY_FILE_BYTES);
            RevocationRecord record = JSON.readValue(Files.readAllBytes(file), RevocationRecord.class);
            validateRevocation(record, fileKeyId);
            if (records.putIfAbsent(record.keyId(), record) != null)
                throw new IOException("Duplicate publisher revocation record " + record.keyId());
        }
        return Map.copyOf(records);
    }

    private List<Path> listFiles(String glob, int maximum) throws IOException {
        try (var stream = Files.newDirectoryStream(directory, glob)) {
            List<Path> files = new ArrayList<>();
            for (Path file : stream) {
                files.add(file);
                if (files.size() >= maximum) break;
            }
            return files.stream().sorted().toList();
        }
    }

    private PublicKey readPublicKey(Path file, String keyId) throws IOException {
        rejectSymbolicOrLargeFile(file, MAX_KEY_FILE_BYTES);
        String pem = Files.readString(file, StandardCharsets.US_ASCII);
        byte[] encoded = decodePem(pem);
        try {
            PublicKey key = KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded));
            if (!key.getAlgorithm().equalsIgnoreCase("Ed25519") && !key.getAlgorithm().equalsIgnoreCase("EdDSA"))
                throw new IOException("Publisher key is not Ed25519 " + keyId);
            return key;
        } catch (java.security.GeneralSecurityException invalid) {
            throw new IOException("Invalid Ed25519 public key " + keyId, invalid);
        }
    }

    private byte[] decodePem(String pem) throws IOException {
        String first = "-----BEGIN PUBLIC KEY-----";
        String last = "-----END PUBLIC KEY-----";
        String normalized = pem.replace("\r\n", "\n");
        if (!normalized.startsWith(first + "\n") || !normalized.endsWith("\n" + last + "\n"))
            throw new IOException("Publisher key must be a single X.509 PUBLIC KEY PEM block");
        String body = normalized.substring(first.length() + 1, normalized.length() - last.length() - 1)
                .replace("\n", "");
        try {
            byte[] result = Base64.getDecoder().decode(body);
            if (result.length < 32 || result.length > 128) throw new IOException("Publisher key DER size outside its bound");
            return result;
        } catch (IllegalArgumentException malformed) {
            throw new IOException("Malformed publisher key PEM", malformed);
        }
    }

    private void removeKeyFiles(String keyId) throws IOException {
        for (Path file : listFiles("*.pub", MAX_KEYS + MAX_REVOKED_KEYS + 1)) {
            String filename = file.getFileName().toString();
            if (keyId.equals(keyIdFromFilename(filename.substring(0, filename.length() - ".pub".length()))))
                Files.deleteIfExists(file);
        }
    }

    private void validateRevocation(RevocationRecord record, String fileKeyId) throws IOException {
        if (record == null || record.schemaVersion() != 1 || !fileKeyId.equals(record.keyId())
                || !record.keyId().matches(KEY_ID_PATTERN) || record.fingerprint() == null
                || !record.fingerprint().matches("[0-9a-f]{64}") || record.revokedAt() == null)
            throw new IOException("Invalid publisher revocation record " + fileKeyId);
        try { Instant.parse(record.revokedAt()); }
        catch (DateTimeParseException invalid) { throw new IOException("Invalid publisher revocation timestamp " + fileKeyId, invalid); }
        try { validateReason(record.reason()); }
        catch (IllegalArgumentException invalid) { throw new IOException("Invalid publisher revocation reason " + fileKeyId, invalid); }
    }

    private String keyIdFromFilename(String stem) throws IOException {
        String keyId;
        if (stem.contains("%")) {
            if (!stem.matches("(?:[a-z0-9_.-]|%3A)+")) throw new IOException("Invalid encoded publisher key filename " + stem);
            keyId = stem.replace("%3A", ":");
            if (!keyFileStem(keyId).equals(stem)) throw new IOException("Non-canonical publisher key filename " + stem);
        } else {
            keyId = stem;
        }
        if (!keyId.matches(KEY_ID_PATTERN)) throw new IOException("Invalid publisher key ID " + keyId);
        return keyId;
    }

    private String keyFileStem(String keyId) {
        return keyId.replace(":", "%3A");
    }

    private void requireKeyId(String keyId) {
        if (keyId == null || !keyId.matches(KEY_ID_PATTERN)) throw new IllegalArgumentException("Invalid publisher key ID");
    }

    private void requireFileAlias(String fileAlias) {
        if (fileAlias == null || !fileAlias.matches(FILE_ALIAS_PATTERN))
            throw new IllegalArgumentException("Public key file alias must be a bounded lowercase ID");
    }

    private String validateReason(String reason) {
        if (reason == null) throw new IllegalArgumentException("A revocation reason is required");
        String normalized = reason.strip();
        if (normalized.isEmpty() || normalized.length() > 256
                || normalized.codePoints().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Revocation reason must contain 1..256 printable characters");
        return normalized;
    }

    private String fingerprint(PublicKey key) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getEncoded())); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }

    private String pem(byte[] encoded) {
        String body = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded);
        return "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----\n";
    }

    private void writeNewAtomic(Path target, byte[] bytes) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        rejectSymbolicOrMissingParent(target);
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Publisher trust entry already exists");
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException failure) {
            Files.deleteIfExists(temporary);
            throw new IOException("Atomic publisher trust update unavailable", failure);
        } catch (IOException failure) {
            Files.deleteIfExists(temporary);
            throw failure;
        }
    }

    private void rejectSymbolicOrMissingParent(Path file) throws IOException {
        if (Files.isSymbolicLink(file.getParent()) || Files.isSymbolicLink(file))
            throw new IOException("Publisher trust symlink rejected");
    }

    private void rejectSymbolicOrLargeFile(Path file, long maximumBytes) throws IOException {
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || Files.size(file) > maximumBytes)
            throw new IOException("Publisher key or revocation file is missing, symbolic, or exceeds its size limit");
    }

    private void checkDirectories() throws IOException {
        if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)
                || Files.isSymbolicLink(inbox) || !Files.isDirectory(inbox, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Publisher trust directories must be regular directories");
    }

    private PublisherEntry entry(RevocationRecord record) {
        return new PublisherEntry(record.keyId(), false, record.fingerprint(), record.revokedAt(), record.reason());
    }

    private record RevocationRecord(int schemaVersion, String keyId, String fingerprint, String revokedAt, String reason) { }

    public record PublisherEntry(String keyId, boolean trusted, String fingerprint, String revokedAt, String reason) { }
}
