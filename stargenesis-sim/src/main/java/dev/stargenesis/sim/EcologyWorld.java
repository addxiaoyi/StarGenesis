package dev.stargenesis.sim;

import dev.stargenesis.api.design.DesignId;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** Deterministic, closed-matter ecology with finite inherited programs and per-step work bounds. */
public final class EcologyWorld implements AutoCloseable {
    public static final String ENGINE_VERSION = "ecology/4";
    public static final int HABITAT_WIDTH = 8;
    public static final int HABITAT_HEIGHT = 8;
    public static final int HABITAT_CELL_COUNT = HABITAT_WIDTH * HABITAT_HEIGHT;
    public static final int MAX_POPULATION_PER_CELL = 8;
    public static final int MAX_EVENTS_PER_STEP = 1_025;
    public static final DesignId EDIBLE_MATTER_ID = new DesignId("ecology:nutrient");
    private static final long MUTATION_ROLL_SALT = 0x243f6a8885a308d3L;
    private static final long MUTATION_GENE_SALT = 0x13198a2e03707344L;
    private static final long MOVEMENT_ROLL_SALT = 0xa4093822299f31d0L;

    private final OwnerThread owner = new OwnerThread();
    private EcologySettings settings;
    private final long conservedMatter;
    private final long conservedReactionMatter;
    private final long energyBaseline;
    private final TreeMap<Long, MutableOrganism> organisms = new TreeMap<>();
    private MatterReactionNetwork reactionNetwork;
    private final TreeMap<DesignId, Long> reactionInventory = new TreeMap<>();
    private final long[] cellNutrient = new long[HABITAT_CELL_COUNT];
    private final long[] cellWaste = new long[HABITAT_CELL_COUNT];
    private long tick;
    private long availableEnergy;
    private long energyInput;
    private long heatReleased;
    private long nextOrganismId;
    private long births;
    private long deaths;
    private long foodConsumed;
    private long decomposed;
    private boolean closed;

    public EcologyWorld(EcologySettings settings) {
        this(Objects.requireNonNull(settings, "settings"), totalFounderEnergy(settings));
    }

