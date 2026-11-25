package dev.stargenesis.design;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ArenaReviewSignatureTest {
    private static final UUID REVIEWER = UUID.fromString("00000000-0000-0000-0000-000000000032");
    private static final String HASH = "a".repeat(64);

    @Test
    void decisionPayloadUsesStableDomainSeparatedFields() {
        String expected = "StarGenesis/ArenaReviewDecision/1\n" + HASH + "\n" + REVIEWER
                + "\nAPPROVE\n";

        assertEquals(expected, new String(ArenaReviewSignature.payload(HASH, REVIEWER,
                ArenaReviewSignature.Outcome.APPROVE, ""), StandardCharsets.UTF_8));
    }

    @Test
    void signaturesBindCandidateReviewerOutcomeAndRejectionReason() {
        var keys = DesignExchange.newSigningKey();
        byte[] signature = ArenaReviewSignature.sign(HASH, REVIEWER,
                ArenaReviewSignature.Outcome.REJECT, "Map spawn overlaps an obstacle.", keys);

        assertTrue(ArenaReviewSignature.verify(HASH, REVIEWER, ArenaReviewSignature.Outcome.REJECT,
                "Map spawn overlaps an obstacle.", keys.getPublic().getEncoded(), signature));
        assertFalse(ArenaReviewSignature.verify("b".repeat(64), REVIEWER, ArenaReviewSignature.Outcome.REJECT,
                "Map spawn overlaps an obstacle.", keys.getPublic().getEncoded(), signature));
        assertFalse(ArenaReviewSignature.verify(HASH, UUID.randomUUID(), ArenaReviewSignature.Outcome.REJECT,
                "Map spawn overlaps an obstacle.", keys.getPublic().getEncoded(), signature));
        assertFalse(ArenaReviewSignature.verify(HASH, REVIEWER, ArenaReviewSignature.Outcome.APPROVE,
                "", keys.getPublic().getEncoded(), signature));
        assertFalse(ArenaReviewSignature.verify(HASH, REVIEWER, ArenaReviewSignature.Outcome.REJECT,
                "Different reason.", keys.getPublic().getEncoded(), signature));
    }

    @Test
    void decisionFieldsAndReasonBoundsAreValidated() {
        assertThrows(IllegalArgumentException.class, () -> ArenaReviewSignature.payload("A".repeat(64), REVIEWER,
                ArenaReviewSignature.Outcome.APPROVE, ""));
        assertThrows(IllegalArgumentException.class, () -> ArenaReviewSignature.payload(HASH, REVIEWER,
                ArenaReviewSignature.Outcome.APPROVE, "approved"));
        assertThrows(IllegalArgumentException.class, () -> ArenaReviewSignature.payload(HASH, REVIEWER,
                ArenaReviewSignature.Outcome.REJECT, "\n"));
        assertThrows(IllegalArgumentException.class, () -> ArenaReviewSignature.payload(HASH, REVIEWER,
                ArenaReviewSignature.Outcome.REJECT, "x".repeat(241)));
    }

    @Test
    void rejectionReasonMustBeLosslessUnicode() {
        for (String reason : new String[]{"bad\uD800text", "bad\uDC00text"})
            assertThrows(IllegalArgumentException.class, () -> ArenaReviewSignature.payload(HASH, REVIEWER,
                    ArenaReviewSignature.Outcome.REJECT, reason));
        assertDoesNotThrow(() -> ArenaReviewSignature.payload(HASH, REVIEWER,
                ArenaReviewSignature.Outcome.REJECT, "地图需要调整🌍"));
    }

    @Test
    void javaAndNodeShareExactMultilingualPayloadsAndSignatures() throws Exception {
        try (var input = getClass().getResourceAsStream("/arena-review-signatures.json")) {
            assertNotNull(input);
            var fixture = new com.fasterxml.jackson.databind.ObjectMapper().readTree(input);
            byte[] publicKey = Base64.getDecoder().decode(fixture.get("publicKeyBase64").asText());
            byte[] privateKey = Base64.getDecoder().decode(fixture.get("privateKeyPkcs8Base64").asText());
            KeyPair keys = new KeyPair(DesignExchange.parsePublicKey(publicKey),
                    KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(privateKey)));
            for (var vector : fixture.get("vectors")) {
                String hash = vector.get("hash").asText();
                UUID reviewer = UUID.fromString(vector.get("reviewer").asText());
                var outcome = ArenaReviewSignature.Outcome.valueOf(vector.get("decision").asText().toUpperCase(java.util.Locale.ROOT));
                String reason = vector.get("reason").asText();
                byte[] signature = Base64.getDecoder().decode(vector.get("signatureBase64").asText());
                assertEquals(vector.get("payloadHex").asText(), HexFormat.of().formatHex(
                        ArenaReviewSignature.payload(hash, reviewer, outcome, reason)));
                assertTrue(ArenaReviewSignature.verify(hash, reviewer, outcome, reason, publicKey, signature));
                assertArrayEquals(signature, ArenaReviewSignature.sign(hash, reviewer, outcome, reason, keys));
            }
        }
    }
}
