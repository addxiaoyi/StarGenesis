package dev.stargenesis.craftengine;

import dev.stargenesis.integrations.ItemCatalog;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.core.plugin.ResourceOperationCoordinator;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.inventory.ItemStack;

import java.util.Optional;

/** Typed calls against the published API; test injection never replaces dependency classes. */
interface CraftEngineAccess {
    Object identity();
    boolean ready();
    ResourceOperationCoordinator resources();
    int count();
    Optional<ItemCatalog.Definition> inspect(String id);
    ItemStack create(String id);
    String customId(ItemStack stack);

    static CraftEngineAccess current() {
        BukkitCraftEngine root = BukkitCraftEngine.instance();
        if (root == null) throw new IllegalStateException("CraftEngine API is unavailable");
        return new CraftEngineAccess() {
            public Object identity() { return root; }
            public boolean ready() {
                return BukkitCraftEngine.instance() == root && root.isFullyLoaded() && !root.isEnabling()
                        && !root.isReloading() && !root.isStopping() && !root.isDisabled();
            }
            public ResourceOperationCoordinator resources() { return root.resourceOperations(); }
            public int count() { return CraftEngineItems.loadedItems().size(); }
            public Optional<ItemCatalog.Definition> inspect(String id) {
                var item = CraftEngineItems.byId(Key.of(id));
                if (item == null) return Optional.empty();
                if (item.settings().tags().size() > 128) throw new IllegalStateException("Item tag budget exceeded");
                return Optional.of(new ItemCatalog.Definition(item.id().toString(), item.material().toString(),
                        item.isVanillaItem(), item.settings().tags().stream().map(Key::toString).toList()));
            }
            public ItemStack create(String id) {
                var item = CraftEngineItems.byId(Key.of(id));
                if (item == null) throw new IllegalArgumentException("Item is no longer loaded");
                return item.buildBukkitItem();
            }
            public String customId(ItemStack stack) {
                var key = CraftEngineItems.getCustomItemId(stack);
                return key == null ? null : key.toString();
            }
        };
    }
}
