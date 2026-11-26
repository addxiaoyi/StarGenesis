package dev.stargenesis.craftengine;

import dev.stargenesis.integrations.InstalledPlugin;
import dev.stargenesis.integrations.ItemCatalog;
import dev.stargenesis.paper.integration.ItemStackBridge;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public final class CraftEngineBridgePlugin extends JavaPlugin implements Listener {
    private CraftEngineBridge bridge;
    private boolean supportedServer() {
        return "Paper".equals(getServer().getName()) && "1.21.11".equals(getServer().getMinecraftVersion());
    }
    @Override public void onEnable() {
        if (!supportedServer()) {
            getLogger().severe("Item bridge requires Paper 1.21.11; Folia is not verified.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        bridge = new CraftEngineBridge(Bukkit::isPrimaryThread, this::supportedServer, () -> {
            var plugin = getServer().getPluginManager().getPlugin("CraftEngine");
            return plugin == null ? null : new InstalledPlugin(plugin.getName(), plugin.getPluginMeta().getVersion(), plugin.isEnabled());
        }, CraftEngineAccess::current, () -> Bukkit.getUnsafe().getDataVersion());
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getServicesManager().register(ItemCatalog.class, bridge, this, ServicePriority.Normal);
        getServer().getServicesManager().register(ItemStackBridge.class, bridge, this, ServicePriority.Normal);
        getLogger().info("Item bridge registered; requests wait for fully loaded resources and use detached stacks.");
    }
    @EventHandler public void onProviderDisabled(PluginDisableEvent event) {
        String name = event.getPlugin().getName();
        if (name.equals("CraftEngine") || name.equals("StarGenesis")) getServer().getPluginManager().disablePlugin(this);
    }
    @Override public void onDisable() {
        getServer().getServicesManager().unregisterAll(this);
        if (bridge != null) bridge.close();
    }
}
