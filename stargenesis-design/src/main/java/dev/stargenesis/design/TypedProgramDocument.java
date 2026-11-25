package dev.stargenesis.design;

import dev.stargenesis.api.design.DesignId;

import java.util.Objects;
import java.util.UUID;

/** Persisted identity envelope for a typed P3 program artifact. */
public record TypedProgramDocument(DesignId id, UUID author, int schemaVersion, String hash, ProgramIR program) {
    public TypedProgramDocument {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(author, "author");
        Objects.requireNonNull(program, "program");
        if (schemaVersion != TypedProgramCodec.SCHEMA_VERSION)
            throw new IllegalArgumentException("Unsupported typed program schemaVersion");
        if (hash == null || !(hash.isEmpty() || hash.matches("[0-9a-f]{64}")))
            throw new IllegalArgumentException("Invalid typed program SHA-256");
    }
}
