package dev.stargenesis.sim;

import java.util.Objects;

public record EcologyCheckpoint(EcologySettings settings, EcologySnapshot snapshot, long energyBaseline) {
    public EcologyCheckpoint {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(snapshot, "snapshot");
        if (energyBaseline < 0) throw new IllegalArgumentException("Invalid ecology energy baseline");
    }

    public EcologyCheckpoint(EcologySettings settings, EcologySnapshot snapshot) {
        this(settings, snapshot, settings.founders().stream().mapToLong(EcologyFounder::initialEnergy).sum());
    }
}
