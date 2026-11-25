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

class PublisherTrustLifecycleTest {
    @Test void newTrustReservesRoomForRevokingEveryActiveKey(@TempDir Path root) throws Exception {
        var store = storeWithKey(root, "existing");
        stage(root, "additional", keyPair());
        seedRevocations(root, PublisherKeyStore.MAX_REVOKED_KEYS - 1);
        assertThrows(IOException.class, () -> store.addFromInbox("additional", "additional"));
        assertEquals(1, store.loadAll().size());
        assertTrue(store.addFromInbox("existing", "existing").trusted());
        assertFalse(store.revoke("existing", "last reserved slot").trusted());
        assertTrue(new PublisherKeyStore(root.resolve("trusted-publishers")).loadAll().isEmpty());
        assertEquals(PublisherKeyStore.MAX_REVOKED_KEYS, store.listEntries().size());
        assertThrows(IOException.class, () -> store.addFromInbox("additional", "additional"));
    }

    @Test void legacyFullRevocationHistoryRejectsBeforeWritingAnUnreadableEntry(@TempDir Path root) throws Exception {
        var store = storeWithKey(root, "existing");
        seedRevocations(root, PublisherKeyStore.MAX_REVOKED_KEYS);
        byte[] before = Files.readAllBytes(root.resolve("trusted-publishers/existing.pub"));
        assertThrows(IOException.class, () -> store.revoke("existing", "capacity reached"));
        assertFalse(Files.exists(root.resolve("trusted-publishers/existing.revoked.json")));
        assertArrayEquals(before, Files.readAllBytes(root.resolve("trusted-publishers/existing.pub")));
        assertTrue(new PublisherKeyStore(root.resolve("trusted-publishers")).loadAll().containsKey("existing"));
    }

    @Test void revocationJsonRejectsScalarCoercion(@TempDir Path root) throws Exception {
        var store = storeWithKey(root, "existing");
        Path record = root.resolve("trusted-publishers/retired.revoked.json");
        for (String schema : new String[]{"1.5", "\"1\""}) {
            Files.writeString(record, revocation("retired").replace("\"schemaVersion\":1", "\"schemaVersion\":" + schema));
            assertThrows(IOException.class, store::loadAll);
        }
        for (String reason : new String[]{"123", "1.5", "true"}) {
            Files.writeString(record, revocation("retired").replace("\"reason\":\"fixture\"", "\"reason\":" + reason));
            assertThrows(IOException.class, store::loadAll);
        }
    }

    @Test void legacyTrustFilesKeepTheirOriginalBytesAndRevocationEvidence(@TempDir Path root) throws Exception {
        Path trust = root.resolve("trusted-publishers");
        var store = new PublisherKeyStore(trust);
        byte[] active = fixture("publisher-v1-active.pub", "ff7d66d4e3e0acf58155557873ba2d45b9cc8a9ca79b876509ba211730b66edf");
        byte[] revoked = fixture("publisher-v1-revoked.json", "1837bc78d133a9ea6ac68f17cd2ceacbe57765f2d4866f6ff2599ae3deea919e");
        Path activeFile = trust.resolve("publisher%3Alegacy.pub");
        Path revokedFile = trust.resolve("publisher%3Aretired.revoked.json");
        Files.write(activeFile, active);
        Files.write(revokedFile, revoked);
        Files.write(root.resolve("publisher-inbox/legacy.pub"), active);
        assertEquals(java.util.Set.of("publisher:legacy"), store.loadAll().keySet());
        assertEquals(2, store.listEntries().size());
        assertEquals("e262ac8c85b8194645c97f02466c3746af931fa9e72c6f205db092914e994cb6",
                store.addFromInbox("publisher:legacy", "legacy").fingerprint());
        assertEquals("legacy fixture rotation", store.revoke("publisher:retired", "retry").reason());
        assertArrayEquals(active, Files.readAllBytes(activeFile));
        assertArrayEquals(revoked, Files.readAllBytes(revokedFile));
    }

    private byte[] fixture(String name, String expectedHash) throws Exception {
        try (var input = getClass().getResourceAsStream("/" + name)) {
            assertNotNull(input);
            byte[] bytes = input.readAllBytes();
            assertEquals(expectedHash, java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)));
            return bytes;
        }
    }

    private PublisherKeyStore storeWithKey(Path root, String keyId) throws Exception {
        stage(root, keyId, keyPair());
        var store = new PublisherKeyStore(root.resolve("trusted-publishers"));
        store.addFromInbox(keyId, keyId);
        return store;
    }

    private void seedRevocations(Path root, int count) throws IOException {
        for (int index = 0; index < count; index++) {
            String keyId = "retired-" + index;
            Files.writeString(root.resolve("trusted-publishers").resolve(keyId + ".revoked.json"), revocation(keyId));
        }
    }

    private String revocation(String keyId) {
        return "{\"schemaVersion\":1,\"keyId\":\"" + keyId + "\",\"fingerprint\":\"" + "0".repeat(64)
                + "\",\"revokedAt\":\"2026-11-26T00:00:00Z\",\"reason\":\"fixture\"}";
    }

    private KeyPair keyPair() throws Exception { return KeyPairGenerator.getInstance("Ed25519").generateKeyPair(); }

    private void stage(Path root, String alias, KeyPair pair) throws IOException {
        Path inbox = root.resolve("publisher-inbox");
        Files.createDirectories(inbox);
        String body = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(pair.getPublic().getEncoded());
        Files.writeString(inbox.resolve(alias + ".pub"), "-----BEGIN PUBLIC KEY-----\n" + body + "\n-----END PUBLIC KEY-----\n",
                StandardCharsets.US_ASCII);
    }
}
