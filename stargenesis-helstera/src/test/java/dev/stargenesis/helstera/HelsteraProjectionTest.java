package dev.stargenesis.helstera;

import dev.helstera.api.Helstera;
import dev.helstera.api.Vec3;
import dev.helstera.api.instance.*;
import dev.helstera.api.model.*;
import dev.stargenesis.integrations.*;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.*;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.*;

/** Faults exercise the real compiled (patched) API contract; server ownership is checked separately on Paper. */
class HelsteraProjectionTest {
    private static final InstalledPlugin ENABLED = new InstalledPlugin("helsteraMobs", "1.0.0", true);
    private final UUID owner = UUID.randomUUID(), worldId = UUID.randomUUID(), first = UUID.randomUUID(), second = UUID.randomUUID();
    private boolean primary = true, paper = true, supported = true, loaded = true, chunks = true, border = true;
    private InstalledPlugin provider = ENABLED;
    private int boneCount = 1, spawned, createdFailureAt, unknownFailureAt, cleanupFailures, moves;
    private boolean failMove;
    private double pivot = 0;
    private Runnable duringSpawn = () -> {};
    private final List<SpawnOptions> options = new ArrayList<>();
    private final Map<Integer, Instance> created = new LinkedHashMap<>();
    private final Map<Integer, Instance> active = new LinkedHashMap<>();
    private final class Instance {
        final int id;
        boolean valid = true;
        int cleanups;
        Location location;
        final ModelInstance handle;
        Instance(int id, Location at) {
            this.id = id; location = at.clone();
            handle = proxy(ModelInstance.class, this::invoke);
        }
        private Object invoke(String name, Object[] args) { return switch (name) {
            case "instanceId" -> id;
            case "isValid" -> valid;
            case "baseEntity" -> Optional.empty();
            case "teleport" -> {
                moves++;
                if (failMove) throw new IllegalStateException("move failure");
                location = ((Location) args[0]).clone();
                yield null;
            }
            case "despawn" -> {
                cleanups++;
                valid = false;
                if (cleanupFailures-- > 0) throw new IllegalStateException("cleanup failure");
                yield null;
            }
            default -> throw new AssertionError(name);
        }; }
    }
    private ModelDefinition model = model();
    private final ModelRegistry registry = proxy(ModelRegistry.class, (name, args) -> switch (name) {
        case "count" -> loaded ? 1 : 0;
        case "get" -> loaded && args[0].equals("probe") ? Optional.of(model) : Optional.empty();
        default -> throw new AssertionError(name);
    });
    private InstanceManager manager = manager();
    private final Helstera root = proxy(Helstera.class, (name, args) -> switch (name) {
        case "apiVersion" -> "1.0.0";
        case "getInstanceManager" -> manager;
        case "getModelRegistry" -> registry;
        default -> throw new AssertionError(name);
    });
    private final World world = proxy(World.class, (name, args) -> switch (name) {
        case "getUID" -> worldId;
        case "getMinHeight" -> -64;
        case "getMaxHeight" -> 320;
        case "isChunkLoaded" -> chunks;
        case "getWorldBorder" -> proxy(WorldBorder.class, (n, a) -> { assertEquals("isInside", n); return border; });
        default -> throw new AssertionError("Unexpected world operation: " + name);
    });
    private final HelsteraModelCatalog catalog = new HelsteraModelCatalog(() -> primary, () -> paper,
            () -> provider, () -> Optional.of(root));
    private final HelsteraProjection projection = new HelsteraProjection(() -> primary, catalog,
            () -> Optional.of(root), id -> id.equals(worldId) ? world : null);

