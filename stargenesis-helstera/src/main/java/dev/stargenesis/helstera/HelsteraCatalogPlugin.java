package dev.stargenesis.helstera;

import dev.helstera.api.HelsteraApi;
import dev.stargenesis.integrations.InstalledPlugin;
import dev.stargenesis.integrations.ModelCatalog;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Optional;

/** Separate optional artifact. The base plugin never resolves a Helstera class. */
public final class HelsteraCatalogPlugin extends JavaPlugin implements Listener {
    private HelsteraModelCatalog catalog;

    @Override public void onEnable() {
        if (!supportedServer()) {
            getLogger().severe("Read-only model bridge requires Paper 1.21.11; Folia is unsupported.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        catalog = new HelsteraModelCatalog(Bukkit::isPrimaryThread, this::supportedServer, () -> {
            var plugin = getServer().getPluginManager().getPlugin("helsteraMobs");
            return plugin == null ? null : new InstalledPlugin(plugin.getName(),
                    plugin.getPluginMeta().getVersion(), plugin.isEnabled());
        }, () -> HelsteraApi.isAvailable() ? Optional.of(HelsteraApi.get()) : Optional.empty());
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getServicesManager().register(ModelCatalog.class, catalog, this, ServicePriority.Normal);
        getLogger().info("Registered read-only model catalog; projection and behavior registration remain inactive.");
    }

    private boolean supportedServer() {
        return "Paper".equals(getServer().getName()) && "1.21.11".equals(getServer().getMinecraftVersion());
    }

    @EventHandler public void onProviderDisabled(PluginDisableEvent event) {
        if (event.getPlugin().getName().equals("helsteraMobs") || event.getPlugin().getName().equals("StarGenesis"))
            getServer().getPluginManager().disablePlugin(this);
    }

    @Override public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
        if (catalog != null) { catalog.close(); catalog = null; }
    }
}
