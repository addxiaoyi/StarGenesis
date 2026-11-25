package dev.stargenesis.paper.ecology;

import dev.stargenesis.sim.EcologyConfig;
import dev.stargenesis.sim.EcologyInput;
import dev.stargenesis.sim.EcologyReplay;
import dev.stargenesis.sim.EcologyReplayVerifier;
import dev.stargenesis.sim.EcologyView;
import dev.stargenesis.sim.EcologyWorld;
import dev.stargenesis.sim.MatterProgram;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Locale;
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
    private static final long DEFAULT_SEED = 20_261_122L;
    private final JavaPlugin plugin;
    private final EcologyPersistence persistence;
    private final ThreadPoolExecutor worker;
    private final AtomicBoolean closed = new AtomicBoolean();

    public EcologyController(JavaPlugin plugin, Path dataDirectory) throws IOException {
        this.plugin = plugin;
        persistence = new EcologyPersistence(dataDirectory);
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
            response = execute(owner, args);
        } catch (IOException failure) {
            plugin.getLogger().log(Level.SEVERE, "Ecology persistence failed for " + owner, failure);
            response = List.of("生态状态未保存：" + failure.getMessage());
        } catch (RuntimeException failure) {
            response = List.of("生态操作未完成：" + failure.getMessage());
        }
        send(owner, player, response);
    }

    private List<String> execute(UUID owner, String[] args) throws IOException {
        if (args.length == 0) return help();
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "start" -> start(owner, args);
            case "program" -> program(owner, args);
            case "step" -> step(owner, args);
            case "dashboard" -> dashboard(owner, args);
            case "replay" -> replay(owner, args);
            case "reset" -> reset(owner, args);
            default -> help();
        };
    }

    private List<String> start(UUID owner, String[] args) throws IOException {
        if (args.length > 2) throw new IllegalArgumentException("用法：/genesis ecology start [seed]");
        if (persistence.load(owner).isPresent())
            throw new IllegalStateException("已有生态舱。先使用 dashboard、reset 或 step。");
        long seed = args.length == 2 ? Long.parseLong(args[1]) : DEFAULT_SEED;
        try (EcologyWorld world = new EcologyWorld(worldId(owner), seed, EcologyConfig.defaults())) {
            persistence.save(owner, world.replay());
            return dashboardLines(world.view());
        }
    }

    private List<String> program(UUID owner, String[] args) throws IOException {
        if (args.length != 6)
            throw new IllegalArgumentException("用法：/genesis ecology program <species> <uptake> <reproductionMass> <lifespan> <mutationPermille>");
        try (EcologyWorld world = load(owner)) {
            MatterProgram current = world.view().programs().get(args[1]);
            if (current == null) throw new IllegalArgumentException("未知 matter species " + args[1]);
            MatterProgram updated = current.withParameters(Integer.parseInt(args[2]), Integer.parseInt(args[3]),
                    Integer.parseInt(args[4]), Integer.parseInt(args[5]));
            world.step(new EcologyInput(world.tick(), Map.of(updated.species(), updated)));
            persistence.save(owner, world.replay());
            List<String> response = new ArrayList<>();
            response.add("Matter program applied at logical tick " + world.tick() + ".");
            response.addAll(dashboardLines(world.view()));
            return List.copyOf(response);
        }
    }

    private List<String> step(UUID owner, String[] args) throws IOException {
        if (args.length > 2) throw new IllegalArgumentException("用法：/genesis ecology step [1-20]");
        int steps = args.length == 2 ? Integer.parseInt(args[1]) : 1;
        if (steps < 1 || steps > 20) throw new IllegalArgumentException("step 每次只能运行1到20个逻辑 tick");
        try (EcologyWorld world = load(owner)) {
            for (int i = 0; i < steps && !world.view().terminal(); i++)
                world.step(EcologyInput.idle(world.tick()));
            persistence.save(owner, world.replay());
            return dashboardLines(world.view());
        }
    }

    private List<String> dashboard(UUID owner, String[] args) throws IOException {
        requireLength(args, 1);
        try (EcologyWorld world = load(owner)) {
            return dashboardLines(world.view());
        }
    }

    private List<String> replay(UUID owner, String[] args) throws IOException {
        requireLength(args, 1);
        EcologyReplay replay = persistence.load(owner)
                .orElseThrow(() -> new IllegalStateException("没有可回放的生态舱。先使用 /genesis ecology start。"));
        requireOwner(owner, replay);
        EcologyReplayVerifier.Verification result = EcologyReplayVerifier.verify(replay);
        if (!result.passed()) throw new IllegalStateException("回放验证失败：" + result.reason());
        return List.of("生态回放通过 " + replay.frames().size() + " ticks · initial="
                + replay.initialStateHash().substring(0, 12));
    }

    private List<String> reset(UUID owner, String[] args) throws IOException {
        requireLength(args, 1);
        return List.of(persistence.delete(owner) ? "已删除个人生态舱与回放。" : "没有需要删除的生态舱。");
    }

    private EcologyWorld load(UUID owner) throws IOException {
        EcologyReplay replay = persistence.load(owner)
                .orElseThrow(() -> new IllegalStateException("没有生态舱。先使用 /genesis ecology start [seed]。"));
        requireOwner(owner, replay);
        return EcologyWorld.restore(replay);
    }

    private void requireOwner(UUID owner, EcologyReplay replay) {
        if (!worldId(owner).equals(replay.worldId())) throw new SecurityException("生态舱所有者不匹配");
    }

    private String worldId(UUID owner) {
        return "ecology-" + owner.toString().replace("-", "");
    }

    private List<String> dashboardLines(EcologyView view) {
        List<String> lines = new ArrayList<>();
        lines.add("生态舱 " + view.tick() + "/" + EcologyConfig.defaults().maxTicks()
                + " · population=" + view.population() + "/" + EcologyConfig.defaults().maxOrganisms()
                + " · generation=" + view.maxGeneration() + "/" + EcologyConfig.defaults().maxGenerations());
        lines.add("Matter=" + view.totalMatter() + " · soil=" + view.soilMatter() + " · detritus="
                + view.detritusMatter() + " · organisms=" + view.organismMatter());
        lines.add("Births=" + view.births() + " · deaths=" + view.deaths() + " · processed="
                + view.processedMatter() + " · evolved=" + view.evolvedOrganisms());
        view.programs().forEach((species, program) -> lines.add(species + " "
                + view.populationBySpecies().getOrDefault(species, 0) + " · matter="
                + view.matterBySpecies().getOrDefault(species, 0L) + " · intake=" + program.intake()
                + " · reproduce=" + program.reproductionMass() + " · life=" + program.lifespanSteps()
                + " · mutationPermille=" + program.mutationPermille()));
        lines.add("state=" + view.stateHash().substring(0, 12) + " · " + view.reason());
        return List.copyOf(lines);
    }

    private List<String> help() {
        return List.of(
                "/genesis ecology start [seed] · step [1-20] · dashboard · replay · reset",
                "/genesis ecology program <producer|grazer|decomposer> <uptake> <reproductionMass> <lifespan> <mutationPermille>");
    }

    private void requireLength(String[] args, int expected) {
        if (args.length != expected) throw new IllegalArgumentException("参数数量不正确；使用 /genesis ecology 查看用法");
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