    private InstanceManager manager() {
        return proxy(InstanceManager.class, (name, args) -> switch (name) {
            case "supportsOwnedVisualCleanup" -> supported;
            case "spawn" -> {
                spawned++;
                options.add((SpawnOptions) args[2]);
                var instance = new Instance(spawned, (Location) args[1]);
                created.put(instance.id, instance);
                duringSpawn.run();
                if (spawned == unknownFailureAt) throw new IllegalStateException("untyped provider failure");
                if (spawned == createdFailureAt) {
                    instance.valid = false;
                    throw new ModelCreationException(instance.handle, new IllegalStateException("creation failure"));
                }
                active.put(instance.id, instance);
                yield instance.handle;
            }
            case "despawn" -> active.remove((Integer) args[0]) != null;
            default -> throw new AssertionError(name);
        });
    }
    private ModelDefinition model() {
        return proxy(ModelDefinition.class, (name, args) -> switch (name) {
            case "id" -> "probe";
            case "scale" -> 1d;
            case "hitbox" -> new ModelHitbox(1, 2);
            case "animationNames" -> List.of("idle");
            case "allBones" -> java.util.stream.IntStream.range(0, boneCount).mapToObj(index -> proxy(Bone.class, (n, a) -> switch (n) {
                case "name" -> "bone" + index;
                case "parent" -> null;
                case "pivot" -> new Vec3(pivot, 0, 0);
                default -> throw new AssertionError(n);
            })).toList();
            default -> throw new AssertionError(name);
        });
    }
    private EntityProjection.Anchor anchor() { return new EntityProjection.Anchor(worldId, 1, 70, 1); }
    private LabProjectionSource.Frame frame(long tick, int x) {
        return new LabProjectionSource.Frame("session", "lab-world", tick, 9, 5,
                List.of(new LabProjectionSource.Agent(first, x, 1), new LabProjectionSource.Agent(second, 3, 2)));
    }
    private void start() { projection.start(owner, "probe", anchor(), frame(0, 0)); }

    @Test void stableAgentMappingUsesPassiveOptionsAndNeverDuplicatesOnRepeatedSnapshot() {
        start();
        assertEquals(4, projection.reservedEntities());
        assertEquals(Set.of("entity.project", "model.inspect"), projection.verify(ENABLED).activeCapabilities());
        for (var option : options) {
            assertFalse(option.persistent()); assertFalse(option.spawnHitbox()); assertFalse(option.collidable());
            assertFalse(option.automaticAnimation()); assertFalse(option.gravity()); assertFalse(option.interactable());
            assertTrue(option.invulnerable()); assertTrue(option.silent()); assertTrue(option.noAi());
            assertNull(option.aiProfile()); assertFalse(option.showName());
        }
        projection.update(owner, frame(0, 0));
        assertEquals(0, moves);
        var next = frame(1, 2);
        projection.update(owner, new LabProjectionSource.Frame(next.sessionId(), next.worldId(), next.tick(), next.width(), next.height(),
                List.of(next.agents().get(1), next.agents().get(0))));
        assertEquals(3, created.get(1).location.getX());
        assertEquals(4, created.get(2).location.getX());
        assertEquals(2, spawned);
        projection.stop(owner);
        projection.stop(owner);
        assertTrue(active.isEmpty()); assertEquals(0, projection.reservedEntities());
    }

