package dev.stargenesis.paper;

import dev.stargenesis.design.DesignExchange;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DesignSigningTrustFileStoreTest {
    private static final UUID AUTHOR = UUID.fromString("00000000-0000-0000-0000-000000000051");

    @Test
    void pinnedKeysPersistRotateAndRevokeByAuthor(@TempDir Path directory) throws Exception {
        DesignSigningTrustFileStore store = new DesignSigningTrustFileStore(directory);
        var first = DesignExchange.newSigningKey().getPublic().getEncoded();
        var rotated = DesignExchange.newSigningKey().getPublic().getEncoded();

        store.trust(AUTHOR, first);
        assertEquals(1, store.count());
        assertArrayEquals(first, new DesignSigningTrustFileStore(directory).publicKey(AUTHOR).orElseThrow());
        store.trust(AUTHOR, rotated);
        assertEquals(1, store.count());
        assertEquals(DesignExchange.fingerprint(rotated), store.loadAll().getFirst().fingerprint());
        assertTrue(store.revoke(AUTHOR));
        assertTrue(store.publicKey(AUTHOR).isEmpty());
        assertFalse(store.revoke(AUTHOR));
    }

    @Test
    void corruptAndUnrecognizedKeysFailClosed(@TempDir Path directory) throws Exception {
        DesignSigningTrustFileStore store = new DesignSigningTrustFileStore(directory);
        store.trust(AUTHOR, DesignExchange.newSigningKey().getPublic().getEncoded());
        Path keyFile = directory.resolve("design-trust").resolve(AUTHOR + ".key");
        byte[] bytes = Files.readAllBytes(keyFile);
        bytes[bytes.length - 1] ^= 1;
        Files.write(keyFile, bytes);

        assertThrows(IllegalStateException.class, () -> store.publicKey(AUTHOR));
        assertThrows(java.io.IOException.class, store::loadAll);
        assertThrows(IllegalArgumentException.class, () -> store.trust(AUTHOR, new byte[32]));
    }
}
