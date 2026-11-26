package dev.stargenesis.helstera;

import dev.helstera.api.Helstera;
import dev.helstera.api.instance.InstanceManager;
import dev.helstera.api.instance.ModelCreationException;
import dev.helstera.api.instance.ModelInstance;
import dev.helstera.api.instance.SpawnOptions;
import dev.helstera.api.model.ModelDefinition;
import dev.stargenesis.integrations.ModelCatalog;
import dev.stargenesis.integrations.AdapterVerification;
import dev.stargenesis.integrations.EntityProjection;
import dev.stargenesis.integrations.InstalledPlugin;
import dev.stargenesis.integrations.LabProjectionSource;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

/** Main-thread disposable projection; stable simulation UUIDs never depend on temporary provider IDs. */
public final class HelsteraProjection implements EntityProjection {
    public static final int MAX_SESSIONS = 4, MAX_ENTITIES = 128, MAX_BONES = 16;
    private final BooleanSupplier primaryThread;
    private final HelsteraModelCatalog catalog;
    private final Supplier<Optional<Helstera>> api;
    private final Function<UUID, World> worlds;
    private final Map<UUID, Session> sessions = new LinkedHashMap<>();
    private boolean closed;
    private boolean mutating;

    private static final class Session {
        final String modelId;
        final Anchor anchor;
        final LabProjectionSource.Frame initial;
        final InstanceManager manager;
        final ModelDefinition model;
        final ModelCatalog.ModelInfo info;
        final int radius;
        final int reservation;
        final Map<UUID, ModelInstance> owned = new LinkedHashMap<>();
        long tick;
        boolean cleanupPending;
        boolean unknownCreation;
        LabProjectionSource.Frame lastFrame;
        Session(String modelId, Anchor anchor, LabProjectionSource.Frame frame, InstanceManager manager, int reservation,
                ModelDefinition model, ModelCatalog.ModelInfo info, int radius) {
            this.modelId = modelId; this.anchor = anchor; initial = frame; this.manager = manager;
            this.reservation = reservation; tick = frame.tick();
            this.model = model; this.info = info; this.radius = radius; lastFrame = frame;
        }
    }

    public HelsteraProjection(BooleanSupplier primaryThread, HelsteraModelCatalog catalog,
                             Supplier<Optional<Helstera>> api, Function<UUID, World> worlds) {
        this.primaryThread = Objects.requireNonNull(primaryThread);
        this.catalog = Objects.requireNonNull(catalog);
        this.api = Objects.requireNonNull(api);
        this.worlds = Objects.requireNonNull(worlds);
    }

    private void thread() {
        if (!primaryThread.getAsBoolean()) throw new IllegalStateException("Projection requires the Paper main thread");
    }

    private void beginMutation() {
        thread();
        if (mutating) throw new IllegalStateException("Reentrant projection changes are forbidden");
        mutating = true;
    }

    private InstanceManager currentManager() {
        thread();
        if (closed) throw new IllegalStateException("Projection is closed");
        catalog.loadedModels(); // Checks current provider, API version, server and lifecycle on every operation.
        var root = api.get().orElseThrow(() -> new IllegalStateException("Helstera API is not ready"));
        var manager = Objects.requireNonNull(root.getInstanceManager(), "Helstera instances are not ready");
        if (!manager.supportsOwnedVisualCleanup())
            throw new IllegalStateException("Helstera requires the verified owned-visual cleanup patch");
        return manager;
    }

    @Override public synchronized AdapterVerification verify(InstalledPlugin plugin) {
        catalog.verify(plugin);
        currentManager();
        if (sessions.values().stream().anyMatch(s -> s.cleanupPending))
            throw new IllegalStateException("Projection cleanup is pending");
        return new AdapterVerification(true, true, Set.of("model.inspect", "entity.project"),
                "helstera-passive-projection/owned-visual-cleanup-v1");
    }

