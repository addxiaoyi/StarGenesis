package dev.stargenesis.design;

import java.util.Objects;
import java.util.Set;

public record ModuleDefinition(String type, Set<ModuleCapability> capabilities, long mass, long energy,
                               long maximumDurability, int connectionRange) {
    public ModuleDefinition {
        Objects.requireNonNull(type, "type");
        if (!type.matches("[a-z][a-z0-9_]{0,31}:[a-z][a-z0-9_]{0,31}"))
            throw new IllegalArgumentException("Invalid module type");
        capabilities = Set.copyOf(capabilities);
        if (capabilities.isEmpty() || mass < 0 || mass > 1_000_000 || energy < 0 || energy > 1_000_000 || maximumDurability < 1
                || maximumDurability > 1_000_000 || connectionRange < 1 || connectionRange > 32)
            throw new IllegalArgumentException("Invalid module definition");
        if (capabilities.contains(ModuleCapability.CORE_CONTROL) && !type.equals(ModuleCatalog.CORE_TYPE))
            throw new IllegalArgumentException("Only the core module may provide core control");
    }
    public boolean isCore() { return capabilities.contains(ModuleCapability.CORE_CONTROL); }
}
