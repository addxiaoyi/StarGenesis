package dev.stargenesis.api.design;
/** Implementations must be deeply immutable and contain only audited declarative data. */
public interface DesignDocument {
    DesignMetadata metadata();
    DesignKind kind();
}
