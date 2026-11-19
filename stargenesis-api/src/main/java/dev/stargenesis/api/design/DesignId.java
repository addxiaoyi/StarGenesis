package dev.stargenesis.api.design;

import java.util.Objects;

/** Stable namespace:path identifier; never a filesystem path. */
public record DesignId(String value) implements Comparable<DesignId> {
    public DesignId {
        Objects.requireNonNull(value, "value");
        if (value.length() > 128 || !value.matches("[a-z][a-z0-9_-]{0,31}:[a-z0-9][a-z0-9_./-]{0,94}")
                || value.contains("..") || value.endsWith("/")) throw new IllegalArgumentException("Invalid design ID");
    }
    @Override public int compareTo(DesignId other) { return value.compareTo(other.value); }
}