    @Override public synchronized void start(UUID owner, String modelId, Anchor anchor, LabProjectionSource.Frame frame) {
        beginMutation();
        try {
        Objects.requireNonNull(owner); Objects.requireNonNull(anchor); Objects.requireNonNull(frame);
        InstanceManager manager = currentManager();
        if (sessions.containsKey(owner)) throw new IllegalStateException("Stop the current projection first");
        if (sessions.size() >= MAX_SESSIONS || sessions.values().stream().anyMatch(s -> s.cleanupPending))
            throw new IllegalStateException("Projection session budget full or cleanup pending");
        var model = catalog.inspect(modelId).orElseThrow(() -> new IllegalArgumentException("Model is not loaded"));
        if (model.bones() < 1 || model.bones() > MAX_BONES || model.scale() > 4)
            throw new IllegalArgumentException("Projection requires 1..16 bones and model scale <= 4");
        int reservation = frame.agents().size() * (model.bones() + 1);
        if (reservedEntities() + reservation > MAX_ENTITIES) throw new IllegalStateException("Projection entity budget exceeded");
        ModelDefinition definition = definition(modelId);
        if (!modelId.equals(definition.id()) || definition.allBones().size() != model.bones()
                || Double.compare(definition.scale(), model.scale()) != 0)
            throw new IllegalStateException("Model changed during projection admission");
        int radius = radius(definition);
        Map<UUID, Location> destinations = destinations(anchor, frame, radius);
        Session session = new Session(modelId, anchor, frame, manager, reservation, definition, model, radius);
        sessions.put(owner, session); // Retain budget and every cleanup handle before invoking the provider.
        try {
            for (var agent : frame.agents()) {
                try {
                    var instance = manager.spawn(modelId, destinations.get(agent.id()), passiveOptions());
                    session.owned.put(agent.id(), Objects.requireNonNull(instance, "Missing created model instance"));
                    if (closed) throw new IllegalStateException("Projection closed during creation");
                    if (!instance.isValid()) throw new IllegalStateException("Created instance is invalid");
                } catch (ModelCreationException failed) {
                    session.owned.put(agent.id(), failed.cleanupHandle());
                    throw failed;
                } catch (RuntimeException | LinkageError failed) {
                    // An untyped failure violates the creation contract: never assume no side effect.
                    if (!session.owned.containsKey(agent.id())) session.unknownCreation = true;
                    throw failed;
                }
            }
        } catch (RuntimeException | LinkageError failure) {
            cleanupAfterFailure(owner, session, failure);
            throw failure;
        }
        } finally { mutating = false; }
    }

    private static SpawnOptions passiveOptions() {
        return SpawnOptions.defaults().persistent(false).spawnHitbox(false).showName(false).showHealthBar(false)
                .collidable(false).invulnerable(true).silent(true).noAi(true).gravity(false)
                .automaticAnimation(false).preventItemPickup(true).preventOtherDrops(true).interactable(false);
    }

    @Override public synchronized void update(UUID owner, LabProjectionSource.Frame frame) {
        beginMutation();
        try {
        Session session = sessions.get(owner);
        if (session == null) throw new IllegalStateException("No projection for this owner");
        if (session.cleanupPending) { cleanup(owner, session); return; }
        try {
            if (currentManager() != session.manager) throw new IllegalStateException("Projection provider changed");
            if (frame == null || !session.initial.sessionId().equals(frame.sessionId())
                    || !session.initial.worldId().equals(frame.worldId()) || frame.width() != session.initial.width()
                    || frame.height() != session.initial.height() || frame.tick() < session.tick
                    || !session.owned.keySet().equals(frame.agents().stream().map(LabProjectionSource.Agent::id)
                    .collect(java.util.stream.Collectors.toSet())))
                throw new IllegalArgumentException("Projection snapshot identity or sequence changed");
            if (definition(session.modelId) != session.model || !catalog.inspect(session.modelId).orElseThrow().equals(session.info)
                    || radius(session.model) != session.radius)
                throw new IllegalStateException("Projection model was unloaded or replaced");
            Map<UUID, Location> destinations = destinations(session.anchor, frame, session.radius);
            if (frame.tick() == session.tick && !sameFrame(session.lastFrame, frame))
                throw new IllegalArgumentException("Projection snapshot changed without advancing its tick");
            for (var entry : session.owned.entrySet()) {
                if (!entry.getValue().isValid()) throw new IllegalStateException("Projection instance was removed");
                if (!sameFrame(session.lastFrame, frame)) entry.getValue().teleport(destinations.get(entry.getKey()));
                if (closed) throw new IllegalStateException("Projection closed during movement");
            }
            session.tick = frame.tick();
            session.lastFrame = frame;
        } catch (RuntimeException | LinkageError failure) {
            cleanupAfterFailure(owner, session, failure);
            throw failure;
        }
        } finally { mutating = false; }
    }

    private static boolean sameFrame(LabProjectionSource.Frame first, LabProjectionSource.Frame second) {
        return first.tick() == second.tick() && Set.copyOf(first.agents()).equals(Set.copyOf(second.agents()));
    }

    private ModelDefinition definition(String id) {
        return api.get().orElseThrow().getModelRegistry().get(id)
                .orElseThrow(() -> new IllegalStateException("Projection model was unloaded"));
    }

