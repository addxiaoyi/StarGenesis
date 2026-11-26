package dev.stargenesis.cookery;

import dev.stargenesis.integrations.InstalledPlugin;
import dev.stargenesis.integrations.RecipeCatalog;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class CookeryBridgePlugin extends JavaPlugin implements Listener {
    private CookeryBridge bridge;
    private boolean supportedServer() {
        return "Paper".equals(getServer().getName()) && "1.21.11".equals(getServer().getMinecraftVersion());
    }
    private InstalledPlugin installed(String name) {
        var plugin = getServer().getPluginManager().getPlugin(name);
        return plugin == null ? null : new InstalledPlugin(plugin.getName(), plugin.getPluginMeta().getVersion(), plugin.isEnabled());
    }
    @Override public void onEnable() {
        if (!supportedServer()) {
            getLogger().severe("Recipe bridge requires Paper 1.21.11; Folia is not verified.");
            getServer().getPluginManager().disablePlugin(this); return;
        }
        bridge = new CookeryBridge(Bukkit::isPrimaryThread, this::supportedServer,
                () -> installed("KaleidoscopeCookeryPlugin"), () -> installed("CraftEngine"), CookeryAccess::current);
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getServicesManager().register(RecipeCatalog.class, bridge, this, ServicePriority.Normal);
        getLogger().info("Recipe metadata bridge registered; requests wait for fully loaded resources.");
    }
    @EventHandler public void onProviderDisabled(PluginDisableEvent event) {
        String name = event.getPlugin().getName();
        if (name.equals("KaleidoscopeCookeryPlugin") || name.equals("CraftEngine") || name.equals("StarGenesis"))
            getServer().getPluginManager().disablePlugin(this);
    }
    @Override public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
        if (bridge != null) bridge.close();
    }
}
