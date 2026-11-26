import dev.helstera.api.HelsteraApi;
import dev.helstera.api.model.ModelDefinition;
import dev.helstera.api.model.ModelRegistry;
import dev.stargenesis.integrations.ModelCatalog;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.nio.file.Files;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Actual provider lifecycle fixture. No model instances, players or inventories are created. */
public final class HelsteraCatalogServerProbe extends JavaPlugin {
    private int phase, attempts, initialCount;
    private ModelCatalog catalog;
    private ModelRegistry registry;
    private CompletableFuture<Optional<ModelDefinition>> loading;
    private BukkitTask task;

    @Override public void onEnable() {
        task = Bukkit.getScheduler().runTaskTimer(this, this::advance, 20, 5);
    }

    private void advance() {
        try {
            if (++attempts > 120) throw new AssertionError("Provider/model load timeout");
            if (phase == 0) {
                if (!HelsteraApi.isAvailable()) return;
                catalog = Bukkit.getServicesManager().load(ModelCatalog.class);
                require(catalog != null, "catalog service registered");
                registry = HelsteraApi.get().getModelRegistry();
                initialCount = registry.count();
                require(catalog.loadedModels() == initialCount, "provider count");
                require(catalog.inspect("stargenesis/catalog_probe").isEmpty(), "unloaded model is absent");
                command("genesis model status");
                command("genesis model inspect stargenesis/catalog_probe");
                command("genesis debug capabilities");
                loading = registry.load(getDataFolder().toPath().resolve("model"));
                phase = 1;
            } else if (phase == 1 || phase == 3) {
                if (!loading.isDone()) return;
                var model = loading.join().orElseThrow(() -> new AssertionError("fixture model failed actual parser/validator"));
                require(registry.get(model.id()).isEmpty(), "load parses; caller still registers on main thread");
                registry.register(model);
                var info = catalog.inspect(model.id()).orElseThrow();
                require(info.id().equals("stargenesis/catalog_probe") && info.bones() == 1
                        && info.scale() == 1 && info.hitboxWidth() == 1 && info.hitboxHeight() == 2,
                        "actual loaded metadata");
                require(catalog.loadedModels() == initialCount + 1, "loaded count reflects registration");
                command("genesis model inspect stargenesis/catalog_probe");
                phase++;
            } else if (phase == 2) {
                require(registry.unload("stargenesis/catalog_probe"), "unload succeeds");
                require(catalog.inspect("stargenesis/catalog_probe").isEmpty(), "unload invalidates inspection immediately");
                require(catalog.loadedModels() == initialCount, "unloaded count");
                loading = registry.load(getDataFolder().toPath().resolve("model"));
                phase = 3;
            } else {
                require(HelsteraApi.get().getInstanceManager().activeCount() == 0, "no model instances created");
                var provider = Bukkit.getPluginManager().getPlugin("helsteraMobs");
                Bukkit.getPluginManager().disablePlugin(provider);
                require(!provider.isEnabled(), "provider disabled");
                require(Bukkit.getServicesManager().load(ModelCatalog.class) == null, "catalog unregistered");
                require(!Bukkit.getPluginManager().getPlugin("StarGenesisHelstera").isEnabled(), "bridge disabled");
                require(Bukkit.getPluginManager().getPlugin("StarGenesis").isEnabled(), "base plugin remains available");
                boolean rejected = false;
                try { catalog.loadedModels(); } catch (IllegalStateException expected) { rejected = true; }
                require(rejected, "previous service reference closed");
                command("genesis model status");
                command("genesis debug capabilities");
                Files.writeString(getDataFolder().toPath().resolve("result.json"),
                        "{\"passed\":true,\"actualProvider\":true,\"modelLoadUnloadReload\":true,"
                        + "\"providerDisable\":true,\"staleReferenceRejected\":true,\"instancesCreated\":0,"
                        + "\"scope\":\"Paper console/API only; no entity projection or visual acceptance\"}\n");
                task.cancel();
                getLogger().info("HELSTERA_CATALOG_PROBE_PASSED");
            }
        } catch (Throwable failure) {
            task.cancel();
            getLogger().log(java.util.logging.Level.SEVERE, "HELSTERA_CATALOG_PROBE_FAILED", failure);
        }
    }

    private static void command(String command) {
        require(Bukkit.dispatchCommand(Bukkit.getConsoleSender(), command), "command: " + command);
    }
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
