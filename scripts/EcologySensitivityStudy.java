import dev.stargenesis.internal.jackson.databind.ObjectMapper;
import dev.stargenesis.sim.EcologyConfig;
import dev.stargenesis.sim.EcologyInput;
import dev.stargenesis.sim.EcologyReplayVerifier;
import dev.stargenesis.sim.EcologyWorld;
import dev.stargenesis.sim.MatterProgram;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** One-parameter sensitivity study against known extinction cases, in the unchanged ecology v1 engine. */
class EcologySensitivityStudy {
    private static final List<Scenario> SCENARIOS = List.of(
            new Scenario("ecology-long-study-2", 52008),
            new Scenario("ecology-long-study-3", 52016),
            new Scenario("ecology-long-study-1", 52016),
            new Scenario("ecology-long-study-0", 52008));

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2)
            throw new IllegalArgumentException("Usage: EcologySensitivityStudy.java <output.json> [candidates:1-64]");
        int count = args.length == 2 ? Integer.parseInt(args[1]) : 64;
        if (count < 1 || count > 64) throw new IllegalArgumentException("Study budget outside 1..64");
        var baseline = Map.of(
                "producer", new MatterProgram("producer", MatterProgram.Role.PRODUCER, 6, 4, 320, 0),
                "grazer", new MatterProgram("grazer", MatterProgram.Role.GRAZER, 4, 24, 512, 0),
                "decomposer", new MatterProgram("decomposer", MatterProgram.Role.DECOMPOSER, 3, 4, 320, 0));
        List<Candidate> candidates = new ArrayList<>();
        candidates.add(new Candidate("baseline", baseline));
        for (String trait : List.of("lifespan", "reproductionMass", "intake")) {
            int[] values = switch (trait) {
                case "lifespan" -> new int[]{256, 288, 320, 352, 384, 448, 512};
                case "reproductionMass" -> new int[]{2, 3, 4, 6, 8, 12, 16, 24, 32};
                default -> new int[]{1, 2, 3, 4, 5, 6, 7, 8};
            };
            for (String species : List.of("producer", "grazer", "decomposer")) {
                MatterProgram original = baseline.get(species);
                for (int value : values) {
                    MatterProgram program = original.withParameters(trait.equals("intake") ? value : original.intake(),
                            trait.equals("reproductionMass") ? value : original.reproductionMass(),
                            trait.equals("lifespan") ? value : original.lifespanSteps(), 0);
                    if (program.equals(original)) continue;
                    var programs = new TreeMap<>(baseline);
                    programs.put(species, program);
                    candidates.add(new Candidate(species + "." + trait + "=" + value, programs));
                }
            }
        }
        if (candidates.size() != 64) throw new IllegalStateException("Unexpected parameter grid");
        Path output = Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectories(output.getParent());
        var mapper = new ObjectMapper();
        List<Result> results = new ArrayList<>();
        for (Candidate candidate : candidates.subList(0, count)) {
            List<Run> runs = new ArrayList<>();
            for (Scenario scenario : SCENARIOS) {
                Run run = run(scenario, candidate.programs());
                runs.add(run);
                if (!run.survived()) break;
            }
            boolean passed = runs.size() == SCENARIOS.size() && runs.stream().allMatch(Run::survived);
            results.add(new Result(candidate, runs, passed));
            Run last = runs.getLast();
            System.out.println("completed=" + results.size() + " candidate=" + candidate.change() + " passed=" + passed
                    + " checked=" + runs.size() + " end=" + last.endTick() + " population=" + last.populationBySpecies());
            mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), Map.of(
                    "engineVersion", EcologyWorld.LEGACY_ENGINE_VERSION, "config", EcologyConfig.forDuration(1536),
                    "orderedScenarios", SCENARIOS, "cases", results,
                    "programTiming", "producer/grazer/decomposer applied at ticks 0/1/2; no later intervention",
                    "scope", "One parameter differs from the prior long-cycle candidate. Profiles stop at the first failing scenario; individual runs stop at first species extinction. All executed final replays verified. Four selected cases, not random sampling or balance acceptance."));
        }
    }

    private static Run run(Scenario scenario, Map<String, MatterProgram> programs) {
        var population = new TreeMap<>(Map.of("producer", 3, "grazer", 2, "decomposer", 2));
        var generations = new TreeMap<String, Map<Integer, Integer>>();
        population.forEach((species, value) -> generations.put(species, new TreeMap<>(Map.of(0, value))));
        var lastBirth = new TreeMap<String, Long>();
        List<Milestone> milestones = new ArrayList<>();
        try (var world = new EcologyWorld(scenario.worldId(), scenario.seed(), EcologyConfig.forDuration(1536), EcologyWorld.LEGACY_ENGINE_VERSION)) {
            while (world.tick() < 1536) {
                long tick = world.tick();
                String species = tick == 0 ? "producer" : tick == 1 ? "grazer" : tick == 2 ? "decomposer" : null;
                var result = world.step(species == null ? EcologyInput.idle(tick) : new EcologyInput(tick, Map.of(species, programs.get(species))));
                result.events().forEach(event -> {
                    int change = event.type().equals("ecology.organism_born") ? 1 : event.type().equals("ecology.organism_died") ? -1 : 0;
                    if (change == 0) return;
                    String name = event.data().get("species");
                    int generation = Integer.parseInt(event.data().get("generation"));
                    population.merge(name, change, Integer::sum);
                    generations.get(name).merge(generation, change, Integer::sum);
                    if (change > 0) lastBirth.put(name, tick);
                });
                boolean extinct = population.values().stream().anyMatch(value -> value == 0);
                if (world.tick() == 500 || world.tick() == 512 || world.tick() == 1024 || world.tick() == 1280
                        || world.tick() == 1536 || extinct) {
                    var view = world.view();
                    if (view.totalMatter() != 1000) throw new IllegalStateException("Matter mismatch");
                    var counts = new TreeMap<String, Map<Integer, Integer>>();
                    generations.forEach((name, values) -> counts.put(name, Map.copyOf(values)));
                    milestones.add(new Milestone(world.tick(), Map.copyOf(population), counts, Map.copyOf(lastBirth)));
                }
                if (extinct || result.terminal()) break;
            }
            var view = world.view();
            population.forEach((species, value) -> {
                if (value != view.populationBySpecies().getOrDefault(species, 0)) throw new IllegalStateException("Population accounting mismatch");
            });
            boolean survived = world.tick() == 1536 && population.values().stream().allMatch(value -> value > 0);
            if (!EcologyReplayVerifier.verify(world.replay()).passed()) throw new IllegalStateException("Replay mismatch");
            return new Run(scenario, world.tick(), survived, Map.copyOf(population), milestones, view.births(), view.deaths(), true);
        }
    }

    record Scenario(String worldId, long seed) { }
    record Candidate(String change, Map<String, MatterProgram> programs) { }
    record Milestone(long tick, Map<String, Integer> population, Map<String, Map<Integer, Integer>> generations,
                     Map<String, Long> lastBirthTick) { }
    record Run(Scenario scenario, long endTick, boolean survived, Map<String, Integer> populationBySpecies,
               List<Milestone> milestones, long births, long deaths, boolean replayVerified) { }
    record Result(Candidate candidate, List<Run> runs, boolean passed) { }
}
