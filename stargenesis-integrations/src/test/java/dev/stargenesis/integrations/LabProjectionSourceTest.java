package dev.stargenesis.integrations;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class LabProjectionSourceTest {
    @Test void snapshotsCopyAgentsAndKeepSessionSeparateFromPersistedWorld() {
        var agents = new ArrayList<>(List.of(new LabProjectionSource.Agent(UUID.randomUUID(), 1, 2)));
        var frame = new LabProjectionSource.Frame("session-2", "persisted-world", 4, 9, 5, agents);
        agents.clear();
        assertEquals(1, frame.agents().size());
        assertThrows(UnsupportedOperationException.class, () -> frame.agents().clear());
        assertNotEquals(frame.sessionId(), frame.worldId());
    }
    @Test void rejectsDuplicateIdsOutOfBoundsAndOversizedFramesBeforeProjection() {
        var id = UUID.randomUUID();
        var agent = new LabProjectionSource.Agent(id, 1, 1);
        for (var agents : List.of(List.<LabProjectionSource.Agent>of(), List.of(agent, agent),
                List.of(new LabProjectionSource.Agent(id, -1, 0)),
                List.of(new LabProjectionSource.Agent(id, 9, 0)), java.util.Collections.nCopies(5, agent)))
            assertThrows(IllegalArgumentException.class, () -> new LabProjectionSource.Frame("s", "w", 0, 9, 5, agents));
        assertThrows(IllegalArgumentException.class, () -> new LabProjectionSource.Frame("s", "w", -1, 9, 5, List.of(agent)));
        assertThrows(IllegalArgumentException.class, () -> new LabProjectionSource.Frame("s", "w", 1, 17, 5, List.of(agent)));
    }
    @Test void rejectsNonFiniteAndOutOfWorldAnchors() {
        var world = UUID.randomUUID();
        for (double x : new double[]{Double.NaN, Double.POSITIVE_INFINITY, -30_000_000})
            assertThrows(IllegalArgumentException.class, () -> new EntityProjection.Anchor(world, x, 64, 0));
    }
}