    @Test void creationFailureCleansBothCompletedAndPartialInstances() {
        createdFailureAt = 2;
        assertThrows(ModelCreationException.class, this::start);
        assertEquals(2, created.size());
        for (var instance : created.values()) { assertFalse(instance.valid); assertEquals(1, instance.cleanups); }
        assertTrue(active.isEmpty()); assertEquals(0, projection.reservedEntities());
    }
    @Test void cleanupFailureRetainsReservationBlocksCreationAndRetriesOnlyRemainingHandles() {
        createdFailureAt = 2; cleanupFailures = 1;
        assertThrows(ModelCreationException.class, this::start);
        assertTrue(projection.status(owner).orElseThrow().cleanupPending());
        assertEquals(4, projection.reservedEntities());
        assertThrows(IllegalStateException.class, () -> projection.start(UUID.randomUUID(), "probe", anchor(), frame(0, 0)));
        assertThrows(IllegalStateException.class, () -> projection.verify(ENABLED));
        projection.stop(owner);
        assertEquals(0, projection.reservedEntities());
        assertEquals(2, created.get(1).cleanups); assertEquals(1, created.get(2).cleanups);
    }
    @Test void untypedCreationFailureCannotBeMistakenForNoSideEffect() {
        unknownFailureAt = 1;
        assertThrows(IllegalStateException.class, this::start);
        assertEquals(4, projection.reservedEntities());
        assertThrows(IllegalStateException.class, () -> projection.stop(owner));
        assertThrows(IllegalStateException.class, projection::close);
        assertTrue(projection.status(owner).orElseThrow().cleanupPending());
    }
    @Test void wrongThreadServerProviderOrCleanupContractNeverCreatesAnything() {
        primary = false; assertThrows(IllegalStateException.class, this::start);
        primary = true; paper = false; assertThrows(IllegalStateException.class, this::start);
        paper = true; supported = false; assertThrows(IllegalStateException.class, this::start);
        supported = true; provider = new InstalledPlugin("helsteraMobs", "1.0.0", false);
        assertThrows(IllegalStateException.class, this::start);
        assertEquals(0, spawned);
    }
    @Test void unavailableChunksWorldBorderAndOversizedBonesAreRejectedBeforeCreation() {
        chunks = false; assertThrows(IllegalStateException.class, this::start);
        chunks = true; border = false; assertThrows(IllegalStateException.class, this::start);
        border = true; boneCount = 17; assertThrows(IllegalArgumentException.class, this::start);
        boneCount = 1; pivot = Double.NaN; assertThrows(IllegalArgumentException.class, this::start);
        pivot = 1000; assertThrows(IllegalArgumentException.class, this::start);
        assertEquals(0, spawned); assertEquals(0, projection.reservedEntities());
    }
    @Test void globalEntityBudgetIsReservedBeforeMutatingTheWorld() {
        boneCount = 16;
        var agents = new ArrayList<>(frame(0, 0).agents());
        agents.add(new LabProjectionSource.Agent(UUID.randomUUID(), 1, 2));
        agents.add(new LabProjectionSource.Agent(UUID.randomUUID(), 2, 2));
        var frame = new LabProjectionSource.Frame("session", "world", 0, 9, 5, agents);
        projection.start(owner, "probe", anchor(), frame);
        assertEquals(68, projection.reservedEntities());
        assertThrows(IllegalStateException.class, () -> projection.start(UUID.randomUUID(), "probe", anchor(), frame));
        assertEquals(4, spawned);
        projection.close(); assertEquals(0, projection.reservedEntities());
    }
    @Test void movementFailureClosesTheWholeViewWithoutChangingTheSnapshot() {
        start(); failMove = true;
        var next = frame(1, 2);
        assertThrows(IllegalStateException.class, () -> projection.update(owner, next));
        assertEquals(2, next.agents().getFirst().x());
        assertTrue(active.isEmpty()); assertEquals(0, projection.reservedEntities());
    }
    @Test void modelReloadAndManagerReplacementReleaseOnlyTheOldOwnedInstances() {
        start(); model = model();
        assertThrows(IllegalStateException.class, () -> projection.update(owner, frame(1, 1)));
        assertTrue(active.isEmpty());
        start(); manager = manager();
        assertThrows(IllegalStateException.class, () -> projection.update(owner, frame(1, 1)));
        assertTrue(active.isEmpty());
    }
    @Test void unloadingModelOrFootprintCleansExistingProjection() {
        start(); loaded = false;
        assertThrows(IllegalStateException.class, () -> projection.update(owner, frame(1, 1)));
        assertTrue(active.isEmpty());
        loaded = true; start(); chunks = false;
        assertThrows(IllegalStateException.class, () -> projection.update(owner, frame(1, 1)));
        assertTrue(active.isEmpty());
    }
    @Test void mismatchedSessionAndSameTickMutationCannotRetargetAnExistingProjection() {
        start();
        assertThrows(IllegalArgumentException.class, () -> projection.update(owner, frame(0, 2)));
        assertTrue(active.isEmpty());
        start(); var next = frame(1, 1);
        var replacement = new LabProjectionSource.Frame("new-session", next.worldId(), next.tick(), next.width(), next.height(), next.agents());
        assertThrows(IllegalArgumentException.class, () -> projection.update(owner, replacement));
        assertTrue(active.isEmpty());
    }
    @Test void closeAfterProviderDisableUsesRetainedHandlesAndCanRetry() {
        start(); provider = null; cleanupFailures = 1;
        assertThrows(IllegalStateException.class, projection::close);
        assertEquals(4, projection.reservedEntities());
        projection.close(); projection.close();
        assertEquals(0, projection.reservedEntities());
        assertTrue(active.isEmpty());
        provider = ENABLED;
        assertThrows(IllegalStateException.class, this::start);
    }
    @Test void reentrantStopCannotDropAnInProgressCreationHandle() {
        duringSpawn = () -> assertThrows(IllegalStateException.class, () -> projection.stop(owner));
        start();
        assertEquals(2, projection.status(owner).orElseThrow().instances());
        projection.close(); assertTrue(active.isEmpty());
    }
    @Test void disableDuringSpawnRejectsReturnedInstanceAndCleansIt() {
        duringSpawn = () -> assertThrows(IllegalStateException.class, projection::close);
        assertThrows(IllegalStateException.class, this::start);
        assertEquals(1, spawned); assertTrue(active.isEmpty()); assertEquals(0, projection.reservedEntities());
    }
    private static <T> T proxy(Class<T> type, BiFunction<String, Object[], Object> call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (ignored, method, args) -> call.apply(method.getName(), args)));
    }
}
