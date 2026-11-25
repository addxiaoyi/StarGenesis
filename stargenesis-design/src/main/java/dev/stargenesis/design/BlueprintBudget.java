package dev.stargenesis.design;

public record BlueprintBudget(int maximumComponents, int maximumConnections, long maximumMass, long maximumEnergy) {
    public BlueprintBudget {
        if (maximumComponents < 1 || maximumComponents > 128 || maximumConnections < 0
                || maximumConnections > 256 || maximumMass < 1 || maximumMass > 1_000_000
                || maximumEnergy < 0 || maximumEnergy > 1_000_000)
            throw new IllegalArgumentException("Blueprint budget outside hard limits");
    }
    public static BlueprintBudget standard() { return new BlueprintBudget(32, 64, 256, 192); }
}
