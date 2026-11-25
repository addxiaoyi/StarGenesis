package dev.stargenesis.design;

import dev.stargenesis.api.design.DesignDocument;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Author-pinned, bounded design exchange. Imported design data is always parsed by DesignCodec. */
public final class DesignExchange {
    private static final String SIGNATURE_DOMAIN = "StarGenesis/DesignSignature/1\n";
    private final DesignCodec codec;

    public DesignExchange(DesignCodec codec) {
        this.codec = Objects.requireNonNull(codec, "codec");
    }

    public static KeyPair newSigningKey() {
        try {
            return KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        } catch (GeneralSecurityException failure) {
            throw new IllegalStateException("Ed25519 is unavailable", failure);
        }
    }

    public SignedDesignArtifact sign(String designJson, KeyPair keyPair) {
        Objects.requireNonNull(keyPair, "key pair");
        DesignDocument document = codec.load(designJson);
        String canonicalJson = codec.write(document);
        UUID author = document.metadata().author();
        byte[] publicKey = keyPair.getPublic().getEncoded();
        String fingerprint = fingerprint(publicKey);
        byte[] payload = signaturePayload(author, fingerprint, canonicalJson);
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(keyPair.getPrivate());
            signer.update(payload);
            byte[] signature = signer.sign();
            SignedDesignArtifact artifact = new SignedDesignArtifact(author, fingerprint, publicKey,
                    canonicalJson, signature);
            if (!verifySignature(publicKey, payload, signature))
                throw new IllegalArgumentException("Private and public signing keys do not form a matching key pair");
            return artifact;
        } catch (GeneralSecurityException failure) {
            throw new IllegalArgumentException("Cannot create Ed25519 design signature", failure);
        }
    }

    public VerifiedDesign verify(byte[] encodedArtifact, DesignSigningTrust trust) {
        Objects.requireNonNull(trust, "trust");
        SignedDesignArtifact artifact = SignedDesignArtifact.decode(encodedArtifact);
        Optional<byte[]> trusted = trust.publicKey(artifact.author());
        if (trusted.isEmpty())
            throw new SecurityException("Design signing key is not trusted for this author");
        return verifyArtifact(artifact, trusted.get());
    }

    /** Verifies an archived signature without making an authorization decision about its publisher. */
    public VerifiedDesign verifyPublisherSignature(byte[] encodedArtifact) {
        return verifyArtifact(SignedDesignArtifact.decode(encodedArtifact), null);
    }

    private VerifiedDesign verifyArtifact(SignedDesignArtifact artifact, byte[] trustedPublicKey) {
        DesignDocument document = codec.load(artifact.designJson());
        if (!document.metadata().author().equals(artifact.author()))
            throw new IllegalArgumentException("Signed design author does not match its document metadata");
        if (!codec.write(document).equals(artifact.designJson()))
            throw new IllegalArgumentException("Signed design JSON must use canonical encoding");
        byte[] embeddedKey = artifact.publicKey();
        if (!fingerprint(embeddedKey).equals(artifact.keyFingerprint()))
            throw new IllegalArgumentException("Signed design key fingerprint does not match its public key");
        if (trustedPublicKey != null && !MessageDigest.isEqual(trustedPublicKey, embeddedKey))
            throw new SecurityException("Design signing key is not trusted for this author");
        boolean validSignature;
        try {
            validSignature = verifySignature(embeddedKey, signaturePayload(artifact.author(), artifact.keyFingerprint(),
                    artifact.designJson()), artifact.signature());
        } catch (GeneralSecurityException failure) {
            throw new IllegalArgumentException("Cannot verify Ed25519 design signature", failure);
        }
        if (!validSignature)
            throw new SecurityException("Signed design signature is invalid");
        return new VerifiedDesign(document, artifact.keyFingerprint());
    }

    public static String fingerprint(byte[] encodedPublicKey) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encodedPublicKey));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    public static PublicKey parsePublicKey(byte[] encodedPublicKey) {
        Objects.requireNonNull(encodedPublicKey, "encoded public key");
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encodedPublicKey));
        } catch (GeneralSecurityException failure) {
            throw new IllegalArgumentException("Invalid Ed25519 public key", failure);
        }
    }

    private static byte[] signaturePayload(UUID author, String fingerprint, String canonicalJson) {
        String payload = SIGNATURE_DOMAIN + author + "\n" + fingerprint + "\n"
                + sha256(canonicalJson.getBytes(StandardCharsets.UTF_8));
        return payload.getBytes(StandardCharsets.UTF_8);
    }

    private static String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static boolean verifySignature(byte[] encodedKey, byte[] payload, byte[] signatureBytes)
            throws GeneralSecurityException {
        Signature signature = Signature.getInstance("Ed25519");
        signature.initVerify(parsePublicKey(encodedKey));
        signature.update(payload);
        return signature.verify(signatureBytes);
    }

    public record VerifiedDesign(DesignDocument document, String keyFingerprint) {
        public VerifiedDesign {
            Objects.requireNonNull(document, "document");
            if (keyFingerprint == null || !keyFingerprint.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid Ed25519 key fingerprint");
        }
    }
}
