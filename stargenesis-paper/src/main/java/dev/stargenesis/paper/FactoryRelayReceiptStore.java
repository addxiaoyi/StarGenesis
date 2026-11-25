package dev.stargenesis.paper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

/** Durable request identities and responses used to make proxy retries idempotent. */
final class FactoryRelayReceiptStore {
    static final int MAX_RECEIPTS = 4096;
    private static final int MAGIC = 0x53474649;
    private static final int VERSION = 1;
    private static final int DIGEST_BYTES = 32;
    private static final int MAX_ENCODED_BYTES = 18 * 1024 * 1024;
    private final Path checkpointPath;
    private final FactoryCheckpointStore.AtomicFileReplacer fileReplacer;
    private Map<UUID, Receipt> receipts;

    FactoryRelayReceiptStore(Path dataDirectory) throws IOException {
        this(dataDirectory, (temporary, target) -> Files.move(temporary, target,
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING));
    }

    FactoryRelayReceiptStore(Path dataDirectory, FactoryCheckpointStore.AtomicFileReplacer fileReplacer)
            throws IOException {
        Path directory = dataDirectory.resolve("factory-relay").toAbsolutePath().normalize();
        checkpointPath = directory.resolve("inbox-v1.checkpoint");
        this.fileReplacer = java.util.Objects.requireNonNull(fileReplacer, "fileReplacer");
        receipts = load();
    }

    synchronized Receipt begin(UUID requestId, String digest) throws IOException {
        validateDigest(digest);
        Receipt existing = receipts.get(requestId);
        if (existing != null) {
            if (!existing.digest().equals(digest))
                throw new IllegalArgumentException("Factory relay request ID was reused with different content");
            return existing;
        }
        if (receipts.size() >= MAX_RECEIPTS) throw new IllegalStateException("Factory relay receipt budget is full");
        TreeMap<UUID, Receipt> updated = new TreeMap<>(receipts);
        Receipt pending = new Receipt(requestId, digest, State.PENDING, "");
        updated.put(requestId, pending);
        save(updated);
        receipts = Map.copyOf(updated);
        return pending;
    }

    synchronized Receipt complete(UUID requestId, String digest, String response) throws IOException {
        validateDigest(digest);
        Receipt current = receipts.get(requestId);
        if (current == null || !current.digest().equals(digest))
            throw new IllegalArgumentException("Factory relay receipt does not match a pending request");
        if (current.state() == State.DONE) {
            if (!current.response().equals(response))
                throw new IllegalArgumentException("Factory relay receipt response is already committed");
            return current;
        }
        Receipt completed = new Receipt(requestId, digest, State.DONE, response);
        TreeMap<UUID, Receipt> updated = new TreeMap<>(receipts);
        updated.put(requestId, completed);
        save(updated);
        receipts = Map.copyOf(updated);
        return completed;
    }

    synchronized Optional<Receipt> find(UUID requestId) {
        return Optional.ofNullable(receipts.get(requestId));
    }

    synchronized int size() {
        return receipts.size();
    }

