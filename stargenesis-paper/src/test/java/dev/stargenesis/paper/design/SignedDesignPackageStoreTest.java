package dev.stargenesis.paper.design;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.stargenesis.api.design.DesignId;
import dev.stargenesis.design.Blueprint;
import dev.stargenesis.design.DesignCodec;
import dev.stargenesis.design.SignedDesignCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SignedDesignPackageStoreTest {
    private static final String KEY_ID = "publisher.test-2026";
    private static final UUID OWNER = new UUID(0, 10001);

    @Test void signedInboxPackageImportsIntoPrivateOwnerDirectory(@TempDir Path directory) throws Exception {
        KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        installKey(directory, keys);
        DesignCodec designs = new DesignCodec();
        SignedDesignCodec signer = new SignedDesignCodec(designs);
        Blueprint blueprint;
        try (var input = getClass().getResourceAsStream("/examples/blueprint.json")) {
            assertNotNull(input);
            blueprint = (Blueprint) designs.load(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        Path inbox = directory.resolve("design-inbox");
        Files.createDirectories(inbox);
        Files.writeString(inbox.resolve("starter_pack.json"), signer.sign(blueprint, KEY_ID, keys.getPrivate()));
        SignedDesignPackageStore store = new SignedDesignPackageStore(directory);

        var imported = store.install(OWNER, "starter_pack");

        assertEquals(KEY_ID, imported.publisherKeyId());
        assertEquals(blueprint.metadata().id(), imported.id());
        assertEquals(blueprint, store.load(OWNER, imported.id()));
        assertEquals(1, store.list(OWNER).size());
        assertEquals(imported, store.install(OWNER, "starter_pack"));
        assertTrue(store.list(new UUID(0, 10002)).isEmpty());
    }

    @Test void untrustedKeyAndTamperedEnvelopeAreRejected(@TempDir Path directory) throws Exception {
        KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        DesignCodec designs = new DesignCodec();
        Blueprint blueprint;
        try (var input = getClass().getResourceAsStream("/examples/blueprint.json")) {
            assertNotNull(input);
            blueprint = (Blueprint) designs.load(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        Path inbox = directory.resolve("design-inbox");
        Files.createDirectories(inbox);
        String signed = new SignedDesignCodec(designs).sign(blueprint, KEY_ID, keys.getPrivate());
        Files.writeString(inbox.resolve("unsigned_root.json"), signed);
        SignedDesignPackageStore store = new SignedDesignPackageStore(directory);
        assertThrows(java.io.IOException.class, () -> store.install(OWNER, "unsigned_root"));

        installKey(directory, keys);
        String altered = signed.replaceFirst("[0-9a-f]{64}", "0".repeat(64));
        Files.writeString(inbox.resolve("unsigned_root.json"), altered);
        assertThrows(java.io.IOException.class, () -> store.install(OWNER, "unsigned_root"));
    }

    @Test void packageNameCannotEscapeTheServerControlledInbox(@TempDir Path directory) throws Exception {
        SignedDesignPackageStore store = new SignedDesignPackageStore(directory);
        assertThrows(IllegalArgumentException.class, () -> store.install(OWNER, "../outside"));
    }

    @Test void unsupportedManifestSchemaIsRejectedByEveryReadEntryPoint(@TempDir Path directory) throws Exception {
        SignedDesignPackageStore store = installedStore(directory);
        var imported = store.list(OWNER).getFirst();
        Path file = importedPath(directory, imported.id());
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode manifest = (ObjectNode) mapper.readTree(Files.readString(file));
        manifest.put("schemaVersion", 2);
        String unsupported = mapper.writeValueAsString(manifest);
        Files.writeString(file, unsupported);

        assertThrows(java.io.IOException.class, () -> store.load(OWNER, imported.id()));
        assertThrows(java.io.IOException.class, () -> store.list(OWNER));
        assertThrows(java.io.IOException.class, () -> store.install(OWNER, "starter_pack"));
        assertEquals(unsupported, Files.readString(file));
    }

    @Test void signedContentCannotBeLoadedUnderAnotherDesignPath(@TempDir Path directory) throws Exception {
        SignedDesignPackageStore store = installedStore(directory);
        var imported = store.list(OWNER).getFirst();
        DesignId otherId = new DesignId("player:different");
        Files.move(importedPath(directory, imported.id()), importedPath(directory, otherId));

        assertThrows(java.io.IOException.class, () -> store.load(OWNER, otherId));
        assertThrows(java.io.IOException.class, () -> store.list(OWNER));
    }

    @Test void repeatedImportRevalidatesSavedEnvelopeBeforeReturningIt(@TempDir Path directory) throws Exception {
        SignedDesignPackageStore store = installedStore(directory);
        var imported = store.list(OWNER).getFirst();
        Path file = importedPath(directory, imported.id());
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode manifest = (ObjectNode) mapper.readTree(Files.readString(file));
        ObjectNode envelope = (ObjectNode) mapper.readTree(manifest.path("signedEnvelope").asText());
        envelope.put("signature", java.util.Base64.getEncoder().withoutPadding().encodeToString(new byte[64]));
        manifest.put("signedEnvelope", mapper.writeValueAsString(envelope));
        String corrupt = mapper.writeValueAsString(manifest);
        Files.writeString(file, corrupt);

        assertThrows(java.io.IOException.class, () -> store.install(OWNER, "starter_pack"));
        assertEquals(corrupt, Files.readString(file));
    }

    @Test void nullManifestIsReportedAsInvalidStorage(@TempDir Path directory) throws Exception {
        SignedDesignPackageStore store = installedStore(directory);
        var imported = store.list(OWNER).getFirst();
        Files.writeString(importedPath(directory, imported.id()), "null");

        assertThrows(java.io.IOException.class, () -> store.load(OWNER, imported.id()));
        assertThrows(java.io.IOException.class, () -> store.list(OWNER));
        assertThrows(java.io.IOException.class, () -> store.install(OWNER, "starter_pack"));
    }

    @Test void revokedPublisherFromAnotherStoreInvalidatesAllImportEntryPoints(@TempDir Path directory) throws Exception {
        SignedDesignPackageStore store = installedStore(directory);
        var imported = store.list(OWNER).getFirst();
        Path file = importedPath(directory, imported.id());
        byte[] before = Files.readAllBytes(file);
        new PublisherKeyStore(directory.resolve("trusted-publishers")).revoke(KEY_ID, "retired signing key");
        assertThrows(java.io.IOException.class, () -> store.load(OWNER, imported.id()));
        assertThrows(java.io.IOException.class, () -> store.list(OWNER));
        assertThrows(java.io.IOException.class, () -> store.install(OWNER, "starter_pack"));
        assertArrayEquals(before, Files.readAllBytes(file));
    }

    private SignedDesignPackageStore installedStore(Path directory) throws Exception {
        KeyPair keys = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        installKey(directory, keys);
        DesignCodec designs = new DesignCodec();
        Blueprint blueprint;
        try (var input = getClass().getResourceAsStream("/examples/blueprint.json")) {
            assertNotNull(input);
            blueprint = (Blueprint) designs.load(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        SignedDesignPackageStore store = new SignedDesignPackageStore(directory);
        Files.writeString(directory.resolve("design-inbox/starter_pack.json"),
                new SignedDesignCodec(designs).sign(blueprint, KEY_ID, keys.getPrivate()));
        store.install(OWNER, "starter_pack");
        return store;
    }

    private Path importedPath(Path directory, DesignId id) throws Exception {
        String hash = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(id.value().getBytes(StandardCharsets.UTF_8)));
        return directory.resolve("imports").resolve(OWNER.toString()).resolve(hash + ".json");
    }

    private void installKey(Path directory, KeyPair pair) throws Exception {
        Path trust = directory.resolve("trusted-publishers");
        Files.createDirectories(trust);
        String body = java.util.Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(pair.getPublic().getEncoded());
        Files.writeString(trust.resolve(KEY_ID + ".pub"), "-----BEGIN PUBLIC KEY-----\n" + body
                + "\n-----END PUBLIC KEY-----\n", StandardCharsets.US_ASCII);
    }
}
