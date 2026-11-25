package dev.stargenesis.design;

import dev.stargenesis.api.design.DesignDocument;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

/** A private, deterministic assembly exercise over a saved blueprint. */
public final class BlueprintAssemblySession {
    public static final int MAX_CHECKPOINT_BYTES = 1_048_576;
    private static final int CHECKPOINT_MAGIC = 0x53474131;
    private static final int CHECKPOINT_VERSION = 1;
    private final UUID owner;
    private final Blueprint blueprint;
    private final List<Blueprint.Component> order;
    private int placedCount;

    private BlueprintAssemblySession(UUID owner, Blueprint blueprint, ModuleCatalog catalog, BlueprintBudget budget) {
        this.owner = Objects.requireNonNull(owner, "owner");
        this.blueprint = Objects.requireNonNull(blueprint, "blueprint");
        Objects.requireNonNull(catalog, "catalog");
        new BlueprintAnalyzer(catalog, Objects.requireNonNull(budget, "budget")).requireValid(blueprint);
        if (!blueprint.metadata().permissions().mayEdit(owner, blueprint.metadata().author()))
            throw new SecurityException("Player is not authorized to assemble this blueprint");
        order = assemblyOrder(blueprint);
    }

    public static BlueprintAssemblySession start(UUID owner, Blueprint blueprint, ModuleCatalog catalog, BlueprintBudget budget) {
        return new BlueprintAssemblySession(owner, blueprint, catalog, budget);
    }

