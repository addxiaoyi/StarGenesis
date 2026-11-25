package dev.stargenesis.sim;

import dev.stargenesis.api.design.DesignId;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.*;

class EcologyCheckpointCodecTest {
    private static final EcologySettings SETTINGS = new EcologySettings("codec-test", 37, 12, 500,
            160, 8, 4, 20, 250,
            List.of(new EcologyFounder(new EcologyGenome(3, 1, 60, 4), 2),
                    new EcologyFounder(new EcologyGenome(2, 2, 80, 5), 1)));

    @Test
    void checkpointRoundTripsAndResumes() {
        try (EcologyWorld original = new EcologyWorld(SETTINGS)) {
            for (int i = 0; i < 8; i++) original.step();
            byte[] encoded = EcologyCheckpointCodec.encode(original.checkpoint());
            EcologyCheckpoint decoded = EcologyCheckpointCodec.decode(encoded);
            assertEquals(original.snapshot(), decoded.snapshot());
            assertArrayEquals(encoded, EcologyCheckpointCodec.encode(decoded));
            try (EcologyWorld resumed = EcologyWorld.restore(decoded)) {
                assertEquals(original.step(), resumed.step());
                assertEquals(original.snapshot(), resumed.snapshot());
            }
        }
    }

    @Test
    void decoderRejectsUnsupportedTruncatedTrailingAndOversizedData() {
        byte[] encoded;
        try (EcologyWorld world = new EcologyWorld(SETTINGS)) {
            encoded = EcologyCheckpointCodec.encode(world.checkpoint());
        }
        byte[] unsupported = encoded.clone();
        unsupported[7] = (byte) 0x7f;
        assertThrows(IllegalArgumentException.class, () -> EcologyCheckpointCodec.decode(unsupported));
        assertThrows(IllegalArgumentException.class,
                () -> EcologyCheckpointCodec.decode(java.util.Arrays.copyOf(encoded, encoded.length - 1)));
        assertThrows(IllegalArgumentException.class,
                () -> EcologyCheckpointCodec.decode(java.util.Arrays.copyOf(encoded, encoded.length + 1)));
        assertThrows(IllegalArgumentException.class,
                () -> EcologyCheckpointCodec.decode(new byte[EcologyCheckpointCodec.MAX_ENCODED_BYTES + 1]));
    }

