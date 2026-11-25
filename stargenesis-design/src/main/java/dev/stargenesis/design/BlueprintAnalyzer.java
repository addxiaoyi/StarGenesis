package dev.stargenesis.design;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

public final class BlueprintAnalyzer {
    private final ModuleCatalog catalog;
    private final BlueprintBudget budget;
    public BlueprintAnalyzer(ModuleCatalog catalog, BlueprintBudget budget) {
        this.catalog = java.util.Objects.requireNonNull(catalog, "catalog");
        this.budget = java.util.Objects.requireNonNull(budget, "budget");
    }
    public BlueprintAnalysis analyze(Blueprint blueprint) {
        List<String> issues = new ArrayList<>();
        Map<String, Integer> moduleCounts = new TreeMap<>();
        Map<ModuleCapability, Integer> capabilityCounts = new EnumMap<>(ModuleCapability.class);
        long mass = 0, energy = 0, durability = 0; int coreCount = 0;
        for (Blueprint.Component component : blueprint.components()) {
            ModuleDefinition definition;
            try { definition = catalog.require(component.type()); }
            catch (IllegalArgumentException unknown) { issues.add("Unknown module type " + component.type()); continue; }
            moduleCounts.merge(component.type(), 1, Integer::sum);
            if (definition.isCore()) coreCount++;
            for (ModuleCapability capability : definition.capabilities()) capabilityCounts.merge(capability, 1, Integer::sum);
            mass = Math.addExact(mass, definition.mass()); energy = Math.addExact(energy, definition.energy());
            durability = Math.addExact(durability, definition.maximumDurability());
        }
        if (coreCount != 1) issues.add("A blueprint must contain exactly one core module");
        if (blueprint.components().size() > budget.maximumComponents()) issues.add("Component budget exceeded");
        if (blueprint.connections().size() > budget.maximumConnections()) issues.add("Connection budget exceeded");
        if (mass > budget.maximumMass()) issues.add("Mass budget exceeded");
        if (energy > budget.maximumEnergy()) issues.add("Energy budget exceeded");
        for (Blueprint.Connection connection : blueprint.connections()) {
            Blueprint.Component from = component(blueprint, connection.from()), to = component(blueprint, connection.to());
            try {
                int distance = distance(from, to);
                if (distance > Math.min(catalog.require(from.type()).connectionRange(), catalog.require(to.type()).connectionRange()))
                    issues.add("Connection range exceeded for " + BlueprintShapeDifference.connectionKey(connection));
            } catch (IllegalArgumentException unknown) {
                // Unknown module types are already reported for their components.
            }
        }
        return new BlueprintAnalysis(blueprint.components().size(), blueprint.connections().size(), mass, energy,
                durability, moduleCounts, capabilityCounts, shapeHash(blueprint), issues);
    }
    public BlueprintPreview preview(Blueprint before, Blueprint candidate) {
        BlueprintAnalysis beforeAnalysis = analyze(before), afterAnalysis = analyze(candidate);
        return new BlueprintPreview(candidate, beforeAnalysis, afterAnalysis,
                BlueprintShapeDifference.between(before, candidate, beforeAnalysis.shapeHash(), afterAnalysis.shapeHash()),
                afterAnalysis.issues());
    }
    public void requireValid(Blueprint blueprint) {
        BlueprintAnalysis analysis = analyze(blueprint);
        if (!analysis.valid()) throw new IllegalArgumentException(String.join("; ", analysis.issues()));
    }
    private static Blueprint.Component component(Blueprint blueprint, String id) {
        return blueprint.components().stream().filter(value -> value.id().equals(id)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown component " + id));
    }
    private static int distance(Blueprint.Component first, Blueprint.Component second) {
        return Math.abs(first.x() - second.x()) + Math.abs(first.y() - second.y()) + Math.abs(first.z() - second.z());
    }
    private static String shapeHash(Blueprint blueprint) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                List<Blueprint.Component> components = blueprint.components().stream()
                        .sorted(Comparator.comparing(Blueprint.Component::id)).toList();
                output.writeInt(components.size());
                for (Blueprint.Component component : components) {
                    writeText(output, component.id()); writeText(output, component.type());
                    output.writeInt(component.x()); output.writeInt(component.y()); output.writeInt(component.z());
                    output.writeInt(component.rotation());
                }
                TreeSet<String> connections = new TreeSet<>();
                blueprint.connections().forEach(connection -> connections.add(BlueprintShapeDifference.connectionKey(connection)));
                output.writeInt(connections.size());
                for (String connection : connections) writeText(output, connection);
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException impossible) { throw new IllegalStateException("Cannot encode blueprint shape", impossible); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 is unavailable", impossible); }
    }
    private static void writeText(DataOutputStream output, String value) throws IOException {
        byte[] encoded = value.getBytes(StandardCharsets.UTF_8); output.writeInt(encoded.length); output.write(encoded);
    }
}
