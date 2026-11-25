package dev.stargenesis.sim;

import dev.stargenesis.api.design.DesignId;
import dev.stargenesis.api.sim.AgentId;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FactoryCheckpointCodecTest {
    private static final List<AgentId> MEMBERS = List.of(1, 2, 3, 4).stream()
            .map(index -> new AgentId(new UUID(1, index))).toList();
    private static final FactorySettings SETTINGS = new FactorySettings("factory-codec", MEMBERS,
            20, 10, 500, 4);
    private static final DesignId ORDER = new DesignId("factory:codec-order");

    @Test
    void checkpointRoundTripsOrdersBidsLedgerAttemptsAndContinuesDeterministically() {
        try (FactoryWorld original = new FactoryWorld(SETTINGS)) {
            original.createOrder(MEMBERS.getFirst(), ORDER, 5, 80, 6, 4);
            original.placeBid(MEMBERS.get(1), ORDER, 4, 20);
            original.placeBid(MEMBERS.get(2), ORDER, 4, 30);
            original.placeBid(MEMBERS.get(3), ORDER, 4, 40);
            original.placeBid(MEMBERS.getFirst(), ORDER, 4, 50);
            original.contribute(MEMBERS.get(0), ORDER, 5, 5);

            byte[] encoded = FactoryCheckpointCodec.encode(original.checkpoint());
            assertTrue(encoded.length < FactoryCheckpointCodec.MAX_ENCODED_BYTES);
            FactoryCheckpoint decoded = FactoryCheckpointCodec.decode(encoded);
            try (FactoryWorld resumed = FactoryWorld.restore(decoded)) {
                assertEquals(original.snapshot(), resumed.snapshot());
                assertTrue(original.fulfill(MEMBERS.get(1), ORDER));
                assertTrue(resumed.fulfill(MEMBERS.get(1), ORDER));
                assertEquals(original.snapshot(), resumed.snapshot());
            }
        }
    }

    @Test
    void rejectsTrailingBytesAndStateHashCorruption() {
        FactoryCheckpoint checkpoint;
        try (FactoryWorld world = new FactoryWorld(SETTINGS)) {
            world.createOrder(MEMBERS.getFirst(), ORDER, 2, 10, 2, 2);
            checkpoint = world.checkpoint();
        }
        byte[] encoded = FactoryCheckpointCodec.encode(checkpoint);
        byte[] trailing = java.util.Arrays.copyOf(encoded, encoded.length + 1);
        assertThrows(IllegalArgumentException.class, () -> FactoryCheckpointCodec.decode(trailing));
        encoded[encoded.length - 1] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> FactoryCheckpointCodec.decode(encoded));
    }

    @Test
    void readsLegacyV1CheckpointAndMigratesWithoutGrantingProductionMaterials() throws Exception {
        FactoryCheckpoint migrated = FactoryCheckpointCodec.decode(legacyCheckpointBytes());
        assertEquals(2, migrated.formatVersion());
        assertFalse(migrated.productionMaterialsSeeded());
        assertTrue(migrated.stationJobs().isEmpty());
        assertTrue(migrated.productionActions().isEmpty());

        AgentId member = MEMBERS.getFirst();
        try (FactoryWorld world = FactoryWorld.restore(migrated)) {
            assertEquals(SETTINGS.startingComponents(), world.balance(FactoryWorld.playerAccount(member),
                    FactoryWorld.COMPONENT_RESOURCE));
            assertEquals(0, world.balance(FactoryWorld.playerAccount(member), FactoryWorld.ORE_RESOURCE));
            assertEquals(0, world.balance(FactoryWorld.playerAccount(member), FactoryWorld.POLYMER_RESOURCE));
            FactoryCheckpointCodec.decode(FactoryCheckpointCodec.encode(world.checkpoint()));
        }
    }

    private static byte[] legacyCheckpointBytes() throws IOException, NoSuchAlgorithmException {
        Map<String, Map<String, Long>> balances = new TreeMap<>();
        for (AgentId member : MEMBERS) {
            balances.put(FactoryWorld.playerAccount(member),
                    Map.of(FactoryWorld.COMPONENT_RESOURCE, SETTINGS.startingComponents()));
        }
        balances.put(FactoryWorld.TREASURY_ACCOUNT,
                Map.of(FactoryWorld.CREDIT_RESOURCE, SETTINGS.initialCredits()));
        String stateHash = legacyStateHash(balances);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeInt(0x53474643);
            output.writeInt(1);
            writeText(output, SETTINGS.worldId());
            output.writeLong(SETTINGS.maxSteps());
            output.writeLong(SETTINGS.startingComponents());
            output.writeLong(SETTINGS.initialCredits());
            output.writeInt(SETTINGS.maxOrders());
            output.writeInt(MEMBERS.size());
            for (AgentId member : MEMBERS) writeText(output, member.value().toString());
            output.writeLong(0);
            output.writeInt(FactoryWorld.INTERNAL_SEQUENCE_BASE);
            output.writeInt(0);
            writeBalances(output, balances);
            output.writeInt(FactoryWorld.MAX_LEDGER_ATTEMPTS);
            output.writeInt(0);
            output.writeInt(0);
            output.writeLong(0);
            writeText(output, stateHash);
        }
        return bytes.toByteArray();
    }

    private static String legacyStateHash(Map<String, Map<String, Long>> balances)
            throws IOException, NoSuchAlgorithmException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            writeText(output, SETTINGS.worldId());
            output.writeLong(SETTINGS.maxSteps());
            output.writeLong(SETTINGS.startingComponents());
            output.writeLong(SETTINGS.initialCredits());
            output.writeInt(SETTINGS.maxOrders());
            output.writeInt(MEMBERS.size());
            for (AgentId member : MEMBERS) writeText(output, member.value().toString());
            output.writeLong(0);
            output.writeInt(FactoryWorld.INTERNAL_SEQUENCE_BASE);
            output.writeInt(0);
            writeBalances(output, balances);
            output.writeInt(FactoryWorld.MAX_LEDGER_ATTEMPTS);
            output.writeInt(0);
            output.writeInt(0);
        }
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
    }

    private static void writeBalances(DataOutputStream output, Map<String, Map<String, Long>> balances)
            throws IOException {
        output.writeInt(balances.size());
        for (Map.Entry<String, Map<String, Long>> account : new TreeMap<>(balances).entrySet()) {
            writeText(output, account.getKey());
            output.writeInt(account.getValue().size());
            for (Map.Entry<String, Long> resource : new TreeMap<>(account.getValue()).entrySet()) {
                writeText(output, resource.getKey());
                output.writeLong(resource.getValue());
            }
        }
    }

    private static void writeText(DataOutputStream output, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(encoded.length);
        output.write(encoded);
    }
}
