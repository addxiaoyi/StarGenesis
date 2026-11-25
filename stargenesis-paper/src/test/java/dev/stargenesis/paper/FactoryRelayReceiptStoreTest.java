package dev.stargenesis.paper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FactoryRelayReceiptStoreTest {
    @TempDir Path temporaryDirectory;

    @Test
    void pendingAndCompletedReceiptsSurviveRestartsAndReturnTheOriginalResponse() throws IOException {
        UUID requestId = UUID.randomUUID();
        String digest = "a".repeat(64);
        FactoryRelayReceiptStore first = new FactoryRelayReceiptStore(temporaryDirectory);
        assertEquals(FactoryRelayReceiptStore.State.PENDING, first.begin(requestId, digest).state());

        FactoryRelayReceiptStore restarted = new FactoryRelayReceiptStore(temporaryDirectory);
        assertEquals(FactoryRelayReceiptStore.State.PENDING,
                restarted.find(requestId).orElseThrow().state());
        var completed = restarted.complete(requestId, digest, "已保存订单状态。");
        assertEquals(FactoryRelayReceiptStore.State.DONE, completed.state());

        FactoryRelayReceiptStore afterCompletion = new FactoryRelayReceiptStore(temporaryDirectory);
        assertEquals(completed, afterCompletion.begin(requestId, digest));
        assertEquals(completed, afterCompletion.complete(requestId, digest, "已保存订单状态。"));
        assertThrows(IllegalArgumentException.class,
                () -> afterCompletion.begin(requestId, "b".repeat(64)));
    }

    @Test
    void receiptCheckpointRejectsTampering() throws IOException {
        FactoryRelayReceiptStore store = new FactoryRelayReceiptStore(temporaryDirectory);
        store.begin(UUID.randomUUID(), "c".repeat(64));
        Path checkpoint = temporaryDirectory.resolve("factory-relay").resolve("inbox-v1.checkpoint");
        byte[] encoded = Files.readAllBytes(checkpoint);
        encoded[10] ^= 1;
        Files.write(checkpoint, encoded);
        assertThrows(IOException.class, () -> new FactoryRelayReceiptStore(temporaryDirectory));
    }
}
