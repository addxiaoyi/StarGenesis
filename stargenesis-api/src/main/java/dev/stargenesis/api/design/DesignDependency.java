package dev.stargenesis.api.design;
import java.util.Objects;
public record DesignDependency(DesignId id, String version, String hash) {
    public DesignDependency {
        Objects.requireNonNull(id); DesignMetadata.requireVersion(version);
        if (hash == null || !hash.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("Invalid dependency SHA-256");
    }
}
