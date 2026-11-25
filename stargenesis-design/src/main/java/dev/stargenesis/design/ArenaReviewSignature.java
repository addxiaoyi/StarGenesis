package dev.stargenesis.design;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.Signature;
import java.util.Objects;
import java.util.UUID;

/** Ed25519 statements for immutable arena review decisions. */
public final class ArenaReviewSignature {
    private static final String DOMAIN = "StarGenesis/ArenaReviewDecision/1\n";
    private static final int SIGNATURE_BYTES = 64;

    private ArenaReviewSignature() { }

    public static byte[] payload(String hash, UUID reviewer, Outcome outcome, String reason) {
        if (hash == null || !hash.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Review ID must be a full lowercase SHA-256 hash");
        Objects.requireNonNull(reviewer, "reviewer");
        Objects.requireNonNull(outcome, "outcome");
        validateReason(outcome, reason);
        return (DOMAIN + hash + "\n" + reviewer + "\n" + outcome.name() + "\n" + reason)
                .getBytes(StandardCharsets.UTF_8);
    }

    public static byte[] sign(String hash, UUID reviewer, Outcome outcome, String reason, KeyPair keyPair) {
        Objects.requireNonNull(keyPair, "key pair");
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(keyPair.getPrivate());
            signer.update(payload(hash, reviewer, outcome, reason));
            byte[] result = signer.sign();
            if (result.length != SIGNATURE_BYTES || !verify(hash, reviewer, outcome, reason,
                    keyPair.getPublic().getEncoded(), result))
                throw new IllegalArgumentException("Private and public review keys do not form a matching Ed25519 pair");
            return result;
        } catch (GeneralSecurityException failure) {
            throw new IllegalArgumentException("Cannot create arena review signature", failure);
        }
    }

    public static boolean verify(String hash, UUID reviewer, Outcome outcome, String reason,
                                 byte[] encodedPublicKey, byte[] signatureBytes) {
        Objects.requireNonNull(signatureBytes, "signature");
        if (signatureBytes.length != SIGNATURE_BYTES) return false;
        try {
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(DesignExchange.parsePublicKey(encodedPublicKey));
            verifier.update(payload(hash, reviewer, outcome, reason));
            return verifier.verify(signatureBytes);
        } catch (GeneralSecurityException failure) {
            throw new IllegalArgumentException("Cannot verify arena review signature", failure);
        }
    }

    public static void validateReason(Outcome outcome, String reason) {
        if (outcome == Outcome.APPROVE && "".equals(reason)) return;
        if (outcome == Outcome.REJECT && reason != null && !reason.isBlank() && reason.length() <= 240
                && reason.chars().noneMatch(Character::isISOControl)
                && StandardCharsets.UTF_8.newEncoder().canEncode(reason)) return;
        throw new IllegalArgumentException(outcome == Outcome.APPROVE
                ? "Approval reason must be empty"
                : "Review rejection reason must be 1..240 printable characters");
    }

    public enum Outcome { APPROVE, REJECT }
}
