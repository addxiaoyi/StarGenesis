package dev.stargenesis.sim;

/** Finite budgets for a four-seat simulated organization factory. */
public record FactoryConfig(long initialCreditsPerOrganization, long auctionOre,
                            long initialFactoryParts, int auctionCloseTick, int orderDurationTicks,
                            int maxTicks, int maxOrders, int faultPermille, int maxLedgerAttempts) {
    public FactoryConfig {
        long minimumAttempts = Math.multiplyExact((long) maxTicks, 64L);
        if (initialCreditsPerOrganization < 1 || initialCreditsPerOrganization > 100_000
                || auctionOre < 1 || auctionOre > 128 || initialFactoryParts < 1 || initialFactoryParts > 128
                || auctionCloseTick < 1 || auctionCloseTick > 16
                || orderDurationTicks < 1 || orderDurationTicks > 16
                || maxTicks < auctionCloseTick + orderDurationTicks || maxTicks > 200
                || maxOrders < 1 || maxOrders > 64 || faultPermille < 0 || faultPermille > 1_000
                || maxLedgerAttempts < minimumAttempts || maxLedgerAttempts > 100_000)
            throw new IllegalArgumentException("Factory configuration exceeds its finite budget");
    }

    public static FactoryConfig defaults() {
        return new FactoryConfig(100, 16, 16, 4, 2, 100, 64, 200, 20_000);
    }
}
