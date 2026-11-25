package dev.stargenesis.paper.design;

import org.bukkit.entity.Player;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Player entry to an administrator-staged, trusted-key signed design import inbox. */
public final class DesignImportController {
    private final SignedDesignPackageStore store;

    public DesignImportController(Path dataDirectory) throws IOException {
        store = new SignedDesignPackageStore(dataDirectory);
    }

    public boolean handle(Player player, String[] args) {
        if (!player.hasPermission("stargenesis.import")) {
            player.sendMessage("StarGenesis: permission denied (stargenesis.import)");
            return true;
        }
        try {
            if (args.length == 1 && args[0].equalsIgnoreCase("list")) {
                List<SignedDesignPackageStore.Imported> imports = store.list(player.getUniqueId());
                if (imports.isEmpty()) { player.sendMessage("没有已导入的签名设计。"); return true; }
                player.sendMessage("私有签名设计 " + imports.size() + "/" + SignedDesignPackageStore.MAX_IMPORTS_PER_PLAYER + ":");
                imports.forEach(item -> player.sendMessage(item.id().value() + " " + item.kind() + " v"
                        + item.version() + " · publisher=" + item.publisherKeyId() + " · hash="
                        + item.documentHash().substring(0, 12)));
                return true;
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("inspect")) {
                var document = store.load(player.getUniqueId(), new dev.stargenesis.api.design.DesignId(args[1]));
                player.sendMessage(document.kind() + " " + document.metadata().id().value()
                        + " v" + document.metadata().version() + " · publisher hash="
                        + document.metadata().hash());
                player.sendMessage("author=" + document.metadata().author() + " · source=" + document.metadata().source()
                        + " · dependencies=" + document.metadata().dependencies().size()
                        + " · publicRead=" + document.metadata().permissions().publicRead()
                        + " · allowClone=" + document.metadata().permissions().allowClone());
                return true;
            }
            if (args.length == 2 && args[0].equalsIgnoreCase("install")) {
                SignedDesignPackageStore.Imported imported = store.install(player.getUniqueId(), args[1]);
                player.sendMessage("签名设计已导入到个人库 " + imported.id().value() + " · " + imported.kind()
                        + " v" + imported.version() + " · publisher=" + imported.publisherKeyId()
                        + " · hash=" + imported.documentHash());
                return true;
            }
        } catch (IOException | IllegalArgumentException | SecurityException failure) {
            player.sendMessage("设计导入未完成：" + failure.getMessage());
            return true;
        }
        player.sendMessage("/genesis design list | install <packageName> | inspect <namespace:id>");
        player.sendMessage("只能导入服务器 inbox 中由 trusted-publishers 下的 Ed25519 key 签名的设计。");
        return true;
    }
}
