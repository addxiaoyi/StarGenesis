package dev.stargenesis.paper;

import dev.stargenesis.design.DesignCodec;
import dev.stargenesis.design.Program;
import dev.stargenesis.design.ProgramEditorSession;
import dev.stargenesis.design.TypedProgram;
import dev.stargenesis.api.design.DesignDocument;
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

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class ProgramEditorMenu implements Listener {
    private static final int SIZE = 54;
    private static final int INPUTS_PER_NODE = 2;
    private static final int NODE_SLOTS = 42;
    private static final int OPERATION = 45;
    private static final int FIRST_INPUT = 46;
    private static final int SECOND_INPUT = 47;
    private static final int PARAMETER = 48;
    private static final int REMOVE = 49;
    private static final int UNDO = 50;
    private static final int PREVIEW = 51;
    private static final int SAVE = 52;
    private static final int CLOSE = 53;

    private final JavaPlugin plugin;
    private final LaboratoryMenu laboratory;
    private final DesignCodec codec = new DesignCodec();
    private final NamespacedKey editorKey;
    private final NamespacedKey savedProgramKey;

    ProgramEditorMenu(JavaPlugin plugin, LaboratoryMenu laboratory) {
        this.plugin = plugin;
        this.laboratory = laboratory;
        editorKey = new NamespacedKey(plugin, "program-editor-checkpoint-v1");
        savedProgramKey = new NamespacedKey(plugin, "program-saved-v1");
    }

    void open(Player player, boolean reset) {
        if (!player.hasPermission("stargenesis.program")) {
            player.sendMessage(Component.text("你没有编辑行为图的权限。"));
            return;
        }
        try {
            ProgramEditorSession session = loadEditor(player, reset);
            ProgramHolder holder = new ProgramHolder(player.getUniqueId(), session);
            Inventory inventory = Bukkit.createInventory(holder, SIZE, Component.text("有限行为图编辑器"));
            holder.inventory = inventory;
            inventory.setContents(render(holder));
            player.openInventory(inventory);
        } catch (IllegalArgumentException | SecurityException failure) {
            player.sendMessage(Component.text("无法打开行为图编辑器：" + failure.getMessage()));
        }
    }

    void runSaved(Player player) {
        try {
            Program program = loadSaved(player);
            if (program == null) {
                player.sendMessage(Component.text("请先在 /genesis program 中编译并保存行为图。"));
                return;
            }
            laboratory.runProgram(player, program);
        } catch (IllegalArgumentException | SecurityException failure) {
            player.sendMessage(Component.text("已保存行为图无效：" + failure.getMessage()));
        }
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory inventory = event.getView().getTopInventory();
        if (!(inventory.getHolder() instanceof ProgramHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !holder.playerId.equals(player.getUniqueId())) return;
        if (!player.hasPermission("stargenesis.program")) {
            player.closeInventory();
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= SIZE) return;
        try {
            if (slot < NODE_SLOTS) selectNode(player, holder, slot);
            else activate(player, holder, slot);
            persistEditor(player, holder.session);
            inventory.setContents(render(holder));
        } catch (IllegalArgumentException | IllegalStateException failure) {
            player.sendMessage(Component.text("行为图操作未应用：" + failure.getMessage()));
            inventory.setContents(render(holder));
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof ProgramHolder) event.setCancelled(true);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof ProgramHolder holder
                && event.getPlayer() instanceof Player player && holder.playerId.equals(player.getUniqueId())) {
            try {
                persistEditor(player, holder.session);
            } catch (IllegalArgumentException | IllegalStateException failure) {
                plugin.getLogger().warning("Could not persist program editor for " + player.getUniqueId()
                        + ": " + failure.getMessage());
            }
        }
    }

    private ProgramEditorSession loadEditor(Player player, boolean reset) {
        if (!reset) {
            byte[] checkpoint = player.getPersistentDataContainer().get(editorKey, PersistentDataType.BYTE_ARRAY);
            if (checkpoint != null) return ProgramEditorSession.resume(player.getUniqueId(), checkpoint, codec);
            Program saved = loadSaved(player);
            if (saved != null) return ProgramEditorSession.edit(player.getUniqueId(), saved);
        }
        ProgramEditorSession created = ProgramEditorSession.create(player.getUniqueId());
        persistEditor(player, created);
        return created;
    }

    private Program loadSaved(Player player) {
        byte[] stored = player.getPersistentDataContainer().get(savedProgramKey, PersistentDataType.BYTE_ARRAY);
        if (stored == null) return null;
        String json = new String(stored, StandardCharsets.UTF_8);
        if (!java.util.Arrays.equals(stored, json.getBytes(StandardCharsets.UTF_8)))
            throw new IllegalArgumentException("Saved program contains invalid UTF-8");
        DesignDocument document = codec.load(json);
        if (!(document instanceof Program program)) throw new IllegalArgumentException("Saved design is not a program");
        if (!program.metadata().author().equals(player.getUniqueId()))
            throw new SecurityException("Saved program belongs to another player");
        return program;
    }

    private void selectNode(Player player, ProgramHolder holder, int slot) {
        List<Program.Node> nodes = holder.session.draft().nodes();
        if (slot >= nodes.size()) return;
        String id = nodes.get(slot).id();
        if (holder.selectedForRemoval) {
            holder.session.remove(id);
            holder.selectedForRemoval = false;
            holder.firstInput = null;
            holder.secondInput = null;
            return;
        }
        Program.Operation operation = holder.session.operations().get(holder.operationIndex);
        if (holder.session.inputCount(operation) == 0) {
            holder.session.append(operation, List.of());
            return;
        }
        Program.Node node = nodes.get(slot);
        if (node.outputType() != inputType(operation)) {
            player.sendMessage(Component.text("所选节点输出类型与当前操作输入不匹配。"));
            return;
        }
        if (holder.session.inputCount(operation) == 1) {
            holder.session.append(operation, List.of(id));
            holder.firstInput = null;
            holder.secondInput = null;
        } else if (holder.firstInput == null) holder.firstInput = id;
        else if (holder.firstInput.equals(id)) holder.firstInput = null;
        else if (holder.secondInput == null) holder.secondInput = id;
        else if (holder.firstInput.equals(holder.secondInput)) holder.secondInput = id;
        else {
            holder.firstInput = holder.secondInput;
            holder.secondInput = id;
        }
    }

    private void activate(Player player, ProgramHolder holder, int slot) {
        if (slot == OPERATION) {
            holder.operationIndex = (holder.operationIndex + 1) % holder.session.operations().size();
            holder.firstInput = null;
            holder.secondInput = null;
        } else if (slot == FIRST_INPUT) {
            Program.Operation operation = holder.session.operations().get(holder.operationIndex);
            if (holder.session.inputCount(operation) == 0) {
                holder.session.append(operation, List.of());
            } else if (holder.firstInput == null) player.sendMessage(Component.text("先点击一个匹配类型的节点。"));
            else player.sendMessage(Component.text("首输入：" + holder.firstInput));
        } else if (slot == SECOND_INPUT) {
            if (holder.secondInput == null) player.sendMessage(Component.text("先点击第二个匹配类型的节点。"));
            else {
                Program.Operation operation = holder.session.operations().get(holder.operationIndex);
                if (holder.session.inputCount(operation) != 2) player.sendMessage(Component.text("当前操作不需要第二个输入节点。"));
                else if (holder.firstInput == null || holder.secondInput == null)
                    player.sendMessage(Component.text("请先在图中选择两个输入节点。"));
                else holder.session.append(operation, List.of(holder.firstInput, holder.secondInput));
                holder.firstInput = null;
                holder.secondInput = null;
            }
        } else if (slot == PARAMETER) {
            holder.session.cycleParameters();
        } else if (slot == REMOVE) {
            holder.selectedForRemoval = !holder.selectedForRemoval;
            holder.firstInput = null;
            holder.secondInput = null;
        } else if (slot == UNDO) {
            if (!holder.session.undo()) player.sendMessage(Component.text("没有可撤销的编辑。"));
            holder.firstInput = null;
            holder.secondInput = null;
        } else if (slot == PREVIEW) {
            TypedProgram compiled = holder.session.preview();
            player.sendMessage(Component.text("编译成功 | typed IR 指令 " + compiled.instructions().size()
                    + " | 每步上限 " + compiled.maxInstructionsPerStep()));
            compiled.instructions().stream().limit(8).forEach(instruction -> player.sendMessage(Component.text(
                    instruction.id() + " " + instruction.operation() + " " + instruction.inputType() + " → " + instruction.outputType())));
        } else if (slot == SAVE) {
            String encoded = holder.session.save(codec);
            player.getPersistentDataContainer().set(savedProgramKey, PersistentDataType.BYTE_ARRAY,
                    encoded.getBytes(StandardCharsets.UTF_8));
            player.sendMessage(Component.text("行为图已编译验证并保存。使用 /genesis program run 在私人实验室运行。"));
        } else if (slot == CLOSE) {
            player.closeInventory();
        }
    }

    private static Program.ValueType inputType(Program.Operation operation) {
        return switch (operation) {
            case SENSE, CONSTANT, VECTOR, WAIT -> Program.ValueType.NONE;
            case AND, OR, XOR, NOT, SELECT, VECTOR_SELECT -> Program.ValueType.BOOLEAN;
            case COMPARE, ADD, SUBTRACT, MULTIPLY, DIVIDE, REMAINDER, MIN, MAX, ABS, NEGATE, CLAMP -> Program.ValueType.INTEGER;
            case EQUAL -> Program.ValueType.INTEGER;
            case MOVE -> Program.ValueType.VECTOR;
            case HARVEST, DEPOSIT -> Program.ValueType.RESOURCE;
        };
    }

    private void persistEditor(Player player, ProgramEditorSession session) {
        player.getPersistentDataContainer().set(editorKey, PersistentDataType.BYTE_ARRAY, session.encodeCheckpoint(codec));
    }

    private ItemStack[] render(ProgramHolder holder) {
        ItemStack[] contents = new ItemStack[SIZE];
        List<Program.Node> nodes = holder.session.draft().nodes();
        for (int slot = 0; slot < NODE_SLOTS; slot++) {
            if (slot >= nodes.size()) {
                contents[slot] = item(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "空节点槽", List.of("当前节点 " + nodes.size() + "/" + ProgramEditorSession.MAX_EDITOR_NODES));
                continue;
            }
            Program.Node node = nodes.get(slot);
            List<String> lore = new ArrayList<>(List.of("输入 " + node.inputType() + " | 输出 " + node.outputType(),
                    "参数 " + node.parameters(), "连线来自 " + holder.session.draft().edges().stream()
                            .filter(edge -> edge.to().equals(node.id())).map(Program.Edge::from).toList(), "点击作为类型化输入"));
            if (node.id().equals(holder.firstInput)) lore.add("已选为第一输入");
            if (node.id().equals(holder.secondInput)) lore.add("已选为第二输入");
            if (holder.selectedForRemoval) lore.add("点击删除此节点及其下游");
            contents[slot] = item(material(node.operation()), node.id() + " · " + node.operation(), lore);
        }
        for (int slot = NODE_SLOTS; slot < 45; slot++) contents[slot] = item(Material.GRAY_STAINED_GLASS_PANE, "", List.of());
        Program.Operation operation = holder.session.operations().get(holder.operationIndex);
        int needed = holder.session.inputCount(operation);
        contents[OPERATION] = item(Material.REDSTONE, "操作 " + operation,
                List.of("输入类型 " + inputType(operation), "需要输入节点 " + needed, "点击轮换有限操作"));
        contents[FIRST_INPUT] = item(Material.COMPARATOR, needed == 0 ? "添加无输入节点" : "第一输入",
                List.of(needed == 0 ? "点击添加当前操作" : holder.firstInput == null ? "未选择，先点击图中节点" : holder.firstInput));
        contents[SECOND_INPUT] = item(Material.REPEATER, "第二输入",
                List.of(needed == 2 ? holder.secondInput == null ? "未选择" : holder.secondInput : "当前操作无需第二输入"));
        contents[PARAMETER] = item(Material.WRITABLE_BOOK, "轮换参数模板", List.of("当前模板索引 " + holder.session.parameterChoice(),
                "传感器、方向和比较符使用有限服务端选项"));
        contents[REMOVE] = item(holder.selectedForRemoval ? Material.BARRIER : Material.IRON_PICKAXE,
                holder.selectedForRemoval ? "取消删除选择" : "删除节点", List.of("选择后点击节点，其下游节点也会移除"));
        contents[UNDO] = item(Material.SPECTRAL_ARROW, "撤销", List.of(holder.session.canUndo() ? "撤销最近编辑" : "没有可撤销编辑"));
        contents[PREVIEW] = item(Material.SPYGLASS, "编译预览", List.of("检查 typed IR、拓扑顺序与指令预算"));
        contents[SAVE] = item(Material.EMERALD, "验证并保存", List.of("需要当前草稿编译预览通过"));
        contents[CLOSE] = item(Material.OAK_DOOR, "关闭编辑器", List.of("草稿和撤销历史保存在玩家检查点"));
        return contents;
    }

    private static Material material(Program.Operation operation) {
        return switch (operation) {
            case SENSE -> Material.COMPASS;
            case CONSTANT -> Material.AMETHYST_SHARD;
            case COMPARE, AND, OR, XOR, NOT, EQUAL, SELECT, VECTOR_SELECT -> Material.COMPARATOR;
            case VECTOR, MOVE -> Material.ARROW;
            case HARVEST -> Material.IRON_PICKAXE;
            case DEPOSIT -> Material.CHEST;
            case WAIT -> Material.CLOCK;
            default -> Material.REDSTONE;
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

    private static final class ProgramHolder implements InventoryHolder {
        private final UUID playerId;
        private final ProgramEditorSession session;
        private Inventory inventory;
        private int operationIndex;
        private String firstInput;
        private String secondInput;
        private boolean selectedForRemoval;

        private ProgramHolder(UUID playerId, ProgramEditorSession session) {
            this.playerId = playerId;
            this.session = session;
        }

        @Override public Inventory getInventory() { return inventory; }
    }
}
