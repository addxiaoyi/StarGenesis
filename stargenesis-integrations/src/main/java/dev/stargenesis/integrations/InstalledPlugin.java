package dev.stargenesis.integrations;

import java.util.Objects;

/** A detached inventory snapshot, never a Bukkit plugin reference. */
public record InstalledPlugin(String name, String version, boolean enabled) {
    public InstalledPlugin {
        name = requireText(name, "name");
        version = requireText(version, "version");
    }

    static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || value.length() > 1024) {
            throw new IllegalArgumentException(field + " must contain 1-1024 characters");
        }
        return value;
    }
}
