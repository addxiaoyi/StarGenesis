package dev.stargenesis.paper;

import dev.stargenesis.sim.FactoryCheckpoint;
import dev.stargenesis.sim.FactorySettings;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/** Serializes factory commands on the global scheduler and persists before publishing mutations. */
final class FactoryService {
    private final JavaPlugin plugin;
    private final FactoryCheckpointStore store;
    private final FactoryOrganizationStore organizationStore;
    private final FactoryRelayReceiptStore relayReceipts;
    private final FactoryRelayTransactionStore relayTransactions;
    private FactorySession session;
    private FactoryOrganizationSession organization;

    FactoryService(JavaPlugin plugin) throws IOException {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        Path dataDirectory = plugin.getDataFolder().toPath();
        store = new FactoryCheckpointStore(dataDirectory);
        organizationStore = new FactoryOrganizationStore(dataDirectory);
        relayReceipts = new FactoryRelayReceiptStore(dataDirectory);
        relayTransactions = new FactoryRelayTransactionStore(dataDirectory);
        relayTransactions.recover(store, relayReceipts);
        Optional<FactoryCheckpoint> checkpoint = store.load();
        session = checkpoint.map(FactorySession::restore).orElse(null);
        organization = organizationStore.load().map(FactoryOrganizationSession::restore).orElse(null);
    }

