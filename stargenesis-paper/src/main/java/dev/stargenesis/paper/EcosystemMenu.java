package dev.stargenesis.paper;

import dev.stargenesis.sim.EcologyOrganism;
import dev.stargenesis.sim.EcologySettings;
import dev.stargenesis.sim.EcologySnapshot;
import dev.stargenesis.sim.EcologyStatistics;
import dev.stargenesis.sim.EcologyStepResult;
import dev.stargenesis.sim.EcologyWorld;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class EcosystemMenu implements Listener {
    private static final int INVENTORY_SIZE = 54;
    private static final int FIRST_ORGANISM_SLOT = 9;
    private static final int ORGANISM_SLOTS = 36;
    private static final int FIRST_GENE_SLOT = 45;
    private static final int STEP_SLOT = 49;
    private static final int CLOSE_SLOT = 53;

    private final JavaPlugin plugin;
    private final NamespacedKey checkpointKey;
    private final NamespacedKey versionThreeCheckpointKey;
    private final NamespacedKey versionTwoCheckpointKey;
    private final NamespacedKey legacyCheckpointKey;
    private EcologyRecipeMenu recipeMenu;

    EcosystemMenu(JavaPlugin plugin) {
        this.plugin = plugin;
        checkpointKey = new NamespacedKey(plugin, "ecosystem-checkpoint-v4");
        versionThreeCheckpointKey = new NamespacedKey(plugin, "ecosystem-checkpoint-v3");
        versionTwoCheckpointKey = new NamespacedKey(plugin, "ecosystem-checkpoint-v2");
        legacyCheckpointKey = new NamespacedKey(plugin, "ecosystem-checkpoint-v1");
    }

    void open(Player player, boolean reset) {
        if (!player.hasPermission("stargenesis.ecosystem")) {
            player.sendMessage(Component.text("你没有使用生态实验室的权限。"));
            return;
        }
        EcologySession session = load(player, reset);
        if (session == null) return;
        EcosystemHolder holder = new EcosystemHolder(player.getUniqueId(), session);
        Inventory inventory = Bukkit.createInventory(holder, INVENTORY_SIZE, Component.text("封闭生态实验室"));
        holder.inventory = inventory;
        inventory.setContents(render(holder));
        player.openInventory(inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory inventory = event.getView().getTopInventory();
        if (!(inventory.getHolder() instanceof EcosystemHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !holder.playerId.equals(player.getUniqueId())) return;
        if (!player.hasPermission("stargenesis.ecosystem")) {
            player.closeInventory();
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= INVENTORY_SIZE) return;
        if (slot == CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        if (slot == 50 && recipeMenu != null) {
            recipeMenu.open(player, holder.session);
            return;
        }
        if (slot >= FIRST_ORGANISM_SLOT && slot < FIRST_GENE_SLOT) {
            List<EcologyOrganism> organisms = holder.session.snapshot(holder.playerId).organisms();
            int organismIndex = slot - FIRST_ORGANISM_SLOT;
            holder.selectedOrganismId = organismIndex < organisms.size() ? organisms.get(organismIndex).id() : null;
            inventory.setContents(render(holder));
            return;
        }
        if (slot >= FIRST_GENE_SLOT && slot < STEP_SLOT) {
            if (holder.selectedOrganismId == null) {
                player.sendMessage(Component.text("先选择一个生态个体。"));
                return;
            }
            if (event.getClick() != ClickType.LEFT && event.getClick() != ClickType.RIGHT) return;
            try {
                int direction = event.getClick() == ClickType.RIGHT ? -1 : 1;
                holder.session.editGenome(holder.playerId, holder.selectedOrganismId,
                        slot - FIRST_GENE_SLOT, direction);
                persist(player, holder);
                inventory.setContents(render(holder));
            } catch (IllegalArgumentException | IllegalStateException failure) {
                player.sendMessage(Component.text("基因编辑未应用：" + failure.getMessage()));
                inventory.setContents(render(holder));
            }
            return;
        }
        if (slot != STEP_SLOT) return;
        try {
            EcologyStepResult result = holder.session.step(holder.playerId);
            persist(player, holder);
            if (result.terminal()) {
                player.sendMessage(Component.text("生态步 " + result.tick() + " 已结束：" + result.terminalReason()));
            }
            inventory.setContents(render(holder));
        } catch (IllegalArgumentException | IllegalStateException failure) {
            player.sendMessage(Component.text("生态步未保存：" + failure.getMessage()));
            inventory.setContents(render(holder));
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof EcosystemHolder) event.setCancelled(true);
    }

    private EcologySession load(Player player, boolean reset) {
        var data = player.getPersistentDataContainer();
        byte[] stored = data.get(checkpointKey, PersistentDataType.BYTE_ARRAY);
        byte[] versionThree = data.get(versionThreeCheckpointKey, PersistentDataType.BYTE_ARRAY);
        byte[] versionTwo = data.get(versionTwoCheckpointKey, PersistentDataType.BYTE_ARRAY);
        byte[] legacy = data.get(legacyCheckpointKey, PersistentDataType.BYTE_ARRAY);
        if (reset || stored == null && versionThree == null && versionTwo == null && legacy == null) {
            EcologySession created = EcologySession.start(player.getUniqueId());
            persist(player, created);
            return created;
        }
        try {
            byte[] checkpoint = stored != null ? stored : versionThree != null ? versionThree
                    : versionTwo != null ? versionTwo : legacy;
            EcologySession resumed = EcologySession.resume(player.getUniqueId(), checkpoint);
            persist(player, resumed);
            return resumed;
        } catch (IllegalArgumentException failure) {
            player.sendMessage(Component.text("生态实验存档无效。使用 /genesis ecosystem reset 创建新生态。"));
            plugin.getLogger().warning("Rejected ecology checkpoint for " + player.getUniqueId() + ": " + failure.getMessage());
            return null;
        }
    }

    private void persist(Player player, EcosystemHolder holder) {
        persist(player, holder.session);
    }

    void persist(Player player, EcologySession session) {
        player.getPersistentDataContainer().set(checkpointKey, PersistentDataType.BYTE_ARRAY,
                session.encodeCheckpoint(player.getUniqueId()));
        player.getPersistentDataContainer().remove(versionThreeCheckpointKey);
        player.getPersistentDataContainer().remove(versionTwoCheckpointKey);
        player.getPersistentDataContainer().remove(legacyCheckpointKey);
    }

    private ItemStack[] render(EcosystemHolder holder) {
        EcologySnapshot snapshot = holder.session.snapshot(holder.playerId);
        EcologySettings settings = holder.session.settings(holder.playerId);
        ItemStack[] contents = new ItemStack[INVENTORY_SIZE];
        EcologyStatistics statistics = snapshot.statistics();
        int[] populationByCell = new int[EcologyWorld.HABITAT_CELL_COUNT];
        snapshot.organisms().forEach(organism -> populationByCell[organism.habitatCell()]++);
        int occupiedCells = 0;
        int mostPopulatedCell = 0;
        for (int population : populationByCell) {
            if (population > 0) occupiedCells++;
            mostPopulatedCell = Math.max(mostPopulatedCell, population);
        }
        EcologyOrganism selected = null;
        if (holder.selectedOrganismId != null) {
            selected = snapshot.organisms().stream().filter(organism -> organism.id() == holder.selectedOrganismId)
                    .findFirst().orElse(null);
            if (selected == null) holder.selectedOrganismId = null;
        }
        for (int slot = 0; slot < FIRST_ORGANISM_SLOT; slot++) {
            contents[slot] = item(Material.GRAY_STAINED_GLASS_PANE, "", List.of());
        }
        contents[0] = item(Material.SEA_PICKLE, "生态步 " + snapshot.tick(),
                List.of("剩余步数 " + Math.max(0, settings.maxSteps() - snapshot.tick()),
                        "哈希 " + snapshot.stateHash().substring(0, 12)));
        contents[1] = item(Material.BLAZE_POWDER, "可用能量 " + statistics.availableEnergy(),
                List.of("等待摄取的能量"));
        contents[2] = item(Material.WHEAT, "营养 " + snapshot.nutrient(),
                List.of("栖息地 " + EcologyWorld.HABITAT_WIDTH + "×" + EcologyWorld.HABITAT_HEIGHT,
                        "营养和废料按格记录，个体只摄取所在格资源"));
        contents[3] = item(Material.LIGHTNING_ROD, "累计输入 " + statistics.energyInput(),
                List.of("每步输入 " + settings.energyInputPerStep()));
        contents[4] = item(Material.ROTTEN_FLESH, "废料 " + snapshot.waste(),
                List.of("每格每步最多分解 " + settings.decompositionPerStep() + " 单位"));
        contents[5] = item(Material.MAGMA_CREAM, "散失热 " + statistics.heatReleased(),
                List.of("累计代谢和淘汰损耗"));
        contents[6] = item(Material.SLIME_BALL, "种群 " + statistics.population(),
                List.of("基因型 " + statistics.speciesCount(),
                        "占用格 " + occupiedCells + " / " + EcologyWorld.HABITAT_CELL_COUNT,
                        "最拥挤格 " + mostPopulatedCell + " 个体，繁殖上限 "
                                + EcologyWorld.MAX_POPULATION_PER_CELL,
                        "遗传多样性来自有限基因突变"));
        contents[7] = item(Material.EXPERIENCE_BOTTLE, "个体储能 " + statistics.organismEnergy(),
                List.of("平均能量 " + statistics.meanEnergy()));
        contents[8] = item(Material.COMPARATOR, "守恒总能量 " + statistics.conservedEnergy(),
                List.of("总物质 " + statistics.conservedMatter(), "摄取 " + statistics.foodConsumed(),
                        "分解 " + statistics.decomposed(), "出生 " + statistics.births() + " / 死亡 " + statistics.deaths()));

        for (int slot = FIRST_ORGANISM_SLOT; slot < FIRST_ORGANISM_SLOT + ORGANISM_SLOTS; slot++) {
            contents[slot] = item(Material.BLACK_STAINED_GLASS_PANE, "", List.of());
        }
        for (int i = 0; i < snapshot.organisms().size(); i++) {
            EcologyOrganism organism = snapshot.organisms().get(i);
            contents[FIRST_ORGANISM_SLOT + i] = renderOrganism(organism,
                    holder.selectedOrganismId != null && holder.selectedOrganismId == organism.id());
        }
        for (int slot = FIRST_GENE_SLOT; slot < STEP_SLOT; slot++) contents[slot] = renderGene(slot, selected);
        boolean terminal = snapshot.tick() >= settings.maxSteps() || statistics.population() == 0;
        contents[STEP_SLOT] = terminal
                ? item(Material.RED_STAINED_GLASS_PANE, "生态已结束", List.of("使用 /genesis ecosystem reset 重新开始"))
                : item(Material.CLOCK, "推进一个生态步", List.of("平均能量 " + statistics.meanEnergy(),
                        "平均摄取效率 " + statistics.meanAssimilationPermille() / 1_000.0,
                        "能量输入 " + settings.energyInputPerStep() + " / 每步"));
        contents[50] = item(Material.CRAFTING_TABLE, "配方工坊",
                List.of("编辑质量守恒反应", "生态营养物质可被生物摄取"));
        contents[51] = item(Material.BREWING_STAND, "反应网络 " + settings.matterReactions().size(),
                List.of("物种 " + settings.reactionSpecies().size(), "每步触发受有限预算约束"));
        contents[52] = item(Material.COMPARATOR, "选择压力", List.of("能量不足导致淘汰", "繁殖受资源和人口上限约束"));
        contents[CLOSE_SLOT] = item(Material.OAK_DOOR, "关闭生态实验室", List.of("关闭界面"));
        return contents;
    }

    private static ItemStack renderOrganism(EcologyOrganism organism, boolean selected) {
        Material material = switch (organism.genome().energyPerFood()) {
            case 1 -> Material.FERN;
            case 2 -> Material.GLOW_BERRIES;
            case 3 -> Material.MOSS_BLOCK;
            default -> Material.AZALEA;
        };
        return item(selected ? Material.NETHER_STAR : material, "个体 " + organism.id(),
                List.of("年龄 " + organism.age() + " / " + organism.genome().lifespanTicks(),
                        "栖息地格 " + organism.habitatCell() % EcologyWorld.HABITAT_WIDTH + ","
                                + organism.habitatCell() / EcologyWorld.HABITAT_WIDTH,
                        "能量 " + organism.energy(),
                        "摄取 " + organism.genome().energyPerFood() + " / 代谢 " + organism.genome().maintenanceEnergy(),
                        "成熟期 " + organism.genome().maturityTicks(),
                        selected ? "当前已选中" : "点击选择此个体"));
    }

    private static ItemStack renderGene(int slot, EcologyOrganism selected) {
        if (selected == null) return item(Material.GRAY_STAINED_GLASS_PANE, "选择个体后编辑基因", List.of());
        int value = switch (slot - FIRST_GENE_SLOT) {
            case 0 -> selected.genome().energyPerFood();
            case 1 -> selected.genome().maintenanceEnergy();
            case 2 -> selected.genome().lifespanTicks();
            case 3 -> selected.genome().maturityTicks();
            default -> throw new IllegalArgumentException("Unknown ecology gene slot");
        };
        String title = switch (slot - FIRST_GENE_SLOT) {
            case 0 -> "摄取效率";
            case 1 -> "代谢成本";
            case 2 -> "寿命";
            case 3 -> "成熟期";
            default -> throw new IllegalArgumentException("Unknown ecology gene slot");
        };
        Material material = switch (slot - FIRST_GENE_SLOT) {
            case 0 -> Material.GLOWSTONE_DUST;
            case 1 -> Material.IRON_INGOT;
            case 2 -> Material.CLOCK;
            case 3 -> Material.AMETHYST_SHARD;
            default -> throw new IllegalArgumentException("Unknown ecology gene slot");
        };
        return item(material, title + " " + value,
                List.of("个体 " + selected.id(), "左键增加，右键减少"));
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

    void setRecipeMenu(EcologyRecipeMenu recipeMenu) {
        this.recipeMenu = recipeMenu;
    }

    private static final class EcosystemHolder implements InventoryHolder {
        private final UUID playerId;
        private final EcologySession session;
        private Long selectedOrganismId;
        private Inventory inventory;

        private EcosystemHolder(UUID playerId, EcologySession session) {
            this.playerId = playerId;
            this.session = session;
        }

        @Override public Inventory getInventory() { return inventory; }
    }

}
