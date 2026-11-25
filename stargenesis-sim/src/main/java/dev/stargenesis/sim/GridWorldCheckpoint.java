package dev.stargenesis.sim;

import java.util.Objects;

public record GridWorldCheckpoint(GridWorldSettings settings, GridWorldSnapshot snapshot) {
    public GridWorldCheckpoint {
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(snapshot, "snapshot");
        if (!settings.worldId().equals(snapshot.worldId()) || settings.seed() != snapshot.seed()
                || settings.width() != snapshot.width() || settings.height() != snapshot.height()) {
            throw new IllegalArgumentException("Checkpoint settings do not match snapshot");
        }
    }
}
