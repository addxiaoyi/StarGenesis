package dev.stargenesis.design;

import dev.stargenesis.api.design.*;
import java.util.*;

public record Protocol(DesignMetadata metadata, List<Field> fields, int maxPayloadBytes) implements DesignDocument {
    public Protocol {
        Checks.metadata(metadata); fields = List.copyOf(fields);
        Checks.range(fields.size(), 1, 32, "fields"); Checks.unique(fields, Field::name, "field name");
        Checks.range(maxPayloadBytes, 16, 4096, "maxPayloadBytes");
    }
    @Override public DesignKind kind() { return DesignKind.PROTOCOL; }
    public enum FieldType { BOOLEAN, INTEGER, POSITION, RESOURCE_ID, TOKEN }
    public record Field(String name, FieldType type, boolean required) {
        public Field { Checks.token(name); Objects.requireNonNull(type); }
    }
}
