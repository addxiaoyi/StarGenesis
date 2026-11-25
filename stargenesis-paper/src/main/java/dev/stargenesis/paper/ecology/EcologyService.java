package dev.stargenesis.paper.ecology;

import dev.stargenesis.sim.EcologyConfig;
import dev.stargenesis.sim.EcologyInput;
import dev.stargenesis.sim.EcologyReplay;
import dev.stargenesis.sim.EcologyReplayVerifier;
import dev.stargenesis.sim.EcologyView;
import dev.stargenesis.sim.EcologyWorld;
import dev.stargenesis.sim.MatterProgram;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Worker-owned command workflow. A failed save discards the transient world, preserving the last replay. */
final class EcologyService {
    private static final long DEFAULT_SEED = 20_261_122L;
    private final EcologyPersistence persistence;

    EcologyService(EcologyPersistence persistence) { this.persistence = persistence; }

    List<String> execute(UUID owner, String[] args) throws IOException {
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
        if (args.length > 3) throw new IllegalArgumentException("用法：/genesis ecology start [seed] [ticks:1-1536]");
        if (persistence.load(owner).isPresent())
            throw new IllegalStateException("已有生态舱。先使用 dashboard、reset 或 step。");
        long seed = args.length >= 2 ? Long.parseLong(args[1]) : DEFAULT_SEED;
        EcologyConfig config = args.length == 3 ? EcologyConfig.forDuration(Integer.parseInt(args[2])) : EcologyConfig.defaults();
        try (EcologyWorld world = new EcologyWorld(worldId(owner), seed, config)) {
            persistence.save(owner, world.replay());
            return dashboardLines(world.view(), world.config());
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
            response.addAll(dashboardLines(world.view(), world.config()));
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
            return dashboardLines(world.view(), world.config());
        }
    }

    private List<String> dashboard(UUID owner, String[] args) throws IOException {
        requireLength(args, 1);
        try (EcologyWorld world = load(owner)) {
            return dashboardLines(world.view(), world.config());
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

    private List<String> dashboardLines(EcologyView view, EcologyConfig config) {
        List<String> lines = new ArrayList<>();
        lines.add("生态舱 " + view.tick() + "/" + config.maxTicks()
                + " · population=" + view.population() + "/" + config.maxOrganisms()
                + " · generation=" + view.maxGeneration() + "/" + config.maxGenerations());
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
                "/genesis ecology start [seed] [ticks:1-1536] · step [1-20] · dashboard · replay · reset",
                "/genesis ecology program <producer|grazer|decomposer> <uptake> <reproductionMass> <lifespan> <mutationPermille>");
    }

    private void requireLength(String[] args, int expected) {
        if (args.length != expected) throw new IllegalArgumentException("参数数量不正确；使用 /genesis ecology 查看用法");
    }

}
