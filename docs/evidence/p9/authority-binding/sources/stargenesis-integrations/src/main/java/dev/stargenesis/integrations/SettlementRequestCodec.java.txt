package dev.stargenesis.integrations;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class SettlementRequestCodec {
    private SettlementRequestCodec() { }

    public static String hash(SettlementRequest request) {
        String canonical = request.sagaId() + "\n" + request.actor() + "\n" + request.buyer() + "\n"
                + request.seller() + "\n" + request.credits() + "\n" + request.itemId() + "\n"
                + request.itemCount() + "\n" + request.expiresAtTick();
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }
}
