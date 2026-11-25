package dev.stargenesis.design;

import dev.stargenesis.api.design.DesignId;
import dev.stargenesis.api.design.DesignMetadata;
import dev.stargenesis.api.design.DesignPermissions;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class BlueprintAssemblySessionTest {
    private static final UUID OWNER = new UUID(0, 17);
    private static final UUID STRANGER = new UUID(0, 18);
    private final ModuleCatalog catalog = ModuleCatalog.standard();
    private final BlueprintBudget budget = BlueprintBudget.standard();
    private final DesignCodec codec = new DesignCodec();

    @Test void assemblyIsDeterministicConnectedAndResumable() {
        BlueprintAssemblySession session = BlueprintAssemblySession.start(OWNER, blueprint(), catalog, budget);
        assertEquals("core", session.nextComponent().id());
        assertThrows(IllegalArgumentException.class, () -> session.placeNextAt(1, 0, 0));
        assertEquals(0, session.placedCount());
        session.placeNextAt(0, 0, 0);
        assertEquals("collector", session.nextComponent().id());
        session.placeNextAt(1, 1, 0);
        assertEquals("frame", session.nextComponent().id());
        session.placeNextAt(0, 1, 0);
        assertTrue(session.complete());
        assertEquals(3, session.completedConnections().size());
        BlueprintAssemblySession resumed = BlueprintAssemblySession.resume(OWNER, session.encodeCheckpoint(codec), codec, catalog, budget);
        assertTrue(resumed.complete());
        assertEquals(session.placedComponents(), resumed.placedComponents());
    }

    @Test void checkpointRejectsAnotherOwnerTamperingAndMalformedBytes() {
        BlueprintAssemblySession session = BlueprintAssemblySession.start(OWNER, blueprint(), catalog, budget);
        session.placeNextAt(0, 0, 0);
        byte[] checkpoint = session.encodeCheckpoint(codec);
        assertThrows(SecurityException.class, () -> BlueprintAssemblySession.resume(STRANGER, checkpoint, codec, catalog, budget));
        byte[] damaged = checkpoint.clone();
        damaged[damaged.length / 2] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> BlueprintAssemblySession.resume(OWNER, damaged, codec, catalog, budget));
        assertThrows(IllegalArgumentException.class, () -> BlueprintAssemblySession.resume(OWNER, new byte[] {1,2,3}, codec, catalog, budget));
    }

    @Test void privateBlueprintCanOnlyBeAssembledByItsOwner() {
        Blueprint privateBlueprint = new Blueprint(new DesignMetadata(new DesignId("genesis:private-assembly"), 1, "1.0.0",
                OWNER, "test:private", List.of(), "0".repeat(64), new DesignPermissions(false, false, Set.of())),
                List.of(component("core", ModuleCatalog.CORE_TYPE, 0, 0), component("collector", "genesis:collector", 1, 0)),
                List.of(new Blueprint.Connection("core", "collector")));
        Blueprint signedPrivateBlueprint = (Blueprint) codec.load(codec.writeEdited(privateBlueprint, OWNER));
        assertDoesNotThrow(() -> BlueprintAssemblySession.start(OWNER, signedPrivateBlueprint, catalog, budget));
        assertThrows(SecurityException.class, () -> BlueprintAssemblySession.start(STRANGER, signedPrivateBlueprint, catalog, budget));
    }

    @Test void completedAssemblyIsTerminal() {
        Blueprint onlyCore = new Blueprint(new DesignMetadata(new DesignId("genesis:single-core"), 1, "1.0.0", OWNER,
                "test:private", List.of(), "0".repeat(64), new DesignPermissions(false, false, Set.of())),
                List.of(component("core", ModuleCatalog.CORE_TYPE, 0, 0)), List.of());
        onlyCore = (Blueprint) codec.load(codec.writeEdited(onlyCore, OWNER));
        BlueprintAssemblySession session = BlueprintAssemblySession.start(OWNER, onlyCore, catalog, budget);
        session.placeNextAt(0, 0, 0);
        assertTrue(session.complete());
        assertNull(session.nextComponent());
        assertThrows(IllegalStateException.class, () -> session.placeNextAt(0, 0, 0));
    }

    private static Blueprint blueprint() {
        Blueprint blueprint = new Blueprint(new DesignMetadata(new DesignId("genesis:assembly-test"), 1, "1.0.0", OWNER, "test:fixture",
                List.of(), "0".repeat(64), new DesignPermissions(false, false, Set.of())),
                List.of(component("collector", "genesis:collector", 1, 1), component("core", ModuleCatalog.CORE_TYPE, 0, 0),
                        component("frame", "genesis:frame", 0, 1)),
                List.of(new Blueprint.Connection("core", "collector"), new Blueprint.Connection("core", "frame"),
                        new Blueprint.Connection("frame", "collector")));
        return (Blueprint) new DesignCodec().load(new DesignCodec().writeEdited(blueprint, OWNER));
    }
    private static Blueprint.Component component(String id, String type, int x, int y) {
        return new Blueprint.Component(id, type, x, y, 0, 0);
    }
}
