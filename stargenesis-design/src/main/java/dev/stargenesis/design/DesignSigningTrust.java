package dev.stargenesis.design;

import java.util.Optional;
import java.util.UUID;

/** Server-owned Ed25519 publisher keys pinned to design author UUIDs. */
@FunctionalInterface
public interface DesignSigningTrust {
    Optional<byte[]> publicKey(UUID author);
}
