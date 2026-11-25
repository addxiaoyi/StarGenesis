package dev.stargenesis.paper;

import dev.stargenesis.api.sim.SimulationBudget;
import dev.stargenesis.api.design.DesignId;
import dev.stargenesis.api.design.DesignMetadata;
import dev.stargenesis.api.design.DesignPermissions;
import dev.stargenesis.api.sim.AgentId;
import dev.stargenesis.design.Program;
import dev.stargenesis.design.DesignCodec;
import dev.stargenesis.sim.GridWorldSnapshot;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class LaboratorySessionTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000123");

    @Test
    void playerCheckpointSurvivesSessionRecreationAndActionsConserveResources() {
        LaboratorySession first = LaboratorySession.start(PLAYER, SimulationBudget.defaults(), 100);
        long initialTotal = total(first.snapshot());
        first.act("gather", Map.of());

        LaboratorySession resumed = LaboratorySession.resume(PLAYER, first.encodeCheckpoint(), SimulationBudget.defaults(), 100);
        assertEquals(first.snapshot(), resumed.snapshot());
        resumed.act("deposit", Map.of("amount", 1L));
        assertEquals(initialTotal, total(resumed.snapshot()));
        assertEquals(1, resumed.snapshot().sharedStock());
    }

    @Test
    void checkpointCannotBeLoadedForAnotherPlayerOrDifferentServerBudget() {
        LaboratorySession session = LaboratorySession.start(PLAYER, SimulationBudget.defaults(), 100);
        byte[] checkpoint = session.encodeCheckpoint();
        assertThrows(IllegalArgumentException.class, () -> LaboratorySession.resume(
                UUID.fromString("00000000-0000-0000-0000-000000000124"), checkpoint, SimulationBudget.defaults(), 100));
        assertThrows(IllegalArgumentException.class, () -> LaboratorySession.resume(PLAYER, checkpoint,
                new SimulationBudget(64, 8, 4, 1000, 8, 32), 100));
        assertThrows(IllegalArgumentException.class,
                () -> LaboratorySession.resume(PLAYER, new byte[] {1, 2}, SimulationBudget.defaults(), 100));
    }

    @Test
    void boundedProgramCompilesRunsAgainstPlayerSnapshotAndPersistsItsAction() {
        LaboratorySession session = LaboratorySession.start(PLAYER, SimulationBudget.defaults(), 100);
        Program program = new Program(new DesignMetadata(new DesignId("genesis:test-program"), 1, "1.0.0",
                PLAYER, "test:program", List.of(), "0".repeat(64), new DesignPermissions(true, true, Set.of())),
                List.of(new Program.Node("resource", Program.Operation.SENSE, Program.ValueType.NONE,
                                Program.ValueType.RESOURCE, Map.of("field", "resource_here")),
                        new Program.Node("harvest", Program.Operation.HARVEST, Program.ValueType.RESOURCE,
                                Program.ValueType.NONE, Map.of())),
                List.of(new Program.Edge("resource", "harvest")), 16);
        DesignCodec codec = new DesignCodec();
        program = (Program) codec.load(codec.writeEdited(program, PLAYER));

        LaboratorySession.ProgramRun run = session.run(program);

        assertEquals("COMPLETED", run.execution().status());
        assertEquals("gather", run.execution().commands().getFirst().action());
        assertNotNull(run.step());
        assertEquals(1, session.snapshot().tick());
        assertEquals(1, session.snapshot().agents().getFirst().inventory());
        LaboratorySession resumed = LaboratorySession.resume(PLAYER, session.encodeCheckpoint(), SimulationBudget.defaults(), 100);
        assertEquals(session.snapshot(), resumed.snapshot());
    }

    @Test
    void playerReplayCheckpointRetainsEveryActionAndRejectsTamperedCurrentState() {
        LaboratorySession session = LaboratorySession.start(PLAYER, SimulationBudget.defaults(), 100);
        session.act("gather", Map.of());
        session.act("deposit", Map.of("amount", 1L));

        byte[] encoded = session.encodeCheckpoint();
        LaboratorySession restored = LaboratorySession.resume(PLAYER, encoded, SimulationBudget.defaults(), 100);
        assertEquals(session.snapshot(), restored.snapshot());
        assertEquals(2, restored.snapshot().tick());
        assertEquals(1, restored.snapshot().sharedStock());
        byte[] damaged = encoded.clone();
        damaged[damaged.length - 1] ^= 1;
        assertThrows(IllegalArgumentException.class,
                () -> LaboratorySession.resume(PLAYER, damaged, SimulationBudget.defaults(), 100));
    }

    private static long total(GridWorldSnapshot snapshot) {
        return snapshot.cells().stream().mapToLong(GridWorldSnapshot.Cell::resourceUnits).sum()
                + snapshot.agents().stream().mapToLong(GridWorldSnapshot.Agent::inventory).sum()
                + snapshot.sharedStock();
    }
}
