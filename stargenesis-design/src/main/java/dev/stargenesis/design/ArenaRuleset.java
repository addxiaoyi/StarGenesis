package dev.stargenesis.design;

import dev.stargenesis.api.design.*;

/** P0 bounded rules envelope; P7 will supply rule composition and reachability checks. */
public record ArenaRuleset(DesignMetadata metadata, int width, int height, long durationSteps,
                           int unitBudget) implements DesignDocument {
    public ArenaRuleset {
        Checks.metadata(metadata); Checks.range(width, 4, 128, "width"); Checks.range(height, 4, 128, "height");
        Checks.range(durationSteps, 1, 72_000, "durationSteps"); Checks.range(unitBudget, 1, 64, "unitBudget");
    }
    @Override public DesignKind kind() { return DesignKind.ARENA_RULESET; }
}
