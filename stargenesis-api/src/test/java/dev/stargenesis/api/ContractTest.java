package dev.stargenesis.api;
import dev.stargenesis.api.design.*;
import dev.stargenesis.api.sim.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ContractTest {
    @Test void idsRejectPathsAndUnboundedValues() {
        assertEquals("public:starter", new DesignId("public:starter").value());
        for (String id : List.of("../escape", "x:../escape", "X:a", "x:")) assertThrows(IllegalArgumentException.class, () -> new DesignId(id));
        assertEquals("sg:v1:lab-1:2:3", new EvidenceId("lab-1", 2, 3).toString());
        assertThrows(IllegalArgumentException.class, () -> new EvidenceId("a:b", 0, 0));
    }
    @Test void commandOrderAndDuplicateRejection() {
        AgentId agent = new AgentId(new UUID(0, 1));
        var a = new StepInput.Command(agent, 2, "move", Map.of());
        var b = new StepInput.Command(agent, 1, "stop", Map.of());
        assertEquals(List.of(b, a), new StepInput(0, List.of(a, b)).commands());
        assertThrows(IllegalArgumentException.class, () -> new StepInput(0, List.of(a, a)));
    }
    @Test void metadataAndCollectionsAreImmutable() {
        assertDoesNotThrow(() -> DesignMetadata.requireVersion("1.0.0"));
        var source = new HashSet<UUID>(); source.add(new UUID(0, 1));
        var permissions = new DesignPermissions(true, true, source); source.clear();
        assertEquals(1, permissions.editors().size());
        assertThrows(UnsupportedOperationException.class, () -> permissions.editors().clear());
        assertThrows(IllegalArgumentException.class, () -> new DesignMetadata(new DesignId("public:x"), 1, "latest", UUID.randomUUID(), "local", List.of(), "", permissions));
    }
    @Test void runtimeBudgetsHaveHardCeilings() {
        assertEquals(128, SimulationBudget.defaults().nodesPerAgentStep());
        assertThrows(IllegalArgumentException.class, () -> new SimulationBudget(1, Integer.MAX_VALUE, 0, 1, 1, 0));
    }
}
