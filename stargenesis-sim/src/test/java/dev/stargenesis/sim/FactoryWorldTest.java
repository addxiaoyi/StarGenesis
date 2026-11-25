package dev.stargenesis.sim;

import dev.stargenesis.api.design.DesignId;
import dev.stargenesis.api.sim.AgentId;
import dev.stargenesis.api.sim.ResourceLedger.TransferResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FactoryWorldTest {
    private static final List<AgentId> MEMBERS = List.of(1, 2, 3, 4).stream()
            .map(index -> new AgentId(new UUID(0, index))).toList();
    private static final FactorySettings SETTINGS = new FactorySettings("factory-test", MEMBERS,
            40, 20, 1_000, 8);
    private static final DesignId ORDER = new DesignId("factory:frame-order");

    @Test
    void fourMemberAuctionUsesLowestBidAndStableMemberTieBreak() {
        try (FactoryWorld world = new FactoryWorld(SETTINGS)) {
            var open = world.createOrder(MEMBERS.getFirst(), ORDER, 10, 100, 8, 5);
            assertEquals(FactoryWorld.OrderStatus.OPEN, open.status());

            world.placeBid(MEMBERS.get(2), ORDER, 1, 20);
            world.placeBid(MEMBERS.get(3), ORDER, 1, 35);
            world.placeBid(MEMBERS.get(1), ORDER, 1, 20);
            var awarded = world.placeBid(MEMBERS.getFirst(), ORDER, 1, 50);

            assertEquals(FactoryWorld.OrderStatus.AWARDED, awarded.status());
            assertEquals(MEMBERS.get(1), awarded.winner());
            assertEquals(5, awarded.deliveryDeadline());
            assertThrows(IllegalArgumentException.class,
                    () -> world.placeBid(MEMBERS.getFirst(), ORDER, 1, 51));
        }
    }

    @Test
    void contributionsFulfillmentAndCheckpointRecoveryAreConservingAndIdempotent() {
        try (FactoryWorld original = new FactoryWorld(SETTINGS)) {
            openWinningOrder(original, 10, 100);
            assertEquals(TransferResult.APPLIED, original.contribute(MEMBERS.getFirst(), ORDER, 2, 4));
            assertEquals(TransferResult.APPLIED, original.contribute(MEMBERS.get(2), ORDER, 2, 4));
            assertEquals(TransferResult.APPLIED, original.contribute(MEMBERS.get(3), ORDER, 2, 4));
            assertEquals(TransferResult.ALREADY_APPLIED, original.contribute(MEMBERS.getFirst(), ORDER, 2, 4));

            FactoryCheckpoint checkpoint = original.checkpoint();
            try (FactoryWorld recovered = FactoryWorld.restore(checkpoint)) {
                assertEquals(original.snapshot(), recovered.snapshot());
                assertTrue(original.fulfill(MEMBERS.get(1), ORDER));
                assertTrue(recovered.fulfill(MEMBERS.get(1), ORDER));
                assertTrue(original.fulfill(MEMBERS.get(1), ORDER));
                assertEquals(original.snapshot(), recovered.snapshot());
                assertEquals(10, recovered.balance(FactoryWorld.CUSTOMER_ACCOUNT, FactoryWorld.COMPONENT_RESOURCE));
                assertEquals(20, recovered.balance(FactoryWorld.playerAccount(MEMBERS.get(1)), FactoryWorld.CREDIT_RESOURCE));
                assertEquals(980, recovered.balance(FactoryWorld.TREASURY_ACCOUNT, FactoryWorld.CREDIT_RESOURCE));
                assertEquals(18, recovered.balance(FactoryWorld.playerAccount(MEMBERS.get(3)), FactoryWorld.COMPONENT_RESOURCE));
                assertEquals(FactoryWorld.OrderStatus.DELIVERED, recovered.snapshot().orders().getFirst().status());
            }
        }
    }

    @Test
    void expiredOrderRefundsEscrowAndEveryContributionAfterRecovery() {
        try (FactoryWorld world = new FactoryWorld(SETTINGS)) {
            world.createOrder(MEMBERS.getFirst(), ORDER, 6, 100, 1, 1);
            world.placeBid(MEMBERS.get(2), ORDER, 1, 10);
            world.step();
            world.contribute(MEMBERS.get(2), ORDER, 2, 3);
            FactoryCheckpoint checkpoint = world.checkpoint();

            try (FactoryWorld recovered = FactoryWorld.restore(checkpoint)) {
                world.step();
                recovered.step();
                assertEquals(world.snapshot(), recovered.snapshot());
                assertEquals(FactoryWorld.OrderStatus.FAILED, recovered.snapshot().orders().getFirst().status());
                assertEquals(1_000, recovered.balance(FactoryWorld.TREASURY_ACCOUNT, FactoryWorld.CREDIT_RESOURCE));
                assertEquals(20, recovered.balance(FactoryWorld.playerAccount(MEMBERS.get(2)), FactoryWorld.COMPONENT_RESOURCE));
            }
        }
    }

    @Test
    void emptyAuctionCancelsAndReturnsRewardEscrow() {
        try (FactoryWorld world = new FactoryWorld(SETTINGS)) {
            world.createOrder(MEMBERS.getFirst(), ORDER, 6, 80, 1, 1);
            world.step();
            assertEquals(FactoryWorld.OrderStatus.CANCELLED, world.snapshot().orders().getFirst().status());
            assertEquals(1_000, world.balance(FactoryWorld.TREASURY_ACCOUNT, FactoryWorld.CREDIT_RESOURCE));
        }
    }

    @Test
    void rejectsNonMembersAndCheckpointTampering() {
        AgentId outsider = new AgentId(new UUID(0, 9));
        try (FactoryWorld world = new FactoryWorld(SETTINGS)) {
            assertThrows(SecurityException.class, () -> world.createOrder(outsider, ORDER, 1, 1, 1, 1));
            world.createOrder(MEMBERS.getFirst(), ORDER, 1, 10, 1, 1);
            FactoryCheckpoint checkpoint = world.checkpoint();
            FactoryCheckpoint tampered = new FactoryCheckpoint(checkpoint.settings(), checkpoint.tick(),
                    checkpoint.orders(), checkpoint.ledger(), checkpoint.bidActions(),
                    checkpoint.nextAutomaticSequence(), "0".repeat(64));
            assertThrows(IllegalArgumentException.class, () -> FactoryWorld.restore(tampered));
        }
    }

    @Test
    void settlementAtEvidenceCapacityFailsBeforeAnyTransfer() {
        try (FactoryWorld world = new FactoryWorld(SETTINGS)) {
            world.createOrder(MEMBERS.getFirst(), ORDER, 4, 100, 8, 5);
            for (int index = 0; index < MEMBERS.size(); index++) {
                world.placeBid(MEMBERS.get(index), ORDER, 1, 0);
            }
            world.contribute(MEMBERS.get(0), ORDER, 2, 4);
            world.contribute(MEMBERS.get(1), ORDER, 2, 4);
            world.contribute(MEMBERS.get(2), ORDER, 2, 20);
            for (int attempt = 0; attempt < FactoryWorld.MAX_LEDGER_ATTEMPTS - 5; attempt++) {
                assertEquals(TransferResult.INSUFFICIENT_FUNDS,
                        world.contribute(MEMBERS.get(2), ORDER, 3L + attempt, 1));
            }
            assertTrue(FactoryCheckpointCodec.encode(world.checkpoint()).length
                    <= FactoryCheckpointCodec.MAX_ENCODED_BYTES);
            var before = world.snapshot();
            assertThrows(IllegalStateException.class, () -> world.fulfill(MEMBERS.getFirst(), ORDER));
            assertEquals(before, world.snapshot());
        }
    }

    @Test
    void workstationProductionSurvivesRecoveryAndConservesMaterialMass() {
        try (FactoryWorld world = new FactoryWorld(SETTINGS)) {
            AgentId member = MEMBERS.getFirst();
            assertEquals(8, world.balance(FactoryWorld.playerAccount(member), FactoryWorld.ORE_RESOURCE));
            assertEquals(16, world.balance(FactoryWorld.playerAccount(member), FactoryWorld.POLYMER_RESOURCE));
            long initialMass = materialMass(world);

            DesignId frame = new DesignId("factory:frame");
            assertEquals(FactoryWorld.ProductionActionResult.QUEUED,
                    world.queueProduction(member, 1, frame, 2));
            assertEquals(FactoryWorld.ProductionActionResult.QUEUED,
                    world.queueProduction(member, 1, frame, 2));
            assertEquals(FactoryWorld.ProductionActionResult.STATION_BUSY,
                    world.queueProduction(member, 2, new DesignId("factory:plate"), 1));
            assertEquals(initialMass, materialMass(world));

            FactoryCheckpoint checkpoint = FactoryCheckpointCodec.decode(
                    FactoryCheckpointCodec.encode(world.checkpoint()));
            try (FactoryWorld resumed = FactoryWorld.restore(checkpoint)) {
                assertEquals(world.snapshot(), resumed.snapshot());
                for (int index = 0; index < 4; index++) {
                    world.step();
                    resumed.step();
                }
                assertEquals(world.snapshot(), resumed.snapshot());
                assertNull(resumed.workstation(member).job());
                assertEquals(8, resumed.workstation(member).components());
                assertEquals(initialMass, materialMass(resumed));
                assertEquals(TransferResult.APPLIED, resumed.collectProduction(member, 3, 8));
                assertEquals(TransferResult.ALREADY_APPLIED, resumed.collectProduction(member, 3, 8));
                assertEquals(SETTINGS.startingComponents() + 8, resumed.balance(FactoryWorld.playerAccount(member),
                        FactoryWorld.COMPONENT_RESOURCE));
                assertEquals(0, resumed.workstation(member).components());
                assertEquals(FactoryWorld.ProductionActionResult.QUEUED,
                        resumed.queueProduction(member, 4, new DesignId("factory:plate"), 4));
                for (int index = 0; index < 4; index++) resumed.step();
                assertEquals(12, resumed.workstation(member).components());
                assertEquals(TransferResult.APPLIED, resumed.collectProduction(member, 5, 12));
                assertEquals(SETTINGS.startingComponents() + 20,
                        resumed.balance(FactoryWorld.playerAccount(member), FactoryWorld.COMPONENT_RESOURCE));
                assertEquals(initialMass, materialMass(resumed));
            }
        }
    }

    @Test
    void cancellingProductionReturnsItsEscrowedMaterialsExactlyOnce() {
        try (FactoryWorld world = new FactoryWorld(SETTINGS)) {
            AgentId member = MEMBERS.get(1);
            String inventory = FactoryWorld.playerAccount(member);
            assertEquals(FactoryWorld.ProductionActionResult.QUEUED,
                    world.queueProduction(member, 7, new DesignId("factory:plate"), 3));
            assertEquals(5, world.balance(inventory, FactoryWorld.ORE_RESOURCE));
            assertEquals(13, world.balance(inventory, FactoryWorld.POLYMER_RESOURCE));
            assertEquals(FactoryWorld.ProductionActionResult.CANCELLED,
                    world.cancelProduction(member, 8));
            assertEquals(FactoryWorld.ProductionActionResult.CANCELLED,
                    world.cancelProduction(member, 8));
            assertEquals(8, world.balance(inventory, FactoryWorld.ORE_RESOURCE));
            assertEquals(16, world.balance(inventory, FactoryWorld.POLYMER_RESOURCE));
            assertNull(world.workstation(member).job());
            assertEquals(0, world.workstation(member).ore());
            assertEquals(0, world.workstation(member).polymer());
        }
    }

    @Test
    void fullScenarioDurationSurvivesPeriodicCheckpointRecovery() {
        FactorySettings longRunSettings = new FactorySettings("factory-long-run", MEMBERS,
                72_000, 24, 1_000, FactorySettings.MAX_ORDERS);
        try (FactoryWorld world = new FactoryWorld(longRunSettings)) {
            assertEquals(FactoryWorld.ProductionActionResult.QUEUED,
                    world.queueProduction(MEMBERS.getFirst(), 1, new DesignId("factory:frame"), 2));
            long initialMass = materialMass(world);
            for (long tick = 1; tick <= longRunSettings.maxSteps(); tick++) {
                world.step();
                if (tick % 10_000 != 0 && tick != longRunSettings.maxSteps()) continue;
                FactoryCheckpoint decoded = FactoryCheckpointCodec.decode(
                        FactoryCheckpointCodec.encode(world.checkpoint()));
                try (FactoryWorld recovered = FactoryWorld.restore(decoded)) {
                    assertEquals(world.snapshot(), recovered.snapshot());
                    assertEquals(initialMass, materialMass(recovered));
                }
            }
            assertEquals(longRunSettings.maxSteps(), world.tick());
            assertEquals(8, world.workstation(MEMBERS.getFirst()).components());
        }
    }

    private static long materialMass(FactoryWorld world) {
        return world.snapshot().balances().values().stream().mapToLong(resources ->
                resources.getOrDefault(FactoryWorld.COMPONENT_RESOURCE, 0L)
                        + 2 * resources.getOrDefault(FactoryWorld.ORE_RESOURCE, 0L)
                        + resources.getOrDefault(FactoryWorld.POLYMER_RESOURCE, 0L)).sum();
    }

    private static void openWinningOrder(FactoryWorld world, long components, long reward) {
        world.createOrder(MEMBERS.getFirst(), ORDER, components, reward, 8, 5);
        world.placeBid(MEMBERS.get(2), ORDER, 1, 20);
        world.placeBid(MEMBERS.get(3), ORDER, 1, 35);
        world.placeBid(MEMBERS.get(1), ORDER, 1, 20);
        world.placeBid(MEMBERS.getFirst(), ORDER, 1, 50);
    }
}
