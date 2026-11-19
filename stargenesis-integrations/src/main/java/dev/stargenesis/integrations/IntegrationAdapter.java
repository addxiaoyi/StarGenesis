package dev.stargenesis.integrations;

/**
 * Trusted, version-specific adapter contract. Verification must be bounded, read-only and
 * idempotent. Called by inspect on its caller's thread, never scheduled automatically.
 * Implementations must honor the provider's documented thread boundary.
 * The platform owner must serialize lifecycle operations on its permitted server thread.
 */
public interface IntegrationAdapter extends AutoCloseable {
    AdapterVerification verify(InstalledPlugin plugin);

    @Override
    default void close() { }
}
