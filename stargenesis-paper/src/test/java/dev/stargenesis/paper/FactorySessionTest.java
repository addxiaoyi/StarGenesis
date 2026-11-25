package dev.stargenesis.paper;

import dev.stargenesis.api.sim.AgentId;
import dev.stargenesis.sim.FactoryWorld;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FactorySessionTest {
    private static final List<UUID> MEMBERS = List.of(1, 2, 3, 4).stream()
            .map(index -> new UUID(0, index)).toList();

    @Test
    void commandTransactionsPersistAuctionContributionAndDeliveryState() {
        FactorySession original = FactorySession.start(MEMBERS);
        FactorySession.CommandResult order = original.command(MEMBERS.getFirst(), new String[] {
                "order", "factory:frame", "10", "100", "8", "5"});
        assertTrue(order.changed());
        assertEquals(0, original.checkpoint().orders().size());

        FactorySession current = order.session();
        current = current.command(MEMBERS.get(2), new String[] {"bid", "factory:frame", "1", "20"}).session();
        current = current.command(MEMBERS.get(3), new String[] {"bid", "factory:frame", "1", "35"}).session();
        current = current.command(MEMBERS.get(1), new String[] {"bid", "factory:frame", "1", "20"}).session();
        current = current.command(MEMBERS.getFirst(), new String[] {"bid", "factory:frame", "1", "50"}).session();

        assertEquals(FactoryWorld.OrderStatus.AWARDED, current.checkpoint().orders().getFirst().status());
        current = current.command(MEMBERS.getFirst(), new String[] {"contribute", "factory:frame", "2", "4"}).session();
        current = current.command(MEMBERS.get(2), new String[] {"contribute", "factory:frame", "2", "4"}).session();
        current = current.command(MEMBERS.get(3), new String[] {"contribute", "factory:frame", "2", "4"}).session();

        FactorySession.CommandResult delivered = current.command(MEMBERS.get(1),
                new String[] {"fulfill", "factory:frame"});
        try (FactoryWorld world = FactoryWorld.restore(delivered.session().checkpoint())) {
            AgentId winner = new AgentId(MEMBERS.get(1));
            assertEquals(FactoryWorld.OrderStatus.DELIVERED, world.snapshot().orders().getFirst().status());
            assertEquals(10, world.balance(FactoryWorld.CUSTOMER_ACCOUNT, FactoryWorld.COMPONENT_RESOURCE));
            assertEquals(20, world.balance(FactoryWorld.playerAccount(winner), FactoryWorld.CREDIT_RESOURCE));
            assertEquals(980, world.balance(FactoryWorld.TREASURY_ACCOUNT, FactoryWorld.CREDIT_RESOURCE));
        }
    }

    @Test
    void fulfillmentRetryAfterCheckpointRecoveryDoesNotChangeTheLedgerAgain() {
        FactorySession session = FactorySession.start(MEMBERS);
        session = session.command(MEMBERS.getFirst(), new String[] {
                "order", "factory:frame", "10", "100", "8", "5"}).session();
        session = session.command(MEMBERS.get(2), new String[] {"bid", "factory:frame", "1", "20"}).session();
        session = session.command(MEMBERS.get(3), new String[] {"bid", "factory:frame", "1", "35"}).session();
        session = session.command(MEMBERS.get(1), new String[] {"bid", "factory:frame", "1", "20"}).session();
        session = session.command(MEMBERS.getFirst(), new String[] {"bid", "factory:frame", "1", "50"}).session();
        session = session.command(MEMBERS.getFirst(), new String[] {"contribute", "factory:frame", "2", "4"}).session();
        session = session.command(MEMBERS.get(2), new String[] {"contribute", "factory:frame", "2", "4"}).session();
        session = session.command(MEMBERS.get(3), new String[] {"contribute", "factory:frame", "2", "4"}).session();

        FactorySession.CommandResult firstDelivery = session.command(MEMBERS.get(1),
                new String[] {"fulfill", "factory:frame"});
        FactorySession recovered = FactorySession.restore(firstDelivery.session().checkpoint());
        FactorySession.CommandResult retriedDelivery = recovered.command(MEMBERS.get(1),
                new String[] {"fulfill", "factory:frame"});

        assertFalse(retriedDelivery.changed());
        assertEquals(firstDelivery.message(), retriedDelivery.message());
        assertEquals(firstDelivery.session().checkpoint(), retriedDelivery.session().checkpoint());
    }

    @Test
    void rejectsForeignPlayersAndFailedOperationsLeaveThePriorCheckpointUntouched() {
        FactorySession session = FactorySession.start(MEMBERS);
        var before = session.checkpoint();
        FactorySession initial = session;
        assertThrows(SecurityException.class, () -> initial.command(new UUID(0, 9), new String[] {
                "order", "factory:frame", "1", "1", "1", "1"}));

        session = session.command(MEMBERS.getFirst(), new String[] {
                "order", "factory:frame", "1", "1", "8", "5"}).session();
        var afterOrder = session.checkpoint();
        FactorySession orderSession = session;
        assertThrows(IllegalArgumentException.class, () -> orderSession.command(MEMBERS.getFirst(), new String[] {
                "bid", "factory:frame", "-1", "1"}));
        assertNotEquals(before, afterOrder);
        assertEquals(1, afterOrder.orders().size());
        assertEquals(FactoryWorld.OrderStatus.OPEN, afterOrder.orders().getFirst().status());
    }

    @Test
    void actionSequenceMakesBidRetryIdempotentWithinTheSameTick() {
        FactorySession session = FactorySession.start(MEMBERS);
        session = session.command(MEMBERS.getFirst(), new String[] {
                "order", "factory:frame", "1", "1", "8", "5"}).session();
        FactorySession.CommandResult first = session.command(MEMBERS.get(1),
                new String[] {"bid", "factory:frame", "42", "1"});
        FactorySession.CommandResult retry = first.session().command(MEMBERS.get(1),
                new String[] {"bid", "factory:frame", "42", "1"});
        assertEquals(first.session().checkpoint(), retry.session().checkpoint());
        assertFalse(retry.changed());
        assertThrows(IllegalArgumentException.class, () -> first.session().command(MEMBERS.get(1),
                new String[] {"bid", "factory:frame", "42", "2"}));
    }

    @Test
    void pagedOrderQueriesKeepEveryOrderReachableWithinBoundedResponses() {
        FactorySession session = FactorySession.start(MEMBERS);
        for (int index = 0; index < 17; index++) {
            session = session.command(MEMBERS.getFirst(), new String[] {
                    "order", String.format(java.util.Locale.ROOT, "factory:order-%02d", index),
                    "1", "1", "1", "1"}).session();
        }

        String firstPage = session.command(MEMBERS.getFirst(), new String[] {"orders"}).message();
        String secondPage = session.command(MEMBERS.getFirst(), new String[] {"orders", "2"}).message();
        assertTrue(firstPage.contains("第 1/2 页"));
        assertTrue(firstPage.contains("factory:order-15"));
        assertFalse(firstPage.contains("factory:order-16"));
        assertTrue(firstPage.getBytes(java.nio.charset.StandardCharsets.UTF_8).length
                <= FactoryRelayPacket.MAX_RESPONSE_BYTES);
        assertTrue(secondPage.contains("factory:order-16"));
        FactorySession completed = session;
        assertThrows(IllegalArgumentException.class,
                () -> completed.command(MEMBERS.getFirst(), new String[] {"orders", "3"}));
    }

    @Test
    void relayMembershipCheckMatchesTheFrozenFactoryRoster() {
        FactorySession session = FactorySession.start(MEMBERS);
        assertTrue(session.containsMember(MEMBERS.getFirst()));
        assertFalse(session.containsMember(new UUID(0, 9)));
        assertFalse(session.containsMember(null));
    }

    @Test
    void expectedTickPreventsRetryFromAdvancingTwice() {
        FactorySession session = FactorySession.start(MEMBERS);
        FactorySession.CommandResult advanced = session.command(MEMBERS.getFirst(),
                new String[] {"advance", "0", "3"});
        assertEquals(3, advanced.session().checkpoint().tick());
        assertThrows(IllegalStateException.class, () -> advanced.session().command(MEMBERS.getFirst(),
                new String[] {"advance", "0", "3"}));
    }

    @Test
    void workstationCommandsQueueCompleteAndCollectVirtualComponents() {
        FactorySession session = FactorySession.start(MEMBERS);
        UUID member = MEMBERS.getFirst();
        FactorySession.CommandResult queued = session.command(member, new String[] {
                "station", "queue", "factory:frame", "11", "1"});
        assertTrue(queued.changed());
        assertEquals(1, queued.session().checkpoint().stationJobs().size());
        assertTrue(queued.message().contains("预计 tick 2"));

        FactorySession.CommandResult advanced = queued.session().command(member, new String[] {
                "advance", "0", "2"});
        assertTrue(advanced.changed());
        assertTrue(advanced.session().checkpoint().stationJobs().isEmpty());
        FactorySession.CommandResult collected = advanced.session().command(member, new String[] {
                "station", "collect", "12", "4"});
        assertTrue(collected.changed());
        assertTrue(collected.message().contains("已领取 4 个组件"));

        try (FactoryWorld world = FactoryWorld.restore(collected.session().checkpoint())) {
            assertEquals(28, world.balance(FactoryWorld.playerAccount(new AgentId(member)),
                    FactoryWorld.COMPONENT_RESOURCE));
            assertEquals(0, world.workstation(new AgentId(member)).components());
        }
    }

    @Test
    void workstationCommandsRejectNonMembersAndPersistCancellation() {
        FactorySession session = FactorySession.start(MEMBERS);
        FactorySession initial = session;
        assertThrows(SecurityException.class, () -> initial.command(new UUID(0, 9),
                new String[] {"station", "status"}));
        session = session.command(MEMBERS.get(1), new String[] {
                "station", "queue", "factory:plate", "21", "2"}).session();
        FactorySession.CommandResult cancelled = session.command(MEMBERS.get(1), new String[] {
                "station", "cancel", "22"});
        assertTrue(cancelled.changed());
        assertTrue(cancelled.message().contains("原料已退回"));
        try (FactoryWorld world = FactoryWorld.restore(cancelled.session().checkpoint())) {
            assertNull(world.workstation(new AgentId(MEMBERS.get(1))).job());
            assertEquals(8, world.balance(FactoryWorld.playerAccount(new AgentId(MEMBERS.get(1))),
                    FactoryWorld.ORE_RESOURCE));
        }
    }
}
