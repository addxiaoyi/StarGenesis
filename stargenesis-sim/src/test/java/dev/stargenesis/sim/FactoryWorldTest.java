package dev.stargenesis.sim;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FactoryWorldTest {
    private static final List<UUID> PLAYERS = List.of(
            new UUID(0, 1001), new UUID(0, 1002), new UUID(0, 1003), new UUID(0, 1004));

    @Test void fourSeatAuctionAndFaultedOrderCompensateAllEscrow() {
        FactoryConfig config = config(1_000);
        try (FactoryWorld world = new FactoryWorld("factory-00000000000000000000000000000001", PLAYERS, 99, config)) {
            auction(world);
            FactoryView auction = world.view();
            assertEquals("SETTLED", auction.auction().state());
            assertEquals("org_ember", auction.auction().winner());
            assertEquals(15, auction.auction().winningBid());
            assertEquals(16, auction.organizations().get("org_ember").ore());
            assertEquals(85, auction.organizations().get("org_ember").credits());

            world.step(new FactoryInput(world.tick(), new FactoryAction(PLAYERS.get(2),
                    FactoryAction.Type.PLACE_ORDER, "press_a", 2)));
            assertEquals(12, world.view().organizations().get("org_ember").ore());
            assertEquals(79, world.view().organizations().get("org_ember").credits());

            world.step(new FactoryInput(world.tick(), FactoryAction.pass(PLAYERS.get(3))));

            FactoryView recovered = world.view();
            assertEquals(1, recovered.recoveredOrders());
            assertEquals("FAILED_RECOVERED", recovered.orders().getFirst().state());
            assertEquals("DETERMINISTIC_FACTORY_FAULT", recovered.orders().getFirst().failure());
            assertEquals(16, recovered.organizations().get("org_ember").ore());
            assertEquals(85, recovered.organizations().get("org_ember").credits());
            assertEquals(0, recovered.organizations().get("org_ember").parts());
            assertEquals(0, total(world, "consumed_ore", FactoryWorld.ORE));
            assertEquals(16, total(world, FactoryWorld.ORE));
            assertEquals(16, total(world, FactoryWorld.PARTS));
            assertEquals(200, total(world, FactoryWorld.CREDITS));
            List<String> recoveryEvidence = world.replay().frames().stream().flatMap(frame -> frame.events().stream())
                    .map(event -> event.type()).toList();
            assertTrue(recoveryEvidence.contains("factory.order_partial_production"));
            assertTrue(recoveryEvidence.contains("factory.compensation_consumed_ore_refunded"));
            assertTrue(recoveryEvidence.contains("factory.compensation_completed"));
        }
    }

    @Test void completedOrderConsumesReservedInputsAndDeliversFactoryParts() {
        try (FactoryWorld world = new FactoryWorld("factory-00000000000000000000000000000002", PLAYERS, 7,
                config(0))) {
            auction(world);
            world.step(new FactoryInput(world.tick(), new FactoryAction(PLAYERS.get(2),
                    FactoryAction.Type.PLACE_ORDER, "press_b", 2)));
            world.step(new FactoryInput(world.tick(), FactoryAction.pass(PLAYERS.get(3))));

            FactoryView completed = world.view();
            assertEquals(1, completed.completedOrders());
            assertEquals(0, completed.recoveredOrders());
            assertEquals("COMPLETED", completed.orders().getFirst().state());
            assertEquals(2, completed.producedParts());
            assertEquals(12, completed.organizations().get("org_ember").ore());
            assertEquals(79, completed.organizations().get("org_ember").credits());
            assertEquals(2, completed.organizations().get("org_ember").parts());
            assertEquals(4, total(world, "consumed_ore", FactoryWorld.ORE));
            assertEquals(21, total(world, "treasury", FactoryWorld.CREDITS));
            assertEquals(200, total(world, FactoryWorld.CREDITS));
        }
    }

    @Test void fourSeatReplayReproducesAuctionOrdersRecoveryAndRejectsTampering() {
        FactoryReplay replay;
        FactoryView expected;
        try (FactoryWorld world = new FactoryWorld("factory-00000000000000000000000000000003", PLAYERS, 45,
                config(1_000))) {
            auction(world);
            world.step(new FactoryInput(world.tick(), new FactoryAction(PLAYERS.get(2),
                    FactoryAction.Type.PLACE_ORDER, "press_c", 1)));
            world.step(new FactoryInput(world.tick(), FactoryAction.pass(PLAYERS.get(3))));
            replay = world.replay();
            expected = world.view();
        }

        assertTrue(FactoryReplayVerifier.verify(replay).passed());
        try (FactoryWorld restored = FactoryWorld.restore(replay)) {
            assertEquals(expected, restored.view());
        }

        FactoryReplay.Frame first = replay.frames().getFirst();
        FactoryReplay.Frame tamperedFrame = new FactoryReplay.Frame(first.input(), "0".repeat(64),
                first.events(), first.eventHash());
        FactoryReplay tampered = new FactoryReplay(replay.schemaVersion(), replay.engineVersion(), replay.matchId(),
                replay.participants(), replay.seed(), replay.config(), replay.initialStateHash(),
                java.util.stream.Stream.concat(java.util.stream.Stream.of(tamperedFrame),
                        replay.frames().stream().skip(1)).toList());
        assertFalse(FactoryReplayVerifier.verify(tampered).passed());
        assertThrows(IllegalArgumentException.class, () -> FactoryWorld.restore(tampered));
    }

    @Test void invalidOrderAndNonParticipantActionsFailWithoutChangingTheWorld() {
        try (FactoryWorld world = new FactoryWorld("factory-00000000000000000000000000000004", PLAYERS, 1,
                config(0))) {
            String before = world.stateHash();
            assertThrows(IllegalStateException.class, () -> world.step(new FactoryInput(0,
                    new FactoryAction(PLAYERS.get(0), FactoryAction.Type.PLACE_ORDER, "early", 1))));
            assertThrows(SecurityException.class, () -> world.step(new FactoryInput(0,
                    new FactoryAction(new UUID(0, 1999), FactoryAction.Type.PASS, "pass", 0))));
            assertEquals(0, world.tick());
            assertEquals(before, world.stateHash());
        }
    }

    @Test void auctionTieBreakIsStableAndNoSaleKeepsTheLotInThePool() {
        try (FactoryWorld tied = new FactoryWorld("factory-00000000000000000000000000000005", PLAYERS, 2,
                config(0))) {
            tied.step(new FactoryInput(0, new FactoryAction(PLAYERS.get(0), FactoryAction.Type.BID, "ore_1", 10)));
            tied.step(new FactoryInput(1, new FactoryAction(PLAYERS.get(2), FactoryAction.Type.BID, "ore_1", 10)));
            tied.step(new FactoryInput(2, FactoryAction.pass(PLAYERS.get(1))));
            tied.step(new FactoryInput(3, FactoryAction.pass(PLAYERS.get(3))));
            assertEquals("org_cedar", tied.view().auction().winner());
            assertEquals(16, tied.view().organizations().get("org_cedar").ore());
            assertEquals(100, tied.view().organizations().get("org_ember").credits());
            assertEquals(0, tied.view().organizations().get("org_ember").ore());
        }

        try (FactoryWorld noSale = new FactoryWorld("factory-00000000000000000000000000000006", PLAYERS, 3,
                config(0))) {
            for (int tick = 0; tick < 4; tick++)
                noSale.step(new FactoryInput(tick, FactoryAction.pass(PLAYERS.get(tick))));
            assertEquals("NO_SALE", noSale.view().auction().state());
            assertEquals(16, total(noSale, FactoryWorld.ORE));
            assertEquals(200, total(noSale, FactoryWorld.CREDITS));
            assertEquals(100, noSale.view().organizations().get("org_cedar").credits());
            assertEquals(100, noSale.view().organizations().get("org_ember").credits());
        }
    }

    private static void auction(FactoryWorld world) {
        world.step(new FactoryInput(0, new FactoryAction(PLAYERS.get(0), FactoryAction.Type.BID, "ore_1", 10)));
        world.step(new FactoryInput(1, new FactoryAction(PLAYERS.get(2), FactoryAction.Type.BID, "ore_1", 15)));
        world.step(new FactoryInput(2, FactoryAction.pass(PLAYERS.get(1))));
        world.step(new FactoryInput(3, FactoryAction.pass(PLAYERS.get(3))));
    }

    private static FactoryConfig config(int faultPermille) {
        return new FactoryConfig(100, 16, 16, 4, 2, 30, 16, faultPermille, 5_000);
    }

    private static long total(FactoryWorld world, String resource) {
        return world.ledgerSnapshot().values().stream().mapToLong(account -> account.getOrDefault(resource, 0L)).sum();
    }

    private static long total(FactoryWorld world, String account, String resource) {
        return world.ledgerSnapshot().getOrDefault(account, java.util.Map.of()).getOrDefault(resource, 0L);
    }
}
