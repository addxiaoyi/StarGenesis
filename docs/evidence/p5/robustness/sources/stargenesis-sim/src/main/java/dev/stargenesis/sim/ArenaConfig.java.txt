/** Bounds extracted from one published schema-v1 ArenaRuleset. */
package dev.stargenesis.sim;

public record ArenaConfig(int width, int height, long durationSteps, int unitBudget) {
    public ArenaConfig {
        if (width < 4 || width > 128 || height < 4 || height > 128
                || durationSteps < 1 || durationSteps > 6_000 || unitBudget < 1 || unitBudget > 64)
            throw new IllegalArgumentException("Arena rules exceed the deterministic match budget");
    }
}
