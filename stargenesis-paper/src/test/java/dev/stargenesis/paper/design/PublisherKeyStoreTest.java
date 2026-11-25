package dev.stargenesis.paper.design;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.*;

class PublisherKeyStoreTest {
    @Test void trustedKeysCanRotateAndRevocationSurvivesRestart(@TempDir Path directory) throws Exception {
        KeyPair oldKey = keyPair();
        KeyPair newKey = keyPair();
        stageKey(directory, "old", oldKey);
        stageKey(directory, "new", newKey);
        PublisherKeyStore store = new PublisherKeyStore(directory.resolve("trusted-publishers"));

        store.addFromInbox("publisher.old", "old");
        store.addFromInbox("publisher.new", "new");
        assertEquals(2, store.loadAll().size());

        PublisherKeyStore.PublisherEntry revoked = store.revoke("publisher.old", "scheduled key rotation");

        assertFalse(revoked.trusted());
        assertTrue(store.loadAll().containsKey("publisher.new"));
        assertFalse(store.loadAll().containsKey("publisher.old"));
        assertEquals("scheduled key rotation", store.listEntries().stream()
                .filter(entry -> entry.keyId().equals("publisher.old")).findFirst().orElseThrow().reason());

        Path staleOldKey = directory.resolve("trusted-publishers/publisher.old.pub");
        Files.writeString(staleOldKey, pem(oldKey), StandardCharsets.US_ASCII);
        PublisherKeyStore restarted = new PublisherKeyStore(directory.resolve("trusted-publishers"));
        assertFalse(restarted.loadAll().containsKey("publisher.old"));
        assertThrows(IOException.class, () -> restarted.addFromInbox("publisher.old", "old"));
        assertEquals("scheduled key rotation", restarted.revoke("publisher.old", "duplicate request").reason());
        assertFalse(Files.exists(staleOldKey));
    }

    @Test void colonKeyIdsUsePortableFilenames(@TempDir Path directory) throws Exception {
        KeyPair keys = keyPair();
        stageKey(directory, "regional", keys);
        PublisherKeyStore store = new PublisherKeyStore(directory.resolve("trusted-publishers"));

        store.addFromInbox("publisher:regional", "regional");

        assertTrue(Files.isRegularFile(directory.resolve("trusted-publishers/publisher%3Aregional.pub")));
        assertArrayEquals(keys.getPublic().getEncoded(), store.loadAll().get("publisher:regional").getEncoded());
    }

    @Test void keyIdsCannotBeReboundToDifferentKeyMaterial(@TempDir Path directory) throws Exception {
        stageKey(directory, "first", keyPair());
        stageKey(directory, "second", keyPair());
        PublisherKeyStore store = new PublisherKeyStore(directory.resolve("trusted-publishers"));
        store.addFromInbox("publisher.shared", "first");

        assertThrows(IOException.class, () -> store.addFromInbox("publisher.shared", "second"));
        assertEquals(1, store.loadAll().size());
    }

    @Test void invalidAliasesAndEmptyRevocationReasonsAreRejected(@TempDir Path directory) throws Exception {
        PublisherKeyStore store = new PublisherKeyStore(directory.resolve("trusted-publishers"));

        assertThrows(IllegalArgumentException.class, () -> store.addFromInbox("publisher.test", "../outside"));
        stageKey(directory, "test", keyPair());
        store.addFromInbox("publisher.test", "test");
        assertThrows(IllegalArgumentException.class, () -> store.revoke("publisher.test", "  "));
        assertTrue(store.loadAll().containsKey("publisher.test"));
    }

    private KeyPair keyPair() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private void stageKey(Path root, String alias, KeyPair pair) throws IOException {
        Path inbox = root.resolve("publisher-inbox");
        Files.createDirectories(inbox);
        Files.writeString(inbox.resolve(alias + ".pub"), pem(pair), StandardCharsets.US_ASCII);
    }

    private String pem(KeyPair pair) {
        String body = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(pair.getPublic().getEncoded());
        return "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----\n";
    }
}