    private EcologyWorld(EcologySettings settings, long energyBaseline) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.energyBaseline = energyBaseline;
        reactionNetwork = new MatterReactionNetwork(settings.reactionSpecies(), settings.matterReactions());
        reactionInventory.putAll(reactionNetwork.canonicalInventory(settings.initialReactionInventory()));
        conservedReactionMatter = reactionNetwork.matterMass(reactionInventory);
        distributeMatter(settings.initialNutrient(), cellNutrient, settings.seed(), settings.founders().size());
        conservedMatter = Math.addExact(Math.addExact(settings.initialNutrient(), settings.founders().size()),
                conservedReactionMatter);
        for (EcologyFounder founder : settings.founders()) {
            long id = nextOrganismId++;
            int habitatCell = (int) ((id + settings.seed()) & (HABITAT_CELL_COUNT - 1));
            organisms.put(id, new MutableOrganism(id, founder.genome(), 0, founder.initialEnergy(), habitatCell));
        }
    }

    private EcologyWorld(EcologyCheckpoint checkpoint) {
        settings = checkpoint.settings();
        EcologySnapshot snapshot = checkpoint.snapshot();
        if (!ENGINE_VERSION.equals(snapshot.engineVersion()) || !settings.worldId().equals(snapshot.worldId())
                || settings.seed() != snapshot.seed() || snapshot.tick() > settings.maxSteps()) {
            throw new IllegalArgumentException("Ecology checkpoint does not match its settings");
        }
        reactionNetwork = new MatterReactionNetwork(settings.reactionSpecies(), settings.matterReactions());
        Map<DesignId, Long> initialReactionInventory = reactionNetwork.canonicalInventory(
                settings.initialReactionInventory());
        conservedReactionMatter = reactionNetwork.matterMass(initialReactionInventory);
        reactionInventory.putAll(reactionNetwork.canonicalInventory(snapshot.reactionInventory()));
        if (!reactionInventory.equals(snapshot.reactionInventory())) {
            throw new IllegalArgumentException("Ecology checkpoint reaction inventory is not canonical");
        }
        conservedMatter = Math.addExact(Math.addExact(settings.initialNutrient(), settings.founders().size()),
                conservedReactionMatter);
        for (EcologyHabitatCell cell : snapshot.habitatCells()) {
            cellNutrient[cell.id()] = cell.nutrient();
            cellWaste[cell.id()] = cell.waste();
        }
        energyBaseline = checkpoint.energyBaseline();
        tick = snapshot.tick();
        births = snapshot.statistics().births();
        deaths = snapshot.statistics().deaths();
        foodConsumed = snapshot.statistics().foodConsumed();
        decomposed = snapshot.statistics().decomposed();
        availableEnergy = snapshot.statistics().availableEnergy();
        energyInput = snapshot.statistics().energyInput();
        heatReleased = snapshot.statistics().heatReleased();
        long stepCounterLimit = Math.multiplyExact(settings.maxPopulation(), settings.maxSteps());
        long decompositionLimit = Math.multiplyExact(
                Math.multiplyExact(settings.decompositionPerStep(), HABITAT_CELL_COUNT), settings.maxSteps());
        try {
            nextOrganismId = Math.addExact(settings.founders().size(), births);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Ecology checkpoint organism sequence overflows", overflow);
        }
        if (snapshot.organisms().size() > settings.maxPopulation()
                || snapshot.statistics().conservedMatter() != conservedMatter
                || births < deaths || births - deaths + settings.founders().size() != snapshot.organisms().size()
                || matterTotal(snapshot.nutrient(), snapshot.waste(), snapshot.organisms().size())
                + reactionNetwork.matterMass(reactionInventory) != conservedMatter
                || births > stepCounterLimit || deaths > stepCounterLimit || foodConsumed > stepCounterLimit
                || decomposed > decompositionLimit || births > snapshot.tick() * settings.maxPopulation()
                || deaths > snapshot.tick() * settings.maxPopulation()
                || foodConsumed > snapshot.tick() * settings.maxPopulation()
                || decomposed > snapshot.tick() * settings.decompositionPerStep() * HABITAT_CELL_COUNT
                || energyInput > snapshot.tick() * settings.energyInputPerStep()) {
            throw new IllegalArgumentException("Ecology checkpoint violates population or matter conservation");
        }
        for (EcologyOrganism organism : snapshot.organisms()) {
            if (organism.id() >= nextOrganismId || organism.age() >= organism.genome().lifespanTicks()) {
                throw new IllegalArgumentException("Ecology checkpoint contains an invalid organism");
            }
            organisms.put(organism.id(), new MutableOrganism(organism.id(), organism.genome(),
                    organism.age(), organism.energy(), organism.habitatCell()));
        }
        long expectedEnergy;
        try {
            expectedEnergy = Math.addExact(energyBaseline, energyInput);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Ecology checkpoint energy baseline overflows", overflow);
        }
        if (snapshot.statistics().conservedEnergy() != expectedEnergy
                || !snapshot.stateHash().equals(stateHashInternal())
                || !statisticsInternal().equals(snapshot.statistics())) {
            throw new IllegalArgumentException("Ecology checkpoint hash or statistics mismatch");
        }
    }

    public static EcologyWorld restore(EcologyCheckpoint checkpoint) {
        Objects.requireNonNull(checkpoint, "checkpoint");
        return new EcologyWorld(checkpoint);
    }

    static EcologyCheckpoint migrateLegacyCheckpoint(EcologySettings settings, long tick, long nutrient, long waste,
                                                       List<EcologyOrganism> organisms, long births, long deaths,
                                                       long foodConsumed, long decomposed, long energyBaseline) {
        try (EcologyWorld world = new EcologyWorld(settings, energyBaseline)) {
            world.tick = tick;
            world.setMigratedMatter(nutrient, waste);
            world.setMigratedOrganisms(organisms);
            world.nextOrganismId = Math.addExact(settings.founders().size(), births);
            world.births = births;
            world.deaths = deaths;
            world.foodConsumed = foodConsumed;
            world.decomposed = decomposed;
            return world.checkpoint();
        }
    }

    static EcologyCheckpoint migratePreviousCheckpoint(EcologySettings settings, long tick, long nutrient, long waste,
                                                         List<EcologyOrganism> organisms, EcologyStatistics statistics,
                                                         long energyBaseline) {
        try (EcologyWorld world = new EcologyWorld(settings, energyBaseline)) {
            world.tick = tick;
            world.setMigratedMatter(nutrient, waste);
            world.setMigratedOrganisms(organisms);
            world.nextOrganismId = Math.addExact(settings.founders().size(), statistics.births());
            world.births = statistics.births();
            world.deaths = statistics.deaths();
            world.foodConsumed = statistics.foodConsumed();
            world.decomposed = statistics.decomposed();
            world.availableEnergy = statistics.availableEnergy();
            world.energyInput = statistics.energyInput();
            world.heatReleased = statistics.heatReleased();
            return world.checkpoint();
        }
    }

    static EcologyCheckpoint migrateVersionThreeCheckpoint(EcologySettings settings, long tick, long nutrient,
                                                             long waste, Map<DesignId, Long> reactionInventory,
                                                             List<EcologyOrganism> organisms,
                                                             EcologyStatistics statistics, long energyBaseline) {
        try (EcologyWorld world = new EcologyWorld(settings, energyBaseline)) {
            world.tick = tick;
            world.setMigratedMatter(nutrient, waste);
            world.reactionInventory.clear();
            world.reactionInventory.putAll(world.reactionNetwork.canonicalInventory(reactionInventory));
            world.setMigratedOrganisms(organisms);
            world.nextOrganismId = Math.addExact(settings.founders().size(), statistics.births());
            world.births = statistics.births();
            world.deaths = statistics.deaths();
            world.foodConsumed = statistics.foodConsumed();
            world.decomposed = statistics.decomposed();
            world.availableEnergy = statistics.availableEnergy();
            world.energyInput = statistics.energyInput();
            world.heatReleased = statistics.heatReleased();
            return world.checkpoint();
        }
    }

    private void setMigratedMatter(long nutrient, long waste) {
        java.util.Arrays.fill(cellNutrient, 0);
        java.util.Arrays.fill(cellWaste, 0);
        cellNutrient[0] = nutrient;
        cellWaste[0] = waste;
    }

    private void setMigratedOrganisms(List<EcologyOrganism> migrated) {
        organisms.clear();
        for (EcologyOrganism organism : migrated) {
            organisms.put(organism.id(), new MutableOrganism(organism.id(), organism.genome(),
                    organism.age(), organism.energy(), 0));
        }
    }

    public EcologySnapshot snapshot() {
        requireOpen();
        return snapshotInternal();
    }

    public EcologyCheckpoint checkpoint() {
        requireOpen();
        return new EcologyCheckpoint(settings, snapshotInternal(), energyBaseline);
    }

    public String stateHash() {
        requireOpen();
        return stateHashInternal();
    }

    public EcologyStepResult step() {
        requireOpen();
        if (tick >= settings.maxSteps()) throw new IllegalStateException("Ecology step budget exhausted");
        if (organisms.isEmpty()) throw new IllegalStateException("Ecology is extinct");

        List<EcologyEvent> events = new ArrayList<>();
        energyInput = Math.addExact(energyInput, settings.energyInputPerStep());
        availableEnergy = Math.addExact(availableEnergy, settings.energyInputPerStep());
        MatterReactionStep reactionStep = reactionNetwork.applyStep(reactionInventory);
        reactionInventory.clear();
        reactionInventory.putAll(reactionStep.inventory());
        absorbEdibleMatter();
        decompose(events);
        List<MutableOrganism> startingPopulation = new ArrayList<>(organisms.values());
        TreeMap<Long, MutableOrganism> survivors = new TreeMap<>();
        for (MutableOrganism organism : startingPopulation) {
            int habitatCell = organism.habitatCell;
            long age = Math.addExact(organism.age, 1);
            if (cellNutrient[habitatCell] > 0 && availableEnergy > 0) {
                cellNutrient[habitatCell]--;
                cellWaste[habitatCell]++;
                long gainedEnergy = Math.min(organism.genome.energyPerFood(), availableEnergy);
                availableEnergy -= gainedEnergy;
                organism.energy = Math.addExact(organism.energy, gainedEnergy);
                foodConsumed++;
                events.add(new EcologyEvent(EcologyEvent.Type.FED, organism.id, 1));
            }
            if (organism.energy < organism.genome.maintenanceEnergy()) {
                heatReleased = Math.addExact(heatReleased, organism.energy);
                organism.energy = 0;
                deaths++;
                cellNutrient[habitatCell]++;
                events.add(new EcologyEvent(EcologyEvent.Type.STARVED, organism.id, 1));
                continue;
            }
            organism.energy -= organism.genome.maintenanceEnergy();
            heatReleased = Math.addExact(heatReleased, organism.genome.maintenanceEnergy());
            if (age >= organism.genome.lifespanTicks()) {
                heatReleased = Math.addExact(heatReleased, organism.energy);
                organism.energy = 0;
                deaths++;
                cellNutrient[habitatCell]++;
                events.add(new EcologyEvent(EcologyEvent.Type.AGED, organism.id, 1));
                continue;
            }
            organism.age = age;
            survivors.put(organism.id, organism);
        }
        organisms.clear();
        organisms.putAll(survivors);
        reproduce(events);
        moveOrganisms();
        tick++;
        if (events.size() > MAX_EVENTS_PER_STEP) throw new IllegalStateException("Ecology event budget exceeded");

        EcologyStatistics statistics = statisticsInternal();
        String terminalReason = organisms.isEmpty() ? "EXTINCT"
                : tick >= settings.maxSteps() ? "MAX_STEPS_REACHED" : "";
        return new EcologyStepResult(tick, events, statistics, reactionStep.events(), stateHashInternal(),
                !terminalReason.isEmpty(), terminalReason);
    }

    public void editGenome(long organismId, int trait, int direction) {
        requireOpen();
        if (tick >= settings.maxSteps()) throw new IllegalStateException("Ecology step budget exhausted");
        MutableOrganism organism = organisms.get(organismId);
        if (organism == null) throw new IllegalArgumentException("Unknown or extinct ecology organism");
        organism.genome = organism.genome.withAdjustedTrait(trait, direction);
    }

    public void addReaction(MatterReaction reaction) {
        requireOpen();
        requireEditable();
        Objects.requireNonNull(reaction, "reaction");
        List<MatterReaction> reactions = new ArrayList<>(settings.matterReactions());
        reactions.add(reaction);
        replaceReactionNetwork(reactions);
    }

    public void installReactionCatalog(List<MatterSpecies> species) {
        requireOpen();
        requireEditable();
        if (!settings.reactionSpecies().isEmpty() || !settings.matterReactions().isEmpty()
                || reactionInventory.values().stream().anyMatch(amount -> amount != 0)) {
            throw new IllegalStateException("An ecology reaction catalog is already installed");
        }
        Map<DesignId, Long> emptyInventory = new TreeMap<>();
        for (MatterSpecies matter : Objects.requireNonNull(species, "species")) {
            emptyInventory.put(matter.id(), 0L);
        }
        EcologySettings updatedSettings = settings.withReactionNetwork(species, List.of(), emptyInventory);
        replaceReactionNetwork(updatedSettings);
    }

    public void removeReaction(DesignId reactionId) {
        requireOpen();
        requireEditable();
        Objects.requireNonNull(reactionId, "reactionId");
        List<MatterReaction> reactions = new ArrayList<>(settings.matterReactions());
        if (!reactions.removeIf(reaction -> reaction.id().equals(reactionId))) {
            throw new IllegalArgumentException("Unknown ecology reaction");
        }
        replaceReactionNetwork(reactions);
    }

    @Override public void close() {
        owner.check();
        closed = true;
    }

    private void moveOrganisms() {
        int[] populationByCell = new int[HABITAT_CELL_COUNT];
        for (MutableOrganism organism : organisms.values()) populationByCell[organism.habitatCell]++;
        for (MutableOrganism organism : organisms.values()) {
            int currentCell = organism.habitatCell;
            int selectedCell = currentCell;
            long highestNutrient = cellNutrient[currentCell];
            int selectedPopulation = populationByCell[currentCell];
            int firstDirection = (int) (mix64(settings.seed() ^ (tick + 1) * MOVEMENT_ROLL_SALT
                    ^ organism.id) & 3);
            for (int offset = 0; offset < 4; offset++) {
                int candidate = adjacentCell(currentCell, (firstDirection + offset) & 3);
                long candidateSupply = Math.multiplyExact(cellNutrient[candidate], selectedPopulation + 1L);
                long selectedSupply = Math.multiplyExact(highestNutrient, populationByCell[candidate] + 1L);
                if (candidateSupply > selectedSupply
                        || candidateSupply == selectedSupply
                        && populationByCell[candidate] < selectedPopulation) {
                    selectedCell = candidate;
                    highestNutrient = cellNutrient[candidate];
                    selectedPopulation = populationByCell[candidate];
                }
            }
            if (selectedCell != currentCell) {
                populationByCell[currentCell]--;
                populationByCell[selectedCell]++;
            }
            organism.habitatCell = selectedCell;
        }
    }

    private static int adjacentCell(int currentCell, int direction) {
        int x = currentCell % HABITAT_WIDTH;
        int y = currentCell / HABITAT_WIDTH;
        return switch (direction) {
            case 0 -> y * HABITAT_WIDTH + (x + 1) % HABITAT_WIDTH;
            case 1 -> y * HABITAT_WIDTH + Math.floorMod(x - 1, HABITAT_WIDTH);
            case 2 -> ((y + 1) % HABITAT_HEIGHT) * HABITAT_WIDTH + x;
            default -> Math.floorMod(y - 1, HABITAT_HEIGHT) * HABITAT_WIDTH + x;
        };
    }

    private void decompose(List<EcologyEvent> events) {
        for (int habitatCell = 0; habitatCell < HABITAT_CELL_COUNT; habitatCell++) {
            long amount = Math.min(cellWaste[habitatCell], settings.decompositionPerStep());
            if (amount == 0) continue;
            cellWaste[habitatCell] -= amount;
            cellNutrient[habitatCell] = Math.addExact(cellNutrient[habitatCell], amount);
            decomposed = Math.addExact(decomposed, amount);
            events.add(new EcologyEvent(EcologyEvent.Type.DECOMPOSED, -1, amount));
        }
    }

    private void absorbEdibleMatter() {
        MatterSpecies edible = reactionNetwork.species().stream()
                .filter(species -> species.id().equals(EDIBLE_MATTER_ID)).findFirst().orElse(null);
        if (edible == null) return;
        if (edible.massPerUnit() != 1) {
            throw new IllegalStateException("Ecology nutrient matter must have unit mass");
        }
        long amount = reactionInventory.get(EDIBLE_MATTER_ID);
        if (amount == 0) return;
        reactionInventory.put(EDIBLE_MATTER_ID, 0L);
        int habitatCell = leastNourishedCell();
        cellNutrient[habitatCell] = Math.addExact(cellNutrient[habitatCell], amount);
    }

    private int leastNourishedCell() {
        int start = (int) Math.floorMod(settings.seed() + tick, HABITAT_CELL_COUNT);
        int selected = start;
        for (int offset = 1; offset < HABITAT_CELL_COUNT; offset++) {
            int candidate = (start + offset) & (HABITAT_CELL_COUNT - 1);
            if (cellNutrient[candidate] < cellNutrient[selected]) selected = candidate;
        }
        return selected;
    }

    private void replaceReactionNetwork(List<MatterReaction> reactions) {
        replaceReactionNetwork(settings.withMatterReactions(reactions));
    }

    private void replaceReactionNetwork(EcologySettings updatedSettings) {
        MatterReactionNetwork updatedNetwork = new MatterReactionNetwork(
                updatedSettings.reactionSpecies(), updatedSettings.matterReactions());
        Map<DesignId, Long> updatedInventory = updatedNetwork.canonicalInventory(reactionInventory);
        settings = updatedSettings;
        reactionNetwork = updatedNetwork;
        reactionInventory.clear();
        reactionInventory.putAll(updatedInventory);
    }

    private void requireEditable() {
        if (tick >= settings.maxSteps()) throw new IllegalStateException("Ecology step budget exhausted");
    }

    private void reproduce(List<EcologyEvent> events) {
        int[] populationByCell = new int[HABITAT_CELL_COUNT];
        for (MutableOrganism organism : organisms.values()) populationByCell[organism.habitatCell]++;
        for (MutableOrganism parent : new ArrayList<>(organisms.values())) {
            int habitatCell = parent.habitatCell;
            if (organisms.size() >= settings.maxPopulation() || cellNutrient[habitatCell] == 0
                    || populationByCell[habitatCell] >= MAX_POPULATION_PER_CELL
                    || parent.age < parent.genome.maturityTicks()
                    || parent.energy < settings.reproductionEnergy()) continue;
            parent.energy -= settings.reproductionEnergy();
            cellNutrient[habitatCell]--;
            EcologyGenome genome = mutate(parent.genome, parent.id);
            long childId = nextOrganismId++;
            organisms.put(childId, new MutableOrganism(childId, genome, 0,
                    settings.reproductionEnergy(), habitatCell));
            populationByCell[habitatCell]++;
            births++;
            events.add(new EcologyEvent(EcologyEvent.Type.BORN, childId, 1));
        }
    }

    private EcologyGenome mutate(EcologyGenome genome, long parentId) {
        long roll = mix64(settings.seed() ^ (tick + 1) * MUTATION_ROLL_SALT ^ parentId);
        if (Math.floorMod(roll, 1_000) >= settings.mutationPermille()) return genome;
        int trait = (int) Math.floorMod(mix64(roll ^ MUTATION_GENE_SALT), 4);
        int direction = (mix64(roll + MUTATION_GENE_SALT) & 1L) == 0 ? -1 : 1;
        return genome.mutate(trait, direction);
    }

    private EcologySnapshot snapshotInternal() {
        List<EcologyOrganism> views = organisms.entrySet().stream()
                .map(entry -> new EcologyOrganism(entry.getKey(), entry.getValue().genome,
                        entry.getValue().age, entry.getValue().energy, entry.getValue().habitatCell)).toList();
        return new EcologySnapshot(ENGINE_VERSION, settings.worldId(), settings.seed(), tick,
                totalNutrient(), totalWaste(), views, reactionInventory, habitatSnapshot(),
                statisticsInternal(), stateHashInternal());
    }

    private EcologyStatistics statisticsInternal() {
        int speciesCount = (int) organisms.values().stream().map(organism -> organism.genome).distinct().count();
        long organismEnergy = 0;
        long assimilation = 0;
        for (MutableOrganism organism : organisms.values()) {
            organismEnergy = Math.addExact(organismEnergy, organism.energy);
            assimilation = Math.addExact(assimilation, organism.genome.energyPerFood());
        }
        long population = organisms.size();
        long meanEnergy = population == 0 ? 0 : organismEnergy / population;
        int meanAssimilation = population == 0 ? 0 : Math.toIntExact(assimilation * 1_000 / population);
        long conservedEnergy = Math.addExact(energyBaseline, energyInput);
        return new EcologyStatistics(organisms.size(), speciesCount, meanEnergy, meanAssimilation,
                births, deaths, foodConsumed, decomposed, conservedMatter, availableEnergy,
                energyInput, organismEnergy, heatReleased, conservedEnergy);
    }

    private String stateHashInternal() {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                writeText(output, ENGINE_VERSION);
                writeText(output, settings.worldId());
                output.writeLong(settings.seed());
                output.writeInt(settings.maxPopulation());
                output.writeLong(settings.maxSteps());
                output.writeLong(settings.initialNutrient());
                output.writeInt(settings.decompositionPerStep());
                output.writeInt(settings.reproductionEnergy());
                output.writeInt(settings.energyInputPerStep());
                output.writeInt(settings.mutationPermille());
                output.writeInt(settings.founders().size());
                for (EcologyFounder founder : settings.founders()) {
                    writeGenome(output, founder.genome());
                    output.writeLong(founder.initialEnergy());
                }
                output.writeInt(settings.reactionSpecies().size());
                for (MatterSpecies species : settings.reactionSpecies()) {
                    writeText(output, species.id().value());
                    output.writeLong(species.massPerUnit());
                }
                output.writeInt(settings.matterReactions().size());
                for (MatterReaction reaction : settings.matterReactions()) {
                    writeText(output, reaction.id().value());
                    output.writeInt(reaction.maxFiringsPerStep());
                    writeReactionSide(output, reaction.inputs());
                    writeReactionSide(output, reaction.outputs());
                }
                writeMatterInventory(output, settings.initialReactionInventory());
                output.writeLong(tick);
                output.writeLong(totalNutrient());
                output.writeLong(totalWaste());
                writeMatterInventory(output, reactionInventory);
                output.writeInt(HABITAT_CELL_COUNT);
                for (int habitatCell = 0; habitatCell < HABITAT_CELL_COUNT; habitatCell++) {
                    output.writeLong(cellNutrient[habitatCell]);
                    output.writeLong(cellWaste[habitatCell]);
                }
                output.writeLong(energyBaseline);
                output.writeLong(availableEnergy);
                output.writeLong(energyInput);
                output.writeLong(heatReleased);
                output.writeLong(nextOrganismId);
                output.writeLong(births);
                output.writeLong(deaths);
                output.writeLong(foodConsumed);
                output.writeLong(decomposed);
                output.writeInt(organisms.size());
                for (Map.Entry<Long, MutableOrganism> entry : organisms.entrySet()) {
                    MutableOrganism organism = entry.getValue();
                    output.writeLong(entry.getKey());
                    writeGenome(output, organism.genome);
                    output.writeLong(organism.age);
                    output.writeLong(organism.energy);
                    output.writeInt(organism.habitatCell);
                }
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException impossible) {
            throw new IllegalStateException("Cannot encode ecology state", impossible);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void writeGenome(DataOutputStream output, EcologyGenome genome) throws IOException {
        output.writeInt(genome.energyPerFood());
        output.writeInt(genome.maintenanceEnergy());
        output.writeInt(genome.lifespanTicks());
        output.writeInt(genome.maturityTicks());
    }

    private static void writeReactionSide(DataOutputStream output, Map<DesignId, Integer> side) throws IOException {
        output.writeInt(side.size());
        for (Map.Entry<DesignId, Integer> entry : side.entrySet()) {
            writeText(output, entry.getKey().value());
            output.writeInt(entry.getValue());
        }
    }

    private static void writeMatterInventory(DataOutputStream output, Map<DesignId, Long> inventory)
            throws IOException {
        output.writeInt(inventory.size());
        for (Map.Entry<DesignId, Long> entry : inventory.entrySet()) {
            writeText(output, entry.getKey().value());
            output.writeLong(entry.getValue());
        }
    }

    private static void writeText(DataOutputStream output, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(encoded.length);
        output.write(encoded);
    }

    private static long mix64(long value) {
        long mixed = value + 0x9e3779b97f4a7c15L;
        mixed = (mixed ^ (mixed >>> 30)) * 0xbf58476d1ce4e5b9L;
        mixed = (mixed ^ (mixed >>> 27)) * 0x94d049bb133111ebL;
        return mixed ^ (mixed >>> 31);
    }

    private static long matterTotal(long nutrient, long waste, int population) {
        try {
            return Math.addExact(Math.addExact(nutrient, waste), population);
        } catch (ArithmeticException overflow) {
            return -1;
        }
    }

    private static void distributeMatter(long amount, long[] destination, long seed, int founderCount) {
        int activeCellCount = (int) Math.min(HABITAT_CELL_COUNT,
                Math.max(founderCount, (amount + 7) / 8));
        long perCell = amount / activeCellCount;
        int remainder = (int) (amount % activeCellCount);
        int start = (int) (seed & (HABITAT_CELL_COUNT - 1));
        for (int offset = 0; offset < activeCellCount; offset++) {
            int habitatCell = (start + offset) & (HABITAT_CELL_COUNT - 1);
            destination[habitatCell] = perCell + (offset < remainder ? 1 : 0);
        }
    }

    private long totalNutrient() {
        long total = 0;
        for (long amount : cellNutrient) total = Math.addExact(total, amount);
        return total;
    }

    private long totalWaste() {
        long total = 0;
        for (long amount : cellWaste) total = Math.addExact(total, amount);
        return total;
    }

    private List<EcologyHabitatCell> habitatSnapshot() {
        List<EcologyHabitatCell> cells = new ArrayList<>(HABITAT_CELL_COUNT);
        for (int habitatCell = 0; habitatCell < HABITAT_CELL_COUNT; habitatCell++) {
            cells.add(new EcologyHabitatCell(habitatCell,
                    cellNutrient[habitatCell], cellWaste[habitatCell]));
        }
        return List.copyOf(cells);
    }

    private static long totalFounderEnergy(EcologySettings settings) {
        long total = 0;
        for (EcologyFounder founder : settings.founders()) total = Math.addExact(total, founder.initialEnergy());
        return total;
    }

    private void requireOpen() {
        owner.check();
        if (closed) throw new IllegalStateException("Ecology world is closed");
    }

    private static final class MutableOrganism {
        private final long id;
        private EcologyGenome genome;
        private long age;
        private long energy;
        private int habitatCell;

        private MutableOrganism(long id, EcologyGenome genome, long age, long energy, int habitatCell) {
            this.id = id;
            this.genome = genome;
            this.age = age;
            this.energy = energy;
            this.habitatCell = habitatCell;
        }
    }
}
