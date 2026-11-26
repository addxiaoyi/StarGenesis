import com.google.gson.GsonBuilder;
import dev.stargenesis.integrations.ItemPayload;
import dev.stargenesis.paper.integration.ItemStackBridge;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Isolated fixture supplies items; production commands are read-only. */
public final class CraftEngineItemClientProbe extends JavaPlugin {
    private final Map<UUID, ItemStack[]> inventories = new HashMap<>();
    private final Map<String, String> hashes = new HashMap<>();
    private int checkedTicks;
    private boolean failed, ready;
    @Override public void onEnable() {
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            try {
                var bridge = Bukkit.getServicesManager().load(ItemStackBridge.class);
                if (bridge == null) return;
                if (!ready) {
                    try { if (bridge.inspect("stargenesis:item_probe").isEmpty()) return; }
                    catch (IllegalStateException loading) { return; }
                    ready = true; getLogger().info("CRAFTENGINE_ITEM_CLIENT_READY");
                }
                for (var player : Bukkit.getOnlinePlayers()) {
                    if (!inventories.containsKey(player.getUniqueId())) {
                        var item = bridge.create("stargenesis:item_probe", 3);
                        var meta = item.getItemMeta();
                        meta.getPersistentDataContainer().set(new NamespacedKey(this, "state"), PersistentDataType.STRING, "玩家状态保留");
                        item.setItemMeta(meta);
                        player.getInventory().clear();
                        player.getInventory().setHeldItemSlot(0);
                        player.getInventory().setItemInMainHand(item);
                        var contents = player.getInventory().getContents();
                        for (int i = 0; i < contents.length; i++) if (contents[i] != null) contents[i] = contents[i].clone();
                        inventories.put(player.getUniqueId(), contents);
                        hashes.put(player.getName(), bridge.capture(item).sha256());
                    }
                    var before = inventories.get(player.getUniqueId());
                    var after = player.getInventory().getContents();
                    if (before.length != after.length) throw new AssertionError("Inventory size changed");
                    for (int i = 0; i < before.length; i++) {
                        if (before[i] == null || before[i].isEmpty()) {
                            if (after[i] != null && !after[i].isEmpty()) throw new AssertionError("Unexpected item in slot " + i);
                        } else {
                            ItemPayload saved = bridge.capture(before[i]);
                            if (after[i] == null || !saved.sameStateAs(bridge.capture(after[i])))
                                throw new AssertionError("Item state changed in slot " + i);
                        }
                    }
                }
                if (Bukkit.getOnlinePlayers().size() == 2) checkedTicks++;
                write(false);
            } catch (Throwable error) {
                failed = true;
                getLogger().log(java.util.logging.Level.SEVERE, "CRAFTENGINE_ITEM_CLIENT_FAILED", error);
                Bukkit.getScheduler().cancelTasks(this);
            }
        }, 20, 2);
    }
    private void write(boolean stopped) throws Exception {
        Files.createDirectories(getDataFolder().toPath());
        var result = Map.of("passed", !failed && inventories.size() == 2 && checkedTicks >= 5,
                "players", hashes, "verifiedTicks", checkedTicks, "stopped", stopped,
                "scope", "Fixture supplies detached custom items; actual commands and complete inventories observed, no production delivery or rendered visuals");
        Files.writeString(getDataFolder().toPath().resolve("observed.json"), new GsonBuilder().setPrettyPrinting().create().toJson(result) + "\n");
    }
    @Override public void onDisable() {
        try { write(true); }
        catch (Exception error) { getLogger().log(java.util.logging.Level.SEVERE, "CRAFTENGINE_ITEM_CLIENT_FAILED", error); }
    }
}
