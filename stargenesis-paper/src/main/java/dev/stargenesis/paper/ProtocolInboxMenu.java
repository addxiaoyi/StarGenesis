package dev.stargenesis.paper;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class ProtocolInboxMenu implements Listener {
    private static final int SIZE = 54;
    private static final int MESSAGE_SLOTS = 45;
    private static final int PREVIOUS_SLOT = 45;
    private static final int CLEAR_SLOT = 47;
    private static final int PAGE_SLOT = 49;
    private static final int CLOSE_SLOT = 51;
    private static final int NEXT_SLOT = 53;

    private final ProtocolInboxStore store;

    ProtocolInboxMenu(ProtocolInboxStore store) {
        this.store = store;
    }

    void open(Player player) {
        if (!player.hasPermission("stargenesis.protocol")) {
            player.sendMessage(Component.text("你没有查看协议收件箱的权限。"));
            return;
        }
        try {
            InboxHolder holder = new InboxHolder(player.getUniqueId());
            Inventory inventory = Bukkit.createInventory(holder, SIZE, Component.text("协议收件箱"));
            holder.inventory = inventory;
            inventory.setContents(render(holder, store.load(player)));
            player.openInventory(inventory);
        } catch (IllegalArgumentException failure) {
            player.sendMessage(Component.text("收件箱存档无效。使用 /genesis protocol inbox clear 清空损坏存档。"));
        }
    }

    void clear(Player player) {
        store.clear(player);
        Inventory inventory = player.getOpenInventory().getTopInventory();
        if (inventory.getHolder() instanceof InboxHolder holder && holder.playerId.equals(player.getUniqueId())) {
            holder.page = 0;
            inventory.setContents(render(holder, store.load(player)));
        }
        player.sendMessage(Component.text("协议收件箱已清空。"));
    }

    boolean appendRelay(Player player, ProtocolInboxEntry entry, UUID messageId,
                        long expiresAtMillis, long nowMillis) {
        return store.appendRelay(player, entry, messageId, expiresAtMillis, nowMillis);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory inventory = event.getView().getTopInventory();
        if (!(inventory.getHolder() instanceof InboxHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !holder.playerId.equals(player.getUniqueId())) return;
        if (!player.hasPermission("stargenesis.protocol")) {
            player.closeInventory();
            return;
        }
        int slot = event.getRawSlot();
        try {
            if (slot == CLOSE_SLOT) {
                player.closeInventory();
            } else if (slot == PREVIOUS_SLOT) {
                holder.page = Math.max(0, holder.page - 1);
            } else if (slot == NEXT_SLOT) {
                holder.page++;
            } else if (slot == CLEAR_SLOT) {
                store.clear(player);
                holder.page = 0;
                player.sendMessage(Component.text("协议收件箱已清空。"));
            } else if (slot >= 0 && slot < MESSAGE_SLOTS && slot < holder.visibleEntries.size()) {
                ProtocolInboxEntry selected = holder.visibleEntries.get(slot);
                ProtocolInboxCheckpoint checkpoint = store.load(player).remove(selected);
                store.save(player, checkpoint);
            }
            inventory.setContents(render(holder, store.load(player)));
        } catch (IllegalArgumentException | IllegalStateException failure) {
            player.sendMessage(Component.text("收件箱操作未保存：" + failure.getMessage()));
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof InboxHolder) event.setCancelled(true);
    }

    private ItemStack[] render(InboxHolder holder, ProtocolInboxCheckpoint checkpoint) {
        ItemStack[] contents = new ItemStack[SIZE];
        int pageCount = Math.max(1, (checkpoint.entries().size() + MESSAGE_SLOTS - 1) / MESSAGE_SLOTS);
        holder.page = Math.min(holder.page, pageCount - 1);
        int first = holder.page * MESSAGE_SLOTS;
        int last = Math.min(checkpoint.entries().size(), first + MESSAGE_SLOTS);
        holder.visibleEntries = checkpoint.entries().subList(first, last);
        for (int slot = 0; slot < MESSAGE_SLOTS; slot++) {
            if (slot >= holder.visibleEntries.size()) {
                contents[slot] = item(Material.GRAY_STAINED_GLASS_PANE, "", List.of());
                continue;
            }
            ProtocolInboxEntry entry = holder.visibleEntries.get(slot);
            contents[slot] = item(Material.PAPER, "来自 " + entry.senderName(), List.of(
                    entry.message().protocolId(), "逻辑步 " + entry.message().sentAt(),
                    "载荷 " + entry.message().payload(), "点击删除"));
        }
        contents[PREVIOUS_SLOT] = item(Material.ARROW, "上一页", List.of());
        contents[CLEAR_SLOT] = item(Material.BARRIER, "清空收件箱", List.of("删除全部 " + checkpoint.entries().size() + " 条消息"));
        contents[PAGE_SLOT] = item(Material.COMPASS, "第 " + (holder.page + 1) + " / " + pageCount + " 页",
                List.of("消息 " + checkpoint.entries().size() + " / " + ProtocolInboxCheckpoint.MAX_MESSAGES));
        contents[CLOSE_SLOT] = item(Material.OAK_DOOR, "关闭", List.of());
        contents[NEXT_SLOT] = item(Material.ARROW, "下一页", List.of());
        return contents;
    }

    private static ItemStack item(Material material, String title, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(title));
        meta.lore(lore.stream().filter(line -> !line.isEmpty()).map(Component::text).toList());
        stack.setItemMeta(meta);
        return stack;
    }

    private static final class InboxHolder implements InventoryHolder {
        private final UUID playerId;
        private Inventory inventory;
        private int page;
        private List<ProtocolInboxEntry> visibleEntries = new ArrayList<>();

        private InboxHolder(UUID playerId) { this.playerId = playerId; }

        @Override public Inventory getInventory() { return inventory; }
    }
}
