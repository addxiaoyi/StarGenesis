package dev.stargenesis.design;

import dev.stargenesis.api.design.*;
import java.util.Objects;

/** Simulation-only service definition; grants no inventory or economic authority. */
public record Contract(DesignMetadata metadata, DesignId protocol, long timeoutSteps,
                       long maxUnits) implements DesignDocument {
    public Contract {
        Checks.metadata(metadata); Objects.requireNonNull(protocol);
        Checks.range(timeoutSteps, 1, 72_000, "timeoutSteps"); Checks.range(maxUnits, 1, 1_000_000, "maxUnits");
        if (metadata.dependencies().stream().noneMatch(d -> d.id().equals(protocol)))
            Checks.fail("Contract protocol must be pinned in dependencies");
    }
    @Override public DesignKind kind() { return DesignKind.CONTRACT; }
}
