package dev.stargenesis.paper;

import dev.stargenesis.design.DesignExchange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ArenaReviewTrustFileStoreTest {
    private static final UUID REVIEWER = UUID.fromString("00000000-0000-0000-0000-000000000032");

    @Test
    void reviewerKeysPersistRotateAndRevokeByUuid(@TempDir Path directory) throws Exception {
        ArenaReviewTrustFileStore store = new ArenaReviewTrustFileStore(directory);
        byte[] first = DesignExchange.newSigningKey().getPublic().getEncoded();
        byte[] rotated = DesignExchange.newSigningKey().getPublic().getEncoded();

        store.trust(REVIEWER, first);
        assertEquals(1, store.count());
        assertArrayEquals(first, new ArenaReviewTrustFileStore(directory).publicKey(REVIEWER).orElseThrow());
        store.trust(REVIEWER, rotated);
        assertEquals(1, store.count());
        assertEquals(DesignExchange.fingerprint(rotated), store.loadAll().getFirst().fingerprint());
        assertTrue(store.revoke(REVIEWER));
        assertTrue(store.publicKey(REVIEWER).isEmpty());
        assertFalse(store.revoke(REVIEWER));
    }

    @Test
    void damagedRecordsAndNonEd25519KeysFailClosed(@TempDir Path directory) throws Exception {
        ArenaReviewTrustFileStore store = new ArenaReviewTrustFileStore(directory);
        store.trust(REVIEWER, DesignExchange.newSigningKey().getPublic().getEncoded());
        Path keyFile = directory.resolve("arena-review-trust").resolve(REVIEWER + ".key");
        byte[] bytes = Files.readAllBytes(keyFile);
        bytes[10] ^= 1;
        Files.write(keyFile, bytes);

        assertThrows(IllegalStateException.class, () -> store.publicKey(REVIEWER));
        assertThrows(java.io.IOException.class, store::loadAll);
        assertThrows(IllegalArgumentException.class, () -> store.trust(REVIEWER, new byte[32]));
    }
}
