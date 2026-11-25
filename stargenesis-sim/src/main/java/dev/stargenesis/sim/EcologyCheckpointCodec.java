package dev.stargenesis.sim;

import dev.stargenesis.api.design.DesignId;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Versioned bounded encoding for player or server-owned ecology checkpoints. */
public final class EcologyCheckpointCodec {
    public static final int MAX_ENCODED_BYTES = 65_536;
    private static final int MAGIC = 0x53474543;
    private static final int VERSION = 4;
    private static final int VERSION_THREE = 3;
    private static final int PREVIOUS_VERSION = 2;
    private static final int LEGACY_VERSION = 1;
    private static final int DEFAULT_LEGACY_ENERGY_INPUT_PER_STEP = 24;

    private EcologyCheckpointCodec() { }

    public static byte[] encode(EcologyCheckpoint checkpoint) {
        if (checkpoint == null) throw new IllegalArgumentException("Ecology checkpoint is required");
        try (EcologyWorld ignored = EcologyWorld.restore(checkpoint)) {
            // Validation is shared with recovery so invalid state is never emitted.
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                EcologySettings settings = checkpoint.settings();
                EcologySnapshot snapshot = checkpoint.snapshot();
                output.writeInt(MAGIC);
                output.writeInt(VERSION);
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
                writeReactionNetwork(output, settings);
                output.writeLong(checkpoint.energyBaseline());
                writeText(output, snapshot.engineVersion());
                writeText(output, snapshot.worldId());
                output.writeLong(snapshot.seed());
                output.writeLong(snapshot.tick());
                output.writeLong(snapshot.nutrient());
                output.writeLong(snapshot.waste());
                writeInventory(output, snapshot.reactionInventory(), settings.reactionSpecies());
                writeHabitat(output, snapshot.habitatCells());
                EcologyStatistics statistics = snapshot.statistics();
                output.writeInt(statistics.population());
                output.writeInt(statistics.speciesCount());
                output.writeLong(statistics.meanEnergy());
                output.writeInt(statistics.meanAssimilationPermille());
                output.writeLong(statistics.births());
                output.writeLong(statistics.deaths());
                output.writeLong(statistics.foodConsumed());
                output.writeLong(statistics.decomposed());
                output.writeLong(statistics.conservedMatter());
                output.writeLong(statistics.availableEnergy());
                output.writeLong(statistics.energyInput());
                output.writeLong(statistics.organismEnergy());
                output.writeLong(statistics.heatReleased());
                output.writeLong(statistics.conservedEnergy());
                writeText(output, snapshot.stateHash());
                output.writeInt(snapshot.organisms().size());
                for (EcologyOrganism organism : snapshot.organisms()) {
                    output.writeLong(organism.id());
                    writeGenome(output, organism.genome());
                    output.writeLong(organism.age());
                    output.writeLong(organism.energy());
                    output.writeInt(organism.habitatCell());
                }
            }
            byte[] encoded = bytes.toByteArray();
            if (encoded.length > MAX_ENCODED_BYTES) throw new IllegalArgumentException("Ecology checkpoint is too large");
            return encoded;
        } catch (IOException impossible) {
            throw new IllegalStateException("Cannot encode ecology checkpoint", impossible);
        }
    }

    public static EcologyCheckpoint decode(byte[] encoded) {
        return decode(encoded, DEFAULT_LEGACY_ENERGY_INPUT_PER_STEP);
    }

    public static EcologyCheckpoint decode(byte[] encoded, int legacyEnergyInputPerStep) {
        if (encoded == null || encoded.length < 8 || encoded.length > MAX_ENCODED_BYTES) {
            throw new IllegalArgumentException("Invalid ecology checkpoint size");
        }
        if (legacyEnergyInputPerStep < 0 || legacyEnergyInputPerStep > 10_000) {
            throw new IllegalArgumentException("Invalid migrated ecology energy input");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            int magic = input.readInt();
            int version = input.readInt();
            if (magic != MAGIC) throw new IllegalArgumentException("Unsupported ecology checkpoint format");
            if (version == LEGACY_VERSION) return decodeLegacy(input, legacyEnergyInputPerStep);
            if (version == PREVIOUS_VERSION) return decodePrevious(input);
            if (version == VERSION_THREE) return decodeVersionThree(input);
            if (version != VERSION) {
                throw new IllegalArgumentException("Unsupported ecology checkpoint format");
            }
            String worldId = readText(input, 64);
            long seed = input.readLong();
            int maxPopulation = input.readInt();
            long maxSteps = input.readLong();
            long initialNutrient = input.readLong();
            int decompositionPerStep = input.readInt();
            int reproductionEnergy = input.readInt();
            int energyInputPerStep = input.readInt();
            int mutationPermille = input.readInt();
            int founderCount = readCount(input, 1, 256, "founder");
            var founders = new java.util.ArrayList<EcologyFounder>(founderCount);
            for (int i = 0; i < founderCount; i++) {
                founders.add(new EcologyFounder(readGenome(input), input.readLong()));
            }
            ReactionDefinition reactionDefinition = readReactionNetwork(input);
            EcologySettings settings = new EcologySettings(worldId, seed, maxPopulation, maxSteps,
                    initialNutrient, decompositionPerStep, reproductionEnergy, energyInputPerStep,
                    mutationPermille, founders, reactionDefinition.species(), reactionDefinition.reactions(),
                    reactionDefinition.initialInventory());
            long energyBaseline = input.readLong();
            String engineVersion = readText(input, 32);
            String snapshotWorldId = readText(input, 64);
            long snapshotSeed = input.readLong();
            long tick = input.readLong();
            long nutrient = input.readLong();
            long waste = input.readLong();
            Map<DesignId, Long> reactionInventory = readInventory(input, settings.reactionSpecies());
            List<EcologyHabitatCell> habitatCells = readHabitat(input);
            int population = input.readInt();
            int speciesCount = input.readInt();
            long meanEnergy = input.readLong();
            int meanAssimilation = input.readInt();
            long births = input.readLong();
            long deaths = input.readLong();
            long foodConsumed = input.readLong();
            long decomposed = input.readLong();
            long conservedMatter = input.readLong();
            long availableEnergy = input.readLong();
            long energyInput = input.readLong();
            long organismEnergy = input.readLong();
            long heatReleased = input.readLong();
            long conservedEnergy = input.readLong();
            EcologyStatistics statistics = new EcologyStatistics(population, speciesCount, meanEnergy,
                    meanAssimilation, births, deaths, foodConsumed, decomposed, conservedMatter,
                    availableEnergy, energyInput, organismEnergy, heatReleased, conservedEnergy);
            String stateHash = readText(input, 64);
            int organismCount = readCount(input, 0, 256, "organism");
            var organisms = new java.util.ArrayList<EcologyOrganism>(organismCount);
            for (int i = 0; i < organismCount; i++) {
                organisms.add(new EcologyOrganism(input.readLong(), readGenome(input), input.readLong(),
                        input.readLong(), input.readInt()));
            }
            if (input.available() != 0) throw new IllegalArgumentException("Trailing ecology checkpoint data");
            EcologySnapshot snapshot = new EcologySnapshot(engineVersion, snapshotWorldId, snapshotSeed,
                    tick, nutrient, waste, organisms, reactionInventory, habitatCells, statistics, stateHash);
            EcologyCheckpoint checkpoint = new EcologyCheckpoint(settings, snapshot, energyBaseline);
            try (EcologyWorld ignored = EcologyWorld.restore(checkpoint)) {
                return checkpoint;
            }
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot decode ecology checkpoint", failure);
        }
    }

    private static void writeReactionNetwork(DataOutputStream output, EcologySettings settings) throws IOException {
        List<MatterSpecies> species = settings.reactionSpecies();
        output.writeInt(species.size());
        for (MatterSpecies matter : species) {
            writeText(output, matter.id().value());
            output.writeLong(matter.massPerUnit());
        }
        output.writeInt(settings.matterReactions().size());
        for (MatterReaction reaction : settings.matterReactions()) {
            writeText(output, reaction.id().value());
            output.writeInt(reaction.maxFiringsPerStep());
            writeReactionSide(output, reaction.inputs(), species);
            writeReactionSide(output, reaction.outputs(), species);
        }
        writeInventory(output, settings.initialReactionInventory(), species);
    }

    private static void writeReactionSide(DataOutputStream output, Map<DesignId, Integer> side,
                                          List<MatterSpecies> species) throws IOException {
        output.writeInt(side.size());
        for (Map.Entry<DesignId, Integer> entry : side.entrySet()) {
            output.writeByte(speciesIndex(species, entry.getKey()));
            output.writeInt(entry.getValue());
        }
    }

    private static void writeInventory(DataOutputStream output, Map<DesignId, Long> inventory,
                                       List<MatterSpecies> species) throws IOException {
        output.writeInt(species.size());
        for (int index = 0; index < species.size(); index++) {
            output.writeByte(index);
            output.writeLong(inventory.get(species.get(index).id()));
        }
    }

    private static void writeHabitat(DataOutputStream output, List<EcologyHabitatCell> habitatCells)
            throws IOException {
        output.writeInt(habitatCells.size());
        for (EcologyHabitatCell cell : habitatCells) {
            output.writeInt(cell.id());
            output.writeLong(cell.nutrient());
            output.writeLong(cell.waste());
        }
    }

    private static int speciesIndex(List<MatterSpecies> species, DesignId id) {
        for (int index = 0; index < species.size(); index++) {
            if (species.get(index).id().equals(id)) return index;
        }
        throw new IllegalArgumentException("Reaction references a species outside its network");
    }

    private static ReactionDefinition readReactionNetwork(DataInputStream input) throws IOException {
        int speciesCount = readCount(input, 0, MatterReactionNetwork.MAX_SPECIES, "reaction species");
        List<MatterSpecies> species = new ArrayList<>(speciesCount);
        for (int index = 0; index < speciesCount; index++) {
            species.add(new MatterSpecies(new DesignId(readText(input, 128)), input.readLong()));
        }
        int reactionCount = readCount(input, 0, MatterReactionNetwork.MAX_REACTIONS, "reaction");
        List<MatterReaction> reactions = new ArrayList<>(reactionCount);
        for (int index = 0; index < reactionCount; index++) {
            DesignId reactionId = new DesignId(readText(input, 128));
            int maxFirings = input.readInt();
            Map<DesignId, Integer> inputs = readReactionSide(input, species);
            Map<DesignId, Integer> outputs = readReactionSide(input, species);
            reactions.add(new MatterReaction(reactionId, inputs, outputs, maxFirings));
        }
        MatterReactionNetwork network = new MatterReactionNetwork(species, reactions);
        if (!network.species().equals(species) || !network.reactions().equals(reactions)) {
            throw new IllegalArgumentException("Reaction definitions are not in canonical order");
        }
        Map<DesignId, Long> initialInventory = readInventory(input, species);
        return new ReactionDefinition(species, reactions, initialInventory);
    }

    private static Map<DesignId, Integer> readReactionSide(DataInputStream input,
                                                            List<MatterSpecies> species) throws IOException {
        int count = readCount(input, 1, MatterReaction.MAX_SPECIES_PER_SIDE, "reaction side");
        Map<DesignId, Integer> side = new TreeMap<>();
        int previousSpeciesIndex = -1;
        for (int index = 0; index < count; index++) {
            int speciesIndex = input.readUnsignedByte();
            if (speciesIndex >= species.size() || speciesIndex <= previousSpeciesIndex) {
                throw new IllegalArgumentException("Invalid or unordered reaction species index");
            }
            previousSpeciesIndex = speciesIndex;
            DesignId speciesId = species.get(speciesIndex).id();
            if (side.putIfAbsent(speciesId, input.readInt()) != null) {
                throw new IllegalArgumentException("Duplicate reaction species");
            }
        }
        return side;
    }

    private static Map<DesignId, Long> readInventory(DataInputStream input,
                                                      List<MatterSpecies> species) throws IOException {
        int count = readCount(input, 0, MatterReactionNetwork.MAX_SPECIES, "reaction inventory");
        if (count != species.size()) throw new IllegalArgumentException("Reaction inventory is not complete");
        Map<DesignId, Long> inventory = new TreeMap<>();
        for (int index = 0; index < count; index++) {
            int speciesIndex = input.readUnsignedByte();
            if (speciesIndex != index) throw new IllegalArgumentException("Reaction inventory is not canonical");
            DesignId id = species.get(speciesIndex).id();
            long amount = input.readLong();
            if (amount < 0 || amount > MatterReactionNetwork.MAX_UNITS_PER_SPECIES
                    || inventory.putIfAbsent(id, amount) != null) {
                throw new IllegalArgumentException("Invalid or duplicate reaction inventory entry");
            }
        }
        return inventory;
    }

    private static List<EcologyHabitatCell> readHabitat(DataInputStream input) throws IOException {
        int count = readCount(input, EcologyWorld.HABITAT_CELL_COUNT,
                EcologyWorld.HABITAT_CELL_COUNT, "habitat cell");
        List<EcologyHabitatCell> cells = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            int id = input.readInt();
            if (id != index) throw new IllegalArgumentException("Ecology habitat cells are not canonical");
            cells.add(new EcologyHabitatCell(id, input.readLong(), input.readLong()));
        }
        return List.copyOf(cells);
    }

    private static EcologyCheckpoint decodeVersionThree(DataInputStream input) throws IOException {
        String worldId = readText(input, 64);
        long seed = input.readLong();
        int maxPopulation = input.readInt();
        long maxSteps = input.readLong();
        long initialNutrient = input.readLong();
        int decompositionPerStep = input.readInt();
        int reproductionEnergy = input.readInt();
        int energyInputPerStep = input.readInt();
        int mutationPermille = input.readInt();
        int founderCount = readCount(input, 1, 256, "founder");
        List<EcologyFounder> founders = new ArrayList<>(founderCount);
        for (int index = 0; index < founderCount; index++) {
            founders.add(new EcologyFounder(readGenome(input), input.readLong()));
        }
        ReactionDefinition reactionDefinition = readReactionNetwork(input);
        EcologySettings settings = new EcologySettings(worldId, seed, maxPopulation, maxSteps,
                initialNutrient, decompositionPerStep, reproductionEnergy, energyInputPerStep,
                mutationPermille, founders, reactionDefinition.species(), reactionDefinition.reactions(),
                reactionDefinition.initialInventory());
        long energyBaseline = input.readLong();
        String engineVersion = readText(input, 32);
        String snapshotWorldId = readText(input, 64);
        long snapshotSeed = input.readLong();
        long tick = input.readLong();
        long nutrient = input.readLong();
        long waste = input.readLong();
        Map<DesignId, Long> reactionInventory = readInventory(input, settings.reactionSpecies());
        int population = input.readInt();
        int speciesCount = input.readInt();
        long meanEnergy = input.readLong();
        int meanAssimilation = input.readInt();
        long births = input.readLong();
        long deaths = input.readLong();
        long foodConsumed = input.readLong();
        long decomposed = input.readLong();
        long conservedMatter = input.readLong();
        long availableEnergy = input.readLong();
        long energyInput = input.readLong();
        long organismEnergy = input.readLong();
        long heatReleased = input.readLong();
        long conservedEnergy = input.readLong();
        EcologyStatistics statistics = new EcologyStatistics(population, speciesCount, meanEnergy,
                meanAssimilation, births, deaths, foodConsumed, decomposed, conservedMatter,
                availableEnergy, energyInput, organismEnergy, heatReleased, conservedEnergy);
        String stateHash = readText(input, 64);
        int organismCount = readCount(input, 0, 256, "organism");
        List<EcologyOrganism> organisms = new ArrayList<>(organismCount);
        for (int index = 0; index < organismCount; index++) {
            organisms.add(new EcologyOrganism(input.readLong(), readGenome(input), input.readLong(), input.readLong()));
        }
        if (input.available() != 0) throw new IllegalArgumentException("Trailing v3 ecology checkpoint data");

        VersionThreeState previous = new VersionThreeState(settings, energyBaseline, engineVersion,
                snapshotWorldId, snapshotSeed, tick, nutrient, waste, reactionInventory,
                statistics, stateHash, organisms);
        validateVersionThree(previous);
        return EcologyWorld.migrateVersionThreeCheckpoint(settings, tick, nutrient, waste,
                reactionInventory, organisms, statistics, energyBaseline);
    }

    private static void validateVersionThree(VersionThreeState state) {
        EcologySettings settings = state.settings();
        EcologyStatistics statistics = state.statistics();
        MatterReactionNetwork network = new MatterReactionNetwork(
                settings.reactionSpecies(), settings.matterReactions());
        Map<DesignId, Long> initialInventory = network.canonicalInventory(settings.initialReactionInventory());
        Map<DesignId, Long> reactionInventory = network.canonicalInventory(state.reactionInventory());
        if (!reactionInventory.equals(state.reactionInventory())) {
            throw new IllegalArgumentException("V3 ecology reaction inventory is not canonical");
        }
        long conservedMatter = Math.addExact(
                Math.addExact(settings.initialNutrient(), settings.founders().size()),
                network.matterMass(initialInventory));
        long stepCounterLimit = Math.multiplyExact(settings.maxPopulation(), settings.maxSteps());
        long decompositionLimit = Math.multiplyExact(settings.decompositionPerStep(), settings.maxSteps());
        long nextOrganismId;
        try {
            nextOrganismId = Math.addExact(settings.founders().size(), statistics.births());
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("V3 ecology organism sequence overflows", overflow);
        }
        if (!"ecology/3".equals(state.engineVersion()) || !settings.worldId().equals(state.worldId())
                || settings.seed() != state.seed() || state.energyBaseline() < 0
                || state.tick() < 0 || state.tick() > settings.maxSteps()
                || state.nutrient() < 0 || state.waste() < 0
                || state.organisms().size() > settings.maxPopulation()
                || statistics.population() != state.organisms().size()
                || statistics.conservedMatter() != conservedMatter
                || matterTotal(state.nutrient(), state.waste(), state.organisms().size())
                + network.matterMass(reactionInventory) != conservedMatter
                || statistics.births() < statistics.deaths()
                || statistics.births() - statistics.deaths() + settings.founders().size()
                != state.organisms().size()
                || statistics.births() > stepCounterLimit || statistics.deaths() > stepCounterLimit
                || statistics.foodConsumed() > stepCounterLimit || statistics.decomposed() > decompositionLimit
                || statistics.births() > state.tick() * settings.maxPopulation()
                || statistics.deaths() > state.tick() * settings.maxPopulation()
                || statistics.foodConsumed() > state.tick() * settings.maxPopulation()
                || statistics.decomposed() > state.tick() * settings.decompositionPerStep()
                || statistics.energyInput() > state.tick() * settings.energyInputPerStep()) {
            throw new IllegalArgumentException("V3 ecology checkpoint violates its bounds");
        }
        long organismEnergy = 0;
        long assimilation = 0;
        int speciesCount = (int) state.organisms().stream().map(EcologyOrganism::genome).distinct().count();
        long previousId = -1;
        for (EcologyOrganism organism : state.organisms()) {
            if (organism.id() <= previousId || organism.id() >= nextOrganismId
                    || organism.age() >= organism.genome().lifespanTicks()) {
                throw new IllegalArgumentException("V3 ecology checkpoint contains an invalid organism");
            }
            previousId = organism.id();
            organismEnergy = Math.addExact(organismEnergy, organism.energy());
            assimilation = Math.addExact(assimilation, organism.genome().energyPerFood());
        }
        long expectedEnergy;
        try {
            expectedEnergy = Math.addExact(state.energyBaseline(), statistics.energyInput());
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("V3 ecology energy baseline overflows", overflow);
        }
        long meanEnergy = state.organisms().isEmpty() ? 0 : organismEnergy / state.organisms().size();
        int meanAssimilation = state.organisms().isEmpty() ? 0
                : Math.toIntExact(assimilation * 1_000 / state.organisms().size());
        if (organismEnergy != statistics.organismEnergy() || speciesCount != statistics.speciesCount()
                || meanEnergy != statistics.meanEnergy()
                || meanAssimilation != statistics.meanAssimilationPermille()
                || expectedEnergy != statistics.conservedEnergy()
                || !state.stateHash().equals(versionThreeStateHash(state, nextOrganismId))) {
            throw new IllegalArgumentException("V3 ecology checkpoint failed its integrity checks");
        }
    }

    private static String versionThreeStateHash(VersionThreeState state, long nextOrganismId) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                EcologySettings settings = state.settings();
                writeText(output, "ecology/3");
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
                    writeVersionThreeReactionSide(output, reaction.inputs());
                    writeVersionThreeReactionSide(output, reaction.outputs());
                }
                writeVersionThreeInventory(output, settings.initialReactionInventory());
                output.writeLong(state.tick());
                output.writeLong(state.nutrient());
                output.writeLong(state.waste());
                writeVersionThreeInventory(output, state.reactionInventory());
                output.writeLong(state.energyBaseline());
                output.writeLong(state.statistics().availableEnergy());
                output.writeLong(state.statistics().energyInput());
                output.writeLong(state.statistics().heatReleased());
                output.writeLong(nextOrganismId);
                output.writeLong(state.statistics().births());
                output.writeLong(state.statistics().deaths());
                output.writeLong(state.statistics().foodConsumed());
                output.writeLong(state.statistics().decomposed());
                output.writeInt(state.organisms().size());
                for (EcologyOrganism organism : state.organisms()) {
                    output.writeLong(organism.id());
                    writeGenome(output, organism.genome());
                    output.writeLong(organism.age());
                    output.writeLong(organism.energy());
                }
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException impossible) {
            throw new IllegalStateException("Cannot verify v3 ecology checkpoint", impossible);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static void writeVersionThreeReactionSide(DataOutputStream output,
                                                       Map<DesignId, Integer> side) throws IOException {
        output.writeInt(side.size());
        for (Map.Entry<DesignId, Integer> entry : side.entrySet()) {
            writeText(output, entry.getKey().value());
            output.writeInt(entry.getValue());
        }
    }

    private static void writeVersionThreeInventory(DataOutputStream output,
                                                    Map<DesignId, Long> inventory) throws IOException {
        output.writeInt(inventory.size());
        for (Map.Entry<DesignId, Long> entry : new TreeMap<>(inventory).entrySet()) {
            writeText(output, entry.getKey().value());
            output.writeLong(entry.getValue());
        }
    }

    private static EcologyCheckpoint decodePrevious(DataInputStream input) throws IOException {
        String worldId = readText(input, 64);
        long seed = input.readLong();
        int maxPopulation = input.readInt();
        long maxSteps = input.readLong();
        long initialNutrient = input.readLong();
        int decompositionPerStep = input.readInt();
        int reproductionEnergy = input.readInt();
        int energyInputPerStep = input.readInt();
        int mutationPermille = input.readInt();
        int founderCount = readCount(input, 1, 256, "founder");
        List<EcologyFounder> founders = new ArrayList<>(founderCount);
        for (int index = 0; index < founderCount; index++) {
            founders.add(new EcologyFounder(readGenome(input), input.readLong()));
        }
        EcologySettings settings = new EcologySettings(worldId, seed, maxPopulation, maxSteps, initialNutrient,
                decompositionPerStep, reproductionEnergy, energyInputPerStep, mutationPermille, founders);
        long energyBaseline = input.readLong();
        String engineVersion = readText(input, 32);
        String snapshotWorldId = readText(input, 64);
        long snapshotSeed = input.readLong();
        long tick = input.readLong();
        long nutrient = input.readLong();
        long waste = input.readLong();
        int population = input.readInt();
        int speciesCount = input.readInt();
        long meanEnergy = input.readLong();
        int meanAssimilation = input.readInt();
        long births = input.readLong();
        long deaths = input.readLong();
        long foodConsumed = input.readLong();
        long decomposed = input.readLong();
        long conservedMatter = input.readLong();
        long availableEnergy = input.readLong();
        long energyInput = input.readLong();
        long organismEnergy = input.readLong();
        long heatReleased = input.readLong();
        long conservedEnergy = input.readLong();
        EcologyStatistics statistics = new EcologyStatistics(population, speciesCount, meanEnergy,
                meanAssimilation, births, deaths, foodConsumed, decomposed, conservedMatter,
                availableEnergy, energyInput, organismEnergy, heatReleased, conservedEnergy);
        String stateHash = readText(input, 64);
        int organismCount = readCount(input, 0, 256, "organism");
        List<EcologyOrganism> organisms = new ArrayList<>(organismCount);
        for (int index = 0; index < organismCount; index++) {
            organisms.add(new EcologyOrganism(input.readLong(), readGenome(input), input.readLong(), input.readLong()));
        }
        if (input.available() != 0) throw new IllegalArgumentException("Trailing v2 ecology checkpoint data");
        validatePrevious(settings, energyBaseline, engineVersion, snapshotWorldId, snapshotSeed,
                tick, nutrient, waste, statistics, stateHash, organisms);
        return EcologyWorld.migratePreviousCheckpoint(settings, tick, nutrient, waste,
                organisms, statistics, energyBaseline);
    }

    private static void validatePrevious(EcologySettings settings, long energyBaseline,
                                         String engineVersion, String worldId, long seed,
                                         long tick, long nutrient, long waste,
                                         EcologyStatistics statistics, String stateHash,
                                         List<EcologyOrganism> organisms) {
        long conservedMatter = Math.addExact(settings.initialNutrient(), settings.founders().size());
        long stepCounterLimit = Math.multiplyExact(settings.maxPopulation(), settings.maxSteps());
        long decompositionLimit = Math.multiplyExact(settings.decompositionPerStep(), settings.maxSteps());
        long nextOrganismId;
        try {
            nextOrganismId = Math.addExact(settings.founders().size(), statistics.births());
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("V2 ecology organism sequence overflows", overflow);
        }
        if (!"ecology/2".equals(engineVersion) || !settings.worldId().equals(worldId)
                || settings.seed() != seed || tick < 0 || tick > settings.maxSteps()
                || nutrient < 0 || waste < 0 || organisms.size() > settings.maxPopulation()
                || statistics.population() != organisms.size()
                || statistics.conservedMatter() != conservedMatter
                || matterTotal(nutrient, waste, organisms.size()) != conservedMatter
                || statistics.births() < statistics.deaths()
                || statistics.births() - statistics.deaths() + settings.founders().size() != organisms.size()
                || statistics.births() > stepCounterLimit || statistics.deaths() > stepCounterLimit
                || statistics.foodConsumed() > stepCounterLimit || statistics.decomposed() > decompositionLimit
                || statistics.births() > tick * settings.maxPopulation()
                || statistics.deaths() > tick * settings.maxPopulation()
                || statistics.foodConsumed() > tick * settings.maxPopulation()
                || statistics.decomposed() > tick * settings.decompositionPerStep()
                || statistics.energyInput() > tick * settings.energyInputPerStep()) {
            throw new IllegalArgumentException("V2 ecology checkpoint violates its bounds");
        }
        long organismEnergy = 0;
        long assimilation = 0;
        int speciesCount = (int) organisms.stream().map(EcologyOrganism::genome).distinct().count();
        for (EcologyOrganism organism : organisms) {
            if (organism.id() >= nextOrganismId || organism.age() >= organism.genome().lifespanTicks()) {
                throw new IllegalArgumentException("V2 ecology checkpoint contains an invalid organism");
            }
            organismEnergy = Math.addExact(organismEnergy, organism.energy());
            assimilation = Math.addExact(assimilation, organism.genome().energyPerFood());
        }
        long expectedEnergy;
        try {
            expectedEnergy = Math.addExact(energyBaseline, statistics.energyInput());
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("V2 ecology energy baseline overflows", overflow);
        }
        long meanEnergy = organisms.isEmpty() ? 0 : organismEnergy / organisms.size();
        int meanAssimilation = organisms.isEmpty() ? 0
                : Math.toIntExact(assimilation * 1_000 / organisms.size());
        if (organismEnergy != statistics.organismEnergy() || speciesCount != statistics.speciesCount()
                || meanEnergy != statistics.meanEnergy() || meanAssimilation != statistics.meanAssimilationPermille()
                || expectedEnergy != statistics.conservedEnergy()
                || !stateHash.equals(previousStateHash(settings, energyBaseline, tick, nutrient, waste,
                statistics, nextOrganismId, organisms))) {
            throw new IllegalArgumentException("V2 ecology checkpoint failed its integrity checks");
        }
    }

    private static String previousStateHash(EcologySettings settings, long energyBaseline,
                                            long tick, long nutrient, long waste,
                                            EcologyStatistics statistics, long nextOrganismId,
                                            List<EcologyOrganism> organisms) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                writeText(output, "ecology/2");
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
                output.writeLong(tick);
                output.writeLong(nutrient);
                output.writeLong(waste);
                output.writeLong(energyBaseline);
                output.writeLong(statistics.availableEnergy());
                output.writeLong(statistics.energyInput());
                output.writeLong(statistics.heatReleased());
                output.writeLong(nextOrganismId);
                output.writeLong(statistics.births());
                output.writeLong(statistics.deaths());
                output.writeLong(statistics.foodConsumed());
                output.writeLong(statistics.decomposed());
                output.writeInt(organisms.size());
                for (EcologyOrganism organism : organisms) {
                    output.writeLong(organism.id());
                    writeGenome(output, organism.genome());
                    output.writeLong(organism.age());
                    output.writeLong(organism.energy());
                }
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException impossible) {
            throw new IllegalStateException("Cannot verify v2 ecology checkpoint", impossible);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static EcologyCheckpoint decodeLegacy(DataInputStream input, int energyInputPerStep) throws IOException {
        String worldId = readText(input, 64);
        long seed = input.readLong();
        int maxPopulation = input.readInt();
        long maxSteps = input.readLong();
        long initialNutrient = input.readLong();
        int decompositionPerStep = input.readInt();
        int reproductionEnergy = input.readInt();
        int mutationPermille = input.readInt();
        int founderCount = readCount(input, 1, 256, "founder");
        var founders = new java.util.ArrayList<EcologyFounder>(founderCount);
        for (int i = 0; i < founderCount; i++) {
            founders.add(new EcologyFounder(readGenome(input), input.readLong()));
        }
        EcologySettings settings = new EcologySettings(worldId, seed, maxPopulation, maxSteps,
                initialNutrient, decompositionPerStep, reproductionEnergy, energyInputPerStep,
                mutationPermille, founders);

        String engineVersion = readText(input, 32);
        String snapshotWorldId = readText(input, 64);
        long snapshotSeed = input.readLong();
        long tick = input.readLong();
        long nutrient = input.readLong();
        long waste = input.readLong();
        LegacyStatistics statistics = new LegacyStatistics(input.readInt(), input.readInt(), input.readLong(),
                input.readInt(), input.readLong(), input.readLong(), input.readLong(), input.readLong(), input.readLong());
        String stateHash = readText(input, 64);
        int organismCount = readCount(input, 0, 256, "organism");
        var organisms = new java.util.ArrayList<EcologyOrganism>(organismCount);
        for (int i = 0; i < organismCount; i++) {
            organisms.add(new EcologyOrganism(input.readLong(), readGenome(input), input.readLong(), input.readLong()));
        }
        if (input.available() != 0) throw new IllegalArgumentException("Trailing legacy ecology checkpoint data");
        validateLegacy(settings, engineVersion, snapshotWorldId, snapshotSeed, tick, nutrient, waste,
                statistics, stateHash, organisms);
        long energyBaseline = sumOrganismEnergy(organisms);
        return EcologyWorld.migrateLegacyCheckpoint(settings, tick, nutrient, waste, organisms,
                statistics.births(), statistics.deaths(), statistics.foodConsumed(), statistics.decomposed(),
                energyBaseline);
    }

    private static void validateLegacy(EcologySettings settings, String engineVersion, String worldId, long seed,
                                       long tick, long nutrient, long waste, LegacyStatistics statistics,
                                       String stateHash, List<EcologyOrganism> organisms) {
        long initialMatter = Math.addExact(settings.initialNutrient(), settings.founders().size());
        long stepCounterLimit = Math.multiplyExact(settings.maxPopulation(), settings.maxSteps());
        long expectedNextId;
        try {
            expectedNextId = Math.addExact(settings.founders().size(), statistics.births());
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("Legacy ecology organism sequence overflows", overflow);
        }
        if (!"ecology/1".equals(engineVersion) || !settings.worldId().equals(worldId) || settings.seed() != seed
                || tick < 0 || tick > settings.maxSteps() || organisms.size() > settings.maxPopulation()
                || nutrient < 0 || waste < 0
                || statistics.population() != organisms.size() || statistics.speciesCount() > organisms.size()
                || statistics.meanEnergy() < 0 || statistics.meanAssimilationPermille() < 0
                || statistics.meanAssimilationPermille() > 4_000
                || statistics.births() < 0 || statistics.deaths() < 0 || statistics.foodConsumed() < 0
                || statistics.decomposed() < 0 || statistics.births() < statistics.deaths()
                || statistics.births() - statistics.deaths() + settings.founders().size() != organisms.size()
                || statistics.conservedMatter() != initialMatter
                || matterTotal(nutrient, waste, organisms.size()) != initialMatter
                || statistics.births() > stepCounterLimit || statistics.deaths() > stepCounterLimit
                || statistics.foodConsumed() > stepCounterLimit
                || statistics.births() > tick * settings.maxPopulation()
                || statistics.deaths() > tick * settings.maxPopulation()
                || statistics.foodConsumed() > tick * settings.maxPopulation()
                || statistics.decomposed() > tick * settings.decompositionPerStep()
                || !stateHash.equals(legacyStateHash(settings, tick, nutrient, waste, statistics, organisms))) {
            throw new IllegalArgumentException("Legacy ecology checkpoint failed its integrity checks");
        }
        long energy = 0;
        long assimilation = 0;
        int species = (int) organisms.stream().map(EcologyOrganism::genome).distinct().count();
        long nextId = expectedNextId;
        for (EcologyOrganism organism : organisms) {
            if (organism.id() >= nextId || organism.age() >= organism.genome().lifespanTicks()) {
                throw new IllegalArgumentException("Legacy ecology checkpoint contains an invalid organism");
            }
            try {
                energy = Math.addExact(energy, organism.energy());
            } catch (ArithmeticException overflow) {
                throw new IllegalArgumentException("Legacy ecology energy overflows", overflow);
            }
            assimilation = Math.addExact(assimilation, organism.genome().energyPerFood());
        }
        long meanEnergy = organisms.isEmpty() ? 0 : energy / organisms.size();
        int meanAssimilation = organisms.isEmpty() ? 0 : Math.toIntExact(assimilation * 1_000 / organisms.size());
        if (species != statistics.speciesCount() || meanEnergy != statistics.meanEnergy()
                || meanAssimilation != statistics.meanAssimilationPermille()) {
            throw new IllegalArgumentException("Legacy ecology statistics do not match its population");
        }
    }

    private static String legacyStateHash(EcologySettings settings, long tick, long nutrient, long waste,
                                          LegacyStatistics statistics, List<EcologyOrganism> organisms) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                writeText(output, "ecology/1");
                writeText(output, settings.worldId());
                output.writeLong(settings.seed());
                output.writeInt(settings.maxPopulation());
                output.writeLong(settings.maxSteps());
                output.writeLong(settings.initialNutrient());
                output.writeInt(settings.decompositionPerStep());
                output.writeInt(settings.reproductionEnergy());
                output.writeInt(settings.mutationPermille());
                output.writeInt(settings.founders().size());
                for (EcologyFounder founder : settings.founders()) {
                    writeGenome(output, founder.genome());
                    output.writeLong(founder.initialEnergy());
                }
                output.writeLong(tick);
                output.writeLong(nutrient);
                output.writeLong(waste);
                output.writeLong(Math.addExact(settings.founders().size(), statistics.births()));
                output.writeLong(statistics.births());
                output.writeLong(statistics.deaths());
                output.writeLong(statistics.foodConsumed());
                output.writeLong(statistics.decomposed());
                output.writeInt(organisms.size());
                for (EcologyOrganism organism : organisms) {
                    output.writeLong(organism.id());
                    writeGenome(output, organism.genome());
                    output.writeLong(organism.age());
                    output.writeLong(organism.energy());
                }
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException impossible) {
            throw new IllegalStateException("Cannot verify legacy ecology state", impossible);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static long matterTotal(long nutrient, long waste, int population) {
        try {
            return Math.addExact(Math.addExact(nutrient, waste), population);
        } catch (ArithmeticException overflow) {
            return -1;
        }
    }

    private static long sumOrganismEnergy(List<EcologyOrganism> organisms) {
        long total = 0;
        for (EcologyOrganism organism : organisms) total = Math.addExact(total, organism.energy());
        return total;
    }

    private static int readCount(DataInputStream input, int minimum, int maximum, String name) throws IOException {
        int count = input.readInt();
        if (count < minimum || count > maximum) throw new IllegalArgumentException("Invalid ecology " + name + " count");
        return count;
    }

    private static void writeGenome(DataOutputStream output, EcologyGenome genome) throws IOException {
        output.writeInt(genome.energyPerFood());
        output.writeInt(genome.maintenanceEnergy());
        output.writeInt(genome.lifespanTicks());
        output.writeInt(genome.maturityTicks());
    }

    private static EcologyGenome readGenome(DataInputStream input) throws IOException {
        return new EcologyGenome(input.readInt(), input.readInt(), input.readInt(), input.readInt());
    }

    private static void writeText(DataOutputStream output, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(encoded.length);
        output.write(encoded);
    }

    private static String readText(DataInputStream input, int maximumBytes) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > maximumBytes) throw new IllegalArgumentException("Ecology text exceeds its bound");
        byte[] encoded = input.readNBytes(length);
        if (encoded.length != length) throw new IOException("Truncated ecology text");
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(encoded)).toString();
    }

    private record LegacyStatistics(int population, int speciesCount, long meanEnergy,
                                    int meanAssimilationPermille, long births, long deaths,
                                    long foodConsumed, long decomposed, long conservedMatter) { }

    private record ReactionDefinition(List<MatterSpecies> species, List<MatterReaction> reactions,
                                      Map<DesignId, Long> initialInventory) { }

    private record VersionThreeState(EcologySettings settings, long energyBaseline, String engineVersion,
                                     String worldId, long seed, long tick, long nutrient, long waste,
                                     Map<DesignId, Long> reactionInventory, EcologyStatistics statistics,
                                     String stateHash, List<EcologyOrganism> organisms) { }
}
