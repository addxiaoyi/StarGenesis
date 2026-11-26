package dev.stargenesis.paper.integration;

import dev.stargenesis.integrations.ItemCatalog;
import dev.stargenesis.integrations.ItemPayload;
import org.bukkit.inventory.ItemStack;

/** Main-thread detached item operations. They never insert, consume, drop or authorize inventory contents. */
public interface ItemStackBridge extends ItemCatalog {
    ItemStack create(String itemId, int amount);
    ItemPayload capture(ItemStack stack);
    ItemStack restore(ItemPayload payload);
}
