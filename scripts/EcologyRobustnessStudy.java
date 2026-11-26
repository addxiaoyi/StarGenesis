import dev.stargenesis.internal.jackson.databind.ObjectMapper;
import dev.stargenesis.sim.EcologyConfig;
import dev.stargenesis.sim.EcologyInput;
import dev.stargenesis.sim.EcologyReplayVerifier;
import dev.stargenesis.sim.EcologyView;
import dev.stargenesis.sim.EcologyWorld;
import dev.stargenesis.sim.MatterProgram;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/** Predeclared held-out, paired one-at-a-time v2 study. Never tunes production defaults. */
class EcologyRobustnessStudy {
    private static final int TICKS = 1536;
    private static final long SAMPLE_SEED = 2_026_112_602L;
    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 4 || (args.length == 4 && !args[3].equals("--resume")))
            throw new IllegalArgumentException(
                    "Usage: EcologyRobustnessStudy.java <output.json> [baselineSeeds:1-128] [pairedSeeds:1-baselineSeeds] [--resume]");
        int baselineCount = args.length > 1 ? Integer.parseInt(args[1]) : 64;
        int pairedCount = args.length > 2 ? Integer.parseInt(args[2]) : 32;
        if (baselineCount < 1 || baselineCount > 128 || pairedCount < 1 || pairedCount > baselineCount)
            throw new IllegalArgumentException("Invalid seed budget");
        Path output = Path.of(args[0]).toAbsolutePath().normalize();
        Path planPath = output.resolveSibling(output.getFileName() + ".plan.json");
        boolean resume = args.length == 4;
        if (!resume && (Files.exists(output) || Files.exists(planPath)))
            throw new IllegalArgumentException("Refusing to overwrite prior study evidence");
        if (resume && (!Files.isRegularFile(output) || !Files.isRegularFile(planPath)))
            throw new IllegalArgumentException("Resume requires both the original plan and committed progress report");
        Files.createDirectories(output.getParent());
        var defaults = defaults();
        var excluded = new ArrayList<String>();
        var profiles = profiles(defaults, excluded);
        var seeds = seeds(baselineCount);
        int expected = baselineCount + (profiles.size() - 1) * pairedCount;
        var plan = new Plan(EcologyWorld.ENGINE_VERSION, EcologyConfig.forDuration(TICKS), SAMPLE_SEED,
                "Distinct java.util.Random(sampleSeed).nextLong(), excluding the 32 original v2 validation seeds and all prior generated values",
                seeds, baselineCount, pairedCount, profiles, excluded, expected,
                "Baseline uses every held-out seed; every OAT profile uses the same first pairedSeeds. Results are paired, not independent extra seeds. "
                        + "No adaptive search, parameter selection or default changes. All cases including extinction are retained. "
                        + "Pure simulation, finite horizon; no server, human, performance or indefinite-balance acceptance.");
        var scheduled = new ArrayList<PlannedCase>();
        for (var entry : profiles.entrySet()) {
            int count = entry.getKey().equals("default") ? baselineCount : pairedCount;
            for (int index = 0; index < count; index++)
                scheduled.add(new PlannedCase(entry.getKey(), index, seeds.get(index), entry.getValue()));
        }
        var results = new ArrayList<Result>();
        if (resume) {
            var previous = JSON.readValue(Files.readString(output), Report.class);
            var originalPlan = JSON.readValue(Files.readString(planPath), Plan.class);
            if (!plan.equals(originalPlan) || !plan.equals(previous.plan()) || previous.cases().size() > expected
                    || previous.complete() != (previous.cases().size() == expected))
                throw new IllegalArgumentException("Resume plan or progress count mismatch");
            for (int index = 0; index < previous.cases().size(); index++) {
                var saved = previous.cases().get(index);
                var item = scheduled.get(index);
                if (!saved.profile().equals(item.profile()) || saved.seed() != item.seed()
                        || !saved.worldId().equals("ecology-v2-robustness-" + item.index())
                        || !saved.replayVerified() || !saved.finalState().terminal())
                    throw new IllegalArgumentException("Resume report is not the planned completed prefix");
            }
            results.addAll(previous.cases());
            // Re-execute the last atomic checkpoint before trusting its continuation position.
            if (!results.isEmpty()) {
                var item = scheduled.get(results.size() - 1);
                var repeated = run("ecology-v2-robustness-" + item.index(), item.seed(), item.profile(), item.programs());
                if (!repeated.equals(results.getLast()))
                    throw new IllegalArgumentException("Resume checkpoint no longer matches the current engine");
            }
            System.out.println("resumed=" + results.size() + "/" + expected + " lastCheckpointReexecuted=true");
        } else {
            Files.writeString(planPath, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(plan) + "\n",
                    StandardOpenOption.CREATE_NEW);
        }
        for (int cursor = results.size(); cursor < scheduled.size(); cursor++) {
                var item = scheduled.get(cursor);
                var result = run("ecology-v2-robustness-" + item.index(), item.seed(), item.profile(), item.programs());
                results.add(result);
                Path temporary = output.resolveSibling(output.getFileName() + ".tmp");
                Files.writeString(temporary, JSON.writerWithDefaultPrettyPrinter().writeValueAsString(
                        new Report(plan, results.size() == expected, List.copyOf(results))) + "\n");
                Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                System.out.println("completed=" + results.size() + "/" + expected + " profile=" + item.profile()
                        + " seed=" + result.seed() + " population=" + result.finalState().populationBySpecies()
                        + " extinction=" + result.firstSpeciesExtinction());
        }
    }

    private static Map<String, MatterProgram> defaults() {
        try (var world = new EcologyWorld("ecology-robustness-plan", 0, EcologyConfig.forDuration(TICKS))) {
            return new TreeMap<>(world.view().programs());
        }
    }

    private static LinkedHashMap<String, Map<String, MatterProgram>> profiles(
            Map<String, MatterProgram> defaults, List<String> excluded) {
        var profiles = new LinkedHashMap<String, Map<String, MatterProgram>>();
        profiles.put("default", defaults);
        for (var original : defaults.values()) {
            for (String parameter : List.of("intake", "reproductionMass", "lifespanSteps")) {
                for (int sign : new int[]{-1, 1}) {
                    int intake = original.intake();
                    int mass = original.reproductionMass();
                    int lifespan = original.lifespanSteps();
                    String name = original.species() + "-" + parameter + (sign < 0 ? "-minus" : "-plus");
                    switch (parameter) {
                        case "intake" -> intake += sign;
                        case "reproductionMass" -> mass += sign;
                        case "lifespanSteps" -> lifespan += sign * Math.max(1, lifespan / 4);
                        default -> throw new IllegalStateException("Unknown parameter");
                    }
                    if (intake < 1 || intake > 8 || mass < 2 || mass > 32 || lifespan < 1 || lifespan > 512) {
                        excluded.add(name + ": outside existing public parameter bounds; not clamped or substituted");
                        continue;
                    }
                    var programs = new TreeMap<>(defaults);
                    programs.put(original.species(), original.withParameters(intake, mass, lifespan, original.mutationPermille()));
                    profiles.put(name, programs);
                }
            }
        }
        return profiles;
    }

    private static List<Long> seeds(int count) {
        var used = new LinkedHashSet<Long>(List.of(0L, 42L, Long.MIN_VALUE, Long.MAX_VALUE));
        var previous = new Random(20_261_126L);
        for (int index = 4; index < 32; index++) used.add(previous.nextLong());
        var selected = new ArrayList<Long>();
        var random = new Random(SAMPLE_SEED);
        while (selected.size() < count) {
            long seed = random.nextLong();
            if (used.add(seed)) selected.add(seed);
        }
        return List.copyOf(selected);
    }

    private static Result run(String worldId, long seed, String profile, Map<String, MatterProgram> programs) {
        var config = EcologyConfig.forDuration(TICKS);
        var firstExtinction = new TreeMap<String, Long>();
        var minimumPopulation = new TreeMap<String, Integer>();
        var milestones = new ArrayList<Milestone>();
        int maxGeneration = 0, maxPopulation = 0, maxEvents = 0, maxEvolved = 0;
        long maxBirths = 0;
        try (var world = new EcologyWorld(worldId, seed, config)) {
            minimumPopulation.putAll(world.view().populationBySpecies());
            while (!world.view().terminal()) {
                long tick = world.tick();
                var step = world.step(tick == 0 && !profile.equals("default")
                        ? new EcologyInput(0, programs) : EcologyInput.idle(tick));
                var view = world.view();
                if (view.totalMatter() != config.initialMatter()
                        || view.soilMatter() + view.detritusMatter() + view.organismMatter() != config.initialMatter()
                        || view.population() > config.maxOrganisms() || view.births() > config.maxBirths()
                        || view.maxGeneration() > config.maxGenerations()
                        || view.population() != EcologyConfig.INITIAL_POPULATION + view.births() - view.deaths()
                        || step.events().size() > EcologyWorld.MAX_EVENTS_PER_STEP)
                    throw new IllegalStateException("Ecology invariant failed: " + profile + "/" + seed + "/" + tick);
                maxGeneration = Math.max(maxGeneration, view.maxGeneration());
                maxPopulation = Math.max(maxPopulation, view.population());
                maxEvents = Math.max(maxEvents, step.events().size());
                maxEvolved = Math.max(maxEvolved, view.evolvedOrganisms());
                maxBirths = Math.max(maxBirths, view.births());
                for (String species : programs.keySet()) {
                    int population = view.populationBySpecies().getOrDefault(species, 0);
                    minimumPopulation.merge(species, population, Math::min);
                    if (population == 0) firstExtinction.putIfAbsent(species, view.tick());
                }
                if (view.tick() % 256 == 0 || step.terminal()) milestones.add(new Milestone(view.tick(),
                        view.populationBySpecies(), view.soilMatter(), view.detritusMatter(), view.births(),
                        view.deaths(), view.maxGeneration(), view.evolvedOrganisms()));
            }
            if (!EcologyReplayVerifier.verify(world.replay()).passed())
                throw new IllegalStateException("Ecology replay mismatch: " + profile + "/" + seed);
            return new Result(profile, worldId, seed, firstExtinction, minimumPopulation, maxPopulation,
                    maxGeneration, maxGeneration == config.maxGenerations(), maxBirths == config.maxBirths(),
                    maxEvents, maxEvolved, milestones, world.view(), true);
        }
    }

    record Plan(String engineVersion, EcologyConfig config, long sampleSeed, String sampling, List<Long> seeds,
                int baselineSeeds, int pairedSeeds, Map<String, Map<String, MatterProgram>> profiles,
                List<String> excludedProfiles, int expectedCases, String scope) { }
    record Report(Plan plan, boolean complete, List<Result> cases) { }
    record PlannedCase(String profile, int index, long seed, Map<String, MatterProgram> programs) { }
    record Milestone(long tick, Map<String, Integer> populationBySpecies, long soilMatter, long detritusMatter,
                     long births, long deaths, int maxGeneration, int evolvedOrganisms) { }
    record Result(String profile, String worldId, long seed, Map<String, Long> firstSpeciesExtinction,
                  Map<String, Integer> minimumPopulationBySpecies, int maxPopulation, int maxGenerationSeen,
                  boolean generationBudgetReached, boolean birthBudgetReached, int maxEventsPerStep,
                  int maxEvolvedOrganisms, List<Milestone> milestones, EcologyView finalState, boolean replayVerified) { }
}