    /** Conservative bound for the static bone transforms; no default/locomotion animation is enabled. */
    private static int radius(ModelDefinition model) {
        Map<String, Double> offsets = new LinkedHashMap<>();
        double maximum = 0;
        for (var bone : model.allBones()) {
            if (bone == null || bone.name() == null || offsets.containsKey(bone.name())
                    || (bone.parent() != null && !offsets.containsKey(bone.parent())))
                throw new IllegalArgumentException("Projection requires ordered, unique model bones");
            var p = Objects.requireNonNull(bone.pivot(), "Missing bone pivot");
            double length = Math.sqrt(p.x() * p.x() + p.y() * p.y() + p.z() * p.z());
            double offset = (bone.parent() == null ? 0 : offsets.get(bone.parent())) + 2 * length;
            double bound = (offset + length) * model.scale() / 16;
            if (!Double.isFinite(bound) || bound > 15) throw new IllegalArgumentException("Projection model exceeds the 16-block footprint radius");
            maximum = Math.max(maximum, bound);
            offsets.put(bone.name(), offset);
        }
        return (int) Math.ceil(maximum) + 1;
    }

    private Map<UUID, Location> destinations(Anchor anchor, LabProjectionSource.Frame frame, int radius) {
        World world = worlds.apply(anchor.world());
        if (world == null || !anchor.world().equals(world.getUID())) throw new IllegalStateException("Projection world is unavailable");
        if (anchor.y() - radius < world.getMinHeight() || anchor.y() + radius >= world.getMaxHeight())
            throw new IllegalArgumentException("Projection exceeds world height");
        // Verify the entire fixed footprint before the first spawn/teleport; never load chunks implicitly.
        int minX = (int) Math.floor(anchor.x() - radius), minZ = (int) Math.floor(anchor.z() - radius);
        int maxX = (int) Math.floor(anchor.x() + frame.width() - 1 + radius);
        int maxZ = (int) Math.floor(anchor.z() + frame.height() - 1 + radius);
        for (int x = minX >> 4; x <= maxX >> 4; x++) for (int z = minZ >> 4; z <= maxZ >> 4; z++)
            if (!world.isChunkLoaded(x, z))
                throw new IllegalStateException("Projection footprint requires loaded chunks inside the world border");
        for (int x : new int[]{minX, maxX}) for (int z : new int[]{minZ, maxZ})
            if (!world.getWorldBorder().isInside(new Location(world, x, anchor.y(), z)))
                throw new IllegalStateException("Projection footprint exceeds the world border");
        Map<UUID, Location> result = new LinkedHashMap<>();
        for (var agent : frame.agents())
            result.put(agent.id(), new Location(world, anchor.x() + agent.x(), anchor.y(), anchor.z() + agent.z()));
        return result;
    }

    private void cleanupAfterFailure(UUID owner, Session session, Throwable original) {
        try { cleanup(owner, session); }
        catch (RuntimeException | LinkageError cleanup) { if (cleanup != original) original.addSuppressed(cleanup); }
    }

    private void cleanup(UUID owner, Session session) {
        session.cleanupPending = true;
        Throwable failure = null;
        for (var entry : java.util.List.copyOf(session.owned.entrySet())) {
            try {
                // A retained instance can still release owned visuals after API unregistration.
                entry.getValue().despawn();
                session.manager.despawn(entry.getValue().instanceId());
                session.owned.remove(entry.getKey());
            } catch (RuntimeException | LinkageError next) {
                if (failure == null) failure = next;
                else if (failure != next) failure.addSuppressed(next);
            }
        }
        if (session.owned.isEmpty() && !session.unknownCreation) sessions.remove(owner, session);
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof LinkageError linkage) throw linkage;
        if (session.unknownCreation) throw new IllegalStateException("Provider failed without a cleanup handle; reservation retained until restart");
    }

    @Override public synchronized void stop(UUID owner) {
        beginMutation();
        try {
        Session session = sessions.get(owner);
        if (session != null) cleanup(owner, session);
        } finally { mutating = false; }
    }
    @Override public synchronized Optional<Status> status(UUID owner) {
        thread();
        Session session = sessions.get(owner);
        return session == null ? Optional.empty() : Optional.of(new Status(session.initial.sessionId(), session.modelId,
                session.tick, session.owned.size(), session.reservation, session.cleanupPending));
    }
    @Override public synchronized int reservedEntities() {
        thread();
        return sessions.values().stream().mapToInt(s -> s.reservation).sum();
    }
    @Override public synchronized void close() {
        thread(); closed = true;
        beginMutation();
        try {
        Throwable failure = null;
        for (UUID owner : java.util.List.copyOf(sessions.keySet())) {
            try { cleanup(owner, sessions.get(owner)); }
            catch (RuntimeException | LinkageError next) {
                if (failure == null) failure = next;
                else if (failure != next) failure.addSuppressed(next);
            }
        }
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof LinkageError linkage) throw linkage;
        } finally { mutating = false; }
    }
}
