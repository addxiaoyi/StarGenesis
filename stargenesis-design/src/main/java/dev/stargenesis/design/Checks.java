package dev.stargenesis.design;

import java.util.Collection;
import java.util.Objects;
import java.util.function.Function;

final class Checks {
    private Checks() { }
    static String token(String value) {
        if (value == null || !value.matches("[a-z][a-z0-9_:-]{0,63}")) fail("Invalid bounded token");
        return value;
    }
    static void range(long value, long min, long max, String label) {
        if (value < min || value > max) fail(label + " outside " + min + ".." + max);
    }
    static <T> void unique(Collection<T> values, Function<T, ?> key, String label) {
        if (values.stream().map(key).distinct().count() != values.size()) fail("Duplicate " + label);
    }
    static void metadata(dev.stargenesis.api.design.DesignMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        if (metadata.schemaVersion() != DesignCodec.SCHEMA_VERSION) fail("Unsupported schemaVersion");
    }
    static void fail(String message) { throw new IllegalArgumentException(message); }
}
