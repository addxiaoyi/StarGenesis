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
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class FactoryOrganizationMenu implements Listener {
    private static final int SIZE = 54;
    private static final int FIRST_MEMBER_SLOT = 10;
    private static final int MEMBER_SLOTS = FactoryOrganization.MAX_MEMBERS;
    private static final int FIRST_ONLINE_SLOT = 28;
    private static final int ONLINE_SLOTS = 18;
    private static final int PREVIOUS_PAGE_SLOT = 47;
    private static final int CLOSE_SLOT = 49;
    private static final int NEXT_PAGE_SLOT = 51;

    private final JavaPlugin plugin;
    private final FactoryService service;

    FactoryOrganizationMenu(JavaPlugin plugin, FactoryService service) {
        this.plugin = plugin;
        this.service = service;
    }

    void open(Player player) {
        if (!player.hasPermission("stargenesis.factory")) {
            player.sendMessage(Component.text("你没有管理虚拟工厂的权限。"));
            return;
        }
        service.inspectOrganization(view -> onPlayer(player, () -> open(player, view, 0)));
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory inventory = event.getView().getTopInventory();
        if (!(inventory.getHolder() instanceof OrganizationHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !holder.playerId.equals(player.getUniqueId())
                || holder.busy) return;
        if (!player.hasPermission("stargenesis.factory")) {
            player.closeInventory();
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) return;
        if (slot == CLOSE_SLOT) {
            player.closeInventory();
        } else if (slot == PREVIOUS_PAGE_SLOT) {
            if (holder.page > 0) open(player, holder.view, holder.page - 1);
        } else if (slot == NEXT_PAGE_SLOT) {
            if (holder.page + 1 < pageCount(candidates(holder).size())) open(player, holder.view, holder.page + 1);
        } else if (slot >= FIRST_MEMBER_SLOT && slot < FIRST_MEMBER_SLOT + MEMBER_SLOTS) {
            removeMember(player, holder, slot - FIRST_MEMBER_SLOT);
        } else if (slot >= FIRST_ONLINE_SLOT && slot < FIRST_ONLINE_SLOT + ONLINE_SLOTS) {
            addMember(player, holder, slot - FIRST_ONLINE_SLOT);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof OrganizationHolder) event.setCancelled(true);
    }

    private void removeMember(Player player, OrganizationHolder holder, int memberIndex) {
        FactoryOrganization organization = holder.view.organization();
        if (organization == null || memberIndex >= organization.members().size()) return;
        UUID member = organization.members().get(memberIndex);
        if (organization.owner().equals(member)) {
            player.sendMessage(Component.text("组织所有者不能从名册中移除。"));
            return;
        }
        if (!canEdit(player, holder)) return;
        mutate(player, holder, "remove", member);
    }

    private void addMember(Player player, OrganizationHolder holder, int candidateIndex) {
        if (!canEdit(player, holder)) return;
        List<FactoryService.OnlineMember> candidates = candidates(holder);
        int index = holder.page * ONLINE_SLOTS + candidateIndex;
        if (index >= candidates.size()) return;
        mutate(player, holder, "add", candidates.get(index).id());
    }

    private boolean canEdit(Player player, OrganizationHolder holder) {
        FactoryOrganization organization = holder.view.organization();
        if (organization == null) {
            player.sendMessage(Component.text("请先使用 /genesis factory org create <name> 创建组织名册。"));
            return false;
        }
        if (!organization.owner().equals(player.getUniqueId())) {
            player.sendMessage(Component.text("只有组织所有者可以修改成员。"));
            return false;
        }
        if (holder.view.factoryStarted()) {
            player.sendMessage(Component.text("工厂已创建，组织名册已冻结。"));
            return false;
        }
        return true;
    }

    private void mutate(Player player, OrganizationHolder holder, String operation, UUID member) {
        holder.busy = true;
        service.execute(player.getUniqueId(), new String[] {"org", operation, member.toString()}, message ->
                onPlayer(player, () -> {
                    player.sendMessage(Component.text(message));
                    if (player.getOpenInventory().getTopInventory().getHolder() == holder) open(player);
                }));
    }

    private void open(Player player, FactoryService.OrganizationView view, int page) {
        FactoryOrganization organization = view.organization();
        String title = organization == null ? "工厂组织名册" : "工厂组织 · " + organization.name();
        OrganizationHolder holder = new OrganizationHolder(player.getUniqueId(), view, page);
        Inventory inventory = Bukkit.createInventory(holder, SIZE, Component.text(title));
        holder.inventory = inventory;
        inventory.setContents(render(holder));
        player.openInventory(inventory);
    }

    private ItemStack[] render(OrganizationHolder holder) {
        ItemStack[] contents = new ItemStack[SIZE];
        FactoryOrganization organization = holder.view.organization();
        boolean canEdit = organization != null && organization.owner().equals(holder.playerId)
                && !holder.view.factoryStarted();
        String rosterState = holder.view.factoryStarted() ? "名册已冻结" : "开厂前名册";
        if (organization == null) {
            contents[4] = item(Material.BOOK, "未创建组织", List.of(
                    "使用 /genesis factory org create <name> 创建名册"));
        } else {
            contents[4] = item(holder.view.factoryStarted() ? Material.IRON_DOOR : Material.WRITABLE_BOOK,
                    rosterState + " · " + organization.members().size() + "/" + FactoryOrganization.MAX_MEMBERS,
                    List.of("所有者 " + playerName(organization.owner(), holder.view.onlineMembers()),
                            "成员变更仅在开厂前可用"));
            for (int index = 0; index < organization.members().size(); index++) {
                UUID member = organization.members().get(index);
                boolean owner = organization.owner().equals(member);
                String name = playerName(member, holder.view.onlineMembers());
                List<String> lore = new ArrayList<>();
                lore.add(member.toString());
                lore.add(owner ? "组织所有者" : canEdit ? "点击移除成员" : "成员");
                contents[FIRST_MEMBER_SLOT + index] = item(owner ? Material.NETHER_STAR : Material.PAPER,
                        name, lore);
            }
        }

        List<FactoryService.OnlineMember> candidates = candidates(holder);
        int firstCandidate = holder.page * ONLINE_SLOTS;
        for (int index = 0; index < ONLINE_SLOTS && firstCandidate + index < candidates.size(); index++) {
            FactoryService.OnlineMember candidate = candidates.get(firstCandidate + index);
            contents[FIRST_ONLINE_SLOT + index] = item(Material.LIME_DYE, candidate.name(), List.of(
                    candidate.id().toString(), canEdit ? "点击加入名册" : "仅组织所有者可邀请成员"));
        }
        contents[PREVIOUS_PAGE_SLOT] = item(Material.ARROW, "上一页",
                List.of("在线玩家 " + (candidates.isEmpty() ? 0 : firstCandidate + 1) + "/" + candidates.size()));
        contents[CLOSE_SLOT] = item(Material.OAK_DOOR, "关闭", List.of());
        contents[NEXT_PAGE_SLOT] = item(Material.ARROW, "下一页",
                List.of("第 " + (holder.page + 1) + "/" + pageCount(candidates.size()) + " 页"));
        return contents;
    }

    private static List<FactoryService.OnlineMember> candidates(OrganizationHolder holder) {
        FactoryOrganization organization = holder.view.organization();
        return holder.view.onlineMembers().stream()
                .filter(member -> organization == null || !organization.members().contains(member.id()))
                .toList();
    }

    private static String playerName(UUID id, List<FactoryService.OnlineMember> onlineMembers) {
        return onlineMembers.stream().filter(member -> member.id().equals(id)).map(FactoryService.OnlineMember::name)
                .findFirst().orElse(id.toString());
    }

    private static int pageCount(int candidates) {
        return Math.max(1, (candidates + ONLINE_SLOTS - 1) / ONLINE_SLOTS);
    }

    private void onPlayer(Player player, Runnable action) {
        player.getScheduler().run(plugin, task -> action.run(), () -> { });
    }

    private static ItemStack item(Material material, String title, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(title));
        meta.lore(lore.stream().map(Component::text).toList());
        stack.setItemMeta(meta);
        return stack;
    }

    private static final class OrganizationHolder implements InventoryHolder {
        private final UUID playerId;
        private final FactoryService.OrganizationView view;
        private final int page;
        private Inventory inventory;
        private boolean busy;

        private OrganizationHolder(UUID playerId, FactoryService.OrganizationView view, int page) {
            this.playerId = playerId;
            this.view = view;
            this.page = page;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
