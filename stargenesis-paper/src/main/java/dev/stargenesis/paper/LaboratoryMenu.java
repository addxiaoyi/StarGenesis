package dev.stargenesis.paper;

import dev.stargenesis.api.sim.SimulationBudget;
import dev.stargenesis.api.sim.StepResult;
import dev.stargenesis.design.Program;
import dev.stargenesis.design.ProgramExecutionResult;
import dev.stargenesis.sim.GridWorldSnapshot;
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
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class LaboratoryMenu implements Listener {
    private static final int MENU_SIZE = 54;
    private static final int MAP_WIDTH = 9;
    private static final int MAP_HEIGHT = 4;
    private static final int MAP_CELLS = MAP_WIDTH * MAP_HEIGHT;

    private final JavaPlugin plugin;
    private final SimulationBudget budget;
    private final long stepDurationMillis;
    private final NamespacedKey checkpointKey;

    LaboratoryMenu(JavaPlugin plugin, SimulationBudget budget, long stepDurationMillis) {
        this.plugin = plugin;
        this.budget = budget;
        this.stepDurationMillis = stepDurationMillis;
        checkpointKey = new NamespacedKey(plugin, "laboratory-checkpoint-v1");
    }

    void open(Player player, boolean reset) {
        LaboratorySession session = load(player, reset);
        if (session == null) return;
        Inventory inventory = createInventory(player.getUniqueId());
        inventory.setContents(render(session.snapshot()));
        player.openInventory(inventory);
    }

    void close(Player player) {
        if (player.getOpenInventory().getTopInventory().getHolder() instanceof LaboratoryHolder holder
                && holder.playerId.equals(player.getUniqueId())) {
            player.closeInventory();
        }
    }

    void runProgram(Player player, Program program) {
        LaboratorySession session = load(player, false);
        if (session == null) return;
        LaboratorySession.ProgramRun run = session.run(program);
        player.getPersistentDataContainer().set(checkpointKey, PersistentDataType.BYTE_ARRAY, run.checkpoint());
        ProgramExecutionResult execution = run.execution();
        player.sendMessage(Component.text("行为图 " + execution.status() + " | 指令 " + execution.instructionsExecuted()
                + " | 生成动作 " + execution.commands().size()));
        execution.trace().stream().filter(event -> !event.nodeId().isEmpty()).limit(8)
                .forEach(event -> player.sendMessage(Component.text(event.nodeId() + " " + event.operation()
                        + (event.output().isEmpty() ? "" : " -> " + event.output()))));
        if (run.step() != null) sendOutcome(player, run.step());
        Inventory open = player.getOpenInventory().getTopInventory();
        if (open.getHolder() instanceof LaboratoryHolder holder && holder.playerId.equals(player.getUniqueId()))
            open.setContents(render(session.snapshot()));
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof LaboratoryHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !holder.playerId.equals(player.getUniqueId())) return;
        if (!player.hasPermission("stargenesis.lab")) {
            player.closeInventory();
            return;
        }

        int slot = event.getRawSlot();
        if (slot < MAP_CELLS || slot >= MENU_SIZE) return;
        Button button = Button.forSlot(slot);
        if (button == null) return;
        if (button == Button.CLOSE) {
            player.closeInventory();
            return;
        }

        LaboratorySession session = load(player, false);
        if (session == null) return;
        try {
            GridWorldSnapshot before = session.snapshot();
            if (before.tick() >= budget.maxSteps() && button.action != null) {
                player.sendMessage(Component.text("实验步数预算已用完。使用 /genesis lab reset 开始新实验。"));
                return;
            }
            if (button.action != null) {
                Map<String, Long> arguments = button.arguments;
                if (button == Button.DEPOSIT) {
                    long inventory = before.agents().getFirst().inventory();
                    arguments = Map.of("amount", Math.max(1, inventory));
                }
                StepResult result = session.act(button.action, arguments);
                player.getPersistentDataContainer().set(checkpointKey, PersistentDataType.BYTE_ARRAY,
                        session.encodeCheckpoint());
                sendOutcome(player, result);
            }
            top.setContents(render(session.snapshot()));
        } catch (IllegalArgumentException | IllegalStateException failure) {
            player.sendMessage(Component.text("实验室操作未保存：" + failure.getMessage()));
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof LaboratoryHolder) {
            event.setCancelled(true);
        }
    }

    private LaboratorySession load(Player player, boolean reset) {
        var data = player.getPersistentDataContainer();
        byte[] stored = data.get(checkpointKey, PersistentDataType.BYTE_ARRAY);
        if (reset || stored == null) {
            LaboratorySession created = LaboratorySession.start(player.getUniqueId(), budget, stepDurationMillis);
            data.set(checkpointKey, PersistentDataType.BYTE_ARRAY, created.encodeCheckpoint());
            return created;
        }
        try {
            return LaboratorySession.resume(player.getUniqueId(), stored, budget, stepDurationMillis);
        } catch (IllegalArgumentException failure) {
            player.sendMessage(Component.text("实验室存档无效或配置已变化。使用 /genesis lab reset 创建新实验。"));
            plugin.getLogger().warning("Rejected laboratory checkpoint for " + player.getUniqueId() + ": " + failure.getMessage());
            return null;
        }
    }

    private Inventory createInventory(UUID playerId) {
        LaboratoryHolder holder = new LaboratoryHolder(playerId);
        Inventory inventory = Bukkit.createInventory(holder, MENU_SIZE, Component.text("创世实验室"));
        holder.inventory = inventory;
        return inventory;
    }

    private ItemStack[] render(GridWorldSnapshot snapshot) {
        ItemStack[] contents = new ItemStack[MENU_SIZE];
        GridWorldSnapshot.Agent agent = snapshot.agents().getFirst();
        int startX = Math.max(0, Math.min(agent.x() - 4, snapshot.width() - MAP_WIDTH));
        int startY = Math.max(0, Math.min(agent.y() - 1, snapshot.height() - MAP_HEIGHT));

        for (int row = 0; row < MAP_HEIGHT; row++) {
            for (int column = 0; column < MAP_WIDTH; column++) {
                int x = startX + column;
                int y = startY + row;
                int slot = row * MAP_WIDTH + column;
                if (x >= snapshot.width() || y >= snapshot.height()) {
                    contents[slot] = item(Material.BLACK_STAINED_GLASS_PANE, "", List.of());
                    continue;
                }
                GridWorldSnapshot.Cell cell = snapshot.cells().get(y * snapshot.width() + x);
                if (x == agent.x() && y == agent.y()) {
                    contents[slot] = item(Material.NETHER_STAR, "实验 Agent",
                            List.of("坐标 " + x + ", " + y, "背包星屑 " + agent.inventory()));
                } else if (cell.blocked()) {
                    contents[slot] = item(Material.BEDROCK, "障碍", List.of("坐标 " + x + ", " + y));
                } else if (cell.resourceUnits() > 0) {
                    contents[slot] = item(Material.AMETHYST_SHARD, "星屑矿脉",
                            List.of("坐标 " + x + ", " + y, "剩余 " + cell.resourceUnits()));
                } else {
                    contents[slot] = item(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "空地",
                            List.of("坐标 " + x + ", " + y));
                }
            }
        }

        for (int slot = MAP_CELLS; slot < 45; slot++) {
            contents[slot] = item(Material.GRAY_STAINED_GLASS_PANE, "", List.of());
        }
        boolean terminal = snapshot.tick() >= budget.maxSteps();
        contents[45] = button(Material.ARROW, "向西移动", terminal);
        contents[46] = button(Material.ARROW, "向北移动", terminal);
        contents[47] = button(Material.IRON_PICKAXE, "采集星屑", terminal);
        contents[48] = button(Material.ARROW, "向东移动", terminal);
        contents[49] = button(Material.CLOCK, "等待一步", terminal);
        contents[50] = button(Material.ARROW, "向南移动", terminal);
        contents[51] = button(Material.CHEST, "存入全部星屑", terminal);
        contents[52] = item(Material.COMPASS, "世界状态", List.of("逻辑 tick " + snapshot.tick(),
                "位置 " + agent.x() + ", " + agent.y(), "背包星屑 " + agent.inventory(),
                "共享星屑 " + snapshot.sharedStock(), "哈希 " + snapshot.stateHash().substring(0, 12)));
        contents[53] = item(Material.OAK_DOOR, "关闭实验室", List.of("关闭界面"));
        return contents;
    }

    private static ItemStack button(Material material, String title, boolean terminal) {
        return terminal
                ? item(Material.RED_STAINED_GLASS_PANE, "实验已结束", List.of("使用 /genesis lab reset 开始新实验"))
                : item(material, title, List.of("点击执行一步"));
    }

    private static ItemStack item(Material material, String title, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(title));
        List<Component> lines = new ArrayList<>(lore.size());
        lore.forEach(line -> lines.add(Component.text(line)));
        meta.lore(lines);
        stack.setItemMeta(meta);
        return stack;
    }

    private static void sendOutcome(Player player, StepResult result) {
        if (result.events().isEmpty()) return;
        var event = result.events().getFirst();
        String status = event.type().equals("action.applied") ? "完成" : "拒绝：" + event.data().get("reason");
        if (result.terminal()) status = "完成，已达到步数上限";
        player.sendMessage(Component.text("实验步 " + result.tick() + "，" + status));
    }

    private static final class LaboratoryHolder implements InventoryHolder {
        private final UUID playerId;
        private Inventory inventory;

        private LaboratoryHolder(UUID playerId) { this.playerId = playerId; }

        @Override public Inventory getInventory() { return inventory; }
    }

    private enum Button {
        WEST(45, "move", Map.of("dx", -1L, "dy", 0L)),
        NORTH(46, "move", Map.of("dx", 0L, "dy", -1L)),
        GATHER(47, "gather", Map.of()),
        EAST(48, "move", Map.of("dx", 1L, "dy", 0L)),
        WAIT(49, "wait", Map.of()),
        SOUTH(50, "move", Map.of("dx", 0L, "dy", 1L)),
        DEPOSIT(51, "deposit", Map.of()),
        CLOSE(53, null, Map.of());

        private final int slot;
        private final String action;
        private final Map<String, Long> arguments;

        Button(int slot, String action, Map<String, Long> arguments) {
            this.slot = slot;
            this.action = action;
            this.arguments = arguments;
        }

        private static Button forSlot(int slot) {
            for (Button button : values()) if (button.slot == slot) return button;
            return null;
        }
    }
}
