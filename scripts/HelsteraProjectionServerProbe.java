import dev.helstera.api.HelsteraApi;
import dev.helstera.api.instance.ModelInstance;
import dev.helstera.api.model.ModelDefinition;
import dev.helstera.api.model.ModelRegistry;
import dev.stargenesis.integrations.EntityProjection;
import dev.stargenesis.integrations.InstalledPlugin;
import dev.stargenesis.integrations.LabProjectionSource;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;

/** Actual model parser/renderer/manager and bridge on Paper. Synthetic immutable lab frames, no visual client claim. */
public final class HelsteraProjectionServerProbe extends JavaPlugin implements org.bukkit.event.Listener {
    private final UUID owner = UUID.randomUUID(), a = UUID.randomUUID(), b = UUID.randomUUID();
    private final List<String> checks = new ArrayList<>();
    private int phase, attempts;
    private EntityProjection projection;
    private ModelRegistry registry;
    private CompletableFuture<Optional<ModelDefinition>> loading;
    private ModelDefinition model;
    private World world;
    private Entity sentinel;
    private EntityProjection.Anchor anchor;
    private List<Entity> owned;
    private BukkitTask task;
    private boolean cancelDisplay;
    private int cancelledDisplays;

    @Override public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        task = Bukkit.getScheduler().runTaskTimer(this, this::advance, 20, 5);
    }

    @org.bukkit.event.EventHandler public void spawning(org.bukkit.event.entity.EntitySpawnEvent event) {
        if (cancelDisplay && event.getEntity() instanceof ItemDisplay) {
            cancelledDisplays++;
            event.setCancelled(true);
        }
    }

    private LabProjectionSource.Frame frame(long tick, int x) {
        return new LabProjectionSource.Frame("server-session", "lab-world", tick, 9, 5,
                List.of(new LabProjectionSource.Agent(a, x, 1), new LabProjectionSource.Agent(b, 3, 2)));
    }
    private void start() {
        Set<UUID> before = new HashSet<>();
        world.getEntities().forEach(e -> before.add(e.getUniqueId()));
        projection.start(owner, model.id(), anchor, frame(0, 0));
        owned = world.getEntities().stream().filter(e -> !before.contains(e.getUniqueId())).toList();
        require(owned.size() == 4, "two carriers and two bone displays created");
        require(projection.reservedEntities() == 4, "entity reservation matches created objects");
        require(owned.stream().noneMatch(Entity::isPersistent), "all projection entities are nonpersistent");
        require(owned.stream().filter(e -> e instanceof ArmorStand).count() == 2, "passive carriers");
        require(owned.stream().filter(e -> e instanceof ItemDisplay).count() == 2, "actual bone displays");
        require(owned.stream().filter(e -> e instanceof ArmorStand).map(e -> (ArmorStand) e)
                .allMatch(e -> e.isMarker() && e.isInvulnerable() && !e.isCollidable() && !e.hasGravity()), "passive carrier flags");
        require(HelsteraApi.get().getInstanceManager().allInstances().stream()
                .allMatch(i -> i.animation().currentAnimation().isEmpty()), "automatic animations disabled");
    }
    private void removed() {
        require(owned.stream().noneMatch(Entity::isValid), "all previously owned entity objects removed");
        require(projection.reservedEntities() == 0, "cleanup releases reservation");
        require(sentinel.isValid(), "unrelated sentinel preserved");
    }

    private void advance() {
        try {
            if (++attempts > 120) throw new AssertionError("Projection probe timeout");
            if (phase == 0) {
                if (!HelsteraApi.isAvailable()) return;
                projection = Bukkit.getServicesManager().load(EntityProjection.class);
                require(projection != null, "projection service registered");
                require(Bukkit.getServicesManager().load(LabProjectionSource.class).snapshot(owner).isEmpty(), "absent lab yields no snapshot");
                require(projection.verify(new InstalledPlugin("helsteraMobs", "1.0.0", true)).activeCapabilities()
                        .contains("entity.project"), "projection capability verified");
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "genesis debug capabilities");
                registry = HelsteraApi.get().getModelRegistry();
                loading = registry.load(getDataFolder().toPath().resolve("model"));
                phase++;
            } else if (phase == 1) {
                if (!loading.isDone()) return;
                model = loading.join().orElseThrow();
                registry.register(model);
                world = Bukkit.getWorlds().getFirst();
                for (int x = -1; x <= 1; x++) for (int z = -1; z <= 1; z++) world.getChunkAt(x, z).load();
                anchor = new EntityProjection.Anchor(world.getUID(), 1.5, 70, 1.5);
                sentinel = world.spawn(new Location(world, 14, 70, 14), ArmorStand.class,
                        entity -> { entity.setMarker(true); entity.setGravity(false); entity.setPersistent(false); });
                int before = world.getEntities().size();
                cancelDisplay = true;
                boolean creationRejected = false;
                try { projection.start(owner, model.id(), anchor, frame(0, 0)); }
                catch (dev.helstera.api.instance.ModelCreationException expected) {
                    creationRejected = !expected.cleanupHandle().isValid();
                } finally { cancelDisplay = false; }
                require(cancelledDisplays == 1 && creationRejected, "actual cancelled bone spawn rolls back with invalid cleanup handle");
                require(world.getEntities().size() == before && projection.reservedEntities() == 0
                        && HelsteraApi.get().getInstanceManager().activeCount() == 0, "cancelled spawn leaves no registered instance or owned entity");
                start();
                projection.update(owner, frame(1, 2));
                List<ModelInstance> instances = List.copyOf(HelsteraApi.get().getInstanceManager().allInstances());
                require(instances.size() == 2, "two active instances after movement");
                require(instances.stream().anyMatch(i -> i.location().getX() == 3.5), "simulation cell moved to anchor plus x");
                require(owned.stream().filter(e -> e instanceof ArmorStand).anyMatch(e -> e.getLocation().getX() == 3.5), "carrier follows movement");
                require(owned.stream().filter(e -> e instanceof ItemDisplay).anyMatch(e -> e.getLocation().getX() == 3.5), "bone follows movement");
                projection.update(owner, frame(1, 2));
                require(world.getEntities().stream().filter(e -> owned.contains(e)).count() == 4, "repeated snapshot does not duplicate entities");
                projection.stop(owner); projection.stop(owner);
                removed();
                require(HelsteraApi.get().getInstanceManager().activeCount() == 0, "stop removes active instances");
                phase++;
            } else if (phase == 2) {
                start();
                require(registry.unload(model.id()), "actual model unload");
                boolean rejected = false;
                try { projection.update(owner, frame(1, 1)); } catch (RuntimeException expected) { rejected = true; }
                require(rejected, "model unload rejects subsequent snapshot");
                removed();
                registry.register(model);
                start();
                phase++;
            } else {
                Bukkit.getPluginManager().disablePlugin(Bukkit.getPluginManager().getPlugin("helsteraMobs"));
                require(Bukkit.getServicesManager().load(EntityProjection.class) == null, "service unregistered on provider disable");
                require(!Bukkit.getPluginManager().getPlugin("StarGenesisHelstera").isEnabled(), "bridge disabled with provider");
                require(Bukkit.getPluginManager().getPlugin("StarGenesis").isEnabled(), "base plugin remains available");
                removed();
                boolean rejected = false;
                try { projection.start(owner, model.id(), anchor, frame(0, 0)); } catch (IllegalStateException expected) { rejected = true; }
                require(rejected, "old projection service rejects creation");
                sentinel.remove();
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "genesis debug capabilities");
                Files.writeString(getDataFolder().toPath().resolve("result.json"),
                        "{\"passed\":true,\"checks\":[" + checks.stream().map(s -> "\"" + s + "\"")
                        .collect(java.util.stream.Collectors.joining(",")) + "],\"scope\":\"Actual Paper model entity lifecycle with synthetic lab frames; no clients or visual acceptance\"}\n");
                task.cancel(); getLogger().info("HELSTERA_PROJECTION_PROBE_PASSED");
            }
        } catch (Throwable failure) {
            task.cancel(); getLogger().log(java.util.logging.Level.SEVERE, "HELSTERA_PROJECTION_PROBE_FAILED", failure);
        }
    }
    private void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        checks.add(message);
    }
}