    private Map<UUID, Receipt> load() throws IOException {
        if (!Files.exists(checkpointPath, LinkOption.NOFOLLOW_LINKS)) return Map.of();
        if (!Files.isRegularFile(checkpointPath, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Factory relay receipt path is not a regular file");
        long size = Files.size(checkpointPath);
        if (size < 44 || size > MAX_ENCODED_BYTES)
            throw new IOException("Factory relay receipt checkpoint size is outside the finite bounds");
        try {
            return decode(Files.readAllBytes(checkpointPath));
        } catch (IllegalArgumentException failure) {
            throw new IOException("Factory relay receipt checkpoint failed validation", failure);
        }
    }

    private void save(Map<UUID, Receipt> updated) throws IOException {
        byte[] encoded;
        try {
            encoded = encode(updated);
        } catch (IllegalArgumentException failure) {
            throw new IOException("Factory relay receipt checkpoint failed validation before save", failure);
        }
        Files.createDirectories(checkpointPath.getParent());
        Path temporary = Files.createTempFile(checkpointPath.getParent(), "factory-relay-inbox-v1-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(encoded);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                fileReplacer.replace(temporary, checkpointPath);
            } catch (AtomicMoveNotSupportedException failure) {
                throw new IOException("Factory relay receipt filesystem does not support atomic replacement", failure);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static byte[] encode(Map<UUID, Receipt> receipts) {
        if (receipts.size() > MAX_RECEIPTS) throw new IllegalArgumentException("Factory relay receipt count is too large");
        try {
            ByteArrayOutputStream payloadBytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(payloadBytes)) {
                output.writeInt(MAGIC);
                output.writeInt(VERSION);
                output.writeInt(receipts.size());
                for (Receipt receipt : new TreeMap<>(receipts).values()) {
                    output.writeLong(receipt.requestId().getMostSignificantBits());
                    output.writeLong(receipt.requestId().getLeastSignificantBits());
                    output.write(HexFormat.of().parseHex(receipt.digest()));
                    output.writeByte(receipt.state().ordinal());
                    writeText(output, receipt.response(), FactoryRelayPacket.MAX_RESPONSE_BYTES);
                }
            }
            byte[] payload = payloadBytes.toByteArray();
            ByteArrayOutputStream encodedBytes = new ByteArrayOutputStream(payload.length + DIGEST_BYTES);
            encodedBytes.write(payload);
            encodedBytes.write(digest(payload));
            byte[] encoded = encodedBytes.toByteArray();
            if (encoded.length > MAX_ENCODED_BYTES)
                throw new IllegalArgumentException("Factory relay receipt checkpoint is too large");
            return encoded;
        } catch (IOException impossible) {
            throw new IllegalStateException("Cannot encode factory relay receipt checkpoint", impossible);
        }
    }

    private static Map<UUID, Receipt> decode(byte[] encoded) {
        if (encoded == null || encoded.length < 44 || encoded.length > MAX_ENCODED_BYTES)
            throw new IllegalArgumentException("Invalid factory relay receipt checkpoint size");
        int payloadLength = encoded.length - DIGEST_BYTES;
        byte[] payload = Arrays.copyOf(encoded, payloadLength);
        byte[] storedDigest = Arrays.copyOfRange(encoded, payloadLength, encoded.length);
        if (!MessageDigest.isEqual(storedDigest, digest(payload)))
            throw new IllegalArgumentException("Factory relay receipt checksum mismatch");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION)
                throw new IllegalArgumentException("Unsupported factory relay receipt format");
            int count = input.readInt();
            if (count < 0 || count > MAX_RECEIPTS) throw new IllegalArgumentException("Invalid factory relay receipt count");
            TreeMap<UUID, Receipt> records = new TreeMap<>();
            for (int index = 0; index < count; index++) {
                UUID requestId = new UUID(input.readLong(), input.readLong());
                byte[] digestBytes = input.readNBytes(DIGEST_BYTES);
                if (digestBytes.length != DIGEST_BYTES) throw new IllegalArgumentException("Truncated factory relay digest");
                String requestDigest = HexFormat.of().formatHex(digestBytes);
                int stateValue = input.readUnsignedByte();
                if (stateValue >= State.values().length) throw new IllegalArgumentException("Invalid factory relay receipt state");
                State state = State.values()[stateValue];
                String response = readText(input, FactoryRelayPacket.MAX_RESPONSE_BYTES);
                Receipt receipt = new Receipt(requestId, requestDigest, state, response);
                if (records.putIfAbsent(requestId, receipt) != null)
                    throw new IllegalArgumentException("Duplicate factory relay receipt ID");
            }
            if (input.available() != 0) throw new IllegalArgumentException("Trailing factory relay receipt data");
            return Map.copyOf(records);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Malformed factory relay receipt checkpoint", failure);
        }
    }

    private static void writeText(DataOutputStream output, String value, int maximumBytes) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maximumBytes) throw new IllegalArgumentException("Factory relay receipt text is too large");
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readText(DataInputStream input, int maximumBytes) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > maximumBytes || length > input.available())
            throw new IllegalArgumentException("Invalid factory relay receipt text length");
        try {
            return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(input.readNBytes(length))).toString();
        } catch (CharacterCodingException failure) {
            throw new IllegalArgumentException("Malformed factory relay receipt UTF-8", failure);
        }
    }

    private static byte[] digest(byte[] payload) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(payload);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void validateDigest(String digest) {
        if (digest == null || !digest.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid factory relay request digest");
    }

    record Receipt(UUID requestId, String digest, State state, String response) {
        Receipt {
            if (requestId == null) throw new IllegalArgumentException("Missing factory relay request identity");
            validateDigest(digest);
            if (state == null || response == null) throw new IllegalArgumentException("Invalid factory relay receipt");
            if (state == State.PENDING && !response.isEmpty())
                throw new IllegalArgumentException("Pending factory relay receipt cannot have a response");
            if (state == State.DONE) {
                if (response.isBlank()) throw new IllegalArgumentException("Completed factory relay response is empty");
                if (response.getBytes(StandardCharsets.UTF_8).length > FactoryRelayPacket.MAX_RESPONSE_BYTES)
                    throw new IllegalArgumentException("Completed factory relay response is too large");
            }
        }
    }

    enum State { PENDING, DONE }
}
