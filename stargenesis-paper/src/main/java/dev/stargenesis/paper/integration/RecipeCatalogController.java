package dev.stargenesis.paper.integration;

import dev.stargenesis.integrations.RecipeCatalog;
import org.bukkit.command.CommandSender;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

/** Operator metadata queries; provider and inventory mutation are not part of this command. */
public final class RecipeCatalogController {
    private final Supplier<RecipeCatalog> catalog;
    public RecipeCatalogController(Supplier<RecipeCatalog> catalog) { this.catalog = Objects.requireNonNull(catalog); }
    public boolean handle(CommandSender sender, String[] args) {
        if (!sender.hasPermission("stargenesis.recipes")) {
            sender.sendMessage("StarGenesis: permission denied (stargenesis.recipes)"); return true;
        }
        String action = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if (!(action.equals("status") && args.length == 1)
                && !(action.equals("list") && (args.length == 3 || args.length == 4))
                && !(action.equals("inspect") && args.length == 4)) {
            sender.sendMessage("/genesis recipe status | list <appliance> <family> [page] | inspect <appliance> <family> <namespace:id>");
            return true;
        }
        try {
            RecipeCatalog.Appliance appliance = null;
            RecipeCatalog.Family family = null;
            int page = 1;
            RecipeCatalog.Key key = null;
            if (!action.equals("status")) {
                appliance = RecipeCatalog.Appliance.valueOf(args[1].toUpperCase(Locale.ROOT));
                family = RecipeCatalog.Family.valueOf(args[2].toUpperCase(Locale.ROOT));
                key = new RecipeCatalog.Key(appliance, family, action.equals("inspect") ? args[3] : "stargenesis:validation");
                if (action.equals("list") && args.length == 4) page = Integer.parseInt(args[3]);
                if (page < 1 || page > RecipeCatalog.MAX_RECIPES / 16) throw new IllegalArgumentException("Invalid page");
            }
            var service = catalog.get();
            if (service == null) { sender.sendMessage("配方桥接未安装或已停用。"); return true; }
            switch (action) {
                case "status" -> {
                    var summary = service.summary();
                    sender.sendMessage("配方桥接可用 · provider=" + service.integrationId() + " · loaded=" + summary.total());
                    sender.sendMessage(summary.byAppliance().toString());
                    sender.sendMessage("配方类别：ACCURATE, FLEX, POT, STOCKPOT, CHOPPING, TEAPOT, BAMBOO_TRAY");
                }
                case "list" -> {
                    var ids = service.list(appliance, family);
                    sender.sendMessage("配方 " + appliance + "/" + family + " · total=" + ids.size() + " · page=" + page);
                    ids.stream().skip((long) (page - 1) * 16).limit(16).forEach(sender::sendMessage);
                }
                case "inspect" -> {
                    var result = service.inspect(key);
                    if (result.isEmpty()) { sender.sendMessage("配方尚未加载：" + key.id()); break; }
                    var definition = result.orElseThrow();
                    sender.sendMessage("配方 " + key.appliance() + "/" + key.family() + "/" + key.id() + " · 元数据快照");
                    definition.fields().entrySet().stream().limit(16).forEach(entry -> {
                        String value = String.valueOf(entry.getValue()).replace('\n', ' ').replace('\r', ' ');
                        sender.sendMessage(entry.getKey() + "=" + (value.length() > 240 ? value.substring(0, 240) + "…" : value));
                    });
                }
                default -> throw new IllegalStateException("Unknown recipe command");
            }
        } catch (IllegalArgumentException invalid) {
            sender.sendMessage("配方参数或元数据无效，请检查器具、类别、完整 ID 和页码。");
        } catch (RuntimeException | LinkageError unavailable) {
            sender.sendMessage("配方桥接暂不可用：" + unavailable.getClass().getSimpleName());
        }
        return true;
    }
}
