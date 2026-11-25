package dev.stargenesis.paper.design;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;

class PublisherTrustConcurrencyTest {
    @Test void concurrentKeyIdCannotBeReboundAcrossStoreInstances(@TempDir Path root) throws Exception {
        concurrentAdd(root, true);
    }

    @Test void concurrentAddsRespectTheActiveKeyBudget(@TempDir Path root) throws Exception {
        concurrentAdd(root, false);
    }

    private void concurrentAdd(Path root, boolean sameId) throws Exception {
        stage(root, "first");
        stage(root, "second");
        Path trust = root.resolve("trusted-publishers");
        var seed = new PublisherKeyStore(trust);
        if (!sameId) for (int index = 0; index < PublisherKeyStore.MAX_KEYS - 1; index++)
            seed.addFromInbox("seed-" + index, "first");
        var writing = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var first = new PublisherKeyStore(trust, (path, bytes) -> {
            writing.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("Writer fixture timed out");
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
            }
            PublisherKeyStore.writeNewAtomic(path, bytes);
        });
        var second = new PublisherKeyStore(trust);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var pending = executor.submit(() -> first.addFromInbox("first", "first"));
            assertTrue(writing.await(10, TimeUnit.SECONDS));
            var contender = executor.submit(() -> {
                try { second.addFromInbox(sameId ? "first" : "second", "second"); return false; }
                catch (IOException expected) { return true; }
            });
            try { assertThrows(TimeoutException.class, () -> contender.get(250, TimeUnit.MILLISECONDS)); }
            finally { release.countDown(); }
            assertTrue(pending.get(10, TimeUnit.SECONDS).trusted());
            assertTrue(contender.get(10, TimeUnit.SECONDS));
            assertEquals(sameId ? 1 : PublisherKeyStore.MAX_KEYS, seed.loadAll().size());
            assertEquals(pending.get().fingerprint(), seed.addFromInbox("first", "first").fingerprint());
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    @Test void failedOrUncertainAddCanRetryWithoutRebinding(@TempDir Path root) throws Exception {
        for (boolean committed : new boolean[]{false, true}) {
            Path scenario = root.resolve(Boolean.toString(committed));
            stage(scenario, "key");
            Path trust = scenario.resolve("trusted-publishers");
            var failing = new PublisherKeyStore(trust, (path, bytes) -> {
                try (var channel = java.nio.channels.FileChannel.open(trust.resolve(".publisher.lock"),
                        java.nio.file.StandardOpenOption.WRITE)) {
                    assertThrows(java.nio.channels.OverlappingFileLockException.class, channel::tryLock);
                }
                if (committed) PublisherKeyStore.writeNewAtomic(path, bytes);
                throw new IOException("fixture persistence failure");
            });
            assertThrows(IOException.class, () -> failing.addFromInbox("publisher", "key"));
            var restarted = new PublisherKeyStore(trust);
            assertEquals(committed, restarted.loadAll().containsKey("publisher"));
            assertTrue(restarted.addFromInbox("publisher", "key").trusted());
            assertEquals(1, restarted.loadAll().size());
        }
    }

    @Test void failedRevocationWritePreservesTheActiveKeyAndRetries(@TempDir Path root) throws Exception {
        stage(root, "key");
        Path trust = root.resolve("trusted-publishers");
        var original = new PublisherKeyStore(trust);
        original.addFromInbox("publisher", "key");
        byte[] before = Files.readAllBytes(trust.resolve("publisher.pub"));
        var failing = new PublisherKeyStore(trust, (path, bytes) -> { throw new IOException("fixture persistence failure"); });
        assertThrows(IOException.class, () -> failing.revoke("publisher", "rotation"));
        assertArrayEquals(before, Files.readAllBytes(trust.resolve("publisher.pub")));
        assertFalse(Files.exists(trust.resolve("publisher.revoked.json")));
        assertTrue(original.loadAll().containsKey("publisher"));
        assertFalse(original.revoke("publisher", "rotation").trusted());
    }

    @Test void durableRevocationTakesEffectBeforeCleanupAndRetryPreservesItsEvidence(@TempDir Path root) throws Exception {
        stage(root, "key");
        Path trust = root.resolve("trusted-publishers");
        new PublisherKeyStore(trust).addFromInbox("publisher", "key");
        var uncertain = new PublisherKeyStore(trust, (path, bytes) -> {
            PublisherKeyStore.writeNewAtomic(path, bytes);
            throw new IOException("fixture failure after commit before key cleanup");
        });
        assertThrows(IOException.class, () -> uncertain.revoke("publisher", "original reason"));
        assertTrue(Files.exists(trust.resolve("publisher.pub")));
        byte[] record = Files.readAllBytes(trust.resolve("publisher.revoked.json"));
        var restarted = new PublisherKeyStore(trust);
        assertFalse(restarted.loadAll().containsKey("publisher"));
        assertThrows(IOException.class, () -> restarted.addFromInbox("publisher", "key"));
        assertEquals("original reason", restarted.revoke("publisher", "retry reason").reason());
        assertArrayEquals(record, Files.readAllBytes(trust.resolve("publisher.revoked.json")));
        assertFalse(Files.exists(trust.resolve("publisher.pub")));
    }

    private void stage(Path root, String alias) throws Exception {
        var keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        Path inbox = root.resolve("publisher-inbox");
        Files.createDirectories(inbox);
        String body = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(keys.getPublic().getEncoded());
        Files.writeString(inbox.resolve(alias + ".pub"), "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----\n",
                StandardCharsets.US_ASCII);
    }
}
