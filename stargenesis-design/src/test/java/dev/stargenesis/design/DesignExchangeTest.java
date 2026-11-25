package dev.stargenesis.design;

import dev.stargenesis.api.design.DesignKind;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DesignExchangeTest {
    private final DesignCodec codec = new DesignCodec();
    private final DesignExchange exchange = new DesignExchange(codec);

    private String blueprint() throws IOException {
        try (var input = getClass().getResourceAsStream("/examples/blueprint.json")) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static UUID author(String json) {
        return new DesignCodec().load(json).metadata().author();
    }

    @Test
    void signedArtifactRoundTripsOnlyWithAnAuthorPinnedPublicKey() throws Exception {
        String json = blueprint();
        UUID author = author(json);
        KeyPair keyPair = DesignExchange.newSigningKey();
        SignedDesignArtifact artifact = exchange.sign(json, keyPair);
        byte[] encoded = artifact.encode();

        var verified = exchange.verify(encoded, id -> id.equals(author)
                ? Optional.of(keyPair.getPublic().getEncoded()) : Optional.empty());

        assertEquals(DesignKind.BLUEPRINT, verified.document().kind());
        assertEquals(codec.load(json), verified.document());
        assertEquals(DesignExchange.fingerprint(keyPair.getPublic().getEncoded()), verified.keyFingerprint());
        assertArrayEquals(encoded, SignedDesignArtifact.decode(encoded).encode());
    }

    @ParameterizedTest
    @EnumSource(DesignKind.class)
    void everyDesignKindCanBeSignedAndVerified(DesignKind kind) throws Exception {
        try (var input = getClass().getResourceAsStream("/examples/" + kind.name().toLowerCase() + ".json")) {
            assertNotNull(input);
            String json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            var keyPair = DesignExchange.newSigningKey();
            UUID author = author(json);

            var verified = exchange.verify(exchange.sign(json, keyPair).encode(), id -> id.equals(author)
                    ? Optional.of(keyPair.getPublic().getEncoded()) : Optional.empty());

            assertEquals(kind, verified.document().kind());
            assertEquals(codec.load(json), verified.document());
        }
    }

    @Test
    void signaturesRejectChangedDocumentsAuthorsAndUntrustedKeys() throws Exception {
        String json = blueprint();
        UUID author = author(json);
        KeyPair keyPair = DesignExchange.newSigningKey();
        byte[] encoded = exchange.sign(json, keyPair).encode();
        var trusted = (DesignSigningTrust) id -> id.equals(author)
                ? Optional.of(keyPair.getPublic().getEncoded()) : Optional.empty();

        encoded[encoded.length - 1] ^= 1;
        assertThrows(SecurityException.class, () -> exchange.verify(encoded, trusted));
        assertThrows(SecurityException.class, () -> exchange.verify(exchange.sign(json,
                DesignExchange.newSigningKey()).encode(), trusted));
        assertThrows(SecurityException.class, () -> exchange.verify(exchange.sign(json, keyPair).encode(),
                id -> Optional.of(DesignExchange.newSigningKey().getPublic().getEncoded())));
    }

    @Test
    void publisherSignatureVerificationDoesNotGrantTrust() throws Exception {
        String json = blueprint();
        KeyPair keyPair = DesignExchange.newSigningKey();
        byte[] encoded = exchange.sign(json, keyPair).encode();

        var archived = exchange.verifyPublisherSignature(encoded);

        assertEquals(DesignKind.BLUEPRINT, archived.document().kind());
        assertThrows(SecurityException.class, () -> exchange.verify(encoded, id -> Optional.empty()));
    }

    @Test
    void binaryArtifactRejectsTruncationAndTrailingData() throws Exception {
        byte[] encoded = exchange.sign(blueprint(), DesignExchange.newSigningKey()).encode();
        assertThrows(IllegalArgumentException.class, () -> SignedDesignArtifact.decode(Arrays.copyOf(encoded, encoded.length - 1)));
        byte[] trailing = Arrays.copyOf(encoded, encoded.length + 1);
        assertThrows(IllegalArgumentException.class, () -> SignedDesignArtifact.decode(trailing));
        assertThrows(IllegalArgumentException.class,
                () -> SignedDesignArtifact.decode(new byte[SignedDesignArtifact.MAX_ARTIFACT_BYTES + 1]));
    }

    @Test
    void serializedKeyAndSignatureAreDefensivelyCopied() throws Exception {
        var signed = exchange.sign(blueprint(), DesignExchange.newSigningKey());
        byte[] key = signed.publicKey();
        byte[] signature = signed.signature();
        key[0] ^= 1;
        signature[0] ^= 1;

        assertNotEquals(key[0], signed.publicKey()[0]);
        assertNotEquals(signature[0], signed.signature()[0]);
    }
}
