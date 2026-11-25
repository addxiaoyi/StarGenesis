import dev.stargenesis.internal.jackson.databind.ObjectMapper;
import dev.stargenesis.sim.EcologyConfig;
import dev.stargenesis.sim.EcologyInput;
import dev.stargenesis.sim.EcologyReplayVerifier;
import dev.stargenesis.sim.EcologyView;
import dev.stargenesis.sim.EcologyWorld;
import dev.stargenesis.sim.MatterProgram;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Bounded simulation-only study. Run with the shaded plugin JAR on the classpath. */
class EcologyLongRunStudy {
    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 3)
            throw new IllegalArgumentException("Usage: EcologyLongRunStudy.java <output.json> [seeds:1-32] [worlds:1-8]");
        int seeds = args.length > 1 ? Integer.parseInt(args[1]) : 4;
        int worlds = args.length > 2 ? Integer.parseInt(args[2]) : 2;
        if (seeds < 1 || seeds > 32 || worlds < 1 || worlds > 8) throw new IllegalArgumentException("Study budget exceeded");
        var profiles = Map.of(
                "previous-500-tick-candidate", profile(6, 2, 512, 1, 12, 512, 1, 12, 512),
                "delayed-reproduction", profile(1, 32, 512, 1, 32, 512, 1, 32, 512),
                "staggered-lifespans", profile(3, 8, 256, 1, 16, 384, 2, 12, 512));
        List<Result> results = new ArrayList<>();
        for (var profile : new TreeMap<>(profiles).entrySet()) {
            for (int world = 0; world < worlds; world++) {
                for (int seed = 0; seed < seeds; seed++) {
                    Result result = run("ecology-long-study-" + world, 52_008L + seed, profile.getKey(), profile.getValue());
                    results.add(result);
                    System.out.println(result.profile() + " world=" + world + " seed=" + result.seed()
                            + " tick=" + result.finalState().tick() + " end=" + result.finalState().reason()
                            + " species=" + result.finalState().populationBySpecies());
                }
            }
        }
        var report = Map.of("engineVersion", EcologyWorld.ENGINE_VERSION,
                "config", EcologyConfig.forDuration(EcologyConfig.MAX_TICKS), "cases", results,
                "scope", "Pure simulation; every step conserves matter and every final replay is verified. No live-server, human, performance or indefinite-balance claim.",
                "programTiming", "producer at tick 0, grazer at tick 1, decomposer at tick 2; no later intervention.");
        Path output = Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectories(output.getParent());
        Files.writeString(output, new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n");
    }

    private static Result run(String worldId, long seed, String profile, Map<String, MatterProgram> programs) {
        Map<String, Long> firstExtinction = new TreeMap<>();
        List<Milestone> milestones = new ArrayList<>();
        EcologyConfig config = EcologyConfig.forDuration(EcologyConfig.MAX_TICKS);
        try (var world = new EcologyWorld(worldId, seed, config)) {
            while (!world.view().terminal()) {
                long tick = world.tick();
                String species = tick == 0 ? "producer" : tick == 1 ? "grazer" : tick == 2 ? "decomposer" : null;
                world.step(species == null ? EcologyInput.idle(tick) : new EcologyInput(tick, Map.of(species, programs.get(species))));
                EcologyView view = world.view();
                if (view.totalMatter() != config.initialMatter() || view.population() > config.maxOrganisms()
                        || view.births() > config.maxBirths() || view.maxGeneration() > config.maxGenerations())
                    throw new IllegalStateException("Conservation or lifecycle budget violated");
                for (String name : programs.keySet()) {
                    if (view.populationBySpecies().getOrDefault(name, 0) == 0) firstExtinction.putIfAbsent(name, view.tick());
                }
                if (List.of(500L, 512L, 1_024L, 1_536L).contains(view.tick()))
                    milestones.add(new Milestone(view.tick(), view.populationBySpecies(), view.births(), view.deaths(), view.maxGeneration()));
            }
            var replay = world.replay();
            if (!EcologyReplayVerifier.verify(replay).passed()) throw new IllegalStateException("Study replay diverged");
            return new Result(profile, worldId, seed, programs, firstExtinction, milestones, world.view(), true);
        }
    }

    private static Map<String, MatterProgram> profile(int producerIntake, int producerMass, int producerLife,
                                                     int grazerIntake, int grazerMass, int grazerLife,
                                                     int decomposerIntake, int decomposerMass, int decomposerLife) {
        return Map.of("producer", new MatterProgram("producer", MatterProgram.Role.PRODUCER, producerIntake, producerMass, producerLife, 0),
                "grazer", new MatterProgram("grazer", MatterProgram.Role.GRAZER, grazerIntake, grazerMass, grazerLife, 0),
                "decomposer", new MatterProgram("decomposer", MatterProgram.Role.DECOMPOSER, decomposerIntake, decomposerMass, decomposerLife, 0));
    }

    record Milestone(long tick, Map<String, Integer> populationBySpecies, long births, long deaths, int maxGeneration) { }
    record Result(String profile, String worldId, long seed, Map<String, MatterProgram> programs,
                  Map<String, Long> firstSpeciesExtinction, List<Milestone> milestones, EcologyView finalState, boolean replayVerified) { }
}
