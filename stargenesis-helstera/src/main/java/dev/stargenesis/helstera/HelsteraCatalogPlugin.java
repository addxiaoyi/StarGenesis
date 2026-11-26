package dev.stargenesis.helstera;

import dev.helstera.api.HelsteraApi;
import dev.stargenesis.integrations.InstalledPlugin;
import dev.stargenesis.integrations.ModelCatalog;
import dev.stargenesis.integrations.EntityProjection;
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
    private HelsteraProjectionController projections;

    @Override public void onEnable() {
        if (!supportedServer()) {
            getLogger().severe("Model bridge requires Paper 1.21.11; Folia is unsupported.");
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
        java.util.Objects.requireNonNull(getCommand("genesisprojection")).setExecutor((sender, command, label, args) -> {
            sender.sendMessage("模型预览不可用：需要经过验证的 Helstera 清理能力。"); return true;
        });
        try {
            if (HelsteraApi.isAvailable() && HelsteraApi.get().getInstanceManager().supportsOwnedVisualCleanup()) {
                var projection = new HelsteraProjection(Bukkit::isPrimaryThread, catalog,
                        () -> HelsteraApi.isAvailable() ? Optional.of(HelsteraApi.get()) : Optional.empty(), Bukkit::getWorld);
                projections = new HelsteraProjectionController(this, projection);
                getServer().getServicesManager().register(EntityProjection.class, projection, this, ServicePriority.Normal);
                getLogger().info("Registered bounded passive model projection; behavior registration remains inactive.");
            } else getLogger().info("Model catalog registered; projection requires the owned-visual cleanup patch.");
        } catch (RuntimeException | LinkageError unavailable) {
            getLogger().warning("Model catalog registered; projection unavailable: " + unavailable);
        }
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
        try { if (projections != null) projections.close(); }
        finally { if (catalog != null) { catalog.close(); catalog = null; } }
    }
}
