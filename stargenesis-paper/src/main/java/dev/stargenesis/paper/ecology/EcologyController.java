package dev.stargenesis.paper.ecology;

import dev.stargenesis.paper.runtime.PersistentWorker;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/** Paper/Folia command adapter for private, replayable ecology tanks. */
public final class EcologyController implements AutoCloseable {
    private static final int MAX_QUEUED_COMMANDS = 128;
    private final JavaPlugin plugin;
    private final EcologyService service;
    private final PersistentWorker worker;
    private final AtomicBoolean closed = new AtomicBoolean();

    public EcologyController(JavaPlugin plugin, Path dataDirectory) throws IOException {
        this.plugin = plugin;
        worker = PersistentWorker.commands("StarGenesis-Ecology", dataDirectory, MAX_QUEUED_COMMANDS);
        try { service = new EcologyService(new EcologyPersistence(dataDirectory)); }
        catch (IOException | RuntimeException | Error failure) { worker.close(); throw failure; }
    }

    public boolean handle(Player player, String[] args) {
        if (!player.hasPermission("stargenesis.ecology")) {
            player.sendMessage("StarGenesis: permission denied (stargenesis.ecology)");
            return true;
        }
        if (closed.get()) {
            player.sendMessage("生态模拟服务已停止。");
            return true;
        }
        UUID owner = player.getUniqueId();
        String[] request = args.clone();
        try {
            worker.execute(() -> execute(owner, player, request));
        } catch (RejectedExecutionException full) {
            player.sendMessage(closed.get() ? "生态模拟服务已停止。" : "生态命令队列已满，请稍后重试。");
        }
        return true;
    }

    private void execute(UUID owner, Player player, String[] args) {
        List<String> response;
        try {
            response = service.execute(owner, args);
        } catch (IOException failure) {
            plugin.getLogger().log(Level.SEVERE, "Ecology persistence failed for " + owner, failure);
            response = List.of("生态状态未保存：" + failure.getMessage());
        } catch (RuntimeException failure) {
            response = List.of("生态操作未完成：" + failure.getMessage());
        }
        send(owner, player, response);
    }

    private void send(UUID owner, Player player, List<String> lines) {
        if (closed.get()) return;
        try {
            player.getScheduler().run(plugin, task -> {
                if (!closed.get() && player.isOnline()) lines.forEach(player::sendMessage);
            }, () -> { });
        } catch (RuntimeException schedulingFailure) {
            plugin.getLogger().log(Level.FINE, "Ecology response could not reach player " + owner, schedulingFailure);
        }
    }

    @Override public void close() {
        closed.set(true);
        worker.shutdown(plugin.getLogger(), "Ecology");
    }

    public boolean isStopped() { return worker.isStopped(); }
}
