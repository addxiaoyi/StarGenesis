package dev.stargenesis.paper;

import dev.stargenesis.api.design.DesignId;
import dev.stargenesis.sim.EcologySettings;
import dev.stargenesis.sim.MatterReaction;
import dev.stargenesis.sim.MatterReactionNetwork;
import dev.stargenesis.sim.MatterSpecies;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class EcologyRecipeMenu implements Listener {
    private static final String NUTRIENT_ID = "ecology:nutrient";
    private static final int INVENTORY_SIZE = 54;
    private static final int INPUT_FIRST_SLOT = 0;
    private static final int OUTPUT_FIRST_SLOT = 27;
    private static final int RATE_SLOT = 45;
    private static final int SELECT_RECIPE_SLOT = 46;
    private static final int REMOVE_RECIPE_SLOT = 47;
    private static final int ADD_RECIPE_SLOT = 49;
    private static final int RETURN_SLOT = 53;
    private static final int MAX_TERMS = EcologyRecipeDraft.MAX_TERMS;
    private static final int MAX_PLAYER_COEFFICIENT = EcologyRecipeDraft.MAX_AMOUNT_PER_TERM;
    private static final int MAX_PLAYER_FIRINGS = 64;

    private final EcosystemMenu ecosystemMenu;

    EcologyRecipeMenu(EcosystemMenu ecosystemMenu) {
        this.ecosystemMenu = ecosystemMenu;
    }

    void open(Player player, EcologySession session) {
        if (!player.hasPermission("stargenesis.ecosystem")) {
            player.sendMessage(Component.text("你没有使用生态实验室的权限。"));
            return;
        }
        RecipeHolder holder = new RecipeHolder(player.getUniqueId(), session);
        EcologySettings settings = session.settings(player.getUniqueId());
        List<MatterSpecies> species = settings.reactionSpecies();
        int biomassIndex = indexOf(species, "ecology:biomass");
        int nutrientIndex = indexOf(species, NUTRIENT_ID);
        if (biomassIndex >= 0 && nutrientIndex >= 0) {
            holder.inputTerms.set(0, new EcologyRecipeDraft.Ingredient(species.get(biomassIndex).id(), 1));
            holder.outputTerms.set(0, new EcologyRecipeDraft.Ingredient(species.get(nutrientIndex).id(), 2));
        }
        Inventory inventory = Bukkit.createInventory(holder, INVENTORY_SIZE, Component.text("生态配方工坊"));
        holder.inventory = inventory;
        inventory.setContents(render(holder));
        player.openInventory(inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory inventory = event.getView().getTopInventory();
        if (!(inventory.getHolder() instanceof RecipeHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !holder.playerId.equals(player.getUniqueId())) return;
        if (!player.hasPermission("stargenesis.ecosystem")) {
            player.closeInventory();
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= INVENTORY_SIZE) return;
        EcologySettings settings = holder.session.settings(holder.playerId);
        List<MatterSpecies> species = settings.reactionSpecies();
        if (slot == RETURN_SLOT) {
            ecosystemMenu.open(player, false);
            return;
        }
        if (slot >= INPUT_FIRST_SLOT && slot < INPUT_FIRST_SLOT + MAX_TERMS) {
            editIngredient(holder, true, slot - INPUT_FIRST_SLOT, species, event.getClick());
        } else if (slot >= OUTPUT_FIRST_SLOT && slot < OUTPUT_FIRST_SLOT + MAX_TERMS) {
            editIngredient(holder, false, slot - OUTPUT_FIRST_SLOT, species, event.getClick());
        } else if (slot == RATE_SLOT) {
            holder.maxFirings = adjustRate(holder.maxFirings, event.getClick());
        } else if (slot == SELECT_RECIPE_SLOT) {
            cycleSelectedReaction(holder, settings.matterReactions(), event.getClick());
        } else if (slot == REMOVE_RECIPE_SLOT) {
            removeSelected(player, holder, settings.matterReactions());
        } else if (slot == ADD_RECIPE_SLOT) {
            addRecipe(player, holder, settings);
        }
        inventory.setContents(render(holder));
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof RecipeHolder) event.setCancelled(true);
    }

    private static void editIngredient(RecipeHolder holder, boolean inputSide, int index,
                                       List<MatterSpecies> species, ClickType click) {
        if (species.isEmpty()) return;
        List<EcologyRecipeDraft.Ingredient> terms = inputSide ? holder.inputTerms : holder.outputTerms;
        EcologyRecipeDraft.Ingredient current = terms.get(index);
        if (click.isShiftClick()) {
            if (current == null) return;
            int direction = click.isRightClick() ? -1 : 1;
            int amount = Math.max(1, Math.min(MAX_PLAYER_COEFFICIENT, current.amount() + direction));
            terms.set(index, new EcologyRecipeDraft.Ingredient(current.speciesId(), amount));
            return;
        }
        int currentIndex = current == null ? species.size() : indexOf(species, current.speciesId().value());
        if (currentIndex < 0) currentIndex = species.size();
        int direction = click.isRightClick() ? -1 : 1;
        int nextIndex = Math.floorMod(currentIndex + direction, species.size() + 1);
        terms.set(index, nextIndex == species.size() ? null
                : new EcologyRecipeDraft.Ingredient(species.get(nextIndex).id(), current == null ? 1 : current.amount()));
    }

    private void addRecipe(Player player, RecipeHolder holder, EcologySettings settings) {
        DesignId reactionId = nextReactionId(settings.matterReactions());
        if (reactionId == null) {
            player.sendMessage(Component.text("反应网络已达到上限。"));
            return;
        }
        try {
            MatterReaction reaction = EcologyRecipeDraft.build(reactionId, settings.reactionSpecies(),
                    holder.inputTerms, holder.outputTerms, holder.maxFirings);
            holder.session.addReaction(holder.playerId, reaction);
            ecosystemMenu.persist(player, holder.session);
            player.sendMessage(Component.text("已保存配方 " + reactionId.value()));
            holder.selectedReactionIndex = holder.session.settings(holder.playerId).matterReactions().size() - 1;
        } catch (IllegalArgumentException | IllegalStateException failure) {
            player.sendMessage(Component.text("配方未保存：" + failure.getMessage()));
        }
    }

    private void removeSelected(Player player, RecipeHolder holder, List<MatterReaction> reactions) {
        if (reactions.isEmpty()) {
            player.sendMessage(Component.text("没有可删除的配方。"));
            return;
        }
        MatterReaction selected = reactions.get(Math.floorMod(holder.selectedReactionIndex, reactions.size()));
        try {
            holder.session.removeReaction(holder.playerId, selected.id());
            ecosystemMenu.persist(player, holder.session);
            holder.selectedReactionIndex = Math.max(0, holder.selectedReactionIndex - 1);
            player.sendMessage(Component.text("已删除配方 " + selected.id().value()));
        } catch (IllegalArgumentException | IllegalStateException failure) {
            player.sendMessage(Component.text("配方未删除：" + failure.getMessage()));
        }
    }

    private ItemStack[] render(RecipeHolder holder) {
        EcologySettings settings = holder.session.settings(holder.playerId);
        List<MatterSpecies> species = settings.reactionSpecies();
        ItemStack[] contents = new ItemStack[INVENTORY_SIZE];
        for (int slot = 0; slot < contents.length; slot++) {
            contents[slot] = item(Material.GRAY_STAINED_GLASS_PANE, "", List.of());
        }
        for (int index = 0; index < MAX_TERMS; index++) {
            contents[INPUT_FIRST_SLOT + index] = renderIngredient(true, index, holder.inputTerms.get(index), species);
            contents[OUTPUT_FIRST_SLOT + index] = renderIngredient(false, index, holder.outputTerms.get(index), species);
        }
        contents[16] = item(Material.PAPER, "反应物", List.of("最多 " + MAX_TERMS + " 个物种"));
        contents[17] = item(Material.WRITABLE_BOOK, "复合配方", List.of("左键/右键切换物种，经过空位", "Shift 左键增加系数", "Shift 右键减少系数"));
        contents[43] = item(Material.PAPER, "生成物", List.of("最多 " + MAX_TERMS + " 个物种"));
        contents[44] = item(Material.WRITABLE_BOOK, "质量平衡", List.of("两侧总质量必须相等"));
        long inputMass = EcologyRecipeDraft.mass(species, holder.inputTerms);
        long outputMass = EcologyRecipeDraft.mass(species, holder.outputTerms);
        boolean hasInputs = holder.inputTerms.stream().anyMatch(java.util.Objects::nonNull);
        boolean hasOutputs = holder.outputTerms.stream().anyMatch(java.util.Objects::nonNull);
        String proposal = format(holder.inputTerms) + " -> " + format(holder.outputTerms);
        if (!hasInputs || !hasOutputs) {
            contents[ADD_RECIPE_SLOT] = item(Material.BARRIER, "配方两侧都需要物质", List.of(proposal));
        } else if (inputMass == outputMass) {
            contents[ADD_RECIPE_SLOT] = item(Material.LIME_DYE, "保存配方",
                    List.of(proposal, "质量 " + inputMass + " / 每步上限 " + holder.maxFirings));
        } else {
            contents[ADD_RECIPE_SLOT] = item(Material.BARRIER, "质量不平衡",
                    List.of("输入 " + inputMass + " / 输出 " + outputMass, proposal));
        }
        contents[RATE_SLOT] = item(Material.CLOCK, "每步反应上限 " + holder.maxFirings,
                List.of("左键增加，右键减少，Shift 调整 10"));
        contents[SELECT_RECIPE_SLOT] = renderSelectedRecipe(holder, settings.matterReactions());
        contents[REMOVE_RECIPE_SLOT] = settings.matterReactions().isEmpty()
                ? item(Material.BARRIER, "没有可删除的配方", List.of())
                : item(Material.RED_DYE, "删除所选配方", List.of("反应库存会保留", "后续生态步不再运行此反应"));
        contents[RETURN_SLOT] = item(Material.OAK_DOOR, "返回生态实验室", List.of());
        return contents;
    }

    private static ItemStack renderIngredient(boolean input, int index,
                                               EcologyRecipeDraft.Ingredient term,
                                               List<MatterSpecies> species) {
        String side = input ? "反应物" : "生成物";
        if (term == null) {
            return item(Material.GRAY_STAINED_GLASS_PANE, side + "位 " + (index + 1) + "：空",
                    List.of("左键/右键循环物种和空位"));
        }
        int speciesIndex = indexOf(species, term.speciesId().value());
        if (speciesIndex < 0) return item(Material.BARRIER, side + "位物种无效", List.of());
        MatterSpecies matter = species.get(speciesIndex);
        long mass = Math.multiplyExact(matter.massPerUnit(), term.amount());
        return item(input ? Material.HOPPER : Material.COMPOSTER,
                side + " " + matter.id().value() + " x" + term.amount(),
                List.of("该项质量 " + mass, "左键/右键循环物种和空位", "Shift 左键增加系数", "Shift 右键减少系数"));
    }

    private static ItemStack renderSelectedRecipe(RecipeHolder holder, List<MatterReaction> reactions) {
        if (reactions.isEmpty()) return item(Material.BOOK, "暂无已保存配方", List.of("配方数量上限 "
                + MatterReactionNetwork.MAX_REACTIONS));
        MatterReaction reaction = reactions.get(Math.floorMod(holder.selectedReactionIndex, reactions.size()));
        return item(Material.WRITABLE_BOOK, "配方 " + reaction.id().value(),
                List.of(format(reaction.inputs()) + " -> " + format(reaction.outputs()),
                        "每步上限 " + reaction.maxFiringsPerStep(), "左键/右键选择"));
    }

    private static String format(List<EcologyRecipeDraft.Ingredient> terms) {
        return terms.stream().filter(java.util.Objects::nonNull)
                .map(term -> term.speciesId().value() + " x" + term.amount())
                .collect(java.util.stream.Collectors.joining(" + "));
    }

    private static String format(Map<DesignId, Integer> side) {
        return side.entrySet().stream().map(entry -> entry.getKey().value() + " x" + entry.getValue())
                .collect(java.util.stream.Collectors.joining(" + "));
    }

    private static void cycleSelectedReaction(RecipeHolder holder, List<MatterReaction> reactions, ClickType click) {
        if (reactions.isEmpty()) return;
        int direction = click == ClickType.RIGHT ? -1 : 1;
        holder.selectedReactionIndex = Math.floorMod(holder.selectedReactionIndex + direction, reactions.size());
    }

    private static int adjustRate(int value, ClickType click) {
        int amount = click.isShiftClick() ? 10 : 1;
        int direction = click.isRightClick() ? -1 : 1;
        return Math.max(1, Math.min(MAX_PLAYER_FIRINGS, value + direction * amount));
    }

    private static int indexOf(List<MatterSpecies> species, String id) {
        for (int index = 0; index < species.size(); index++) {
            if (species.get(index).id().value().equals(id)) return index;
        }
        return -1;
    }

    private static DesignId nextReactionId(List<MatterReaction> reactions) {
        for (int index = 1; index <= MatterReactionNetwork.MAX_REACTIONS; index++) {
            String suffix = index < 10 ? "0" + index : Integer.toString(index);
            DesignId candidate = new DesignId("player:recipe-" + suffix);
            if (reactions.stream().noneMatch(reaction -> reaction.id().equals(candidate))) return candidate;
        }
        return null;
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

    private static final class RecipeHolder implements InventoryHolder {
        private final UUID playerId;
        private final EcologySession session;
        private final List<EcologyRecipeDraft.Ingredient> inputTerms = emptySide();
        private final List<EcologyRecipeDraft.Ingredient> outputTerms = emptySide();
        private int maxFirings = 16;
        private int selectedReactionIndex;
        private Inventory inventory;

        private static List<EcologyRecipeDraft.Ingredient> emptySide() {
            List<EcologyRecipeDraft.Ingredient> terms = new ArrayList<>();
            for (int index = 0; index < MAX_TERMS; index++) terms.add(null);
            return terms;
        }

        private RecipeHolder(UUID playerId, EcologySession session) {
            this.playerId = playerId;
            this.session = session;
        }

        @Override public Inventory getInventory() { return inventory; }
    }

}
