import dev.helstera.api.HelsteraApi;
import dev.stargenesis.integrations.EntityProjection;
import dev.stargenesis.integrations.LabProjectionSource;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Files;
import java.util.List;
import java.util.UUID;

/** Observes actual player commands and the lab worker without modifying its simulation. */
public final class HelsteraLabProjectionServerProbe extends JavaPlugin implements Listener {
    private EntityProjection projection;
    private LabProjectionSource source;
    private UUID owner;
    private int samples, matched, active, clean;
    private long firstTick = -1, lastTick = -1;
    private String firstWorld, currentSession;
    private final java.util.Set<String> sessions = new java.util.HashSet<>();
    private boolean failed;

    @Override public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            projection = Bukkit.getServicesManager().load(EntityProjection.class);
            source = Bukkit.getServicesManager().load(LabProjectionSource.class);
            HelsteraApi.get().getModelRegistry().load(getDataFolder().toPath().resolve("model")).thenAccept(model ->
                    Bukkit.getScheduler().runTask(this, () -> {
                        HelsteraApi.get().getModelRegistry().register(model.orElseThrow());
                        Bukkit.getScheduler().runTaskTimer(this, this::sample, 1, 2);
                        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "genesis debug capabilities");
                        getLogger().info("HELSTERA_LAB_PROJECTION_PROBE_READY");
                    }));
        }, 20);
    }
    @EventHandler public void join(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        player.setGameMode(GameMode.SPECTATOR);
        player.teleport(new Location(player.getWorld(), 1, 70, 1));
        if (player.getName().equals("SGProjectAlice")) owner = player.getUniqueId();
    }
    private void sample() {
        if (owner == null || failed) return;
        try {
            var status = projection.status(owner);
            var frame = source.snapshot(owner);
            int count = HelsteraApi.get().getInstanceManager().activeCount();
            int entities = Bukkit.getWorlds().getFirst().getEntities().stream()
                    .filter(e -> e instanceof org.bukkit.entity.ArmorStand || e instanceof org.bukkit.entity.ItemDisplay).toList().size();
            if (status.isPresent()) {
                var current = status.orElseThrow();
                require(!current.cleanupPending() && current.instances() == 2 && count == 2 && entities == 4, "active counts");
                active++;
                currentSession = current.sessionId(); sessions.add(currentSession);
                if (frame.isPresent() && frame.orElseThrow().tick() == current.tick()) {
                    var snapshot = frame.orElseThrow();
                    if (firstWorld == null) firstWorld = snapshot.worldId();
                    require(firstWorld.equals(snapshot.worldId()), "resume retains simulation world");
                    var expected = snapshot.agents().stream().map(agent -> (1.5 + agent.x()) + ":" + (1.5 + agent.z())).sorted().toList();
                    var actual = HelsteraApi.get().getInstanceManager().allInstances().stream()
                            .map(i -> i.location().getX() + ":" + i.location().getZ()).sorted().toList();
                    require(expected.equals(actual), "real lab cells match provider instance locations");
                    matched++;
                    if (firstTick < 0) firstTick = snapshot.tick();
                    lastTick = snapshot.tick();
                }
            } else {
                require(count == 0 && entities == 0 && projection.reservedEntities() == 0, "clean counts");
                clean++;
            }
            samples++;
            Files.writeString(getDataFolder().toPath().resolve("observed.json"), "{\"samples\":" + samples
                    + ",\"active\":" + active + ",\"matched\":" + matched + ",\"clean\":" + clean
                    + ",\"instances\":" + count + ",\"entities\":" + entities + ",\"sessions\":" + sessions.size()
                    + ",\"firstTick\":" + firstTick + ",\"lastTick\":" + lastTick + "}\n");
        } catch (Throwable error) {
            failed = true;
            getLogger().log(java.util.logging.Level.SEVERE, "HELSTERA_LAB_PROJECTION_PROBE_FAILED", error);
        }
    }
    @Override public void onDisable() {
        try {
            boolean passed = !failed && active > 5 && matched > 5 && clean > 5 && lastTick > firstTick && sessions.size() >= 2;
            Files.writeString(getDataFolder().toPath().resolve("result.json"), "{\"passed\":" + passed
                    + ",\"activeSamples\":" + active + ",\"matchingLabSamples\":" + matched
                    + ",\"cleanSamples\":" + clean + ",\"sessions\":" + sessions.size()
                    + ",\"scope\":\"Actual lab worker and fixed offline client commands; no rendered visual acceptance\"}\n");
        } catch (Exception error) { getLogger().log(java.util.logging.Level.SEVERE, "HELSTERA_LAB_PROJECTION_PROBE_FAILED", error); }
    }
    private static void require(boolean value, String label) { if (!value) throw new AssertionError(label); }
}
