package dev.stargenesis.paper.lab;

import dev.stargenesis.api.sim.SimulationBudget;
import dev.stargenesis.paper.lab.LabExperiment.Mode;
import dev.stargenesis.paper.lab.LabExperiment.Phase;
import dev.stargenesis.sim.SandboxConfig;
import dev.stargenesis.sim.SandboxView;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Path;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

/**
 * Paper host for the virtual resource experiment. A single worker owns every simulation world;
 * the server thread sees only immutable views. No simulated asset enters a real inventory.
 *
 * Paper projects the simulation into a virtual inventory. Folia keeps the same workflow command-driven
 * so a synchronous plugin-disable event never blocks region-owned inventory cleanup.
 */
public final class LabController implements Listener, AutoCloseable {
    private static final int MAX_ACTIVE_LABS = 4;
    private static final int MAX_RETAINED_PLAYERS = 64;
    private final JavaPlugin plugin;
    private final Logger logger;
    private final SimulationBudget budget;
    private final LabPersistence persistence;
    private final ScheduledExecutorService worker;
    private final ConcurrentMap<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, LabMenu> menus = new ConcurrentHashMap<>();
    private final Object reservationLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final boolean folia;

    public LabController(JavaPlugin plugin, SimulationBudget budget, long stepMillis, Path dataDir) {
        this.plugin = Objects.requireNonNull(plugin);
        this.logger = plugin.getLogger();
        this.budget = Objects.requireNonNull(budget);
        Objects.requireNonNull(dataDir, "dataDir");
        try { persistence = new LabPersistence(dataDir.resolve("lab")); }
        catch (IOException failure) { throw new IllegalStateException("Cannot prepare lab evidence directory", failure); }
        if (stepMillis < 50 || stepMillis > 1000) throw new IllegalArgumentException("Lab step duration must be 50..1000ms");
        String server = (plugin.getServer().getName() + " " + plugin.getServer().getVersion()).toLowerCase(Locale.ROOT);
        folia = server.contains("folia");
        worker = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "StarGenesis-Lab"); thread.setDaemon(true); return thread;
        });
        worker.scheduleWithFixedDelay(this::tick, 0, stepMillis, TimeUnit.MILLISECONDS);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public boolean isAvailable() { return !closed.get(); }
    public String availabilityReason() {
        if (closed.get()) return "STOPPED";
        return folia ? "FOLIA_COMMAND_LAB" : "PAPER_VIRTUAL_INVENTORY_LAB";
    }

    /** args begin after /genesis lab, for example {start, seed, agentCount}. */
    public boolean handle(Player player, String[] args) {
        if (!player.hasPermission("stargenesis.lab")) {
            player.sendMessage("StarGenesis: permission denied (stargenesis.lab)"); return true;
        }
        if (!isAvailable()) {
            player.sendMessage("资源试验箱当前不可用：" + availabilityReason());
            return true;
        }
        String action = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        UUID owner = player.getUniqueId();
        switch (action) {
            case "start" -> {
                if (args.length > 3) { help(player); break; }
                try {
                    long seed = args.length > 1 ? Long.parseLong(args[1]) : 20_261_119L;
                    int count = args.length > 2 ? Integer.parseInt(args[2]) : Math.min(2, budget.maxAgents());
                    if (count < 1 || count > 4 || count > budget.maxAgents()) throw new IllegalArgumentException();
                    start(player, seed, count, Mode.NEW);
                } catch (IllegalArgumentException invalid) {
                    player.sendMessage("参数无效：/genesis lab start [整数种子] [1–4造物，受配置上限限制]");
                }
            }
            case "stop" -> {
                Session current = sessions.get(owner);
                if (current == null || !current.active()) player.sendMessage("当前没有正在运行的实验。");
                else { current.stopRequested.set(true); player.sendMessage("实验将在下一个逻辑步安全停止，并保留本次回放。"); }
            }
            case "replay" -> start(player, 0, 1, Mode.REPLAY);
            case "resume" -> start(player, 0, 1, Mode.RESUME);
            case "status" -> {
                Session current = sessions.get(owner);
                if (current == null) { player.sendMessage("尚未开始实验；/genesis lab start"); break; }
                SandboxView view = current.view;
                player.sendMessage("试验箱 " + current.phase + " | " + current.message
                        + (view == null ? "" : " | step=" + view.tick() + " score=" + view.score() + " hash=" + view.stateHash()));
                if (!folia) open(player, current);
            }
            default -> help(player);
        }
        return true;
    }

    private void help(Player player) {
        player.sendMessage("/genesis lab start [种子] [1–4造物] · stop · replay · resume · status");
        player.sendMessage(folia
                ? "Folia 实验通过 status、stop、replay 和 resume 控制，不打开玩家背包。"
                : "虚拟资源试验箱：观察造物寻找、移动、采集、投递。菜单内全部是不可领取的投影。");
        player.sendMessage(folia
                ? "默认约4分钟；只计模拟分，不生成真实物品或货币。使用 stop 可安全停止并保存回放。"
                : "默认约4分钟；只计模拟分，不生成真实物品或货币。关闭菜单会停止实验。");
    }

    private void start(Player player, long seed, int count, Mode mode) {
        UUID owner = player.getUniqueId();
        Session session;
        synchronized (reservationLock) {
            Session previous = sessions.get(owner);
            if (previous != null && previous.active()) {
                player.sendMessage("先停止当前实验再开始或重播；/genesis lab status 可重新查看。"); return;
            }
            long active = sessions.values().stream().filter(Session::active).count();
            if (active >= MAX_ACTIVE_LABS) { player.sendMessage("实验并发预算已满（4场），请稍后再试。"); return; }
            if (!sessions.containsKey(owner) && sessions.size() >= MAX_RETAINED_PLAYERS) {
                var idle = sessions.entrySet().stream().filter(e -> !e.getValue().active() && !menus.containsKey(e.getKey())).findFirst();
                if (idle.isEmpty()) { player.sendMessage("实验视图保留预算已满，请稍后再试。"); return; }
                sessions.remove(idle.get().getKey(), idle.get().getValue());
            }
            SandboxConfig defaults = SandboxConfig.defaults();
            SandboxConfig config = new SandboxConfig(defaults.width(), defaults.height(), count, defaults.actionIntervalSteps(),
                    Math.min(defaults.durationSteps(), budget.maxSteps()), defaults.energyPerAgent(), defaults.nodeStock(),
                    defaults.carryCapacity(), budget);
            session = new Session(owner, "lab-" + UUID.randomUUID(), seed, config, mode);
            sessions.put(owner, session);
        }
        if (!folia) open(player, session);
        String started = switch (mode) {
            case NEW -> folia ? "Folia 实验已启动：种子=" + seed + "；使用 status 查看进度。"
                    : "实验开始：种子=" + seed + "；菜单每个位置对应模拟格，灰色不可通行。";
            case RESUME -> "正在受控线程读取并验证最近检查点；最多每100步保存一次。";
            case REPLAY -> "回放开始：逐步重算并核对每步状态哈希和事件，不领取奖励。";
        };
        player.sendMessage(started);
    }

    private void open(Player player, Session session) {
        LabMenu old = menus.get(session.owner);
        if (old != null && old.session == session && player.getOpenInventory().getTopInventory().getHolder() == old) return;
        LabMenu menu = new LabMenu(session, this);
        menus.put(session.owner, menu);
        player.openInventory(menu.getInventory());
        ScheduledTask task = player.getScheduler().runAtFixedRate(plugin, running -> {
            if (closed.get() || !player.isOnline() || player.getOpenInventory().getTopInventory().getHolder() != menu) {
                running.cancel(); retire(menu); return;
            }
            menu.render();
            if (session.terminalMessageSent.compareAndSet(false, !session.active()) && !session.active()) {
                SandboxView view = session.view;
                player.sendMessage("资源试验箱结束：" + session.message + (view == null ? "" : " | 模拟分=" + view.score()));
                player.sendMessage("使用 /genesis lab replay 逐步验证回放，或 /genesis lab start 再试。无真实物品/货币结算。");
            }
        }, () -> retire(menu), 1, 2);
        menu.task = task;
        if (task == null) retire(menu);
    }

    private void tick() {
        if (closed.get()) return;
        for (Session session : new ArrayList<>(sessions.values())) {
            if (!session.active()) continue;
            if (session.experiment == null) session.experiment = new LabExperiment(session.owner, session.worldId,
                    session.seed, session.config, session.mode, budget, persistence);
            try {
                session.experiment.advance(session.stopRequested.get());
                if (session.persistenceFailureReported) {
                    logger.info("Lab evidence persistence recovered for " + session.owner);
                    session.persistenceFailureReported = false;
                }
            } catch (IOException failure) {
                if (!session.persistenceFailureReported)
                    logger.warning("Lab evidence save/load failed for " + session.owner + ": " + failure.getMessage());
                session.persistenceFailureReported = true;
            } catch (RuntimeException invalid) {
                logger.warning("Lab " + session.worldId + " rejected: " + invalid.getMessage());
            } finally {
                session.refresh();
            }
        }
    }

    private void retire(LabMenu menu) {
        // This method may be called by an entity scheduler's retirement callback: no Bukkit access.
        menus.remove(menu.session.owner, menu);
        menu.session.stopRequested.set(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof LabMenu menu)) return;
        event.setCancelled(true); // Covers player inventory, shift/number keys, double-click and outside clicks.
        if (!(event.getWhoClicked() instanceof Player player) || !player.getUniqueId().equals(menu.session.owner)) return;
        int raw = event.getRawSlot();
        if (raw == 47) menu.session.stopRequested.set(true);
        else if (raw == 49 || raw == 51) {
            player.getScheduler().run(plugin, ignored -> handle(player, new String[]{raw == 49 ? "replay" : "start"}), null);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof LabMenu) event.setCancelled(true);
    }

    @EventHandler public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof LabMenu menu)) return;
        if (menu.task != null) menu.task.cancel();
        menu.getInventory().clear(); // The closing player's Paper/server thread owns this menu.
        retire(menu);
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        LabMenu menu = menus.get(event.getPlayer().getUniqueId());
        if (menu != null) { if (menu.task != null) menu.task.cancel(); menu.getInventory().clear(); retire(menu); }
        Session session = sessions.get(event.getPlayer().getUniqueId());
        if (session != null) session.stopRequested.set(true);
    }

    @Override public void close() {
        if (closed.get()) return;
        if (!folia && !Bukkit.isPrimaryThread()) throw new IllegalStateException("Paper lab must close on the primary thread");
        if (!closed.compareAndSet(false, true)) return;
        for (LabMenu menu : menus.values()) if (menu.task != null) menu.task.cancel();
        for (LabMenu menu : menus.values()) menu.getInventory().clear();
        menus.clear();
        worker.execute(() -> {
            for (Session session : sessions.values()) {
                if (session.experiment != null) {
                    try { session.experiment.close(); }
                    catch (IOException | RuntimeException failure) {
                        logger.warning("Lab shutdown evidence save failed for " + session.owner + ": " + failure.getMessage());
                        session.experiment.abort("SHUTDOWN_SAVE_FAILED_PREVIOUS_CHECKPOINT_PRESERVED");
                    }
                    session.refresh();
                } else { session.phase = Phase.STOPPED; session.message = "PLUGIN_STOPPED"; }
            }
            sessions.clear();
        });
        worker.shutdown();
        try {
            if (!worker.awaitTermination(5, TimeUnit.SECONDS)) { worker.shutdownNow(); logger.warning("Lab worker shutdown exceeded 5s"); }
        } catch (InterruptedException interrupted) { worker.shutdownNow(); Thread.currentThread().interrupt(); }
    }

    static final class Session {
        final UUID owner;
        final String worldId;
        volatile long seed;
        volatile SandboxConfig config;
        final Mode mode;
        final AtomicBoolean stopRequested = new AtomicBoolean();
        final AtomicBoolean terminalMessageSent = new AtomicBoolean();
        volatile Phase phase = Phase.STARTING;
        volatile String message = "STARTING";
        volatile SandboxView view;
        LabExperiment experiment; // worker only
        boolean persistenceFailureReported; // worker only
        Session(UUID owner, String worldId, long seed, SandboxConfig config, Mode mode) {
            this.owner = owner; this.worldId = worldId; this.seed = seed; this.config = config; this.mode = mode;
        }
        void refresh() {
            view = experiment.view(); seed = experiment.seed(); config = experiment.config();
            message = experiment.message(); phase = experiment.phase();
        }
        boolean active() { return phase == Phase.STARTING || phase == Phase.RUNNING || phase == Phase.SAVING; }
    }
}
