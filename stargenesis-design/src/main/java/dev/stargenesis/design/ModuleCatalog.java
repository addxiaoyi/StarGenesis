package dev.stargenesis.design;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

public final class ModuleCatalog {
    public static final String CORE_TYPE = "genesis:core";
    private final Map<String, ModuleDefinition> definitions;

    public ModuleCatalog(Collection<ModuleDefinition> definitions) {
        Objects.requireNonNull(definitions, "definitions");
        TreeMap<String, ModuleDefinition> indexed = new TreeMap<>();
        for (ModuleDefinition definition : definitions) {
            Objects.requireNonNull(definition, "definition");
            if (indexed.putIfAbsent(definition.type(), definition) != null)
                throw new IllegalArgumentException("Duplicate module type " + definition.type());
        }
        if (indexed.size() > 64 || !indexed.containsKey(CORE_TYPE)
                || indexed.values().stream().filter(ModuleDefinition::isCore).count() != 1)
            throw new IllegalArgumentException("Catalog must define exactly one core and at most 64 module types");
        this.definitions = Map.copyOf(indexed);
    }

    public static ModuleCatalog standard() {
        return new ModuleCatalog(List.of(
                new ModuleDefinition(CORE_TYPE, java.util.Set.of(ModuleCapability.CORE_CONTROL, ModuleCapability.STRUCTURE), 8, 4, 100, 8),
                new ModuleDefinition("genesis:frame", java.util.Set.of(ModuleCapability.STRUCTURE), 4, 0, 80, 6),
                new ModuleDefinition("genesis:collector", java.util.Set.of(ModuleCapability.GATHER), 6, 3, 60, 5),
                new ModuleDefinition("genesis:thruster", java.util.Set.of(ModuleCapability.MOVE), 5, 6, 50, 5),
                new ModuleDefinition("genesis:relay", java.util.Set.of(ModuleCapability.SIGNAL), 3, 2, 40, 8),
                new ModuleDefinition("genesis:storage", java.util.Set.of(ModuleCapability.STORAGE), 4, 1, 70, 6),
                new ModuleDefinition("genesis:repair", java.util.Set.of(ModuleCapability.REPAIR), 5, 3, 55, 5)));
    }
    public Collection<ModuleDefinition> definitions() { return definitions.values(); }
    public ModuleDefinition require(String type) {
        ModuleDefinition definition = definitions.get(type);
        if (definition == null) throw new IllegalArgumentException("Unknown module type " + type);
        return definition;
    }
}
