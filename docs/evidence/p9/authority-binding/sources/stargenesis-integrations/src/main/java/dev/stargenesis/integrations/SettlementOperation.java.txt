package dev.stargenesis.integrations;

import java.util.Objects;
import java.util.UUID;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

public record SettlementOperation(String idempotencyKey, UUID source, UUID target,
                                  Asset asset, String assetId, long amount) {
    public SettlementOperation {
        if (idempotencyKey == null || !idempotencyKey.matches("[0-9a-f]{64}"))
            throw new IllegalArgumentException("Invalid settlement idempotency key");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(asset, "asset");
        if (source.equals(target) || amount < 1 || amount > 1_000_000_000L)
            throw new IllegalArgumentException("Invalid settlement operation");
        if (asset == Asset.ITEM && (assetId == null
                || !assetId.matches("[a-z][a-z0-9_-]{0,31}:[a-z0-9][a-z0-9_./-]{0,94}")
                || assetId.contains("..") || assetId.endsWith("/")))
            throw new IllegalArgumentException("Invalid item asset ID");
        if (asset == Asset.CREDITS && !"credits".equals(assetId))
            throw new IllegalArgumentException("Credit transfer asset ID must be credits");
    }

    public enum Asset { CREDITS, ITEM }
    public enum Phase { PAYMENT, DELIVERY, COMPENSATION }

    /** The operation identity is shared by automatic recovery and explicit reconciliation. */
    public static SettlementOperation forPhase(SettlementRequest request, Phase phase) {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(phase, "phase");
        String key;
        try {
            key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                    (request.sagaId() + ":" + phase.name().toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
        return switch (phase) {
            case PAYMENT -> new SettlementOperation(key, request.buyer(), request.seller(), Asset.CREDITS, "credits", request.credits());
            case DELIVERY -> new SettlementOperation(key, request.seller(), request.buyer(), Asset.ITEM, request.itemId(), request.itemCount());
            case COMPENSATION -> new SettlementOperation(key, request.seller(), request.buyer(), Asset.CREDITS, "credits", request.credits());
        };
    }
}
