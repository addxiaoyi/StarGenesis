package dev.stargenesis.design;

import java.util.List;
import java.util.Map;

public record BlueprintAnalysis(int componentCount, int connectionCount, long totalMass, long totalEnergy,
                                long maximumDurability, Map<String, Integer> moduleCounts,
                                Map<ModuleCapability, Integer> capabilityCounts, String shapeHash, List<String> issues) {
    public BlueprintAnalysis {
        moduleCounts = Map.copyOf(moduleCounts);
        capabilityCounts = Map.copyOf(capabilityCounts);
        issues = List.copyOf(issues);
    }
    public boolean valid() { return issues.isEmpty(); }
}
