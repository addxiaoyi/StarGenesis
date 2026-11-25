package dev.stargenesis.sim;

import java.util.Objects;

public record EcologyEvent(Type type, long organismId, long amount) {
    public EcologyEvent {
        Objects.requireNonNull(type, "type");
        if (amount < 1) throw new IllegalArgumentException("Ecology event amount must be positive");
        if (type == Type.DECOMPOSED) {
            if (organismId != -1) throw new IllegalArgumentException("Decomposition is a world event");
        } else if (organismId < 0 || amount != 1) {
            throw new IllegalArgumentException("Organism event requires one organism");
        }
    }

    public enum Type { DECOMPOSED, FED, STARVED, AGED, BORN }
}
