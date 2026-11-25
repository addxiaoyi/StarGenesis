package dev.stargenesis.sim;

public record EcologyStatistics(int population, int speciesCount, long meanEnergy,
                                int meanAssimilationPermille, long births, long deaths,
                                long foodConsumed, long decomposed, long conservedMatter,
                                long availableEnergy, long energyInput, long organismEnergy,
                                long heatReleased, long conservedEnergy) {
    public EcologyStatistics {
        if (population < 0 || speciesCount < 0 || meanEnergy < 0 || meanAssimilationPermille < 0
                || births < 0 || deaths < 0 || foodConsumed < 0 || decomposed < 0 || conservedMatter < 0
                || availableEnergy < 0 || energyInput < 0 || organismEnergy < 0 || heatReleased < 0
                || conservedEnergy < 0) {
            throw new IllegalArgumentException("Invalid ecology statistics");
        }
        if (speciesCount > population || meanAssimilationPermille > 4_000) {
            throw new IllegalArgumentException("Ecology statistics exceed population bounds");
        }
        long accountedEnergy;
        try {
            accountedEnergy = Math.addExact(Math.addExact(availableEnergy, organismEnergy), heatReleased);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Ecology energy ledger overflows", overflow);
        }
        if (accountedEnergy != conservedEnergy) throw new IllegalArgumentException("Ecology energy is not conserved");
    }
}
