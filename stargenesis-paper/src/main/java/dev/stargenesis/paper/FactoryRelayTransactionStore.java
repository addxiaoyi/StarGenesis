package dev.stargenesis.paper;

import dev.stargenesis.sim.FactoryCheckpoint;
import dev.stargenesis.sim.FactoryCheckpointCodec;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Optional;
import java.util.UUID;

/** Write-ahead record joining a factory checkpoint and its remote request receipt. */
final class FactoryRelayTransactionStore {
    private static final int MAGIC = 0x53474654;
    private static final int VERSION = 1;
    private static final int DIGEST_BYTES = 32;
    private static final int MAX_BYTES = FactoryCheckpointCodec.MAX_ENCODED_BYTES + 8192;
    private final Path path;

    FactoryRelayTransactionStore(Path dataDirectory) {
        path = dataDirectory.resolve("factory-relay").resolve("transaction-v1.checkpoint");
    }

    void prepare(FactoryCheckpoint before, FactoryCheckpoint after,
                 UUID requestId, String requestDigest, String response) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("A factory relay transaction still requires recovery");
        FactoryRelayReceiptStore.Receipt receipt = new FactoryRelayReceiptStore.Receipt(
                requestId, requestDigest, FactoryRelayReceiptStore.State.DONE, response);
        byte[] afterBytes = FactoryCheckpointCodec.encode(after);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            output.writeLong(receipt.requestId().getMostSignificantBits());
            output.writeLong(receipt.requestId().getLeastSignificantBits());
            output.writeUTF(receipt.digest());
            output.writeUTF(receipt.response());
            output.write(digest(FactoryCheckpointCodec.encode(before)));
            output.writeInt(afterBytes.length);
            output.write(afterBytes);
        }
        byte[] payload = bytes.toByteArray();
        bytes.write(digest(payload));
        byte[] encoded = bytes.toByteArray();
        if (encoded.length > MAX_BYTES) throw new IOException("Factory relay transaction exceeds its size limit");
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), "factory-transaction-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING)) {
                ByteBuffer buffer = ByteBuffer.wrap(encoded);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    Optional<FactoryCheckpoint> recover(FactoryCheckpointStore factory,
                                        FactoryRelayReceiptStore receipts) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
                || Files.size(path) < 64 || Files.size(path) > MAX_BYTES)
            throw new IOException("Invalid factory relay transaction file");
        Transaction transaction = decode(Files.readAllBytes(path));
        FactoryCheckpoint current = factory.load().orElseThrow(
                () -> new IOException("Factory relay transaction has no factory checkpoint"));
        byte[] currentBytes = FactoryCheckpointCodec.encode(current);
        byte[] afterBytes = FactoryCheckpointCodec.encode(transaction.after());
        boolean alreadyCommitted = Arrays.equals(currentBytes, afterBytes);
        if (!alreadyCommitted && !MessageDigest.isEqual(digest(currentBytes), transaction.beforeDigest()))
            throw new IOException("Factory state conflicts with its pending relay transaction");
        try {
            FactoryRelayReceiptStore.Receipt receipt = transaction.receipt();
            FactoryRelayReceiptStore.Receipt existing = receipts.begin(receipt.requestId(), receipt.digest());
            if (existing.state() == FactoryRelayReceiptStore.State.DONE && !existing.equals(receipt))
                throw new IOException("Factory relay transaction conflicts with its completed receipt");
            if (!alreadyCommitted) factory.save(transaction.after());
            receipts.complete(receipt.requestId(), receipt.digest(), receipt.response());
            Files.delete(path);
            return Optional.of(transaction.after());
        } catch (IllegalArgumentException | IllegalStateException failure) {
            throw new IOException("Cannot reconcile factory relay transaction", failure);
        }
    }

    private static Transaction decode(byte[] encoded) throws IOException {
        int payloadLength = encoded.length - DIGEST_BYTES;
        byte[] payload = Arrays.copyOf(encoded, payloadLength);
        byte[] checksum = Arrays.copyOfRange(encoded, payloadLength, encoded.length);
        if (!MessageDigest.isEqual(digest(payload), checksum))
            throw new IOException("Factory relay transaction checksum mismatch");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION)
                throw new IOException("Unsupported factory relay transaction format");
            UUID requestId = new UUID(input.readLong(), input.readLong());
            FactoryRelayReceiptStore.Receipt receipt = new FactoryRelayReceiptStore.Receipt(
                    requestId, input.readUTF(), FactoryRelayReceiptStore.State.DONE, input.readUTF());
            byte[] beforeDigest = input.readNBytes(DIGEST_BYTES);
            int size = input.readInt();
            if (beforeDigest.length != DIGEST_BYTES || size < 8
                    || size > FactoryCheckpointCodec.MAX_ENCODED_BYTES || size != input.available())
                throw new IOException("Invalid factory relay transaction checkpoint length");
            FactoryCheckpoint after = FactoryCheckpointCodec.decode(input.readNBytes(size));
            return new Transaction(receipt, beforeDigest, after);
        } catch (IllegalArgumentException failure) {
            throw new IOException("Invalid factory relay transaction data", failure);
        }
    }

    private static byte[] digest(byte[] payload) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(payload);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private record Transaction(FactoryRelayReceiptStore.Receipt receipt, byte[] beforeDigest,
                               FactoryCheckpoint after) { }
}
