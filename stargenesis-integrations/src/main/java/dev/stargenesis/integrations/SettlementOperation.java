package dev.stargenesis.integrations;

import java.util.Objects;
import java.util.UUID;

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
}
