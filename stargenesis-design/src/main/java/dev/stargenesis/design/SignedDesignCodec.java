package dev.stargenesis.design;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.stargenesis.api.design.DesignDocument;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.Map;
import java.util.Objects;

/** Signed, schema-validated design import using trusted Ed25519 publisher keys. */
public final class SignedDesignCodec {
    public static final int ENVELOPE_VERSION = 1;
    public static final int MAX_ENVELOPE_BYTES = 300_000;
    private static final String DOMAIN = "StarGenesis-SignedDesign/v1\n";
    private final DesignCodec designs;
    private final ObjectMapper mapper;

    public SignedDesignCodec(DesignCodec designs) {
        this.designs = Objects.requireNonNull(designs, "designs");
        mapper = new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(36)
                        .maxStringLength(DesignCodec.MAX_DOCUMENT_BYTES).maxNumberLength(20).build()).build());
        mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
        mapper.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        mapper.disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    }

    public String sign(DesignDocument document, String keyId, PrivateKey privateKey) {
        requireKeyId(keyId);
        Objects.requireNonNull(privateKey, "privateKey");
        if (!privateKey.getAlgorithm().equalsIgnoreCase("EdDSA")
                && !privateKey.getAlgorithm().equalsIgnoreCase("Ed25519"))
            throw new IllegalArgumentException("Publisher signing key must use Ed25519");
        String canonicalDocument = designs.write(document);
        byte[] signature = signBytes(keyId, canonicalDocument, privateKey);
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("envelopeVersion", ENVELOPE_VERSION);
        envelope.put("algorithm", "Ed25519");
        envelope.put("keyId", keyId);
        envelope.put("document", canonicalDocument);
        envelope.put("signature", Base64.getEncoder().withoutPadding().encodeToString(signature));
        String result;
        try { result = mapper.writeValueAsString(envelope); }
        catch (com.fasterxml.jackson.core.JsonProcessingException impossible) {
            throw new IllegalStateException("Could not encode signed design", impossible);
        }
        requireEnvelopeBudget(result);
        return result;
    }

    public ImportedDesign verifyAndLoad(String encoded, Map<String, PublicKey> trustedKeys) {
        requireEnvelopeBudget(encoded);
        Objects.requireNonNull(trustedKeys, "trustedKeys");
        ObjectNode envelope;
        try {
            var parsed = mapper.readTree(encoded);
            if (!(parsed instanceof ObjectNode object)) throw invalid("Expected signed design object");
            envelope = object;
        } catch (java.io.IOException malformed) {
            throw invalid("Malformed signed design envelope", malformed);
        }
        if (envelope.size() != 5 || !envelope.has("envelopeVersion") || !envelope.has("algorithm")
                || !envelope.has("keyId") || !envelope.has("document") || !envelope.has("signature"))
            throw invalid("Unknown or missing signed design envelope fields");
        if (!envelope.path("envelopeVersion").isIntegralNumber()
                || envelope.path("envelopeVersion").intValue() != ENVELOPE_VERSION
                || !envelope.path("algorithm").isTextual() || !envelope.path("algorithm").asText().equals("Ed25519")
                || !envelope.path("keyId").isTextual() || !envelope.path("document").isTextual()
                || !envelope.path("signature").isTextual())
            throw invalid("Unsupported signed design envelope");
        String keyId = envelope.path("keyId").asText();
        String document = envelope.path("document").asText();
        String encodedSignature = envelope.path("signature").asText();
        requireKeyId(keyId);
        if (encodedSignature.length() > 128) throw invalid("Signature exceeds its byte budget");
        PublicKey key = trustedKeys.get(keyId);
        if (key == null) throw invalid("Publisher key is not trusted");
        if (!key.getAlgorithm().equalsIgnoreCase("EdDSA") && !key.getAlgorithm().equalsIgnoreCase("Ed25519"))
            throw invalid("Trusted publisher key is not Ed25519");
        byte[] signature;
        try { signature = Base64.getDecoder().decode(encodedSignature); }
        catch (IllegalArgumentException malformed) { throw invalid("Malformed publisher signature", malformed); }
        if (signature.length != 64 || !Base64.getEncoder().withoutPadding().encodeToString(signature).equals(encodedSignature))
            throw invalid("Publisher signature encoding is not canonical");
        if (!verifySignature(keyId, document, signature, key)) throw invalid("Publisher signature mismatch");
        DesignDocument loaded = designs.load(document);
        return new ImportedDesign(keyId, loaded, loaded.metadata().hash());
    }

    private byte[] signBytes(String keyId, String document, PrivateKey key) {
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(key);
            signer.update(payload(keyId, document));
            return signer.sign();
        } catch (GeneralSecurityException failure) {
            throw new IllegalArgumentException("Publisher signature could not be created", failure);
        }
    }

    private boolean verifySignature(String keyId, String document, byte[] signature, PublicKey key) {
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(key);
            verifier.update(payload(keyId, document));
            return verifier.verify(signature);
        } catch (GeneralSecurityException failure) {
            throw invalid("Publisher signature could not be verified", failure);
        }
    }

    private byte[] payload(String keyId, String document) {
        return (DOMAIN + keyId + "\n" + document).getBytes(StandardCharsets.UTF_8);
    }

    private void requireKeyId(String keyId) {
        if (keyId == null || !keyId.matches("[a-z0-9][a-z0-9_.:-]{0,63}"))
            throw new IllegalArgumentException("Invalid publisher key ID");
    }

    private void requireEnvelopeBudget(String value) {
        if (value == null || value.getBytes(StandardCharsets.UTF_8).length > MAX_ENVELOPE_BYTES)
            throw invalid("Signed design envelope exceeds its byte budget");
    }

    private DesignValidationException invalid(String message) { return new DesignValidationException(message); }
    private DesignValidationException invalid(String message, Throwable cause) { return new DesignValidationException(message, cause); }

    public record ImportedDesign(String publisherKeyId, DesignDocument document, String documentHash) { }
}
