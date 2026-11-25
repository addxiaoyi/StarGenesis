package dev.stargenesis.paper.ecology;

import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/** Paper/Folia command adapter for private, replayable ecology tanks. */
public final class EcologyController implements AutoCloseable {
    private static final int MAX_QUEUED_COMMANDS = 128;
    private final JavaPlugin plugin;
    private final EcologyService service;
    private final ThreadPoolExecutor worker;
    private final AtomicBoolean closed = new AtomicBoolean();

    public EcologyController(JavaPlugin plugin, Path dataDirectory) throws IOException {
        this.plugin = plugin;
        service = new EcologyService(new EcologyPersistence(dataDirectory));
        worker = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(MAX_QUEUED_COMMANDS), task -> {
                    Thread thread = new Thread(task, "StarGenesis-Ecology");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
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
            player.sendMessage("生态命令队列已满，请稍后重试。");
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
        try {
            player.getScheduler().run(plugin, task -> {
                if (player.isOnline()) lines.forEach(player::sendMessage);
            }, () -> { });
        } catch (RuntimeException schedulingFailure) {
            plugin.getLogger().log(Level.FINE, "Ecology response could not reach player " + owner, schedulingFailure);
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
}