    public static BlueprintAssemblySession resume(UUID owner, byte[] encoded, DesignCodec codec,
                                                  ModuleCatalog catalog, BlueprintBudget budget) {
        Objects.requireNonNull(encoded, "encoded");
        Objects.requireNonNull(codec, "codec");
        if (encoded.length < 44 || encoded.length > MAX_CHECKPOINT_BYTES)
            throw new IllegalArgumentException("Blueprint assembly checkpoint exceeds its byte budget");
        byte[] payload = java.util.Arrays.copyOf(encoded, encoded.length - 32);
        byte[] checksum = java.util.Arrays.copyOfRange(encoded, encoded.length - 32, encoded.length);
        if (!MessageDigest.isEqual(checksum, digest(payload)))
            throw new IllegalArgumentException("Blueprint assembly checkpoint checksum mismatch");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(payload))) {
            if (input.readInt() != CHECKPOINT_MAGIC || input.readInt() != CHECKPOINT_VERSION)
                throw new IllegalArgumentException("Unknown blueprint assembly checkpoint");
            UUID storedOwner = new UUID(input.readLong(), input.readLong());
            if (!Objects.requireNonNull(owner, "owner").equals(storedOwner))
                throw new SecurityException("Blueprint assembly checkpoint belongs to another player");
            int length = input.readInt();
            if (length < 1 || length > DesignCodec.MAX_DOCUMENT_BYTES || length > input.available())
                throw new IllegalArgumentException("Invalid blueprint assembly document length");
            byte[] documentBytes = input.readNBytes(length);
            String json = new String(documentBytes, StandardCharsets.UTF_8);
            if (!java.util.Arrays.equals(documentBytes, json.getBytes(StandardCharsets.UTF_8)))
                throw new IllegalArgumentException("Invalid UTF-8 in blueprint assembly checkpoint");
            DesignDocument document = codec.load(json);
            if (!(document instanceof Blueprint blueprint))
                throw new IllegalArgumentException("Assembly checkpoint contains a non-blueprint design");
            int placed = input.readInt();
            if (input.available() != 0) throw new IllegalArgumentException("Trailing blueprint assembly checkpoint data");
            BlueprintAssemblySession restored = new BlueprintAssemblySession(owner, blueprint, catalog, budget);
            if (placed < 0 || placed > restored.order.size())
                throw new IllegalArgumentException("Invalid blueprint assembly progress");
            restored.placedCount = placed;
            return restored;
        } catch (IOException failure) {
            throw new IllegalArgumentException("Invalid blueprint assembly checkpoint", failure);
        }
    }

    public UUID owner() { return owner; }
    public Blueprint blueprint() { return blueprint; }
    public int placedCount() { return placedCount; }
    public int totalComponents() { return order.size(); }
    public boolean complete() { return placedCount == order.size(); }
    public Blueprint.Component nextComponent() { return complete() ? null : order.get(placedCount); }
    public List<Blueprint.Component> placedComponents() { return List.copyOf(order.subList(0, placedCount)); }

    public List<Blueprint.Connection> completedConnections() {
        Set<String> placed = new HashSet<>();
        placedComponents().forEach(component -> placed.add(component.id()));
        return blueprint.connections().stream()
                .filter(connection -> placed.contains(connection.from()) && placed.contains(connection.to()))
                .sorted(java.util.Comparator.comparing(BlueprintShapeDifference::connectionKey)).toList();
    }

    public void placeNextAt(int x, int y, int z) {
        if (complete()) throw new IllegalStateException("Blueprint assembly is already complete");
        Blueprint.Component target = order.get(placedCount);
        if (target.x() != x || target.y() != y || target.z() != z)
            throw new IllegalArgumentException("Place the next module at its blueprint coordinate");
        if (placedCount > 0) {
            Set<String> placed = new HashSet<>();
            placedComponents().forEach(component -> placed.add(component.id()));
            boolean connected = blueprint.connections().stream().anyMatch(connection ->
                    connection.from().equals(target.id()) && placed.contains(connection.to())
                            || connection.to().equals(target.id()) && placed.contains(connection.from()));
            if (!connected) throw new IllegalStateException("The next module is not connected to the assembled structure");
        }
        placedCount++;
    }

    public byte[] encodeCheckpoint(DesignCodec codec) {
        Objects.requireNonNull(codec, "codec");
        try {
            byte[] document = codec.write(blueprint).getBytes(StandardCharsets.UTF_8);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(CHECKPOINT_MAGIC);
                output.writeInt(CHECKPOINT_VERSION);
                output.writeLong(owner.getMostSignificantBits());
                output.writeLong(owner.getLeastSignificantBits());
                output.writeInt(document.length);
                output.write(document);
                output.writeInt(placedCount);
            }
            byte[] payload = bytes.toByteArray();
            byte[] result = java.util.Arrays.copyOf(payload, payload.length + 32);
            System.arraycopy(digest(payload), 0, result, payload.length, 32);
            if (result.length > MAX_CHECKPOINT_BYTES)
                throw new IllegalStateException("Blueprint assembly checkpoint exceeds its byte budget");
            return result;
        } catch (IOException impossible) {
            throw new IllegalStateException("Cannot encode blueprint assembly checkpoint", impossible);
        }
    }

    private static List<Blueprint.Component> assemblyOrder(Blueprint blueprint) {
        Map<String, Blueprint.Component> components = new HashMap<>();
        blueprint.components().forEach(component -> components.put(component.id(), component));
        Map<String, TreeSet<String>> neighbors = new HashMap<>();
        components.keySet().forEach(id -> neighbors.put(id, new TreeSet<>()));
        blueprint.connections().forEach(connection -> {
            neighbors.get(connection.from()).add(connection.to());
            neighbors.get(connection.to()).add(connection.from());
        });
        String coreId = blueprint.components().stream().filter(component -> component.type().equals(ModuleCatalog.CORE_TYPE))
                .map(Blueprint.Component::id).findFirst().orElseThrow(
                        () -> new IllegalArgumentException("Blueprint must contain its core before assembly"));
        List<Blueprint.Component> result = new ArrayList<>(components.size());
        Set<String> visited = new HashSet<>();
        ArrayDeque<String> frontier = new ArrayDeque<>();
        frontier.add(coreId);
        while (!frontier.isEmpty()) {
            String id = frontier.removeFirst();
            if (!visited.add(id)) continue;
            result.add(components.get(id));
            neighbors.get(id).forEach(frontier::addLast);
        }
        if (result.size() != components.size()) throw new IllegalArgumentException("Blueprint assembly requires a connected structure");
        return List.copyOf(result);
    }

    private static byte[] digest(byte[] value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable", impossible); }
    }
}
