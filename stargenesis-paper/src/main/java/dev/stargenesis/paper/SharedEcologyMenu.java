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

import java.util.List;
import java.util.UUID;

final class SharedEcologyMenu implements Listener {
    private static final int SIZE = 54;
    private static final int FIRST_GROUP_SLOT = 10;
    private static final int GROUP_SLOTS = 34;
    private static final int FIRST_MEMBER_SLOT = 10;
    private static final int MEMBER_SLOTS = EcologyGroupRecord.MAX_MEMBERS;
    private static final int FIRST_INVITE_SLOT = 28;
    private static final int INVITE_SLOTS = 16;
    private static final int PREVIOUS_PAGE_SLOT = 45;
    private static final int LEAVE_SLOT = 46;
    private static final int DISBAND_SLOT = 47;
    private static final int BACK_SLOT = 49;
    private static final int CLOSE_SLOT = 50;
    private static final int NEXT_PAGE_SLOT = 53;

    private final JavaPlugin plugin;
    private final SharedEcologyService service;

    SharedEcologyMenu(JavaPlugin plugin, SharedEcologyService service) {
        this.plugin = plugin;
        this.service = service;
    }

    void open(Player player) {
        if (!player.hasPermission("stargenesis.ecosystem")) {
            player.sendMessage(Component.text("你没有使用共享生态的权限。"));
            return;
        }
        refresh(player, null, 0, null);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder() instanceof SharedHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !holder.playerId.equals(player.getUniqueId())
                || holder.busy) return;
        if (!player.hasPermission("stargenesis.ecosystem")) {
            player.closeInventory();
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) return;
        switch (holder.screen) {
            case GROUPS -> clickGroups(player, holder, slot);
            case GROUP -> clickGroup(player, holder, slot);
            case CONFIRM_DISBAND -> clickDisbandConfirmation(player, holder, slot);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof SharedHolder) event.setCancelled(true);
    }

    private void clickGroups(Player player, SharedHolder holder, int slot) {
        if (slot == CLOSE_SLOT) {
            player.closeInventory();
        } else if (slot == PREVIOUS_PAGE_SLOT && holder.page > 0) {
            openGroups(player, holder.view, holder.page - 1);
        } else if (slot == NEXT_PAGE_SLOT && holder.page + 1 < pageCount(holder.view.groups().size(), GROUP_SLOTS)) {
            openGroups(player, holder.view, holder.page + 1);
        } else if (slot == BACK_SLOT) {
            mutate(player, holder, new String[] {"create"}, null);
        } else if (slot >= FIRST_GROUP_SLOT && slot < FIRST_GROUP_SLOT + GROUP_SLOTS) {
            int index = holder.page * GROUP_SLOTS + slot - FIRST_GROUP_SLOT;
            if (index < holder.view.groups().size()) {
                EcologyGroupRecord group = holder.view.groups().get(index);
                refresh(player, group.id(), holder.page, holder);
            }
        }
    }

    private void clickGroup(Player player, SharedHolder holder, int slot) {
        EcologyGroupRecord group = group(holder);
        if (group == null) {
            refresh(player, null, holder.groupListPage, holder);
            return;
        }
        if (slot == CLOSE_SLOT) {
            player.closeInventory();
        } else if (slot == BACK_SLOT) {
            refresh(player, null, holder.groupListPage, holder);
        } else if (slot == PREVIOUS_PAGE_SLOT && holder.page > 0) {
            openGroup(player, holder.view, group, holder.groupListPage, holder.page - 1);
        } else if (slot == NEXT_PAGE_SLOT && holder.page + 1 < pageCount(candidates(holder).size(), INVITE_SLOTS)) {
            openGroup(player, holder.view, group, holder.groupListPage, holder.page + 1);
        } else if (slot == 22) {
            player.sendMessage(Component.text(status(group)));
        } else if (slot == 24) {
            mutate(player, holder, new String[] {"step", group.id().toString()}, group.id());
        } else if (slot == LEAVE_SLOT) {
            if (group.ownerId().equals(player.getUniqueId())) {
                player.sendMessage(Component.text("共享生态组所有者不能离组。"));
            } else {
                mutate(player, holder, new String[] {"leave", group.id().toString()}, group.id());
            }
        } else if (slot == DISBAND_SLOT) {
            if (group.ownerId().equals(player.getUniqueId())) {
                openDisbandConfirmation(player, holder.view, group, holder.groupListPage);
            } else {
                player.sendMessage(Component.text("只有共享生态组所有者可以解散。"));
            }
        } else if (slot >= FIRST_MEMBER_SLOT && slot < FIRST_MEMBER_SLOT + MEMBER_SLOTS) {
            removeMember(player, holder, group, slot - FIRST_MEMBER_SLOT);
        } else if (slot >= FIRST_INVITE_SLOT && slot < FIRST_INVITE_SLOT + INVITE_SLOTS) {
            inviteMember(player, holder, group, slot - FIRST_INVITE_SLOT);
        }
    }

    private void clickDisbandConfirmation(Player player, SharedHolder holder, int slot) {
        if (slot == CLOSE_SLOT) {
            player.closeInventory();
        } else if (slot == BACK_SLOT) {
            refresh(player, holder.groupId, holder.groupListPage, holder);
        } else if (slot == 22) {
            mutate(player, holder, new String[] {"disband", holder.groupId.toString()}, holder.groupId);
        }
    }

    private void removeMember(Player player, SharedHolder holder, EcologyGroupRecord group, int memberIndex) {
        if (!group.ownerId().equals(player.getUniqueId())) return;
        if (memberIndex >= group.members().size()) return;
        UUID member = group.members().get(memberIndex);
        if (group.ownerId().equals(member)) return;
        mutate(player, holder, new String[] {"remove", group.id().toString(), member.toString()}, group.id());
    }

    private void inviteMember(Player player, SharedHolder holder, EcologyGroupRecord group, int candidateIndex) {
        if (!group.ownerId().equals(player.getUniqueId())
                || group.members().size() >= EcologyGroupRecord.MAX_MEMBERS) return;
        List<SharedEcologyService.OnlineMember> candidates = candidates(holder);
        int index = holder.page * INVITE_SLOTS + candidateIndex;
        if (index >= candidates.size()) return;
        mutate(player, holder, new String[] {"add", group.id().toString(), candidates.get(index).id().toString()}, group.id());
    }

    private void mutate(Player player, SharedHolder holder, String[] arguments, UUID selectedGroupId) {
        holder.busy = true;
        service.execute(player.getUniqueId(), arguments, message -> onPlayer(player, () -> {
            player.sendMessage(Component.text(message));
            refresh(player, selectedGroupId, holder.groupListPage, holder);
        }));
    }

    private void refresh(Player player, UUID selectedGroupId, int groupListPage, SharedHolder expected) {
        service.inspect(player.getUniqueId(), view -> onPlayer(player, () -> {
            if (expected != null && !isOpen(player, expected)) return;
            if (!player.hasPermission("stargenesis.ecosystem")) {
                player.closeInventory();
                return;
            }
            if (selectedGroupId == null) {
                openGroups(player, view, groupListPage);
                return;
            }
            EcologyGroupRecord selected = view.groups().stream()
                    .filter(group -> group.id().equals(selectedGroupId)).findFirst().orElse(null);
            if (selected == null) openGroups(player, view, groupListPage);
            else openGroup(player, view, selected, groupListPage, 0);
        }));
    }

    private void openGroups(Player player, SharedEcologyService.View view, int page) {
        int boundedPage = Math.min(page, pageCount(view.groups().size(), GROUP_SLOTS) - 1);
        SharedHolder holder = new SharedHolder(player.getUniqueId(), view, Screen.GROUPS, null, boundedPage, boundedPage);
        openInventory(player, holder, "共享生态组");
    }

    private void openGroup(Player player, SharedEcologyService.View view, EcologyGroupRecord group,
            int groupListPage, int page) {
        int candidateCount = candidates(view, group).size();
        int boundedPage = Math.min(page, pageCount(candidateCount, INVITE_SLOTS) - 1);
        SharedHolder holder = new SharedHolder(player.getUniqueId(), view, Screen.GROUP, group.id(),
                boundedPage, groupListPage);
        openInventory(player, holder, "共享生态详情");
    }

    private void openDisbandConfirmation(Player player, SharedEcologyService.View view,
            EcologyGroupRecord group, int groupListPage) {
        SharedHolder holder = new SharedHolder(player.getUniqueId(), view, Screen.CONFIRM_DISBAND,
                group.id(), 0, groupListPage);
        openInventory(player, holder, "确认解散共享生态");
    }

    private void openInventory(Player player, SharedHolder holder, String title) {
        Inventory inventory = Bukkit.createInventory(holder, SIZE, Component.text(title));
        holder.inventory = inventory;
        inventory.setContents(render(holder));
        player.openInventory(inventory);
    }

    private ItemStack[] render(SharedHolder holder) {
        return switch (holder.screen) {
            case GROUPS -> renderGroups(holder);
            case GROUP -> renderGroup(holder);
            case CONFIRM_DISBAND -> renderDisbandConfirmation(holder);
        };
    }

    private ItemStack[] renderGroups(SharedHolder holder) {
        ItemStack[] contents = new ItemStack[SIZE];
        List<EcologyGroupRecord> groups = holder.view.groups();
        int first = holder.page * GROUP_SLOTS;
        for (int index = 0; index < GROUP_SLOTS && first + index < groups.size(); index++) {
            EcologyGroupRecord group = groups.get(first + index);
            contents[FIRST_GROUP_SLOT + index] = item(Material.SLIME_BALL,
                    "共享生态 " + shortId(group.id()), List.of(group.id().toString(),
                            "成员 " + group.members().size() + "/" + EcologyGroupRecord.MAX_MEMBERS,
                            "步数 " + group.checkpoint().snapshot().tick(), "点击查看"));
        }
        if (groups.isEmpty()) {
            contents[22] = item(Material.BOOK, "还没有共享生态组", List.of("点击创建一个新组"));
        }
        contents[PREVIOUS_PAGE_SLOT] = item(Material.ARROW, "上一页",
                List.of("第 " + (holder.page + 1) + "/" + pageCount(groups.size(), GROUP_SLOTS) + " 页"));
        contents[BACK_SLOT] = item(Material.WRITABLE_BOOK, "创建共享生态组", List.of("你的组将由你担任所有者"));
        contents[CLOSE_SLOT] = item(Material.OAK_DOOR, "关闭", List.of());
        contents[NEXT_PAGE_SLOT] = item(Material.ARROW, "下一页",
                List.of("已加入 " + groups.size() + " 个组"));
        return contents;
    }

    private ItemStack[] renderGroup(SharedHolder holder) {
        ItemStack[] contents = new ItemStack[SIZE];
        EcologyGroupRecord group = group(holder);
        if (group == null) return contents;
        boolean owner = group.ownerId().equals(holder.playerId);
        var snapshot = group.checkpoint().snapshot();
        contents[4] = item(Material.COMPASS, "共享生态 " + shortId(group.id()), List.of(
                group.id().toString(), "所有者 " + playerName(group.ownerId(), holder.view.onlineMembers()),
                "成员 " + group.members().size() + "/" + EcologyGroupRecord.MAX_MEMBERS,
                "步数 " + snapshot.tick() + " · 种群 " + snapshot.statistics().population()));
        for (int index = 0; index < group.members().size(); index++) {
            UUID member = group.members().get(index);
            boolean memberOwner = member.equals(group.ownerId());
            contents[FIRST_MEMBER_SLOT + index] = item(memberOwner ? Material.NETHER_STAR : Material.PAPER,
                    playerName(member, holder.view.onlineMembers()), List.of(member.toString(),
                            memberOwner ? "组所有者" : owner ? "点击移除此成员" : "组成员"));
        }
        contents[22] = item(Material.BOOK, "查看共享状态", List.of("状态哈希 " + snapshot.stateHash()));
        contents[24] = item(Material.CLOCK, "共同步进", List.of("推进所有成员共享的生态状态"));
        List<SharedEcologyService.OnlineMember> candidates = candidates(holder);
        int firstCandidate = holder.page * INVITE_SLOTS;
        for (int index = 0; index < INVITE_SLOTS && firstCandidate + index < candidates.size(); index++) {
            SharedEcologyService.OnlineMember candidate = candidates.get(firstCandidate + index);
            boolean canInvite = owner && group.members().size() < EcologyGroupRecord.MAX_MEMBERS;
            contents[FIRST_INVITE_SLOT + index] = item(canInvite ? Material.LIME_DYE : Material.GRAY_DYE,
                    candidate.name(), List.of(canInvite ? "点击邀请加入" : "只有所有者可邀请，且组需有空位"));
        }
        contents[PREVIOUS_PAGE_SLOT] = item(Material.ARROW, "上一页",
                List.of("在线玩家 " + (candidates.isEmpty() ? 0 : firstCandidate + 1) + "/" + candidates.size()));
        contents[LEAVE_SLOT] = item(Material.OAK_DOOR, "离开共享组",
                List.of(owner ? "所有者不能离组" : "离开后仍可由所有者重新邀请"));
        contents[DISBAND_SLOT] = item(owner ? Material.BARRIER : Material.GRAY_DYE,
                owner ? "解散共享组" : "仅所有者可解散", List.of(owner ? "下一屏将要求确认" : ""));
        contents[BACK_SLOT] = item(Material.ARROW, "返回共享组列表", List.of());
        contents[CLOSE_SLOT] = item(Material.OAK_DOOR, "关闭", List.of());
        contents[NEXT_PAGE_SLOT] = item(Material.ARROW, "下一页",
                List.of("在线玩家页 " + (holder.page + 1) + "/" + pageCount(candidates.size(), INVITE_SLOTS)));
        return contents;
    }

    private ItemStack[] renderDisbandConfirmation(SharedHolder holder) {
        ItemStack[] contents = new ItemStack[SIZE];
        EcologyGroupRecord group = group(holder);
        if (group != null) {
            contents[4] = item(Material.BARRIER, "解散 " + shortId(group.id()), List.of(
                    group.id().toString(), "成员 " + group.members().size(), "此操作会删除共享检查点"));
        }
        contents[22] = item(Material.REDSTONE_BLOCK, "确认解散", List.of("删除组记录与检查点"));
        contents[BACK_SLOT] = item(Material.ARROW, "返回", List.of("保留共享生态组"));
        contents[CLOSE_SLOT] = item(Material.OAK_DOOR, "关闭", List.of());
        return contents;
    }

    private List<SharedEcologyService.OnlineMember> candidates(SharedHolder holder) {
        EcologyGroupRecord group = group(holder);
        return group == null ? List.of() : candidates(holder.view, group);
    }

    private static List<SharedEcologyService.OnlineMember> candidates(
            SharedEcologyService.View view, EcologyGroupRecord group) {
        return view.onlineMembers().stream().filter(member -> !group.members().contains(member.id())).toList();
    }

    private static String playerName(UUID playerId, List<SharedEcologyService.OnlineMember> onlineMembers) {
        return onlineMembers.stream().filter(member -> member.id().equals(playerId))
                .map(SharedEcologyService.OnlineMember::name).findFirst().orElse(playerId.toString());
    }

    private static String status(EcologyGroupRecord group) {
        var snapshot = group.checkpoint().snapshot();
        return "共享生态 " + group.id() + " | 步数=" + snapshot.tick()
                + " | 种群=" + snapshot.statistics().population()
                + " | 成员=" + group.members().size() + "/" + EcologyGroupRecord.MAX_MEMBERS
                + " | 哈希=" + snapshot.stateHash();
    }

    private static EcologyGroupRecord group(SharedHolder holder) {
        return holder.view.groups().stream().filter(group -> group.id().equals(holder.groupId))
                .findFirst().orElse(null);
    }

    private boolean isOpen(Player player, SharedHolder holder) {
        return player.getOpenInventory().getTopInventory().getHolder() == holder;
    }

    private void onPlayer(Player player, Runnable action) {
        player.getScheduler().run(plugin, task -> action.run(), () -> { });
    }

    private static int pageCount(int size, int pageSize) {
        return Math.max(1, (size + pageSize - 1) / pageSize);
    }

    private static String shortId(UUID id) {
        return id.toString().substring(0, 8);
    }

    private static ItemStack item(Material material, String title, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(title));
        meta.lore(lore.stream().filter(line -> !line.isEmpty()).map(Component::text).toList());
        stack.setItemMeta(meta);
        return stack;
    }

    private enum Screen { GROUPS, GROUP, CONFIRM_DISBAND }

    private static final class SharedHolder implements InventoryHolder {
        private final UUID playerId;
        private final SharedEcologyService.View view;
        private final Screen screen;
        private final UUID groupId;
        private final int page;
        private final int groupListPage;
        private Inventory inventory;
        private boolean busy;

        private SharedHolder(UUID playerId, SharedEcologyService.View view, Screen screen,
                UUID groupId, int page, int groupListPage) {
            this.playerId = playerId;
            this.view = view;
            this.screen = screen;
            this.groupId = groupId;
            this.page = page;
            this.groupListPage = groupListPage;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