    @Test
    void decoderRejectsInvalidUtf8AndIncorrectStateHash() {
        byte[] encoded;
        try (EcologyWorld world = new EcologyWorld(SETTINGS)) {
            encoded = EcologyCheckpointCodec.encode(world.checkpoint());
        }
        byte[] invalidUtf8 = encoded.clone();
        invalidUtf8[12] = (byte) 0xff;
        assertThrows(IllegalArgumentException.class, () -> EcologyCheckpointCodec.decode(invalidUtf8));

        byte[] badHash = encoded.clone();
        badHash[badHash.length - 1] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> EcologyCheckpointCodec.decode(badHash));
    }

    @Test
    void versionOneCheckpointMigratesWithoutLosingPopulationOrStoredEnergy() throws Exception {
        byte[] legacy = legacyCheckpoint();
        EcologyCheckpoint migrated = EcologyCheckpointCodec.decode(legacy, 17);
        assertEquals(EcologyWorld.ENGINE_VERSION, migrated.snapshot().engineVersion());
        assertEquals(17, migrated.settings().energyInputPerStep());
        assertEquals(2, migrated.energyBaseline());
        assertEquals(2, migrated.snapshot().statistics().organismEnergy());
        assertEquals(0, migrated.snapshot().statistics().energyInput());

        try (EcologyWorld world = EcologyWorld.restore(migrated)) {
            EcologyStepResult result = world.step();
            assertEquals(19, result.statistics().conservedEnergy());
            assertEquals(1, result.statistics().heatReleased());
            assertEquals(17, result.statistics().energyInput());
            assertEquals(4, result.statistics().organismEnergy());
            assertEquals(14, result.statistics().availableEnergy());
        }
    }

    @Test
    void versionTwoCheckpointMigratesAndPreservesEnergyState() throws Exception {
        EcologyCheckpoint migrated = EcologyCheckpointCodec.decode(previousCheckpoint());
        assertEquals(EcologyWorld.ENGINE_VERSION, migrated.snapshot().engineVersion());
        assertEquals(2, migrated.energyBaseline());
        assertEquals(17, migrated.settings().energyInputPerStep());
        assertTrue(migrated.settings().reactionSpecies().isEmpty());
        assertTrue(migrated.snapshot().reactionInventory().isEmpty());
        assertEquals(2, migrated.snapshot().statistics().organismEnergy());
        assertEquals(2, migrated.snapshot().statistics().conservedEnergy());

        try (EcologyWorld world = EcologyWorld.restore(migrated)) {
            assertEquals(19, world.step().statistics().conservedEnergy());
        }
    }

    @Test
    void versionThreeCheckpointVerifiesItsOriginalHashBeforeSpatialMigration() throws Exception {
        byte[] previous = versionThreeCheckpoint();
        EcologyCheckpoint migrated = EcologyCheckpointCodec.decode(previous);

        assertEquals(EcologyWorld.ENGINE_VERSION, migrated.snapshot().engineVersion());
        assertEquals(SETTINGS.initialNutrient(), migrated.snapshot().nutrient());
        assertEquals(SETTINGS.founders().size(), migrated.snapshot().statistics().population());
        assertTrue(migrated.snapshot().habitatCells().stream().skip(1)
                .allMatch(cell -> cell.nutrient() == 0 && cell.waste() == 0));
        assertTrue(migrated.snapshot().organisms().stream().allMatch(organism -> organism.habitatCell() == 0));

        byte[] changedState = previous.clone();
        changedState[changedState.length - 1] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> EcologyCheckpointCodec.decode(changedState));

        try (EcologyWorld world = EcologyWorld.restore(migrated)) {
            EcologyStepResult result = world.step();
            EcologySnapshot snapshot = world.snapshot();
            assertEquals(SETTINGS.initialNutrient() + SETTINGS.founders().size(),
                    snapshot.nutrient() + snapshot.waste() + snapshot.organisms().size());
            assertEquals(result.statistics().conservedEnergy(), result.statistics().availableEnergy()
                    + result.statistics().organismEnergy() + result.statistics().heatReleased());
        }
    }

    @Test
    void largestSupportedReactionCheckpointFitsTheEncodedSizeLimit() {
        List<MatterSpecies> species = new ArrayList<>();
        List<MatterReaction> reactions = new ArrayList<>();
        Map<DesignId, Long> inventory = new TreeMap<>();
        String matterNamespace = "m".repeat(32);
        String reactionNamespace = "r".repeat(32);
        for (int index = 0; index < MatterReactionNetwork.MAX_SPECIES; index++) {
            DesignId id = new DesignId(matterNamespace + ":s%03d%s".formatted(index, "x".repeat(91)));
            species.add(new MatterSpecies(id, 1));
            inventory.put(id, 0L);
        }
        for (int index = 0; index < MatterReactionNetwork.MAX_REACTIONS; index++) {
            DesignId id = new DesignId(reactionNamespace + ":a%02d%s".formatted(index, "x".repeat(92)));
            reactions.add(new MatterReaction(id,
                    Map.of(species.get(index).id(), 1), Map.of(species.get(index + 1).id(), 1), 1));
        }
        EcologyFounder founder = new EcologyFounder(new EcologyGenome(1, 1, 10_000, 1), 0);
        EcologySettings settings = new EcologySettings("maximum-network", 12, 256, 100_000,
                0, 256, 100_000, 0, 0, Collections.nCopies(256, founder), species, reactions, inventory);
        try (EcologyWorld world = new EcologyWorld(settings)) {
            byte[] encoded = EcologyCheckpointCodec.encode(world.checkpoint());
            assertTrue(encoded.length <= EcologyCheckpointCodec.MAX_ENCODED_BYTES);
            assertEquals(world.checkpoint(), EcologyCheckpointCodec.decode(encoded));
        }
    }

    private static byte[] legacyCheckpoint() throws IOException {
        EcologyGenome genome = new EcologyGenome(3, 1, 60, 4);
        EcologyFounder founder = new EcologyFounder(genome, 2);
        EcologyOrganism organism = new EcologyOrganism(0, genome, 0, 2);
        String stateHash = legacyHash(founder, genome, organism);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(0x53474543);
            output.writeInt(1);
            writeText(output, "legacy-world");
            output.writeLong(37);
            output.writeInt(2);
            output.writeLong(20);
            output.writeLong(10);
            output.writeInt(1);
            output.writeInt(4);
            output.writeInt(250);
            output.writeInt(1);
            writeGenome(output, genome);
            output.writeLong(founder.initialEnergy());
            writeText(output, "ecology/1");
            writeText(output, "legacy-world");
            output.writeLong(37);
            output.writeLong(0);
            output.writeLong(10);
            output.writeLong(0);
            output.writeInt(1);
            output.writeInt(1);
            output.writeLong(2);
            output.writeInt(3_000);
            output.writeLong(0);
            output.writeLong(0);
            output.writeLong(0);
            output.writeLong(0);
            output.writeLong(11);
            writeText(output, stateHash);
            output.writeInt(1);
            output.writeLong(organism.id());
            writeGenome(output, organism.genome());
            output.writeLong(organism.age());
            output.writeLong(organism.energy());
        }
        return bytes.toByteArray();
    }

    private static byte[] previousCheckpoint() throws IOException {
        EcologyGenome genome = new EcologyGenome(3, 1, 60, 4);
        EcologyFounder founder = new EcologyFounder(genome, 2);
        EcologyOrganism organism = new EcologyOrganism(0, genome, 0, 2);
        EcologyStatistics statistics = new EcologyStatistics(1, 1, 2, 3_000,
                0, 0, 0, 0, 21, 0, 0, 2, 0, 2);
        String stateHash = previousHash(founder, organism, statistics);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(0x53474543);
            output.writeInt(2);
            writeText(output, "legacy-world");
            output.writeLong(37);
            output.writeInt(12);
            output.writeLong(500);
            output.writeLong(20);
            output.writeInt(8);
            output.writeInt(4);
            output.writeInt(17);
            output.writeInt(250);
            output.writeInt(1);
            writeGenome(output, genome);
            output.writeLong(founder.initialEnergy());
            output.writeLong(2);
            writeText(output, "ecology/2");
            writeText(output, "legacy-world");
            output.writeLong(37);
            output.writeLong(0);
            output.writeLong(20);
            output.writeLong(0);
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
            writeText(output, stateHash);
            output.writeInt(1);
            output.writeLong(organism.id());
            writeGenome(output, organism.genome());
            output.writeLong(organism.age());
            output.writeLong(organism.energy());
        }
        return bytes.toByteArray();
    }

    private static byte[] versionThreeCheckpoint() throws IOException {
        List<EcologyOrganism> organisms = new ArrayList<>();
        for (int index = 0; index < SETTINGS.founders().size(); index++) {
            EcologyFounder founder = SETTINGS.founders().get(index);
            organisms.add(new EcologyOrganism(index, founder.genome(), 0, founder.initialEnergy()));
        }
        long energyBaseline = SETTINGS.founders().stream().mapToLong(EcologyFounder::initialEnergy).sum();
        long organismEnergy = energyBaseline;
        long assimilation = SETTINGS.founders().stream()
                .mapToLong(founder -> founder.genome().energyPerFood()).sum();
        EcologyStatistics statistics = new EcologyStatistics(organisms.size(),
                (int) organisms.stream().map(EcologyOrganism::genome).distinct().count(),
                organismEnergy / organisms.size(), Math.toIntExact(assimilation * 1_000 / organisms.size()),
                0, 0, 0, 0, SETTINGS.initialNutrient() + organisms.size(),
                0, 0, organismEnergy, 0, energyBaseline);
        String stateHash = versionThreeHash(SETTINGS, energyBaseline, statistics, organisms);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(0x53474543);
            output.writeInt(3);
            writeText(output, SETTINGS.worldId());
            output.writeLong(SETTINGS.seed());
            output.writeInt(SETTINGS.maxPopulation());
            output.writeLong(SETTINGS.maxSteps());
            output.writeLong(SETTINGS.initialNutrient());
            output.writeInt(SETTINGS.decompositionPerStep());
            output.writeInt(SETTINGS.reproductionEnergy());
            output.writeInt(SETTINGS.energyInputPerStep());
            output.writeInt(SETTINGS.mutationPermille());
            output.writeInt(SETTINGS.founders().size());
            for (EcologyFounder founder : SETTINGS.founders()) {
                writeGenome(output, founder.genome());
                output.writeLong(founder.initialEnergy());
            }
            output.writeInt(0);
            output.writeInt(0);
            output.writeInt(0);
            output.writeLong(energyBaseline);
            writeText(output, "ecology/3");
            writeText(output, SETTINGS.worldId());
            output.writeLong(SETTINGS.seed());
            output.writeLong(0);
            output.writeLong(SETTINGS.initialNutrient());
            output.writeLong(0);
            output.writeInt(0);
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
            writeText(output, stateHash);
            output.writeInt(organisms.size());
            for (EcologyOrganism organism : organisms) {
                output.writeLong(organism.id());
                writeGenome(output, organism.genome());
                output.writeLong(organism.age());
                output.writeLong(organism.energy());
            }
        }
        return bytes.toByteArray();
    }

    private static String versionThreeHash(EcologySettings settings, long energyBaseline,
                                          EcologyStatistics statistics, List<EcologyOrganism> organisms) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
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
                output.writeInt(0);
                output.writeInt(0);
                output.writeInt(0);
                output.writeLong(0);
                output.writeLong(SETTINGS.initialNutrient());
                output.writeLong(0);
                output.writeInt(0);
                output.writeLong(energyBaseline);
                output.writeLong(statistics.availableEnergy());
                output.writeLong(statistics.energyInput());
                output.writeLong(statistics.heatReleased());
                output.writeLong(settings.founders().size() + statistics.births());
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
        } catch (IOException | NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String previousHash(EcologyFounder founder, EcologyOrganism organism,
                                       EcologyStatistics statistics) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                writeText(output, "ecology/2");
                writeText(output, "legacy-world");
                output.writeLong(37);
                output.writeInt(12);
                output.writeLong(500);
                output.writeLong(20);
                output.writeInt(8);
                output.writeInt(4);
                output.writeInt(17);
                output.writeInt(250);
                output.writeInt(1);
                writeGenome(output, founder.genome());
                output.writeLong(founder.initialEnergy());
                output.writeLong(0);
                output.writeLong(20);
                output.writeLong(0);
                output.writeLong(2);
                output.writeLong(statistics.availableEnergy());
                output.writeLong(statistics.energyInput());
                output.writeLong(statistics.heatReleased());
                output.writeLong(1);
                output.writeLong(statistics.births());
                output.writeLong(statistics.deaths());
                output.writeLong(statistics.foodConsumed());
                output.writeLong(statistics.decomposed());
                output.writeInt(1);
                output.writeLong(organism.id());
                writeGenome(output, organism.genome());
                output.writeLong(organism.age());
                output.writeLong(organism.energy());
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String legacyHash(EcologyFounder founder, EcologyGenome genome, EcologyOrganism organism) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                writeText(output, "ecology/1");
                writeText(output, "legacy-world");
                output.writeLong(37);
                output.writeInt(2);
                output.writeLong(20);
                output.writeLong(10);
                output.writeInt(1);
                output.writeInt(4);
                output.writeInt(250);
                output.writeInt(1);
                writeGenome(output, founder.genome());
                output.writeLong(founder.initialEnergy());
                output.writeLong(0);
                output.writeLong(10);
                output.writeLong(0);
                output.writeLong(1);
                output.writeLong(0);
                output.writeLong(0);
                output.writeLong(0);
                output.writeLong(0);
                output.writeInt(1);
                output.writeLong(organism.id());
                writeGenome(output, genome);
                output.writeLong(organism.age());
                output.writeLong(organism.energy());
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static void writeGenome(DataOutputStream output, EcologyGenome genome) throws IOException {
        output.writeInt(genome.energyPerFood());
        output.writeInt(genome.maintenanceEnergy());
        output.writeInt(genome.lifespanTicks());
        output.writeInt(genome.maturityTicks());
    }

    private static void writeText(DataOutputStream output, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(encoded.length);
        output.write(encoded);
    }
}
