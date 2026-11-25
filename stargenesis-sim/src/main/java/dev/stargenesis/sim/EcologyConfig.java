package dev.stargenesis.sim;

/** Hard limits and finite initial matter for one closed ecology tank. */
public record EcologyConfig(long initialMatter, long initialDetritus, int maxOrganisms,
                            int maxGenerations, int maxTicks, int maxBirths, int maxLedgerAttempts) {
    public static final long INITIAL_ORGANISM_MATTER = 14;
    public static final int INITIAL_POPULATION = 7;

    public EcologyConfig {
        long minimumLedgerAttempts = Math.multiplyExact(Math.multiplyExact((long) maxTicks, maxOrganisms), 2);
        if (initialMatter < INITIAL_ORGANISM_MATTER || initialMatter > 100_000
                || initialDetritus < 0 || initialDetritus > initialMatter - INITIAL_ORGANISM_MATTER
                || maxOrganisms < INITIAL_POPULATION || maxOrganisms > 32
                || maxGenerations < 0 || maxGenerations > 8
                || maxTicks < 1 || maxTicks > 500
                || maxBirths < 0 || maxBirths > 2_048
                || maxLedgerAttempts < minimumLedgerAttempts || maxLedgerAttempts > 100_000)
            throw new IllegalArgumentException("Ecology configuration exceeds its finite budget");
    }

    public static EcologyConfig defaults() {
        return new EcologyConfig(1_000, 86, 32, 8, 500, 512, 50_000);
    }
}
