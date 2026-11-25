package dev.stargenesis.design;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.Base64;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class SignedDesignCodecTest {
    private static final String KEY_ID = "publisher:test-2026";
    private final DesignCodec designs = new DesignCodec();
    private final SignedDesignCodec signed = new SignedDesignCodec(designs);
    private final ObjectMapper json = new ObjectMapper();

    @Test void signedDesignLoadsOnlyWithTheTrustedEd25519PublisherKey() throws Exception {
        KeyPair key = keyPair();
        Blueprint design = blueprint();
        String envelope = signed.sign(design, KEY_ID, key.getPrivate());

        SignedDesignCodec.ImportedDesign imported = signed.verifyAndLoad(envelope, Map.of(KEY_ID, key.getPublic()));

        assertEquals(KEY_ID, imported.publisherKeyId());
        assertEquals(design, imported.document());
        assertEquals(design.metadata().hash(), imported.documentHash());
        assertThrows(DesignValidationException.class, () -> signed.verifyAndLoad(envelope, Map.of()));
    }

    @Test void documentOrPublisherKeyTamperingIsRejected() throws Exception {
        KeyPair key = keyPair();
        KeyPair other = keyPair();
        String envelope = signed.sign(blueprint(), KEY_ID, key.getPrivate());
        ObjectNode keyTampered = (ObjectNode) json.readTree(envelope);
        keyTampered.put("keyId", "publisher:other");
        assertThrows(DesignValidationException.class,
                () -> signed.verifyAndLoad(keyTampered.toString(), Map.of("publisher:other", key.getPublic())));

        ObjectNode documentTampered = (ObjectNode) json.readTree(envelope);
        String document = documentTampered.path("document").asText().replace("\"x\":0", "\"x\":1");
        documentTampered.put("document", document);
        assertThrows(DesignValidationException.class,
                () -> signed.verifyAndLoad(documentTampered.toString(), Map.of(KEY_ID, key.getPublic())));
        assertNotEquals(other.getPublic(), key.getPublic());
    }

    @Test void unknownEnvelopeFieldsAndImplicitLegacyMigrationAreRejected() throws Exception {
        KeyPair key = keyPair();
        String envelope = signed.sign(blueprint(), KEY_ID, key.getPrivate());
        ObjectNode withUnknown = (ObjectNode) json.readTree(envelope);
        withUnknown.put("execute", "command");
        assertThrows(DesignValidationException.class,
                () -> signed.verifyAndLoad(withUnknown.toString(), Map.of(KEY_ID, key.getPublic())));

        String legacy = json.readTree(envelope).path("document").asText()
                .replace("\"schemaVersion\":1", "\"schemaVersion\":0");
        String signedLegacy = envelope(KEY_ID, legacy, key);
        assertThrows(DesignValidationException.class,
                () -> signed.verifyAndLoad(signedLegacy, Map.of(KEY_ID, key.getPublic())));
    }

    @Test void envelopeAndKeyIdsAreBoundedAndMalformedKeysAreRejected() throws Exception {
        KeyPair key = keyPair();
        assertThrows(IllegalArgumentException.class, () -> signed.sign(blueprint(), "../key", key.getPrivate()));
        assertThrows(DesignValidationException.class, () -> signed.verifyAndLoad(" ".repeat(400_000), Map.of()));
        assertThrows(IllegalArgumentException.class, () -> signed.sign(blueprint(), KEY_ID,
                KeyPairGenerator.getInstance("RSA").generateKeyPair().getPrivate()));
    }

    private Blueprint blueprint() throws IOException {
        try (var input = getClass().getResourceAsStream("/examples/blueprint.json")) {
            assertNotNull(input);
            return (Blueprint) designs.load(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private KeyPair keyPair() throws Exception {
        return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
    }

    private String envelope(String keyId, String document, KeyPair key) throws Exception {
        Signature signature = Signature.getInstance("Ed25519");
        signature.initSign(key.getPrivate());
        signature.update(("StarGenesis-SignedDesign/v1\n" + keyId + "\n" + document).getBytes(StandardCharsets.UTF_8));
        ObjectNode root = json.createObjectNode();
        root.put("envelopeVersion", 1);
        root.put("algorithm", "Ed25519");
        root.put("keyId", keyId);
        root.put("document", document);
        root.put("signature", Base64.getEncoder().withoutPadding().encodeToString(signature.sign()));
        return root.toString();
    }
}
