package dev.stargenesis.paper;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.Comparator;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

/** Routes shared ecology command execution through Paper/Folia's global scheduler. */
final class SharedEcologyService {
    private final JavaPlugin plugin;
    private final SharedEcologyCommands commands;

    SharedEcologyService(JavaPlugin plugin) throws IOException {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        commands = new SharedEcologyCommands(plugin.getDataFolder().toPath());
    }

    void execute(UUID actorId, String[] arguments, Consumer<String> reply) {
        if (actorId == null) throw new IllegalArgumentException("Shared ecology commands require a player");
        String[] copiedArguments = Arrays.copyOf(arguments, arguments.length);
        Objects.requireNonNull(reply, "reply");
        plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
            String message;
            try {
                message = commands.execute(actorId, copiedArguments);
            } catch (IOException failure) {
                plugin.getLogger().warning("Shared ecology state was not committed: " + failure.getMessage());
                message = "共享生态未保存，旧状态仍有效：" + failure.getMessage();
            } catch (IllegalArgumentException | IllegalStateException | SecurityException failure) {
                message = "共享生态操作未完成：" + failure.getMessage();
            }
            reply.accept(message);
        });
    }

    void inspect(UUID actorId, Consumer<View> reply) {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(reply, "reply");
        plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
            List<OnlineMember> onlineMembers = plugin.getServer().getOnlinePlayers().stream()
                    .map(player -> new OnlineMember(player.getUniqueId(), player.getName()))
                    .sorted(Comparator.comparing(OnlineMember::name, String.CASE_INSENSITIVE_ORDER)
                            .thenComparing(member -> member.id().toString()))
                    .toList();
            reply.accept(new View(commands.groupsFor(actorId), onlineMembers));
        });
    }

    record OnlineMember(UUID id, String name) {
        OnlineMember {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
        }
    }

    record View(List<EcologyGroupRecord> groups, List<OnlineMember> onlineMembers) {
        View {
            groups = List.copyOf(groups);
            onlineMembers = List.copyOf(onlineMembers);
        }
    }
}
