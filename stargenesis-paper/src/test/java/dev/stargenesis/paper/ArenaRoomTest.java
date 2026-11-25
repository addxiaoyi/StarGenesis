package dev.stargenesis.paper;

import dev.stargenesis.design.ArenaRuleset;
import dev.stargenesis.design.DesignCodec;
import dev.stargenesis.sim.ArenaMatchWorld.Action;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ArenaRoomTest {
    private final ArenaRuleset ruleset = loadStarter();

    @Test
    void checkpointsRestoreSeatsPendingPlayerActionsAndTheExactMatchState() {
        UUID host = uuid(1);
        UUID second = uuid(2);
        UUID third = uuid(3);
        UUID fourth = uuid(4);
        ArenaRoom room = new ArenaRoom(uuid(10), host, ruleset);
        room.join(host, "red:1");
        room.join(second, "red:2");
        room.join(third, "blue:1");
        room.join(fourth, "blue:2");
        room.start(host, 100);
        room.submit(host, 0, Action.WAIT, 100);

        byte[] encoded = ArenaRoomCheckpointCodec.encode(room.checkpoint());
        ArenaRoom.Data decoded = ArenaRoomCheckpointCodec.decode(encoded);
        ArenaRoom restored = ArenaRoom.restore(decoded, ruleset, 200);

        assertEquals(room.snapshot(), restored.snapshot());
        assertEquals(room.occupied(), restored.occupied());
        assertEquals(1, restored.submittedCount());
        assertEquals("red:1", restored.unitFor(host));
        restored.submit(second, 0, Action.WAIT, 300);
        restored.submit(third, 0, Action.WAIT, 400);
        restored.submit(fourth, 0, Action.WAIT, 500);
        assertEquals(1, restored.tick());
        assertEquals(0, restored.submittedCount());
    }

    @Test
    void roomAdmissionActionsAndTurnTimeoutEnforceTheHostAndPlayerSlots() {
        UUID host = uuid(11);
        UUID second = uuid(12);
        UUID third = uuid(15);
        UUID fourth = uuid(16);
        ArenaRoom room = new ArenaRoom(uuid(13), host, ruleset);
        assertThrows(SecurityException.class, () -> room.start(second, 100));
        room.join(host, "red:1");
        assertThrows(IllegalStateException.class, () -> room.start(host, 100));
        room.join(third, "red:2");
        room.join(second, "blue:1");
        room.join(fourth, "blue:2");
        assertThrows(IllegalStateException.class, () -> room.submit(host, 1, Action.WAIT, 100));
        room.start(host, 100);
        assertThrows(SecurityException.class, () -> room.submit(uuid(14), 0, Action.WAIT, 100));
        assertThrows(IllegalArgumentException.class, () -> room.submit(host, 0, Action.WEST, 100));
        room.submit(host, 0, Action.WAIT, 100);
        assertFalse(room.submit(host, 0, Action.WAIT, 101));
        assertFalse(room.due(100 + ArenaRoom.TURN_NANOS - 1));
        assertTrue(room.due(100 + ArenaRoom.TURN_NANOS));
        assertThrows(IllegalStateException.class, () -> room.submit(second, 0, Action.WAIT, 100 + ArenaRoom.TURN_NANOS));
        room.expire(100 + ArenaRoom.TURN_NANOS);
        assertEquals(1, room.tick());
        assertFalse(room.due(100 + 2 * ArenaRoom.TURN_NANOS - 1));
        room.expire(100 + 2 * ArenaRoom.TURN_NANOS);
        assertEquals(2, room.tick());
    }

    @Test
    void spectatorViewContainsPinnedRulesAndABoundedMapViewport() {
        ArenaRoom room = new ArenaRoom(uuid(31), uuid(32), ruleset);
        String view = ArenaMatchService.formatSpectator(room);
        assertTrue(view.startsWith("观战 | genesis:arena_ruleset@1.1.0"));
        assertTrue(view.contains("图例"));
        assertEquals(5, view.lines().skip(1).limit(3).mapToInt(String::length).max().orElseThrow());
    }

    @Test
    void checkpointRejectsTruncationChecksumDamageAndTrailingData() {
        UUID host = uuid(21);
        ArenaRoom room = new ArenaRoom(uuid(22), host, ruleset);
        byte[] encoded = ArenaRoomCheckpointCodec.encode(room.checkpoint());
        assertThrows(IllegalArgumentException.class,
                () -> ArenaRoomCheckpointCodec.decode(java.util.Arrays.copyOf(encoded, encoded.length - 33)));
        byte[] damaged = encoded.clone();
        damaged[4] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> ArenaRoomCheckpointCodec.decode(damaged));
        byte[] withTrailingByte = java.util.Arrays.copyOf(encoded, encoded.length + 1);
        assertThrows(IllegalArgumentException.class,
                () -> ArenaRoomCheckpointCodec.decode(withTrailingByte));
    }

    private static UUID uuid(long value) {
        return new UUID(0, value);
    }

    private static ArenaRuleset loadStarter() {
        try (var input = ArenaRoomTest.class.getResourceAsStream("/examples/arena_ruleset-v2.json")) {
            if (input == null) throw new IllegalStateException("Missing arena fixture");
            return (ArenaRuleset) new DesignCodec().load(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }
}
