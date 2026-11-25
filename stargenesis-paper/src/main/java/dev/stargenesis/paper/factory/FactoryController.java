package dev.stargenesis.paper.factory;

import dev.stargenesis.sim.FactoryReplay;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.stream.Collectors;

/** Paper/Folia routing for worker-owned simulated organizations and factory matches. */
public final class FactoryController implements Listener, AutoCloseable {
    private static final int MAX_QUEUE = 128;
    private final JavaPlugin plugin;
    private final FactoryService service;
    private final ThreadPoolExecutor worker;
    private final AtomicBoolean closed = new AtomicBoolean();

    public FactoryController(JavaPlugin plugin, Path directory) throws IOException {
        this.plugin = plugin;
        service = new FactoryService(new FactoryPersistence(directory));
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(MAX_QUEUE), task -> {
            Thread thread = new Thread(task, "StarGenesis-Factory");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public boolean handle(Player player, String[] args) {
        if (!player.hasPermission("stargenesis.factory")) {
            player.sendMessage("StarGenesis: permission denied (stargenesis.factory)");
            return true;
        }
        if (closed.get()) {
            player.sendMessage("工厂模拟已停止。");
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("invite")) return invite(player, args);
        if (args.length > 0 && args[0].equalsIgnoreCase("accept")) return accept(player, args);
        UUID owner = player.getUniqueId();
        String[] request = args.clone();
        boolean manager = player.hasPermission("stargenesis.factory.manage");
        if (args.length > 0 && args[0].equalsIgnoreCase("start")) {
            plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
                Set<UUID> online = plugin.getServer().getOnlinePlayers().stream()
                        .map(Player::getUniqueId).collect(Collectors.toUnmodifiableSet());
                enqueue(owner, player, request, online, manager);
            });
        } else enqueue(owner, player, request, Set.of(), manager);
        return true;
    }

    private void enqueue(UUID owner, Player player, String[] request, Set<UUID> online, boolean manager) {
        submit(owner, player, () -> {
            List<String> lines = service.run(owner, request, online, manager);
            if (request.length > 0 && request[0].equalsIgnoreCase("start")) {
                try { broadcastMatch(service.current(owner)); }
                catch (IOException | RuntimeException failure) {
                    plugin.getLogger().log(Level.WARNING, "Factory saved successfully but start notification failed", failure);
                }
            }
            return lines;
        });
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (player.hasPermission("stargenesis.factory"))
            enqueue(player.getUniqueId(), player, new String[]{"restore"}, Set.of(), false);
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        UUID leaving = event.getPlayer().getUniqueId();
        try { worker.execute(() -> service.disconnected(leaving)); }
        catch (RejectedExecutionException ignored) { }
    }

    private boolean invite(Player inviter, String[] args) {
        if (args.length != 2) { inviter.sendMessage("用法：/genesis factory invite <online player>"); return true; }
        UUID owner = inviter.getUniqueId();
        String inviterName = inviter.getName();
        String invitedName = args[1];
        plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
            Player invited = plugin.getServer().getPlayerExact(invitedName);
            if (invited == null) { send(owner, inviter, List.of("受邀玩家须在线。")); return; }
            UUID invitedId = invited.getUniqueId();
            submit(owner, inviter, () -> {
                service.invite(owner, invitedId);
                send(invitedId, invited, List.of(inviterName + " 邀请你组成模拟工厂组织。使用 /genesis factory accept。"));
                return List.of("已邀请 " + invitedName + " 加入双人模拟组织。");
            });
        });
        return true;
    }

    private boolean accept(Player player, String[] args) {
        if (args.length != 1) { player.sendMessage("用法：/genesis factory accept"); return true; }
        UUID invited = player.getUniqueId();
        String invitedName = player.getName();
        submit(invited, player, () -> {
            UUID inviter = service.accept(invited);
            notifyOnline(inviter, invitedName + " 已接受组织邀请。");
            return List.of("已组成双人模拟组织。集齐另一个双人组织后即可开始四人工厂。");
        });
        return true;
    }

    private void submit(UUID owner, Player player, Operation operation) {
        try {
            worker.execute(() -> {
                List<String> lines;
                try { lines = operation.run(); }
                catch (IOException failure) {
                    plugin.getLogger().log(Level.SEVERE, "Factory persistence failed for " + owner, failure);
                    lines = List.of("工厂状态未保存：" + failure.getMessage());
                } catch (RuntimeException failure) {
                    lines = List.of("工厂操作未完成：" + failure.getMessage());
                }
                send(owner, player, lines);
            });
        } catch (RejectedExecutionException full) {
            send(owner, player, List.of("工厂命令队列已满，请稍后重试。"));
        }
    }

    private void broadcastMatch(FactoryReplay replay) {
        plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
            for (UUID participant : replay.participants()) {
                Player player = plugin.getServer().getPlayer(participant);
                if (player != null) send(participant, player, List.of("四人工厂已开始 · match=" + replay.matchId()));
            }
        });
    }

    private void send(UUID owner, Player player, List<String> lines) {
        if (player == null) return;
        try {
            player.getScheduler().run(plugin, task -> {
                if (player.isOnline()) lines.forEach(player::sendMessage);
            }, () -> { });
        } catch (RuntimeException failure) {
            plugin.getLogger().log(Level.FINE, "Factory response could not reach player " + owner, failure);
        }
    }

    private void notifyOnline(UUID owner, String message) {
        try {
            plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
                Player player = plugin.getServer().getPlayer(owner);
                if (player != null) send(owner, player, List.of(message));
            });
        } catch (RuntimeException failure) {
            plugin.getLogger().log(Level.FINE, "Factory notification could not reach player " + owner, failure);
        }
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        worker.shutdown();
        try {
            if (!worker.awaitTermination(5, TimeUnit.SECONDS)) worker.shutdownNow();
        } catch (InterruptedException interrupted) {
            worker.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    @FunctionalInterface private interface Operation { List<String> run() throws IOException; }
}
