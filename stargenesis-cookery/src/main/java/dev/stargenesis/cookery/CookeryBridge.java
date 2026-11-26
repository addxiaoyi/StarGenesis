package dev.stargenesis.cookery;

import dev.stargenesis.integrations.*;
import net.kaleidoscope.cookery.recipe.FoodRecipeRegistry;
import java.util.*;
import java.util.function.*;

/** Main-thread admission, actual CE resource lease, and one pinned registry view per query. */
public final class CookeryBridge implements RecipeCatalog {
    public static final String SOURCE_REVISION = "7aca215fca067314d1593dd287621f96fb8913e4";
    private final BooleanSupplier primaryThread, supportedServer;
    private final Supplier<InstalledPlugin> cookery, craftengine;
    private final Supplier<CookeryAccess> provider;
    private boolean closed, reading;

    CookeryBridge(BooleanSupplier primaryThread, BooleanSupplier supportedServer,
                  Supplier<InstalledPlugin> cookery, Supplier<InstalledPlugin> craftengine, Supplier<CookeryAccess> provider) {
        this.primaryThread = Objects.requireNonNull(primaryThread);
        this.supportedServer = Objects.requireNonNull(supportedServer);
        this.cookery = Objects.requireNonNull(cookery); this.craftengine = Objects.requireNonNull(craftengine);
        this.provider = Objects.requireNonNull(provider);
    }
    private void thread() {
        if (!primaryThread.getAsBoolean()) throw new IllegalStateException("Recipe bridge requires the Paper main thread");
    }
    private static void requirePlugin(InstalledPlugin plugin, String name, String version) {
        if (plugin == null || !plugin.enabled() || !name.equals(plugin.name()) || !version.equals(plugin.version()))
            throw new IllegalStateException(name + " is missing, disabled or not audited");
    }
    private CookeryAccess current() {
        if (closed) throw new IllegalStateException("Recipe bridge is closed");
        if (!supportedServer.getAsBoolean()) throw new IllegalStateException("Recipe bridge requires verified Paper 1.21.11");
        requirePlugin(cookery.get(), "KaleidoscopeCookeryPlugin", "1.3.0");
        requirePlugin(craftengine.get(), "CraftEngine", "26.9.1");
        var access = Objects.requireNonNull(provider.get());
        if (!access.ready() || access.registry().isConfigurationLoading()) throw new IllegalStateException("Recipe provider is loading or stopping");
        // A provider callback can close or replace a service even on the main thread.
        var latest = Objects.requireNonNull(provider.get());
        if (closed || latest.identity() != access.identity() || latest.resourceIdentity() != access.resourceIdentity()
                || latest.registry() != access.registry()) throw new IllegalStateException("Recipe provider changed during readiness check");
        requirePlugin(cookery.get(), "KaleidoscopeCookeryPlugin", "1.3.0");
        requirePlugin(craftengine.get(), "CraftEngine", "26.9.1");
        return access;
    }
    private synchronized <T> T read(Function<FoodRecipeRegistry, T> action) {
        thread();
        if (reading) throw new IllegalStateException("Reentrant recipe bridge access is forbidden");
        reading = true;
        try {
            var access = current();
            try (var lease = access.resources().acquire()) {
                var registry = access.registry();
                if (!access.ready() || registry.isConfigurationLoading()) throw new IllegalStateException("Recipe provider changed during admission");
                long before = registry.generation();
                T result = registry.readSnapshot(() -> {
                    // Bounds precede list allocations by the provider's public metadata methods.
                    int count = registry.totalRecipeCount();
                    if (count < 0 || count > MAX_RECIPES) throw new IllegalStateException("Recipe registry budget exceeded");
                    return action.apply(registry);
                });
                var after = current();
                if (after.identity() != access.identity() || after.resourceIdentity() != access.resourceIdentity()
                        || after.registry() != registry || registry.generation() != before)
                    throw new IllegalStateException("Recipe provider changed during read");
                return result;
            }
        } finally { reading = false; }
    }
    @Override public String integrationId() { return "senluo.cookery"; }
    @Override public AdapterVerification verify(InstalledPlugin installed) {
        requirePlugin(installed, "KaleidoscopeCookeryPlugin", "1.3.0");
        summary();
        return new AdapterVerification(true, true, Set.of("cookery.read"), SOURCE_REVISION);
    }
    @Override public Summary summary() {
        return read(registry -> {
            var counts = new EnumMap<Appliance, Integer>(Appliance.class);
            for (var appliance : Appliance.values()) counts.put(appliance, registry.recipeCount(RecipeCopies.appliance(appliance)));
            return new Summary(registry.totalRecipeCount(), counts);
        });
    }
    @Override public List<String> list(Appliance appliance, Family family) {
        new Key(appliance, family, "stargenesis:validation");
        return read(registry -> RecipeCopies.ids(registry, appliance, family));
    }
    @Override public Optional<Definition> inspect(Key key) {
        Objects.requireNonNull(key);
        return read(registry -> RecipeCopies.inspect(registry, key));
    }
    @Override public synchronized void close() { thread(); closed = true; }
}
