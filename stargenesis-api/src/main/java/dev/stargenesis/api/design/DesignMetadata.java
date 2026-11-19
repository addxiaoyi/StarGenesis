package dev.stargenesis.api.design;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
public record DesignMetadata(DesignId id, int schemaVersion, String version, UUID author, String source,
                             List<DesignDependency> dependencies, String hash, DesignPermissions permissions) {
    public DesignMetadata {
        Objects.requireNonNull(id); Objects.requireNonNull(author); Objects.requireNonNull(permissions);
        requireVersion(version);
        if (schemaVersion < 1 || schemaVersion > 1_000) throw new IllegalArgumentException("Invalid schema version");
        if (source == null || source.isBlank() || source.length() > 512) throw new IllegalArgumentException("Invalid source");
        dependencies = List.copyOf(dependencies);
        if (dependencies.size() > 64 || dependencies.stream().map(DesignDependency::id).distinct().count() != dependencies.size())
            throw new IllegalArgumentException("Duplicate or excessive dependencies");
        if (hash == null || !(hash.isEmpty() || hash.matches("[0-9a-f]{64}"))) throw new IllegalArgumentException("Invalid SHA-256");
    }
    public static void requireVersion(String version) {
        if (version == null || !version.matches("(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})"))
            throw new IllegalArgumentException("Expected bounded semantic version x.y.z");
    }
}
