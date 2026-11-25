package dev.stargenesis.design;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

/** Bounded Ed25519-signed interchange package. The embedded key is never trusted on its own. */
public record SignedDesignArtifact(UUID author, String keyFingerprint, byte[] publicKey,
                                  String designJson, byte[] signature) {
    public static final int MAX_ARTIFACT_BYTES = DesignCodec.MAX_DOCUMENT_BYTES + 512;
    private static final int MAGIC = 0x53474431;
    private static final int FORMAT = 1;
    private static final int MAX_PUBLIC_KEY_BYTES = 128;
    private static final int SIGNATURE_BYTES = 64;

    public SignedDesignArtifact {
        Objects.requireNonNull(author, "author");
        if (keyFingerprint == null || !keyFingerprint.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid Ed25519 key fingerprint");
        publicKey = Objects.requireNonNull(publicKey, "public key").clone();
        if (publicKey.length < 32 || publicKey.length > MAX_PUBLIC_KEY_BYTES)
            throw new IllegalArgumentException("Public key size is outside its bounds");
        if (designJson == null || designJson.length() < 2
                || designJson.getBytes(StandardCharsets.UTF_8).length > DesignCodec.MAX_DOCUMENT_BYTES)
            throw new IllegalArgumentException("Design document size is outside its bounds");
        signature = Objects.requireNonNull(signature, "signature").clone();
        if (signature.length != SIGNATURE_BYTES) throw new IllegalArgumentException("Invalid Ed25519 signature size");
    }

    @Override public byte[] publicKey() { return publicKey.clone(); }

    @Override public byte[] signature() { return signature.clone(); }

    public byte[] encode() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(MAGIC);
                output.writeByte(FORMAT);
                output.writeLong(author.getMostSignificantBits());
                output.writeLong(author.getLeastSignificantBits());
                output.write(keyFingerprint.getBytes(StandardCharsets.US_ASCII));
                output.writeShort(publicKey.length);
                output.write(publicKey);
                byte[] document = designJson.getBytes(StandardCharsets.UTF_8);
                output.writeInt(document.length);
                output.write(document);
                output.write(signature);
            }
            byte[] encoded = bytes.toByteArray();
            if (encoded.length > MAX_ARTIFACT_BYTES)
                throw new IllegalArgumentException("Signed design exceeds its artifact byte budget");
            return encoded;
        } catch (IOException impossible) {
            throw new IllegalStateException("Cannot encode signed design", impossible);
        }
    }

    public static SignedDesignArtifact decode(byte[] encoded) {
        Objects.requireNonNull(encoded, "encoded artifact");
        if (encoded.length < 4 + 1 + 16 + 64 + 2 + 32 + 4 + 2 + SIGNATURE_BYTES
                || encoded.length > MAX_ARTIFACT_BYTES)
            throw new IllegalArgumentException("Signed design artifact size is outside its bounds");
        try (var input = new java.io.DataInputStream(new java.io.ByteArrayInputStream(encoded))) {
            if (input.readInt() != MAGIC || input.readUnsignedByte() != FORMAT)
                throw new IllegalArgumentException("Unknown signed design artifact format");
            UUID author = new UUID(input.readLong(), input.readLong());
            String fingerprint = new String(input.readNBytes(64), StandardCharsets.US_ASCII);
            int publicKeySize = input.readUnsignedShort();
            if (publicKeySize < 32 || publicKeySize > MAX_PUBLIC_KEY_BYTES)
                throw new IllegalArgumentException("Public key size is outside its bounds");
            byte[] publicKey = input.readNBytes(publicKeySize);
            int documentSize = input.readInt();
            if (documentSize < 2 || documentSize > DesignCodec.MAX_DOCUMENT_BYTES
                    || documentSize > input.available() - SIGNATURE_BYTES)
                throw new IllegalArgumentException("Design document size is outside its bounds");
            byte[] documentBytes = input.readNBytes(documentSize);
            byte[] signature = input.readNBytes(SIGNATURE_BYTES);
            if (input.available() != 0 || publicKey.length != publicKeySize
                    || documentBytes.length != documentSize || signature.length != SIGNATURE_BYTES)
                throw new IllegalArgumentException("Signed design artifact is truncated or has trailing data");
            String document = new String(documentBytes, StandardCharsets.UTF_8);
            if (!java.util.Arrays.equals(documentBytes, document.getBytes(StandardCharsets.UTF_8)))
                throw new IllegalArgumentException("Design document contains invalid UTF-8");
            return new SignedDesignArtifact(author, fingerprint, publicKey, document, signature);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Malformed signed design artifact", failure);
        }
    }
}
