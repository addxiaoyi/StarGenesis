package dev.stargenesis.integrations;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Optional platform-owned, disposable views. Simulation identities and evidence remain authoritative. */
public interface EntityProjection extends IntegrationAdapter {
    void start(UUID owner, String modelId, Anchor anchor, LabProjectionSource.Frame frame);
    void update(UUID owner, LabProjectionSource.Frame frame);
    void stop(UUID owner);
    Optional<Status> status(UUID owner);
    int reservedEntities();

    record Anchor(UUID world, double x, double y, double z) {
        public Anchor {
            Objects.requireNonNull(world, "world");
            if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
                    || Math.abs(x) > 29_999_000 || Math.abs(z) > 29_999_000 || Math.abs(y) > 4096)
                throw new IllegalArgumentException("Invalid projection anchor");
        }
    }
    record Status(String sessionId, String modelId, long tick, int instances, int reservedEntities,
                  boolean cleanupPending) {}
}
