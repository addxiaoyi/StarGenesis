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
import java.util.Random;
import java.util.TreeMap;

/** Reproducible, bounded parameter search in the unchanged v1 engine. Survivors are hypotheses, not balance acceptance. */
class EcologyBalanceSearch {
    private static final long SEARCH_SEED = 20_261_125L;
    private static final String WORLD = "ecology-long-study-0";
    private static final long WORLD_SEED = 52_008L;

    public static void main(String[] args) throws Exception {
        if (args.length < 1 || args.length > 2) throw new IllegalArgumentException("Usage: EcologyBalanceSearch.java <output.json> [candidates:1-512]");
        int candidates = args.length == 2 ? Integer.parseInt(args[1]) : 128;
        if (candidates < 1 || candidates > 512) throw new IllegalArgumentException("Candidate budget outside 1..512");
        var random = new Random(SEARCH_SEED);
        List<Result> results = new ArrayList<>();
        List<Confirmation> confirmations = new ArrayList<>();
        Path output = Path.of(args[0]).toAbsolutePath().normalize();
        Files.createDirectories(output.getParent());
        var mapper = new ObjectMapper();
        for (int candidate = 0; candidate < candidates; candidate++) {
            Map<String, MatterProgram> profile = candidate == 0 ? original() : candidate == 1 ? allSlow() : randomProfile(random);
            Result result = run(candidate, WORLD, WORLD_SEED, profile);
            results.add(result);
            if (result.survived()) {
                for (int world = 0; world < 2; world++) for (int seed = 0; seed < 4; seed++) {
                    if (world == 0 && seed == 0) continue;
                    confirmations.add(new Confirmation(candidate, run(candidate, "ecology-long-study-" + world, WORLD_SEED + seed, profile)));
                }
            }
            if (candidate % 16 == 15 || result.survived() || candidate == candidates - 1) {
                System.out.println("completed=" + (candidate + 1) + " survivors=" + results.stream().filter(Result::survived).count()
                        + " latest=" + result.endTick() + " " + result.population());
                mapper.writerWithDefaultPrettyPrinter().writeValue(output.toFile(), Map.of(
                        "engineVersion", EcologyWorld.ENGINE_VERSION, "searchSeed", SEARCH_SEED,
                        "config", EcologyConfig.forDuration(EcologyConfig.MAX_TICKS),
                        "sampling", "Candidates 0/1 are previous/delayed controls; others use java.util.Random with intake in 1,2,3,4,6,8; mass in 4,8,12,16,24,32; lifespan in 256,320,384,448,512; mutation zero.",
                        "programTiming", "producer/grazer/decomposer applied at ticks 0/1/2; no later intervention",
                        "scope", "Unchanged engine parameter search. Stop a case at its first species extinction; survivors reach 1536 and their full replay is verified. Search-world survivors are checked on seven additional fixed world/seed combinations. No server, human or indefinite-balance acceptance.",
                        "cases", results, "confirmations", confirmations));
            }
        }
    }

    private static Result run(int candidate, String worldId, long seed, Map<String, MatterProgram> profile) {
        var population = new TreeMap<>(Map.of("producer", 3, "grazer", 2, "decomposer", 2));
        var milestones = new TreeMap<Long, Map<String, Integer>>();
        try (var world = new EcologyWorld(worldId, seed, EcologyConfig.forDuration(EcologyConfig.MAX_TICKS))) {
            while (world.tick() < EcologyConfig.MAX_TICKS) {
                long tick = world.tick();
                String species = tick == 0 ? "producer" : tick == 1 ? "grazer" : tick == 2 ? "decomposer" : null;
                var result = world.step(species == null ? EcologyInput.idle(tick) : new EcologyInput(tick, Map.of(species, profile.get(species))));
                result.events().forEach(event -> {
                    if (event.type().equals("ecology.organism_born")) population.merge(event.data().get("species"), 1, Integer::sum);
                    if (event.type().equals("ecology.organism_died")) population.merge(event.data().get("species"), -1, Integer::sum);
                });
                if (world.tick() == 500 || world.tick() == 512 || world.tick() == 1024 || world.tick() == 1536)
                    milestones.put(world.tick(), Map.copyOf(population));
                if (population.values().stream().anyMatch(value -> value == 0) || result.terminal()) break;
            }
            var view = world.view();
            if (view.totalMatter() != world.config().initialMatter()) throw new IllegalStateException("Matter conservation failed");
            population.forEach((species, count) -> {
                if (count != view.populationBySpecies().getOrDefault(species, 0)) throw new IllegalStateException("Population event accounting failed");
            });
            boolean survived = world.tick() == EcologyConfig.MAX_TICKS && population.values().stream().allMatch(value -> value > 0);
            boolean replayVerified = survived && EcologyReplayVerifier.verify(world.replay()).passed();
            if (survived && !replayVerified) throw new IllegalStateException("Survivor replay mismatch");
            return new Result(candidate, worldId, seed, profile, world.tick(), Map.copyOf(population), milestones,
                    view.births(), view.deaths(), view.maxGeneration(), view.totalMatter(), survived, replayVerified);
        }
    }

    private static Map<String, MatterProgram> randomProfile(Random random) {
        var result = new TreeMap<String, MatterProgram>();
        for (var role : MatterProgram.Role.values()) {
            String species = role.name().toLowerCase(java.util.Locale.ROOT);
            int intake = pick(random, 1, 2, 3, 4, 6, 8), mass = pick(random, 4, 8, 12, 16, 24, 32);
            int life = pick(random, 256, 320, 384, 448, 512);
            result.put(species, new MatterProgram(species, role, intake, mass, life, 0));
        }
        return result;
    }

    private static int pick(Random random, int... values) { return values[random.nextInt(values.length)]; }
    private static Map<String, MatterProgram> original() {
        return Map.of("producer", new MatterProgram("producer", MatterProgram.Role.PRODUCER, 6, 2, 512, 0),
                "grazer", new MatterProgram("grazer", MatterProgram.Role.GRAZER, 1, 12, 512, 0),
                "decomposer", new MatterProgram("decomposer", MatterProgram.Role.DECOMPOSER, 1, 12, 512, 0));
    }
    private static Map<String, MatterProgram> allSlow() {
        return Map.of("producer", new MatterProgram("producer", MatterProgram.Role.PRODUCER, 1, 32, 512, 0),
                "grazer", new MatterProgram("grazer", MatterProgram.Role.GRAZER, 1, 32, 512, 0),
                "decomposer", new MatterProgram("decomposer", MatterProgram.Role.DECOMPOSER, 1, 32, 512, 0));
    }

    record Result(int candidate, String worldId, long seed, Map<String, MatterProgram> programs, long endTick,
                  Map<String, Integer> population, Map<Long, Map<String, Integer>> milestones,
                  long births, long deaths, int maxGeneration, long totalMatter, boolean survived, boolean replayVerified) { }
    record Confirmation(int candidate, Result result) { }
}
