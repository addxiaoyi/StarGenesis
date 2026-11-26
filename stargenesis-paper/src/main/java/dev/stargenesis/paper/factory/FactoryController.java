package dev.stargenesis.paper.factory;

import dev.stargenesis.sim.FactoryReplay;
import dev.stargenesis.paper.runtime.PersistentWorker;
import dev.stargenesis.paper.runtime.PlayerCommandQueue;
import dev.stargenesis.paper.runtime.PlayerCommandQueue.Connection;
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
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.stream.Collectors;

/** Paper/Folia routing for worker-owned simulated organizations and factory matches. */
public final class FactoryController implements Listener, AutoCloseable {
    private static final int MAX_QUEUE = 128;
    private final JavaPlugin plugin;
    private final FactoryService service;
    private final PersistentWorker worker;
    private final PlayerCommandQueue commands;
    private final AtomicBoolean closed = new AtomicBoolean();

    public FactoryController(JavaPlugin plugin, Path directory) throws IOException {
        this.plugin = plugin;
        worker = PersistentWorker.commands("StarGenesis-Factory", directory, MAX_QUEUE);
        try {
            service = new FactoryService(new FactoryPersistence(directory));
            commands = new PlayerCommandQueue(worker, service::disconnected, service::retainOnline);
            plugin.getServer().getOnlinePlayers().forEach(player -> commands.connected(player.getUniqueId()));
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
        } catch (IOException | RuntimeException | Error failure) { worker.close(); throw failure; }
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
        UUID owner = player.getUniqueId();
        Connection connection = commands.connection(owner);
        if (!commands.isCurrent(connection)) return true;
        if (args.length > 0 && args[0].equalsIgnoreCase("invite")) return invite(player, connection, args);
        if (args.length > 0 && args[0].equalsIgnoreCase("accept")) return accept(player, connection, args);
        String[] request = args.clone();
        boolean manager = player.hasPermission("stargenesis.factory.manage");
        if (args.length > 0 && args[0].equalsIgnoreCase("start")) {
            plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
                if (closed.get() || !commands.isCurrent(connection)) return;
                Set<UUID> online = plugin.getServer().getOnlinePlayers().stream()
                        .map(Player::getUniqueId).collect(Collectors.toUnmodifiableSet());
                enqueue(connection, player, request, online, manager);
            });
        } else enqueue(connection, player, request, Set.of(), manager);
        return true;
    }

    private void enqueue(Connection connection, Player player, String[] request, Set<UUID> online, boolean manager) {
        UUID owner = connection.player();
        submit(connection, connection, player, () -> {
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
        if (closed.get()) return;
        Player player = event.getPlayer();
        Connection connection;
        try { connection = commands.connected(player.getUniqueId()); }
        catch (RejectedExecutionException stopped) { return; }
        if (player.hasPermission("stargenesis.factory"))
            enqueue(connection, player, new String[]{"restore"}, Set.of(), false);
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        UUID leaving = event.getPlayer().getUniqueId();
        commands.disconnected(leaving);
    }

    private boolean invite(Player inviter, Connection connection, String[] args) {
        if (args.length != 2) { inviter.sendMessage("用法：/genesis factory invite <online player>"); return true; }
        UUID owner = inviter.getUniqueId();
        String inviterName = inviter.getName();
        String invitedName = args[1];
        plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
            if (closed.get() || !commands.isCurrent(connection)) return;
            Player invited = plugin.getServer().getPlayerExact(invitedName);
            if (invited == null) { send(owner, inviter, List.of("受邀玩家须在线。")); return; }
            UUID invitedId = invited.getUniqueId();
            submit(connection, commands.connection(invitedId), inviter, () -> {
                service.invite(owner, invitedId);
                send(invitedId, invited, List.of(inviterName + " 邀请你组成模拟工厂组织。使用 /genesis factory accept。"));
                return List.of("已邀请 " + invitedName + " 加入双人模拟组织。");
            });
        });
        return true;
    }

    private boolean accept(Player player, Connection connection, String[] args) {
        if (args.length != 1) { player.sendMessage("用法：/genesis factory accept"); return true; }
        UUID invited = player.getUniqueId();
        String invitedName = player.getName();
        submit(connection, connection, player, () -> {
            UUID inviter = service.accept(invited);
            notifyOnline(inviter, invitedName + " 已接受组织邀请。");
            return List.of("已组成双人模拟组织。集齐另一个双人组织后即可开始四人工厂。");
        });
        return true;
    }

    private void submit(Connection connection, Connection other, Player player, Operation operation) {
        UUID owner = connection.player();
        try {
            commands.execute(connection, other, () -> {
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
            send(owner, player, List.of(closed.get() ? "工厂模拟已停止。" : "工厂命令队列已满或玩家连接已失效，请稍后重试。"));
        }
    }

    private void broadcastMatch(FactoryReplay replay) {
        if (closed.get()) return;
        plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
            if (closed.get()) return;
            for (UUID participant : replay.participants()) {
                Player player = plugin.getServer().getPlayer(participant);
                if (player != null) send(participant, player, List.of("四人工厂已开始 · match=" + replay.matchId()));
            }
        });
    }

    private void send(UUID owner, Player player, List<String> lines) {
        if (player == null || closed.get()) return;
        try {
            player.getScheduler().run(plugin, task -> {
                if (!closed.get() && player.isOnline()) lines.forEach(player::sendMessage);
            }, () -> { });
        } catch (RuntimeException failure) {
            plugin.getLogger().log(Level.FINE, "Factory response could not reach player " + owner, failure);
        }
    }

    private void notifyOnline(UUID owner, String message) {
        if (closed.get()) return;
        try {
            plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
                if (closed.get()) return;
                Player player = plugin.getServer().getPlayer(owner);
                if (player != null) send(owner, player, List.of(message));
            });
        } catch (RuntimeException failure) {
            plugin.getLogger().log(Level.FINE, "Factory notification could not reach player " + owner, failure);
        }
    }

    @Override public void close() {
        closed.set(true);
        commands.close();
        worker.shutdown(plugin.getLogger(), "Factory");
    }

    public boolean isStopped() { return worker.isStopped(); }

    @FunctionalInterface private interface Operation { List<String> run() throws IOException; }
}
