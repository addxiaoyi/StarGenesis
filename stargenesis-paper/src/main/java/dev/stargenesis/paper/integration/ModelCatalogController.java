package dev.stargenesis.paper.integration;

import dev.stargenesis.integrations.ModelCatalog;
import org.bukkit.command.CommandSender;

import java.util.Locale;
import java.util.function.Supplier;

/** Synchronous read-only command. A service lookup on every request avoids stale providers. */
public final class ModelCatalogController {
    private final Supplier<ModelCatalog> catalog;

    public ModelCatalogController(Supplier<ModelCatalog> catalog) { this.catalog = catalog; }

    public boolean handle(CommandSender sender, String[] args) {
        if (!sender.hasPermission("stargenesis.models")) {
            sender.sendMessage("StarGenesis: permission denied (stargenesis.models)");
            return true;
        }
        String action = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if (!(action.equals("status") && args.length == 1)
                && !(action.equals("inspect") && args.length == 2)) {
            sender.sendMessage("/genesis model status · /genesis model inspect <model ID>");
            return true;
        }
        try {
            ModelCatalog service = catalog.get();
            if (service == null) {
                sender.sendMessage("模型查询桥接未安装或已停用。");
                return true;
            }
            if (action.equals("status")) {
                sender.sendMessage("模型查询可用 · provider=" + service.integrationId() + " · loaded=" + service.loadedModels());
            } else {
                var result = service.inspect(args[1]);
                if (result.isEmpty()) sender.sendMessage("模型尚未加载：" + args[1]);
                else {
                    var model = result.get();
                    sender.sendMessage("模型 " + model.id() + " · scale=" + model.scale() + " · bones=" + model.bones()
                            + " · hitbox=" + model.hitboxWidth() + "x" + model.hitboxHeight());
                    sender.sendMessage("动画数量=" + model.animations().size() + " · "
                            + String.join(", ", model.animations().stream().limit(16).toList()));
                }
            }
        } catch (RuntimeException | LinkageError failure) {
            sender.sendMessage("模型查询暂不可用：" + failure.getClass().getSimpleName());
        }
        return true;
    }
}
