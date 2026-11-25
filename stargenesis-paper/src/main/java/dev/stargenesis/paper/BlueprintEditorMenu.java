package dev.stargenesis.paper;

import dev.stargenesis.api.design.DesignDocument;
import dev.stargenesis.design.Blueprint;
import dev.stargenesis.design.BlueprintAnalysis;
import dev.stargenesis.design.BlueprintEditorSession;
import dev.stargenesis.design.BlueprintPreview;
import dev.stargenesis.design.DesignCodec;
import dev.stargenesis.design.ModuleCatalog;
import dev.stargenesis.design.ModuleDefinition;
import dev.stargenesis.design.BlueprintBudget;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class BlueprintEditorMenu implements Listener {
    private static final int SIZE = 54;
    private static final int WIDTH = 9;
    private static final int HEIGHT = 5;
    private static final int BOARD_CELLS = WIDTH * HEIGHT;
    private static final int LAYER_DOWN = 45;
    private static final int LAYER_UP = 46;
    private static final int MODULE = 47;
    private static final int MODE = 48;
    private static final int ROTATE = 49;
    private static final int REMOVE = 50;
    private static final int UNDO = 51;
    private static final int PREVIEW = 52;
    private static final int SAVE = 53;

    private final JavaPlugin plugin;
    private final DesignCodec codec = new DesignCodec();
    private final ModuleCatalog catalog = ModuleCatalog.standard();
    private final BlueprintBudget budget = BlueprintBudget.standard();
    private final NamespacedKey checkpointKey;
    private final NamespacedKey savedBlueprintKey;
    private final List<ModuleDefinition> placeable;

    BlueprintEditorMenu(JavaPlugin plugin) {
        this.plugin = plugin;
        checkpointKey = new NamespacedKey(plugin, "blueprint-editor-checkpoint-v1");
        savedBlueprintKey = new NamespacedKey(plugin, "blueprint-saved-v1");
        placeable = catalog.definitions().stream().filter(definition -> !definition.isCore())
                .sorted(java.util.Comparator.comparing(ModuleDefinition::type)).toList();
    }

    Blueprint loadSaved(Player player) {
        byte[] stored = player.getPersistentDataContainer().get(savedBlueprintKey, PersistentDataType.BYTE_ARRAY);
        if (stored == null) return null;
        String json = new String(stored, StandardCharsets.UTF_8);
        if (!java.util.Arrays.equals(stored, json.getBytes(StandardCharsets.UTF_8)))
            throw new IllegalArgumentException("Saved blueprint contains invalid UTF-8");
        DesignDocument document = codec.load(json);
        if (!(document instanceof Blueprint blueprint))
            throw new IllegalArgumentException("Saved design is not a blueprint");
        if (!blueprint.metadata().author().equals(player.getUniqueId()))
            throw new SecurityException("Saved blueprint belongs to another player");
        new dev.stargenesis.design.BlueprintAnalyzer(catalog, budget).requireValid(blueprint);
        return blueprint;
    }

    void open(Player player, boolean reset) {
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof EditorHolder holder
                && holder.playerId.equals(player.getUniqueId())) {
            player.closeInventory();
        }
        BlueprintEditorSession editor = load(player, reset);
        if (editor == null) return;
        EditorHolder holder = new EditorHolder(player.getUniqueId(), editor);
        Inventory inventory = Bukkit.createInventory(holder, SIZE, Component.text("星舰蓝图编辑器"));
        holder.inventory = inventory;
        inventory.setContents(render(holder));
        player.openInventory(inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory inventory = event.getView().getTopInventory();
        if (!(inventory.getHolder() instanceof EditorHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !holder.playerId.equals(player.getUniqueId())) return;
        if (!player.hasPermission("stargenesis.blueprint")) {
            player.closeInventory();
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) return;
        try {
            if (slot < BOARD_CELLS) {
                editCell(player, holder, slot);
            } else {
                activate(player, holder, slot);
            }
            persist(player, holder.editor);
            inventory.setContents(render(holder));
        } catch (IllegalArgumentException | IllegalStateException failure) {
            player.sendMessage(Component.text("蓝图操作未应用：" + failure.getMessage()));
            inventory.setContents(render(holder));
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof EditorHolder) event.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof EditorHolder holder
                && event.getPlayer() instanceof Player player && holder.playerId.equals(player.getUniqueId())) {
            try {
                persist(player, holder.editor);
            } catch (IllegalArgumentException | IllegalStateException failure) {
                plugin.getLogger().warning("Could not persist blueprint editor for " + player.getUniqueId()
                        + ": " + failure.getMessage());
            }
        }
    }

    private BlueprintEditorSession load(Player player, boolean reset) {
        var data = player.getPersistentDataContainer();
        byte[] stored = data.get(checkpointKey, PersistentDataType.BYTE_ARRAY);
        if (!reset && stored != null) {
            try {
                return BlueprintEditorSession.restoreCheckpoint(player.getUniqueId(), stored, codec, catalog, budget);
            } catch (IllegalArgumentException | SecurityException failure) {
                player.sendMessage(Component.text("蓝图存档无效。使用 /genesis blueprint reset 创建新蓝图。"));
                plugin.getLogger().warning("Rejected blueprint editor checkpoint for " + player.getUniqueId()
                        + ": " + failure.getMessage());
                return null;
            }
        }
        try (var input = getClass().getResourceAsStream("/examples/blueprint.json")) {
            if (input == null) throw new IllegalStateException("Bundled starter blueprint is missing");
            DesignDocument starter = codec.load(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            if (!(starter instanceof Blueprint blueprint)) throw new IllegalStateException("Starter design is not a blueprint");
            BlueprintEditorSession editor = BlueprintEditorSession.cloneFor(player.getUniqueId(), blueprint, catalog, budget);
            persist(player, editor);
            return editor;
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot load the starter blueprint", failure);
        }
    }

    private void editCell(Player player, EditorHolder holder, int slot) {
        int column = slot % WIDTH;
        int row = slot / WIDTH;
        int x = holder.centerX + column - WIDTH / 2;
        int y = holder.centerY + row - HEIGHT / 2;
        if (x < -16 || x > 16 || y < -16 || y > 16) return;
        Blueprint.Component occupant = holder.editor.draft().components().stream()
                .filter(component -> component.x() == x && component.y() == y && component.z() == holder.layer)
                .findFirst().orElse(null);
        if (holder.mode == EditMode.MOVE) {
            if (holder.selectedComponent == null) {
                if (occupant != null) {
                    holder.selectedComponent = occupant.id();
                    holder.centerX = x;
                    holder.centerY = y;
                }
                else player.sendMessage(Component.text("先选择一个蓝图组件。"));
            } else if (occupant == null) {
                holder.editor.move(holder.selectedComponent, x, y, holder.layer);
                holder.centerX = x;
                holder.centerY = y;
                holder.selectedComponent = null;
            } else {
                holder.selectedComponent = occupant.id();
                holder.centerX = x;
                holder.centerY = y;
            }
        } else if (holder.mode == EditMode.CONNECT || holder.mode == EditMode.DISCONNECT) {
            if (occupant == null) {
                player.sendMessage(Component.text("选择一个蓝图组件。"));
            } else if (holder.selectedComponent == null) {
                holder.selectedComponent = occupant.id();
                holder.centerX = x;
                holder.centerY = y;
            } else {
                if (holder.mode == EditMode.CONNECT) holder.editor.connect(holder.selectedComponent, occupant.id());
                else holder.editor.disconnect(holder.selectedComponent, occupant.id());
                holder.selectedComponent = null;
            }
        } else if (occupant == null) {
            Blueprint.Component placed = holder.editor.place(placeable.get(holder.moduleIndex).type(), x, y, holder.layer);
            holder.selectedComponent = placed.id();
            holder.centerX = x;
            holder.centerY = y;
        } else {
            holder.selectedComponent = occupant.id();
            holder.centerX = x;
            holder.centerY = y;
        }
    }

    private void activate(Player player, EditorHolder holder, int slot) {
        if (slot == LAYER_DOWN) {
            holder.layer = Math.max(-16, holder.layer - 1);
        } else if (slot == LAYER_UP) {
            holder.layer = Math.min(16, holder.layer + 1);
        } else if (slot == MODULE) {
            holder.moduleIndex = (holder.moduleIndex + 1) % placeable.size();
        } else if (slot == MODE) {
            holder.mode = EditMode.values()[(holder.mode.ordinal() + 1) % EditMode.values().length];
            holder.selectedComponent = null;
        } else if (slot == ROTATE) {
            if (holder.selectedComponent == null) player.sendMessage(Component.text("先选择一个组件。"));
            else holder.editor.rotate(holder.selectedComponent);
        } else if (slot == REMOVE) {
            if (holder.selectedComponent == null) player.sendMessage(Component.text("先选择一个组件。"));
            else {
                holder.editor.remove(holder.selectedComponent);
                holder.selectedComponent = null;
            }
        } else if (slot == UNDO) {
            if (!holder.editor.undo()) player.sendMessage(Component.text("没有可撤销的编辑。"));
            holder.selectedComponent = null;
        } else if (slot == PREVIEW) {
            showPreview(player, holder.editor.preview());
        } else if (slot == SAVE) {
            String saved = holder.editor.save(codec);
            player.getPersistentDataContainer().set(savedBlueprintKey, PersistentDataType.BYTE_ARRAY,
                    saved.getBytes(StandardCharsets.UTF_8));
            persist(player, holder.editor);
            player.sendMessage(Component.text("蓝图已验证并保存到你的玩家数据。使用 /genesis assemble 开始装配。"));
        }
    }

    private void showPreview(Player player, BlueprintPreview preview) {
        BlueprintAnalysis after = preview.after();
        player.sendMessage(Component.text("蓝图预览 " + (preview.canSave() ? "可保存" : "存在问题")
                + " | 模块 " + after.componentCount() + " | 质量 " + after.totalMass() + "/" + budget.maximumMass()
                + " | 能量 " + after.totalEnergy() + "/" + budget.maximumEnergy()
                + " | 耐久 " + after.maximumDurability()));
        player.sendMessage(Component.text("能力 " + after.capabilityCounts() + " | 形态 " + after.shapeHash().substring(0, 16)));
        player.sendMessage(Component.text("形态变化 +" + preview.difference().addedComponents().size()
                + " -" + preview.difference().removedComponents().size()
                + " ~" + preview.difference().changedComponents().size()
                + " | 连接 +" + preview.difference().addedConnections().size()
                + " -" + preview.difference().removedConnections().size()));
        preview.issues().forEach(issue -> player.sendMessage(Component.text("蓝图校验：" + issue)));
    }

    private void persist(Player player, BlueprintEditorSession editor) {
        player.getPersistentDataContainer().set(checkpointKey, PersistentDataType.BYTE_ARRAY, editor.encodeCheckpoint(codec));
    }

    private ItemStack[] render(EditorHolder holder) {
        ItemStack[] contents = new ItemStack[SIZE];
        Blueprint blueprint = holder.editor.draft();
        int startX = holder.centerX - WIDTH / 2;
        int startY = holder.centerY - HEIGHT / 2;
        for (int row = 0; row < HEIGHT; row++) {
            for (int column = 0; column < WIDTH; column++) {
                int x = startX + column;
                int y = startY + row;
                int slot = row * WIDTH + column;
                Blueprint.Component component = blueprint.components().stream()
                        .filter(value -> value.x() == x && value.y() == y && value.z() == holder.layer)
                        .findFirst().orElse(null);
                contents[slot] = renderCell(component, x, y, holder);
            }
        }
        ModuleDefinition module = placeable.get(holder.moduleIndex);
        contents[LAYER_DOWN] = item(Material.SPECTRAL_ARROW, "下层 z=" + Math.max(-16, holder.layer - 1), List.of("切换到下一个高度层"));
        contents[LAYER_UP] = item(Material.SPECTRAL_ARROW, "上层 z=" + Math.min(16, holder.layer + 1), List.of("切换到下一个高度层"));
        contents[MODULE] = item(material(module), "模块：" + module.type(), List.of("点击切换模块", "质量 " + module.mass()
                + " | 能量 " + module.energy() + " | 耐久 " + module.maximumDurability(), "能力 " + module.capabilities()));
        contents[MODE] = item(switch (holder.mode) {
                    case PLACE -> Material.CRAFTING_TABLE;
                    case MOVE -> Material.PISTON;
                    case CONNECT -> Material.TRIPWIRE_HOOK;
                    case DISCONNECT -> Material.SHEARS;
                }, "模式：" + holder.mode.label, switch (holder.mode) {
                    case PLACE -> List.of("点击空格放置，点击组件选择", "再次点击切换模式");
                    case MOVE -> List.of("选中组件，再点击空格移动", "再次点击切换模式");
                    case CONNECT -> List.of("依次点击两个组件建立连接", "再次点击切换模式");
                    case DISCONNECT -> List.of("依次点击两个组件移除连接", "再次点击切换模式");
                });
        contents[ROTATE] = item(Material.ARMOR_STAND, "旋转所选组件", List.of("顺时针旋转 90 度"));
        contents[REMOVE] = item(Material.IRON_PICKAXE, "删除所选组件", List.of("核心组件不能删除"));
        contents[UNDO] = item(Material.SPECTRAL_ARROW, "撤销", List.of(holder.editor.canUndo() ? "撤销最近一次编辑" : "没有可撤销编辑"));
        contents[PREVIEW] = item(Material.SPYGLASS, "预览蓝图", List.of("检查预算、连接、能力和形态差异"));
        contents[SAVE] = item(Material.WRITABLE_BOOK, "验证并保存", List.of("需要先预览当前形态"));
        return contents;
    }

    private ItemStack renderCell(Blueprint.Component component, int x, int y, EditorHolder holder) {
        if (component == null) return item(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "空格", List.of("坐标 " + x + ", " + y + ", " + holder.layer));
        ModuleDefinition definition = catalog.require(component.type());
        List<String> lore = new ArrayList<>();
        lore.add("坐标 " + x + ", " + y + ", " + holder.layer);
        lore.add("能力 " + definition.capabilities());
        lore.add("点击选择组件 " + component.id());
        String title = definition.type() + (component.id().equals(holder.selectedComponent) ? " [已选择]" : "");
        return item(material(definition), title, lore);
    }

    private static Material material(ModuleDefinition definition) {
        if (definition.isCore()) return Material.NETHER_STAR;
        return switch (definition.type()) {
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
        meta.lore(lore.stream().map(Component::text).toList());
        stack.setItemMeta(meta);
        return stack;
    }

    private static final class EditorHolder implements InventoryHolder {
        private final UUID playerId;
        private final BlueprintEditorSession editor;
        private Inventory inventory;
        private int centerX;
        private int centerY;
        private int layer;
        private int moduleIndex;
        private EditMode mode = EditMode.PLACE;
        private String selectedComponent;

        private EditorHolder(UUID playerId, BlueprintEditorSession editor) {
            this.playerId = playerId;
            this.editor = editor;
            Blueprint.Component core = editor.draft().components().stream().filter(component -> component.type().equals(ModuleCatalog.CORE_TYPE))
                    .findFirst().orElseThrow();
            centerX = core.x();
            centerY = core.y();
            layer = core.z();
            selectedComponent = core.id();
        }

        @Override public Inventory getInventory() { return inventory; }
    }

    private enum EditMode {
        PLACE("放置组件"), MOVE("移动组件"), CONNECT("连接组件"), DISCONNECT("断开连接");

        private final String label;

        EditMode(String label) { this.label = label; }
    }
}