    void execute(UUID actorId, String[] arguments, Consumer<String> reply) {
        String[] copiedArguments = Arrays.copyOf(arguments, arguments.length);
        plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
            String message;
            try {
                recoverRelayTransaction();
                message = apply(actorId, copiedArguments);
            } catch (IOException failure) {
                plugin.getLogger().warning("Factory state was not committed: " + failure.getMessage());
                message = "工厂操作等待持久化恢复，请稍后重试：" + failure.getMessage();
            } catch (IllegalArgumentException | IllegalStateException | SecurityException | ArithmeticException failure) {
                message = "工厂操作未完成：" + failure.getMessage();
            }
            reply.accept(message);
        });
    }

    FactoryRelayReceiptStore relayReceipts() {
        return relayReceipts;
    }

    void executeRelayed(FactoryRelayPacket request, Consumer<String> reply) {
        plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
            String message = null;
            try {
                recoverRelayTransaction();
                String requestDigest = FactoryRelayPacketCodec.fingerprint(request);
                FactoryRelayReceiptStore.Receipt previous = relayReceipts.find(request.requestId()).orElse(null);
                if (previous != null) {
                    if (!previous.digest().equals(requestDigest)) {
                        reply.accept("远程请求 ID 已绑定到另一组命令。");
                    } else if (previous.state() == FactoryRelayReceiptStore.State.DONE) {
                        reply.accept(previous.response());
                    } else {
                        reply.accept("此请求的旧版回执未完成，需要管理员核查后处理，requestId=" + request.requestId());
                    }
                    return;
                }
                if (relayReceipts.size() >= FactoryRelayReceiptStore.MAX_RECEIPTS) {
                    reply.accept("工厂远程请求回执容量已满，请联系服务器管理员。");
                    return;
                }
                FactorySession staged = session;
                try {
                    if (session == null || !session.containsMember(request.actorId()))
                        throw new SecurityException("玩家不属于此服务器的工厂四人名册。");
                    String[] arguments = request.arguments().toArray(String[]::new);
                    FactoryRelayCommandRules.validate(arguments);
                    FactorySession.CommandResult result = session.command(request.actorId(), arguments);
                    staged = result.session();
                    message = result.message();
                } catch (IllegalArgumentException | IllegalStateException | SecurityException | ArithmeticException failure) {
                    message = "工厂操作未完成：" + failure.getMessage();
                }
                message = FactoryCommandRelayService.boundResponse(message);
                if (session == null) {
                    relayReceipts.begin(request.requestId(), requestDigest);
                    relayReceipts.complete(request.requestId(), requestDigest, message);
                } else {
                    relayTransactions.prepare(session.checkpoint(), staged.checkpoint(),
                            request.requestId(), requestDigest, message);
                    recoverRelayTransaction();
                }
            } catch (IOException | RuntimeException failure) {
                plugin.getLogger().warning("Relayed factory operation requires retry or recovery: " + failure.getMessage());
                message = null;
            }
            reply.accept(message);
        });
    }

    private void recoverRelayTransaction() throws IOException {
        Optional<FactoryCheckpoint> recovered = relayTransactions.recover(store, relayReceipts);
        if (recovered.isPresent()) session = FactorySession.restore(recovered.orElseThrow());
    }

    void inspectOrganization(Consumer<OrganizationView> reply) {
        plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
            List<OnlineMember> onlineMembers = plugin.getServer().getOnlinePlayers().stream()
                    .map(player -> new OnlineMember(player.getUniqueId(), player.getName()))
                    .sorted(Comparator.comparing(OnlineMember::name, String.CASE_INSENSITIVE_ORDER)
                            .thenComparing(member -> member.id().toString()))
                    .toList();
            reply.accept(new OrganizationView(organization == null ? null : organization.organization(),
                    session != null, onlineMembers));
        });
    }

    private String apply(UUID actorId, String[] arguments) throws IOException {
        if (arguments.length > 0 && arguments[0].equalsIgnoreCase("org"))
            return applyOrganization(actorId, arguments);
        if (arguments.length > 0 && arguments[0].equalsIgnoreCase("create"))
            return createFactory(actorId, arguments);

        if (session == null) {
            if (arguments.length > 0 && arguments[0].equalsIgnoreCase("status")) {
                return organization == null
                        ? "此服务器还没有虚拟工厂。使用 /genesis factory org create <name> 创建组织名册。"
                        : "组织名册已准备 " + organization.organization().members().size() + "/4，所有者可用 /genesis factory create 开始工厂。";
            }
            throw new IllegalStateException("此服务器还没有虚拟工厂。");
        }

        FactorySession.CommandResult result = session.command(actorId, arguments);
        if (result.changed()) {
            store.save(result.session().checkpoint());
            session = result.session();
        }
        return result.message();
    }

    private String applyOrganization(UUID actorId, String[] arguments) throws IOException {
        if (arguments.length < 2) {
            throw new IllegalArgumentException("组织命令：org status | org create <name> | org add <uuid> | org remove <uuid>");
        }
        String operation = arguments[1].toLowerCase(java.util.Locale.ROOT);
        if (operation.equals("create")) {
            if (arguments.length != 3) throw new IllegalArgumentException("Usage: /genesis factory org create <name>");
            if (organization != null) throw new IllegalStateException("此服务器的组织名册已创建。");
            FactoryOrganizationSession.CommandResult created = FactoryOrganizationSession.create(
                    actorId, arguments[2], session != null);
            organizationStore.save(created.session().organization());
            organization = created.session();
            return created.message();
        }
        if (organization == null) {
            if (operation.equals("status") && arguments.length == 2) return "此服务器还没有工厂组织名册。";
            throw new IllegalStateException("此服务器还没有工厂组织名册。");
        }
        FactoryOrganizationSession.CommandResult result = organization.command(actorId,
                Arrays.copyOfRange(arguments, 1, arguments.length), session != null);
        if (result.changed()) {
            organizationStore.save(result.session().organization());
            organization = result.session();
        }
        return result.message();
    }

    private String createFactory(UUID actorId, String[] arguments) throws IOException {
        if (session != null) throw new IllegalStateException("此服务器的虚拟工厂已创建，不能覆盖现有检查点。");
        List<UUID> members;
        if (arguments.length == 1) {
            if (organization == null) {
                throw new IllegalArgumentException("先创建四人组织名册，或使用四 UUID 建厂命令。");
            }
            members = organization.factoryMembers(actorId);
        } else if (arguments.length == FactorySettings.MEMBER_COUNT + 1 && organization == null) {
            members = Arrays.stream(arguments, 1, arguments.length).map(FactoryService::parseUuid).toList();
            if (members.stream().distinct().count() != FactorySettings.MEMBER_COUNT) {
                throw new IllegalArgumentException("工厂名册必须包含四个不同的 UUID。");
            }
            if (actorId != null && members.stream().noneMatch(actorId::equals)) {
                throw new SecurityException("创建者必须包含在工厂四人名册中。");
            }
        } else {
            throw new IllegalArgumentException("Usage: /genesis factory create [uuid-1 uuid-2 uuid-3 uuid-4]");
        }
        FactorySession staged = FactorySession.start(members);
        store.save(staged.checkpoint());
        session = staged;
        return "四人虚拟工厂已创建并保存。使用 /genesis factory status 查看名册。";
    }

    private static UUID parseUuid(String value) {
        if (value == null || !value.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new IllegalArgumentException("工厂成员必须使用完整 UUID。");
        }
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("工厂成员必须使用完整 UUID。", failure);
        }
    }

    record OrganizationView(FactoryOrganization organization, boolean factoryStarted, List<OnlineMember> onlineMembers) {
        OrganizationView {
            onlineMembers = List.copyOf(onlineMembers);
        }
    }

    record OnlineMember(UUID id, String name) {
        OnlineMember {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
        }
    }
}
