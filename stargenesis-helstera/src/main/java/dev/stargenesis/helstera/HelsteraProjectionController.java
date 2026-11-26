package dev.stargenesis.helstera;

import dev.stargenesis.integrations.EntityProjection;
import dev.stargenesis.integrations.LabProjectionSource;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Paper-only command/lifecycle host. The lab worker publishes immutable coordinates through a service. */
final class HelsteraProjectionController implements Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final HelsteraProjection projection;
    private final Map<UUID, UUID> tracked = new HashMap<>();
    private final Set<UUID> reported = new HashSet<>();
    private final BukkitTask task;

    HelsteraProjectionController(JavaPlugin plugin, HelsteraProjection projection) {
        this.plugin = plugin; this.projection = projection;
        var command = java.util.Objects.requireNonNull(plugin.getCommand("genesisprojection"));
        command.setExecutor((sender, ignored, label, args) -> handle(sender, args));
        command.setTabCompleter((sender, ignored, label, args) -> args.length == 1
                && sender.hasPermission("stargenesis.projection")
                ? List.of("start", "stop", "status").stream().filter(s -> s.startsWith(args[0])).toList() : List.of());
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        task = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 1, 2);
    }

    private LabProjectionSource source() {
        var service = plugin.getServer().getServicesManager().getRegistration(LabProjectionSource.class);
        if (service == null || !service.getPlugin().isEnabled()) throw new IllegalStateException("实验室快照服务不可用");
        return service.getProvider();
    }

    private boolean handle(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) { sender.sendMessage("模型预览需要在线玩家。"); return true; }
        if (!player.hasPermission("stargenesis.projection")) { player.sendMessage("缺少权限：stargenesis.projection"); return true; }
        UUID owner = player.getUniqueId();
        try {
            if (args.length == 2 && args[0].equalsIgnoreCase("start")) {
                var frame = source().snapshot(owner).orElseThrow(() -> new IllegalStateException("先启动自己的实验室并保持实验运行"));
                var at = player.getLocation();
                var anchor = new EntityProjection.Anchor(at.getWorld().getUID(), at.getBlockX() + 0.5,
                        at.getBlockY() + 1, at.getBlockZ() + 0.5);
                try { projection.start(owner, args[1], anchor, frame); }
                finally { if (projection.status(owner).isPresent()) tracked.put(owner, anchor.world()); }
                player.sendMessage("模型预览已开启；实验结束、退出或切换世界时自动清理。");
            } else if (args.length == 1 && args[0].equalsIgnoreCase("stop")) {
                stop(owner);
                player.sendMessage("模型预览已停止。");
            } else if (args.length == 1 && args[0].equalsIgnoreCase("status")) {
                player.sendMessage(projection.status(owner).map(status -> "模型预览：" + status.modelId()
                        + " | step=" + status.tick() + " | instances=" + status.instances()
                        + " | cleanupPending=" + status.cleanupPending()).orElse("当前没有模型预览。"));
            } else player.sendMessage("/genesisprojection start <已加载模型ID> | stop | status");
        } catch (RuntimeException | LinkageError failure) {
            player.sendMessage("模型预览未完成：" + failure.getMessage());
        }
        return true;
    }

    private void tick() {
        for (UUID owner : List.copyOf(tracked.keySet())) {
            try {
                Player player = plugin.getServer().getPlayer(owner);
                if (player == null || !player.isOnline() || !player.hasPermission("stargenesis.projection")
                        || !player.getWorld().getUID().equals(tracked.get(owner))) stop(owner);
                else {
                    var frame = source().snapshot(owner);
                    if (frame.isEmpty()) stop(owner);
                    else projection.update(owner, frame.orElseThrow());
                }
            } catch (RuntimeException | LinkageError failure) {
                // A source/provider failure also closes the disposable view; retries retain its budget.
                try { stop(owner); }
                catch (RuntimeException | LinkageError cleanup) { if (failure != cleanup) failure.addSuppressed(cleanup); }
                if (reported.add(owner)) plugin.getLogger().warning("Projection stopped for " + owner + ": " + failure);
            } finally {
                if (projection.status(owner).isEmpty()) { tracked.remove(owner); reported.remove(owner); }
            }
        }
    }

    private void stop(UUID owner) {
        projection.stop(owner);
        tracked.remove(owner); reported.remove(owner);
    }
    private void stopFromEvent(UUID owner) {
        try { stop(owner); }
        catch (RuntimeException | LinkageError failure) {
            if (reported.add(owner)) plugin.getLogger().severe("Projection cleanup pending for " + owner + ": " + failure);
        }
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) { stopFromEvent(event.getPlayer().getUniqueId()); }
    @EventHandler public void onWorldChange(PlayerChangedWorldEvent event) { stopFromEvent(event.getPlayer().getUniqueId()); }
    @EventHandler(ignoreCancelled = true) public void onWorldUnload(WorldUnloadEvent event) {
        for (var entry : List.copyOf(tracked.entrySet())) {
            if (!entry.getValue().equals(event.getWorld().getUID())) continue;
            stopFromEvent(entry.getKey());
            if (projection.status(entry.getKey()).isPresent()) event.setCancelled(true);
        }
    }
    @Override public void close() {
        task.cancel();
        projection.close();
        tracked.clear(); reported.clear();
    }
}
