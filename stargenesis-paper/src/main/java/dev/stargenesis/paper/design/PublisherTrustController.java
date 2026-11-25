package dev.stargenesis.paper.design;

import org.bukkit.command.CommandSender;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;

/** Operator entry point for adding and revoking publisher trust roots. */
public final class PublisherTrustController {
    private final PublisherKeyStore store;

    public PublisherTrustController(Path dataDirectory) throws IOException {
        store = new PublisherKeyStore(dataDirectory.resolve("trusted-publishers"));
    }

    public boolean handle(CommandSender sender, String[] args) {
        if (!sender.hasPermission("stargenesis.publishers")) {
            sender.sendMessage("StarGenesis: permission denied (stargenesis.publishers)");
            return true;
        }
        try {
            if (args.length == 0 || args.length == 1 && args[0].equalsIgnoreCase("list")) {
                var entries = store.listEntries();
                if (entries.isEmpty()) {
                    sender.sendMessage("没有配置 publisher trust root。");
                    return true;
                }
                entries.forEach(entry -> {
                    if (entry.trusted()) {
                        sender.sendMessage("TRUSTED " + entry.keyId() + " sha256=" + entry.fingerprint());
                    } else {
                        sender.sendMessage("REVOKED " + entry.keyId() + " sha256=" + entry.fingerprint()
                                + " at=" + entry.revokedAt() + " reason=" + entry.reason());
                    }
                });
                return true;
            }
            if (args.length == 3 && args[0].equalsIgnoreCase("add")) {
                var entry = store.addFromInbox(args[1], args[2]);
                sender.sendMessage("Publisher trust root active " + entry.keyId() + " sha256=" + entry.fingerprint());
                return true;
            }
            if (args.length >= 3 && args[0].equalsIgnoreCase("revoke")) {
                String reason = String.join(" ", Arrays.copyOfRange(args, 2, args.length));
                var entry = store.revoke(args[1], reason);
                sender.sendMessage("Publisher trust root revoked " + entry.keyId() + " sha256=" + entry.fingerprint());
                return true;
            }
        } catch (IOException | IllegalArgumentException failure) {
            sender.sendMessage("Publisher trust update failed: " + failure.getMessage());
            return true;
        }
        sender.sendMessage("/genesis design publishers list | add <keyId> <fileAlias> | revoke <keyId> <reason>");
        sender.sendMessage("Stage public PEM files under plugins/StarGenesis/publisher-inbox/<fileAlias>.pub.");
        return true;
    }
}
