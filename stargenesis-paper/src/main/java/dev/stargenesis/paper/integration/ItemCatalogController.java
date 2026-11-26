package dev.stargenesis.paper.integration;

import dev.stargenesis.integrations.ItemCatalog;
import dev.stargenesis.integrations.ItemPayload;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Supplier;

/** Operator queries copy metadata or the sender's own held stack; no inventory writes. */
public final class ItemCatalogController {
    private final Supplier<ItemCatalog> catalog;
    private final Function<Player, ItemPayload> held;

    public ItemCatalogController(Supplier<ItemCatalog> catalog, Function<Player, ItemPayload> held) {
        this.catalog = Objects.requireNonNull(catalog);
        this.held = Objects.requireNonNull(held);
    }
    public boolean handle(CommandSender sender, String[] args) {
        if (!sender.hasPermission("stargenesis.items")) {
            sender.sendMessage("StarGenesis: permission denied (stargenesis.items)"); return true;
        }
        String action = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if (!(args.length == 1 && (action.equals("status") || action.equals("held")))
                && !(args.length == 2 && action.equals("inspect"))) {
            sender.sendMessage("/genesis item status · inspect <namespace:item> · held"); return true;
        }
        try {
            if (action.equals("held")) {
                if (!(sender instanceof Player player)) { sender.sendMessage("手持物品查询需要在线玩家。"); return true; }
                var snapshot = held.apply(player);
                sender.sendMessage("手持物品 " + snapshot.itemId() + " · amount=" + snapshot.amount()
                        + " · material=" + snapshot.material() + " · bytes=" + snapshot.size()
                        + " · sha256=" + snapshot.sha256());
                return true;
            }
            var service = catalog.get();
            if (service == null) { sender.sendMessage("物品桥接未安装或已停用。"); return true; }
            if (action.equals("status")) sender.sendMessage("物品桥接可用 · provider=" + service.integrationId() + " · loaded=" + service.loadedItems());
            else {
                var result = service.inspect(args[1]);
                if (result.isEmpty()) sender.sendMessage("物品尚未加载：" + args[1]);
                else {
                    var item = result.orElseThrow();
                    sender.sendMessage("物品 " + item.id() + " · material=" + item.material() + " · vanillaOverride=" + item.vanillaOverride());
                    sender.sendMessage("标签数量=" + item.tags().size() + " · " + String.join(", ", item.tags().stream().limit(16).toList()));
                }
            }
        } catch (RuntimeException | LinkageError unavailable) {
            sender.sendMessage("物品桥接暂不可用：" + unavailable.getClass().getSimpleName());
        }
        return true;
    }
}
