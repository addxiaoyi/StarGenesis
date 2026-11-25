package dev.stargenesis.sim;

import dev.stargenesis.api.design.DesignId;
import dev.stargenesis.api.sim.AgentId;
import dev.stargenesis.api.sim.EvidenceId;
import dev.stargenesis.api.sim.ResourceLedger.TransferResult;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Bounded binary encoding for server-owned virtual factory recovery state. */
public final class FactoryCheckpointCodec {
    public static final int MAX_ENCODED_BYTES = 2_097_152;
    private static final int MAGIC = 0x53474643;
    private static final int VERSION = 2;
    private static final int MIN_SUPPORTED_VERSION = 1;
    private static final int MAX_LEDGER_ACCOUNTS = FactorySettings.MAX_ORDERS * 2
            + FactorySettings.MEMBER_COUNT * 2 + 2;

    private FactoryCheckpointCodec() { }

    public static byte[] encode(FactoryCheckpoint checkpoint) {
        if (checkpoint == null) throw new IllegalArgumentException("Factory checkpoint is required");
        try (FactoryWorld world = FactoryWorld.restore(checkpoint)) {
            checkpoint = world.checkpoint();
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                FactorySettings settings = checkpoint.settings();
                output.writeInt(MAGIC);
                output.writeInt(VERSION);
                writeText(output, settings.worldId());
                output.writeLong(settings.maxSteps());
                output.writeLong(settings.startingComponents());
                output.writeLong(settings.initialCredits());
                output.writeInt(settings.maxOrders());
                output.writeInt(settings.members().size());
                for (AgentId member : settings.members()) writeText(output, member.value().toString());
                output.writeLong(checkpoint.tick());
                output.writeInt(checkpoint.nextAutomaticSequence());
                output.writeInt(checkpoint.orders().size());
                for (FactoryWorld.Order order : checkpoint.orders()) writeOrder(output, order);
                writeBalances(output, checkpoint.ledger().balances());
                output.writeInt(checkpoint.ledger().maxAttempts());
                output.writeInt(checkpoint.ledger().attempts().size());
                for (ConservingResourceLedger.Attempt attempt : checkpoint.ledger().attempts()) {
                    writeEvidence(output, attempt.key());
                    writeText(output, attempt.source());
                    writeText(output, attempt.target());
                    writeText(output, attempt.resource());
                    output.writeLong(attempt.amount());
                    output.writeByte(attempt.result().ordinal());
                }
                output.writeInt(checkpoint.bidActions().size());
                for (FactoryWorld.BidAction action : checkpoint.bidActions()) {
                    writeEvidence(output, action.evidenceId());
                    writeText(output, action.bidder().value().toString());
                    writeText(output, action.fingerprint());
                }
                output.writeBoolean(checkpoint.productionMaterialsSeeded());
                output.writeInt(checkpoint.stationJobs().size());
                for (FactoryWorld.StationJob job : checkpoint.stationJobs()) {
                    writeText(output, job.owner().value().toString());
                    writeText(output, job.recipeId().value());
                    output.writeInt(job.batches());
                    output.writeLong(job.startedAt());
                    output.writeLong(job.completesAt());
                }
                output.writeInt(checkpoint.productionActions().size());
                for (FactoryWorld.ProductionAction action : checkpoint.productionActions()) {
                    writeEvidence(output, action.evidenceId());
                    writeText(output, action.member().value().toString());
                    writeText(output, action.fingerprint());
                    output.writeByte(action.result().ordinal());
                }
                output.writeLong(checkpoint.tick());
                writeText(output, checkpoint.stateHash());
            }
            byte[] encoded = bytes.toByteArray();
            if (encoded.length > MAX_ENCODED_BYTES) throw new IllegalArgumentException("Factory checkpoint is too large");
            return encoded;
        } catch (IOException impossible) {
            throw new IllegalStateException("Cannot encode factory checkpoint", impossible);
        }
    }

    public static FactoryCheckpoint decode(byte[] encoded) {
        if (encoded == null || encoded.length < 8 || encoded.length > MAX_ENCODED_BYTES) {
            throw new IllegalArgumentException("Invalid factory checkpoint size");
        }
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != MAGIC) {
                throw new IllegalArgumentException("Unsupported factory checkpoint format");
            }
            int version = input.readInt();
            if (version < MIN_SUPPORTED_VERSION || version > VERSION) {
                throw new IllegalArgumentException("Unsupported factory checkpoint format");
            }
            String worldId = readText(input, 64);
            long maxSteps = input.readLong();
            long startingComponents = input.readLong();
            long initialCredits = input.readLong();
            int maxOrders = input.readInt();
            int memberCount = readCount(input, FactorySettings.MEMBER_COUNT,
                    FactorySettings.MEMBER_COUNT, "factory member");
            List<AgentId> members = new ArrayList<>(memberCount);
            for (int index = 0; index < memberCount; index++) members.add(readAgent(input));
            FactorySettings settings = new FactorySettings(worldId, members, maxSteps,
                    startingComponents, initialCredits, maxOrders);
            long tick = input.readLong();
            int nextAutomaticSequence = input.readInt();
            int orderCount = readCount(input, 0, settings.maxOrders(), "factory order");
            List<FactoryWorld.Order> orders = new ArrayList<>(orderCount);
            for (int index = 0; index < orderCount; index++) orders.add(readOrder(input));
            Map<String, Map<String, Long>> balances = readBalances(input);
            int maxAttempts = input.readInt();
            if (maxAttempts != FactoryWorld.MAX_LEDGER_ATTEMPTS) {
                throw new IllegalArgumentException("Unsupported factory ledger evidence budget");
            }
            int attemptCount = readCount(input, 0, maxAttempts, "ledger attempt");
            List<ConservingResourceLedger.Attempt> attempts = new ArrayList<>(attemptCount);
            for (int index = 0; index < attemptCount; index++) {
                EvidenceId key = readEvidence(input, settings.worldId());
                String source = readText(input, 128);
                String target = readText(input, 128);
                String resource = readText(input, 128);
                long amount = input.readLong();
                TransferResult result = readEnum(input, TransferResult.values(), "transfer result");
                attempts.add(new ConservingResourceLedger.Attempt(key, source, target, resource, amount, result));
            }
            ConservingResourceLedger.Checkpoint ledger = new ConservingResourceLedger.Checkpoint(
                    balances, attempts, maxAttempts);
            int actionCount = readCount(input, 0, FactoryWorld.MAX_BID_ACTIONS, "factory bid action");
            List<FactoryWorld.BidAction> bidActions = new ArrayList<>(actionCount);
            for (int index = 0; index < actionCount; index++) {
                EvidenceId evidenceId = readEvidence(input, settings.worldId());
                AgentId bidder = readAgent(input);
                bidActions.add(new FactoryWorld.BidAction(evidenceId, bidder, readText(input, 256)));
            }
            boolean productionMaterialsSeeded = false;
            List<FactoryWorld.StationJob> stationJobs = new ArrayList<>();
            List<FactoryWorld.ProductionAction> productionActions = new ArrayList<>();
            if (version >= 2) {
                productionMaterialsSeeded = input.readBoolean();
                int stationCount = readCount(input, 0, FactorySettings.MEMBER_COUNT,
                        "factory workstation job");
                for (int index = 0; index < stationCount; index++) {
                    AgentId owner = readAgent(input);
                    DesignId recipeId = new DesignId(readText(input, 128));
                    int batches = input.readInt();
                    long startedAt = input.readLong();
                    long completesAt = input.readLong();
                    stationJobs.add(new FactoryWorld.StationJob(owner, recipeId, batches,
                            startedAt, completesAt));
                }
                int productionCount = readCount(input, 0, FactoryWorld.MAX_PRODUCTION_ACTIONS,
                        "factory production action");
                for (int index = 0; index < productionCount; index++) {
                    EvidenceId evidenceId = readEvidence(input, settings.worldId());
                    AgentId member = readAgent(input);
                    String fingerprint = readText(input, 256);
                    FactoryWorld.ProductionActionResult result = readEnum(input,
                            FactoryWorld.ProductionActionResult.values(), "production action result");
                    productionActions.add(new FactoryWorld.ProductionAction(evidenceId, member,
                            fingerprint, result));
                }
            }
            long repeatedTick = input.readLong();
            String stateHash = readText(input, 64);
            if (repeatedTick != tick || input.available() != 0) {
                throw new IllegalArgumentException("Factory checkpoint has inconsistent or trailing data");
            }
            FactoryCheckpoint checkpoint = new FactoryCheckpoint(settings, tick, orders, ledger,
                    bidActions, stationJobs, productionActions, productionMaterialsSeeded, version,
                    nextAutomaticSequence, stateHash);
            try (FactoryWorld restored = FactoryWorld.restore(checkpoint)) {
                return restored.checkpoint();
            }
        } catch (IOException failure) {
            throw new IllegalArgumentException("Cannot decode factory checkpoint", failure);
        }
    }

    private static void writeOrder(DataOutputStream output, FactoryWorld.Order order) throws IOException {
        writeText(output, order.id().value());
        writeText(output, order.creator().value().toString());
        output.writeLong(order.requiredComponents());
        output.writeLong(order.rewardCredits());
        output.writeLong(order.auctionClosesAt());
        output.writeInt(order.deliveryWindow());
        output.writeLong(order.deliveryDeadline());
        output.writeByte(order.status().ordinal());
        output.writeBoolean(order.winner() != null);
        if (order.winner() != null) writeText(output, order.winner().value().toString());
        output.writeLong(order.deliveredComponents());
        output.writeInt(order.bids().size());
        for (FactoryWorld.Bid bid : order.bids()) {
            writeText(output, bid.bidder().value().toString());
            output.writeLong(bid.priceCredits());
            output.writeLong(bid.tick());
        }
        output.writeInt(order.contributions().size());
        for (Map.Entry<AgentId, Long> contribution : order.contributions().entrySet()) {
            writeText(output, contribution.getKey().value().toString());
            output.writeLong(contribution.getValue());
        }
    }

    private static FactoryWorld.Order readOrder(DataInputStream input) throws IOException {
        DesignId id = new DesignId(readText(input, 128));
        AgentId creator = readAgent(input);
        long requiredComponents = input.readLong();
        long rewardCredits = input.readLong();
        long auctionClosesAt = input.readLong();
        int deliveryWindow = input.readInt();
        long deliveryDeadline = input.readLong();
        FactoryWorld.OrderStatus status = readEnum(input, FactoryWorld.OrderStatus.values(), "order status");
        AgentId winner = input.readBoolean() ? readAgent(input) : null;
        long deliveredComponents = input.readLong();
        int bidCount = readCount(input, 0, FactorySettings.MEMBER_COUNT, "factory bid");
        List<FactoryWorld.Bid> bids = new ArrayList<>(bidCount);
        for (int index = 0; index < bidCount; index++) {
            bids.add(new FactoryWorld.Bid(readAgent(input), input.readLong(), input.readLong()));
        }
        int contributionCount = readCount(input, 0, FactorySettings.MEMBER_COUNT, "factory contribution");
        Map<AgentId, Long> contributions = new TreeMap<>();
        for (int index = 0; index < contributionCount; index++) {
            AgentId contributor = readAgent(input);
            long amount = input.readLong();
            if (contributions.putIfAbsent(contributor, amount) != null) {
                throw new IllegalArgumentException("Duplicate factory contribution");
            }
        }
        return new FactoryWorld.Order(id, creator, requiredComponents, rewardCredits, auctionClosesAt,
                deliveryWindow, deliveryDeadline, status, bids, winner, deliveredComponents, contributions);
    }

    private static void writeBalances(DataOutputStream output, Map<String, Map<String, Long>> balances)
            throws IOException {
        output.writeInt(balances.size());
        for (Map.Entry<String, Map<String, Long>> account : new TreeMap<>(balances).entrySet()) {
            writeText(output, account.getKey());
            output.writeInt(account.getValue().size());
            for (Map.Entry<String, Long> balance : new TreeMap<>(account.getValue()).entrySet()) {
                writeText(output, balance.getKey());
                output.writeLong(balance.getValue());
            }
        }
    }

    private static Map<String, Map<String, Long>> readBalances(DataInputStream input) throws IOException {
        int accountCount = readCount(input, 0, MAX_LEDGER_ACCOUNTS, "ledger account");
        Map<String, Map<String, Long>> balances = new TreeMap<>();
        for (int accountIndex = 0; accountIndex < accountCount; accountIndex++) {
            String account = readText(input, 128);
            int resourceCount = readCount(input, 0, 4, "account resource");
            Map<String, Long> resources = new TreeMap<>();
            for (int resourceIndex = 0; resourceIndex < resourceCount; resourceIndex++) {
                String resource = readText(input, 128);
                long amount = input.readLong();
                if (amount < 0 || resources.putIfAbsent(resource, amount) != null) {
                    throw new IllegalArgumentException("Invalid ledger balance entry");
                }
            }
            if (balances.putIfAbsent(account, resources) != null) {
                throw new IllegalArgumentException("Duplicate ledger account");
            }
        }
        return balances;
    }

    private static void writeEvidence(DataOutputStream output, EvidenceId evidenceId) throws IOException {
        writeText(output, evidenceId.world());
        output.writeLong(evidenceId.tick());
        output.writeInt(evidenceId.sequence());
    }

    private static EvidenceId readEvidence(DataInputStream input, String expectedWorld) throws IOException {
        EvidenceId evidenceId = new EvidenceId(readText(input, 64), input.readLong(), input.readInt());
        if (!expectedWorld.equals(evidenceId.world())) throw new IllegalArgumentException("Foreign factory evidence ID");
        return evidenceId;
    }

    private static AgentId readAgent(DataInputStream input) throws IOException {
        try {
            return new AgentId(UUID.fromString(readText(input, 36)));
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("Invalid factory member ID", failure);
        }
    }

    private static <T> T readEnum(DataInputStream input, T[] values, String label) throws IOException {
        int ordinal = input.readUnsignedByte();
        if (ordinal >= values.length) throw new IllegalArgumentException("Invalid " + label);
        return values[ordinal];
    }

    private static int readCount(DataInputStream input, int minimum, int maximum, String label) throws IOException {
        int count = input.readInt();
        if (count < minimum || count > maximum) throw new IllegalArgumentException("Invalid " + label + " count");
        return count;
    }

    private static void writeText(DataOutputStream output, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(encoded.length);
        output.write(encoded);
    }

    private static String readText(DataInputStream input, int maximumCharacters) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > maximumCharacters * 4) throw new IllegalArgumentException("Invalid factory text size");
        byte[] encoded = input.readNBytes(length);
        if (encoded.length != length) throw new IllegalArgumentException("Truncated factory text");
        String decoded = new String(encoded, StandardCharsets.UTF_8);
        if (decoded.length() > maximumCharacters || !Arrays.equals(encoded, decoded.getBytes(StandardCharsets.UTF_8))) {
            throw new IllegalArgumentException("Invalid factory UTF-8 text");
        }
        return decoded;
    }
}
