package dev.stargenesis.paper;

import dev.stargenesis.sim.ArenaMatchWorld;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ArenaMatchServiceTest {
    @Test
    void parsesOnlyTheBoundedCardinalArenaActions() {
        assertEquals(ArenaMatchWorld.Action.NORTH, ArenaMatchService.parseAction("north"));
        assertEquals(ArenaMatchWorld.Action.WAIT, ArenaMatchService.parseAction("WAIT"));
        assertThrows(IllegalArgumentException.class, () -> ArenaMatchService.parseAction("teleport"));
        assertThrows(IllegalArgumentException.class, () -> ArenaMatchService.parseAction(""));
    }
}
