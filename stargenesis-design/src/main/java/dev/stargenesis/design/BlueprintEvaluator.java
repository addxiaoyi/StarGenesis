package dev.stargenesis.design;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** Deterministic physical cost and capability projection using abstract catalogue units. */
public final class BlueprintEvaluator {
    private final Map<String, ComponentModule> modules;

    public BlueprintEvaluator(Map<String, ComponentModule> modules) {
        Objects.requireNonNull(modules, "modules");
        LinkedHashMap<String, ComponentModule> copy = new LinkedHashMap<>();
        modules.forEach((id, module) -> {
            Objects.requireNonNull(module, "module");
            if (!id.equals(module.id()) || copy.putIfAbsent(id, module) != null)
                throw new IllegalArgumentException("Duplicate or mismatched module ID");
        });
        this.modules = Collections.unmodifiableMap(copy);
    }

    public static BlueprintEvaluator standard() {
        return new BlueprintEvaluator(Map.of(
                "genesis:core", new ComponentModule("genesis:core", 16, 128, 4, 1, 32, 0, 0, true,
                        Set.of("control")),
                "genesis:frame", new ComponentModule("genesis:frame", 4, 32, 0, 0, 8, 0, 0, false,
                        Set.of("structure")),
                "genesis:solar_panel", new ComponentModule("genesis:solar_panel", 5, 0, 12, 0, 14, 0, 0, false,
                        Set.of("power")),
                "genesis:sensor", new ComponentModule("genesis:sensor", 2, 0, 0, 1, 5, 0, 0, false,
                        Set.of("sensing")),
                "genesis:wheel", new ComponentModule("genesis:wheel", 8, 16, 0, 2, 10, 0, 0, false,
                        Set.of("ground_motion")),
                "genesis:thruster", new ComponentModule("genesis:thruster", 12, 0, 0, 6, 18, 40, 0, false,
                        Set.of("thrust")),
                "genesis:wing", new ComponentModule("genesis:wing", 8, 0, 0, 0, 16, 0, 40, false,
                        Set.of("lift"))));
    }

    public List<ComponentModule> availableModules() {
        return modules.values().stream().sorted((left, right) -> left.id().compareTo(right.id())).toList();
    }

    public boolean hasModule(String id) { return modules.containsKey(id); }

    public Evaluation evaluate(Blueprint blueprint) {
        Objects.requireNonNull(blueprint, "blueprint");
        long mass = 0;
        long supportCapacity = 0;
        long powerGeneration = 0;
        long powerDemand = 0;
        long materialCost = 0;
        long thrust = 0;
        long lift = 0;
        int cores = 0;
        Set<String> capabilities = new TreeSet<>();
        List<Issue> issues = new ArrayList<>();

        for (Blueprint.Component component : blueprint.components()) {
            ComponentModule module = modules.get(component.type());
            if (module == null) {
                issues.add(new Issue(IssueCode.UNKNOWN_MODULE, component.id(), component.type()));
                continue;
            }
            mass += module.mass();
            supportCapacity += module.supportCapacity();
            powerGeneration += module.powerGeneration();
            powerDemand += module.powerDemand();
            materialCost += module.materialCost();
            thrust += module.thrust();
            lift += module.lift();
            if (module.core()) cores++;
            capabilities.addAll(module.capabilities());
        }

        if (cores != 1) issues.add(new Issue(IssueCode.CORE_COUNT, "", "expected exactly one core, found " + cores));
        if (supportCapacity < mass)
            issues.add(new Issue(IssueCode.OVERLOADED, "", "mass " + mass + " exceeds support capacity " + supportCapacity));
        if (powerGeneration < powerDemand)
            issues.add(new Issue(IssueCode.POWER_DEFICIT, "", "demand " + powerDemand + " exceeds generation " + powerGeneration));

        return new Evaluation(mass, supportCapacity, powerGeneration, powerDemand, materialCost, thrust, lift,
                capabilities, issues);
    }

    /** Values are virtual scoring units and never represent Bukkit items or placed blocks. */
    public record ComponentModule(String id, long mass, long supportCapacity, long powerGeneration,
                                  long powerDemand, long materialCost, long thrust, long lift,
                                  boolean core, Set<String> capabilities) {
        private static final long MAX_VALUE = 1_000_000;

        public ComponentModule {
            if (id == null || !id.matches("[a-z][a-z0-9_:-]{0,63}"))
                throw new IllegalArgumentException("Invalid module ID");
            requireRange(mass, 1, MAX_VALUE, "mass");
            requireRange(supportCapacity, 0, MAX_VALUE, "support capacity");
            requireRange(powerGeneration, 0, MAX_VALUE, "power generation");
            requireRange(powerDemand, 0, MAX_VALUE, "power demand");
            requireRange(materialCost, 0, MAX_VALUE, "material cost");
            requireRange(thrust, 0, MAX_VALUE, "thrust");
            requireRange(lift, 0, MAX_VALUE, "lift");
            capabilities = Set.copyOf(capabilities);
            if (capabilities.stream().anyMatch(value -> value == null || !value.matches("[a-z][a-z0-9_:-]{0,63}")))
                throw new IllegalArgumentException("Invalid module capability");
        }

        private static void requireRange(long value, long min, long max, String label) {
            if (value < min || value > max) throw new IllegalArgumentException("Invalid " + label);
        }
    }

    public record Evaluation(long mass, long supportCapacity, long powerGeneration, long powerDemand,
                             long materialCost, long thrust, long lift,
                             Set<String> installedCapabilities, List<Issue> issues) {
        public Evaluation {
            installedCapabilities = Set.copyOf(installedCapabilities);
            issues = List.copyOf(issues);
        }

        public boolean ready() { return issues.isEmpty(); }
    }

    public record Issue(IssueCode code, String componentId, String detail) { }

    public enum IssueCode { UNKNOWN_MODULE, CORE_COUNT, OVERLOADED, POWER_DEFICIT }
}
