package dev.stargenesis.paper;

import dev.stargenesis.design.Blueprint;
import dev.stargenesis.design.BlueprintAssemblySession;
import dev.stargenesis.design.BlueprintBudget;
import dev.stargenesis.design.DesignCodec;
import dev.stargenesis.design.ModuleCatalog;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class BlueprintAssemblyMenu implements Listener {
    private static final int SIZE = 54;
    private static final int WIDTH = 9;
    private static final int HEIGHT = 5;
    private static final int CELLS = WIDTH * HEIGHT;
    private static final int LAYER_DOWN = 45;
    private static final int LAYER_UP = 46;
    private static final int STATUS = 49;
    private static final int RESTART = 51;
    private static final int CLOSE = 53;

    private final JavaPlugin plugin;
    private final BlueprintEditorMenu editor;
    private final DesignCodec codec = new DesignCodec();
    private final ModuleCatalog catalog = ModuleCatalog.standard();
    private final BlueprintBudget budget = BlueprintBudget.standard();
    private final NamespacedKey checkpointKey;

    BlueprintAssemblyMenu(JavaPlugin plugin, BlueprintEditorMenu editor) {
        this.plugin = plugin;
        this.editor = editor;
        checkpointKey = new NamespacedKey(plugin, "blueprint-assembly-checkpoint-v1");
    }

    void open(Player player, boolean reset) {
        if (!player.hasPermission("stargenesis.blueprint")) {
            player.sendMessage(Component.text("你没有装配蓝图的权限。"));
            return;
        }
        try {
            BlueprintAssemblySession session = load(player, reset);
            if (session == null) return;
            AssemblyHolder holder = new AssemblyHolder(player.getUniqueId(), session);
            centerOnNext(holder);
            Inventory inventory = Bukkit.createInventory(holder, SIZE, Component.text("蓝图虚拟装配台"));
            holder.inventory = inventory;
            inventory.setContents(render(holder));
            player.openInventory(inventory);
        } catch (IllegalArgumentException | SecurityException failure) {
            player.sendMessage(Component.text("无法打开蓝图装配：" + failure.getMessage()));
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory inventory = event.getView().getTopInventory();
        if (!(inventory.getHolder() instanceof AssemblyHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !holder.owner.equals(player.getUniqueId())) return;
        if (!player.hasPermission("stargenesis.blueprint")) {
            player.closeInventory();
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) return;
        try {
            if (slot < CELLS) placeAt(player, holder, slot);
            else activate(player, holder, slot);
            persist(player, holder.session);
            inventory.setContents(render(holder));
        } catch (IllegalArgumentException | IllegalStateException | SecurityException failure) {
            player.sendMessage(Component.text("装配步骤未完成：" + failure.getMessage()));
            inventory.setContents(render(holder));
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof AssemblyHolder) event.setCancelled(true);
    }

    private BlueprintAssemblySession load(Player player, boolean reset) {
        var data = player.getPersistentDataContainer();
        byte[] stored = data.get(checkpointKey, PersistentDataType.BYTE_ARRAY);
        if (!reset && stored != null) {
            BlueprintAssemblySession resumed = BlueprintAssemblySession.resume(player.getUniqueId(), stored, codec, catalog, budget);
            Blueprint saved = editor.loadSaved(player);
            if (saved == null || !saved.equals(resumed.blueprint()))
                throw new IllegalArgumentException("保存的蓝图已变化，请重启装配");
            return resumed;
        }
        Blueprint saved = editor.loadSaved(player);
        if (saved == null) {
            player.sendMessage(Component.text("请先在 /genesis blueprint 中验证并保存蓝图。"));
            return null;
        }
        BlueprintAssemblySession created = BlueprintAssemblySession.start(player.getUniqueId(), saved, catalog, budget);
        persist(player, created);
        return created;
    }

    private void placeAt(Player player, AssemblyHolder holder, int slot) {
        Blueprint.Component target = holder.session.nextComponent();
        if (target == null) {
            player.sendMessage(Component.text("蓝图装配已经完成。"));
            return;
        }
        int x = holder.centerX + slot % WIDTH - WIDTH / 2;
        int y = holder.centerY + slot / WIDTH - HEIGHT / 2;
        if (target.x() != x || target.y() != y || target.z() != holder.layer) {
            player.sendMessage(Component.text("下一组件位于 " + target.x() + ", " + target.y() + ", " + target.z()));
            return;
        }
        holder.session.placeNextAt(x, y, holder.layer);
        centerOnNext(holder);
        if (holder.session.complete()) player.sendMessage(Component.text("虚拟装配完成，共 " + holder.session.totalComponents() + " 个模块。"));
        else player.sendMessage(Component.text("已装配 " + holder.session.placedCount() + "/" + holder.session.totalComponents() + "。"));
    }

    private void activate(Player player, AssemblyHolder holder, int slot) {
        if (slot == LAYER_DOWN) holder.layer = Math.max(-16, holder.layer - 1);
        else if (slot == LAYER_UP) holder.layer = Math.min(16, holder.layer + 1);
        else if (slot == RESTART) {
            Blueprint saved = editor.loadSaved(player);
            if (saved == null) throw new IllegalStateException("No saved blueprint is available");
            holder.session = BlueprintAssemblySession.start(player.getUniqueId(), saved, catalog, budget);
            centerOnNext(holder);
            player.sendMessage(Component.text("已从蓝图核心重新开始虚拟装配。"));
        } else if (slot == CLOSE) player.closeInventory();
    }

    private static void centerOnNext(AssemblyHolder holder) {
        Blueprint.Component next = holder.session.nextComponent();
        if (next != null) {
            holder.centerX = next.x();
            holder.centerY = next.y();
            holder.layer = next.z();
        }
    }

    private void persist(Player player, BlueprintAssemblySession session) {
        player.getPersistentDataContainer().set(checkpointKey, PersistentDataType.BYTE_ARRAY, session.encodeCheckpoint(codec));
    }

    private ItemStack[] render(AssemblyHolder holder) {
        ItemStack[] contents = new ItemStack[SIZE];
        Blueprint blueprint = holder.session.blueprint();
        Set<String> placed = new HashSet<>();
        holder.session.placedComponents().forEach(component -> placed.add(component.id()));
        Blueprint.Component next = holder.session.nextComponent();
        for (int row = 0; row < HEIGHT; row++) {
            for (int column = 0; column < WIDTH; column++) {
                int x = holder.centerX + column - WIDTH / 2;
                int y = holder.centerY + row - HEIGHT / 2;
                Blueprint.Component component = blueprint.components().stream()
                        .filter(value -> value.x() == x && value.y() == y && value.z() == holder.layer)
                        .findFirst().orElse(null);
                int slot = row * WIDTH + column;
                if (component == null) {
                    contents[slot] = item(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "空格",
                            List.of("坐标 " + x + ", " + y + ", " + holder.layer));
                } else {
                    boolean assembled = placed.contains(component.id());
                    boolean ready = next != null && next.id().equals(component.id());
                    contents[slot] = item(material(component), (assembled ? "已装配 " : ready ? "下一组件 " : "待装配 ") + component.type(),
                            List.of("坐标 " + x + ", " + y + ", " + holder.layer, "朝向 " + component.rotation() * 90 + "°",
                                    ready ? "点击按蓝图坐标放置" : ""));
                }
            }
        }
        for (int slot = CELLS; slot < 45; slot++) contents[slot] = item(Material.GRAY_STAINED_GLASS_PANE, "", List.of());
        contents[LAYER_DOWN] = item(Material.SPECTRAL_ARROW, "下层 z=" + Math.max(-16, holder.layer - 1), List.of("切换虚拟高度层"));
        contents[LAYER_UP] = item(Material.SPECTRAL_ARROW, "上层 z=" + Math.min(16, holder.layer + 1), List.of("切换虚拟高度层"));
        contents[STATUS] = item(Material.COMPASS, "装配进度 " + holder.session.placedCount() + "/" + holder.session.totalComponents(),
                List.of("已形成连接 " + holder.session.completedConnections().size(), "模拟装配，不修改 Minecraft 世界或背包"));
        contents[RESTART] = item(Material.CLOCK, "从头装配", List.of("依据当前已保存蓝图重置进度"));
        List<String> connections = holder.session.completedConnections().stream().limit(3)
                .map(connection -> connection.from() + " ↔ " + connection.to()).toList();
        List<String> blueprintDetails = new ArrayList<>(List.of(blueprint.metadata().id().value(),
                "形态 " + new dev.stargenesis.design.BlueprintAnalyzer(catalog, budget).analyze(blueprint).shapeHash().substring(0, 12),
                "连接端点 " + connections));
        contents[52] = item(Material.WRITABLE_BOOK, "已绑定蓝图", blueprintDetails);
        contents[CLOSE] = item(Material.OAK_DOOR, "关闭装配台", List.of("进度已保存到玩家数据"));
        return contents;
    }

    private static Material material(Blueprint.Component component) {
        return switch (component.type()) {
            case ModuleCatalog.CORE_TYPE -> Material.NETHER_STAR;
            case "genesis:frame" -> Material.IRON_BLOCK;
            case "genesis:collector" -> Material.IRON_PICKAXE;
            case "genesis:thruster" -> Material.FIREWORK_ROCKET;
            case "genesis:relay" -> Material.REDSTONE_LAMP;
            case "genesis:storage" -> Material.CHEST;
            case "genesis:repair" -> Material.ANVIL;
            default -> Material.IRON_INGOT;
        };
    }

    private static ItemStack item(Material material, String title, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(title));
        meta.lore(lore.stream().filter(line -> !line.isEmpty()).map(Component::text).toList());
        stack.setItemMeta(meta);
        return stack;
    }

    private static final class AssemblyHolder implements InventoryHolder {
        private final UUID owner;
        private BlueprintAssemblySession session;
        private Inventory inventory;
        private int centerX;
        private int centerY;
        private int layer;

        private AssemblyHolder(UUID owner, BlueprintAssemblySession session) {
            this.owner = owner;
            this.session = session;
        }

        @Override public Inventory getInventory() { return inventory; }
    }
}
