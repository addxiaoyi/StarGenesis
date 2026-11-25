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
import java.util.Random;
import java.util.TreeMap;

/** Bounded, deterministic ecology v2 validation; all runs, including extinctions, are retained. */
class EcologyLifecycleStudy {
    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 4) throw new IllegalArgumentException(
                "Usage: EcologyLifecycleStudy.java <output.json> [seeds:1-128] [profiles:comma-separated|all] [sampleSeed]");
        int count = args.length > 1 ? Integer.parseInt(args[1]) : 32;
        String profiles = args.length > 2 ? args[2] : "all";
        long sampleSeed = args.length > 3 ? Long.parseLong(args[3]) : 20_261_126L;
        if (count < 1 || count > 128) throw new IllegalArgumentException("Seed budget outside 1..128");
        var available = List.of("default", "mutation-max", "old-short-lifespans", "lifespan-1");
        var selected = profiles.equals("all") ? available : List.of(profiles.split(",", -1));
        if (!available.containsAll(selected) || new java.util.HashSet<>(selected).size() != selected.size())
            throw new IllegalArgumentException("Unknown or duplicate profile");
        var random = new Random(sampleSeed);
        var seeds = new ArrayList<Long>();
        var boundarySeeds = List.of(0L, 42L, Long.MIN_VALUE, Long.MAX_VALUE);
        for (int index = 0; index < count; index++) seeds.add(index < 4 ? boundarySeeds.get(index) : random.nextLong());
        Path output = Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectories(output.getParent());
        var mapper = new ObjectMapper();
        var results = new ArrayList<Result>();
        for (String profile : selected) for (int index = 0; index < seeds.size(); index++) {
            var result = run("ecology-v2-validation-" + index, seeds.get(index), profile);
            results.add(result);
            System.out.println("completed=" + results.size() + "/" + (count * selected.size()) + " profile=" + profile
                    + " seed=" + result.seed() + " tick=" + result.finalState().tick() + " population=" + result.finalState().populationBySpecies()
                    + " firstExtinction=" + result.firstSpeciesExtinction());
            var report = Map.of("engineVersion", EcologyWorld.ENGINE_VERSION, "config", EcologyConfig.forDuration(1536),
                    "sampleSeed", sampleSeed, "sampling", "0, 42, Long.MIN_VALUE, Long.MAX_VALUE, then java.util.Random(sampleSeed).nextLong(). Each seed uses a distinct world ID.",
                    "expectedCases", count * selected.size(), "complete", results.size() == count * selected.size(),
                    "cases", results, "scope", "Pure simulation. Every step checks matter and lifecycle budgets; every final replay including extinction is verified. No server, human, performance or indefinite-balance acceptance.");
            Files.writeString(output, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(report) + "\n");
        }
    }

    private static Result run(String worldId, long seed, String profile) {
        var firstExtinction = new TreeMap<String, Long>();
        var milestones = new ArrayList<Milestone>();
        var config = EcologyConfig.forDuration(1536);
        try (var world = new EcologyWorld(worldId, seed, config)) {
            Map<String, MatterProgram> programs = new TreeMap<>(world.view().programs());
            switch (profile) {
                case "default" -> { }
                case "mutation-max" -> programs.replaceAll((species, program) -> program.withParameters(
                        program.intake(), program.reproductionMass(), program.lifespanSteps(), 500));
                case "old-short-lifespans" -> {
                    programs.put("producer", new MatterProgram("producer", MatterProgram.Role.PRODUCER, 3, 5, 40, 150));
                    programs.put("grazer", new MatterProgram("grazer", MatterProgram.Role.GRAZER, 2, 6, 24, 100));
                    programs.put("decomposer", new MatterProgram("decomposer", MatterProgram.Role.DECOMPOSER, 2, 5, 32, 80));
                }
                case "lifespan-1" -> programs.replaceAll((species, program) -> program.withParameters(
                        program.intake(), program.reproductionMass(), 1, program.mutationPermille()));
                default -> throw new IllegalArgumentException("Unknown profile");
            }
            int maxEvolved = 0;
            while (!world.view().terminal()) {
                long tick = world.tick();
                var result = world.step(tick == 0 && !profile.equals("default") ? new EcologyInput(0, programs) : EcologyInput.idle(tick));
                var view = world.view();
                if (view.totalMatter() != config.initialMatter() || view.population() > config.maxOrganisms()
                        || view.births() > config.maxBirths() || view.maxGeneration() > config.maxGenerations()
                        || view.population() != EcologyConfig.INITIAL_POPULATION + view.births() - view.deaths()
                        || result.events().size() > EcologyWorld.MAX_EVENTS_PER_STEP) throw new IllegalStateException("Ecology invariant failed");
                maxEvolved = Math.max(maxEvolved, view.evolvedOrganisms());
                for (String species : programs.keySet()) if (view.populationBySpecies().getOrDefault(species, 0) == 0)
                    firstExtinction.putIfAbsent(species, world.tick());
                if (List.of(160L, 320L, 500L, 512L, 768L, 1024L, 1280L, 1536L).contains(view.tick()) || result.terminal())
                    milestones.add(new Milestone(view.tick(), view.populationBySpecies(), view.soilMatter(), view.detritusMatter(),
                            view.births(), view.deaths(), view.maxGeneration(), view.evolvedOrganisms()));
            }
            if (!EcologyReplayVerifier.verify(world.replay()).passed()) throw new IllegalStateException("Replay mismatch");
            return new Result(profile, worldId, seed, programs, firstExtinction, milestones, world.view(), maxEvolved, true);
        }
    }

    record Milestone(long tick, Map<String, Integer> populationBySpecies, long soilMatter, long detritusMatter,
                     long births, long deaths, int maxGeneration, int evolvedOrganisms) { }
    record Result(String profile, String worldId, long seed, Map<String, MatterProgram> programs,
                  Map<String, Long> firstSpeciesExtinction, List<Milestone> milestones, EcologyView finalState,
                  int maxEvolvedOrganisms, boolean replayVerified) { }
}
