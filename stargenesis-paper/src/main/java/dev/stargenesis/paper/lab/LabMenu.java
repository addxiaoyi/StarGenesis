package dev.stargenesis.paper.lab;

import dev.stargenesis.sim.GridPoint;
import dev.stargenesis.sim.ResourceType;
import dev.stargenesis.sim.SandboxView;
import dev.stargenesis.sim.Terrain;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import java.util.Arrays;
import java.util.List;

/** All contents are projections in a private virtual inventory, never transferable rewards. */
final class LabMenu implements InventoryHolder {
    final LabController.Session session;
    volatile ScheduledTask task;
    private final Inventory inventory;
    private String renderedStamp = "";

    LabMenu(LabController.Session session, LabController controller) {
        this.session = session;
        inventory = Bukkit.createInventory(this, 54, Component.text("Genesis · 资源试验箱"));
        render();
    }

    @Override public Inventory getInventory() { return inventory; }

    void render() {
        SandboxView view = session.view;
        String stamp = session.phase + ":" + session.message + ":" + (view == null ? -1 : view.tick());
        if (stamp.equals(renderedStamp)) return;
        renderedStamp = stamp;
        inventory.clear();
        if (view != null) {
            int width = Math.min(9, view.width()), height = Math.min(5, view.height());
            for (int y = 0; y < height; y++) for (int x = 0; x < width; x++) {
                Terrain terrain = view.terrain().get(y * view.width() + x);
                inventory.setItem(y * 9 + x, item(terrain == Terrain.BLOCKED ? Material.GRAY_STAINED_GLASS_PANE : Material.LIGHT_GRAY_STAINED_GLASS_PANE,
                        terrain == Terrain.BLOCKED ? "阻挡 (" + x + "," + y + ")" : "地面 (" + x + "," + y + ")", "仅模拟格，不更改真实世界"));
            }
            if (visible(view.base())) inventory.setItem(slot(view.base()), item(Material.BARREL, "基地 · 模拟分 " + view.score(), "造物在此投递；不生成真实物品"));
            for (SandboxView.ResourceNodeView node : view.nodes()) if (visible(node.position())) {
                inventory.setItem(slot(node.position()), item(node.resource() == ResourceType.ORE ? Material.RAW_IRON : Material.WHEAT_SEEDS,
                        node.id() + " · " + node.resource(), "剩余 " + node.remaining(), "仅为投影图标，禁止领取"));
            }
            int index = 0;
            for (SandboxView.AgentView agent : view.agents()) {
                index++;
                if (!visible(agent.position())) continue;
                inventory.setItem(slot(agent.position()), item(Material.CLOCK, "造物 " + index + " · " + agent.status(),
                        "格 " + agent.position().x() + "," + agent.position().y(),
                        "能源 " + agent.energy() + " · 载重 " + (agent.ore() + agent.biomass()) + "/" + agent.capacity(),
                        "矿石 " + agent.ore() + " · 生物质 " + agent.biomass(),
                        "目标 " + (agent.target() == null ? "无" : agent.target().x() + "," + agent.target().y()),
                        "时钟图标移动表示造物的权威模拟位置"));
            }
        }
        inventory.setItem(45, item(Material.BOOK, "逻辑步 " + (view == null ? 0 : view.tick()),
                "阶段 " + session.phase, session.message, "仅模拟分，无真实物品或货币"));
        inventory.setItem(46, item(Material.COMPASS, "种子 " + session.seed,
                view == null ? "等待受控模拟线程" : "状态哈希 " + view.stateHash().substring(0, 16), "相同种子与输入应得到相同结果"));
        inventory.setItem(47, item(Material.BARRIER, "停止实验", "保存进度和回放，下一逻辑步生效", "保存失败时暂停并自动重试"));
        inventory.setItem(49, item(Material.RECOVERY_COMPASS, "验证并重播上一局", "逐步核对状态哈希与事件", "当前实验须先结束；已保存回放可在重启后读取"));
        inventory.setItem(50, item(Material.CHEST, "恢复最近检查点", "使用 /genesis lab resume", "每100逻辑步及停止时保存进度和回放"));
        inventory.setItem(51, item(Material.LIME_DYE, "再开始一局", "先结束当前实验", "命令可指定种子和1–4造物"));
        inventory.setItem(53, item(Material.PAPER, "观察四个原语", "寻找 → 移动 → 采集 → 投递", "灰色=阻挡，桶=基地，时钟=造物", "关闭界面将停止本局"));
    }

    private static boolean visible(GridPoint point) { return point.x() >= 0 && point.x() < 9 && point.y() >= 0 && point.y() < 5; }
    private static int slot(GridPoint point) { return point.y() * 9 + point.x(); }
    private static ItemStack item(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        var meta = item.getItemMeta();
        meta.displayName(Component.text(name));
        List<Component> lines = Arrays.stream(lore).map(Component::text).map(component -> (Component) component).toList();
        meta.lore(lines);
        item.setItemMeta(meta);
        return item;
    }
}
