package dev.stargenesis.paper.communication;

import dev.stargenesis.design.Protocol;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/** Paper command adapter for a virtual, protocol-bound tick radio. */
public final class RadioController implements Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final RadioService service;
    private final NamespacedKey chipProtocolKey;
    private final NamespacedKey chipHashKey;
    private final Object stateLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ScheduledTask ticker;
    private boolean checkpointFailureReported;
    private boolean shutdownSaved;

    public RadioController(JavaPlugin plugin, int messagesPerTick) throws java.io.IOException {
        this.plugin = plugin;
        var persistence = new RadioPersistence(plugin.getDataFolder().toPath().resolve("radio-state.json"),
                RadioProtocol.protocol(), ServiceContractDefinition.protocol(), ServiceContractDefinition.contract());
        service = new RadioService(persistence, messagesPerTick);
        plugin.getLogger().info("P4 radio state ready | tick=" + service.tick()
                + " | retained=" + service.retainedEvidenceCount() + " | active contracts=" + service.activeContracts());
        chipProtocolKey = new NamespacedKey(plugin, "knowledge_chip_protocol");
        chipHashKey = new NamespacedKey(plugin, "knowledge_chip_hash");
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        ticker = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
            synchronized (stateLock) {
                if (closed.get()) { task.cancel(); return; }
                try {
                    service.advance();
                    if (checkpointFailureReported) {
                        plugin.getLogger().info("P4 radio checkpoint persistence recovered");
                        checkpointFailureReported = false;
                    }
                } catch (java.io.IOException failure) {
                    if (!checkpointFailureReported)
                        plugin.getLogger().log(Level.SEVERE, "P4 checkpoint was not saved; previous state retained for retry", failure);
                    checkpointFailureReported = true;
                } catch (RuntimeException failure) {
                    closed.set(true);
                    plugin.getLogger().log(Level.SEVERE, "Virtual radio tick failed; radio stopped", failure);
                    task.cancel();
                }
            }
        }, 1, 1);
    }

    public boolean handle(Player player, String[] args) {
        synchronized (stateLock) {
            if (!player.hasPermission("stargenesis.radio")) {
                player.sendMessage("StarGenesis: permission denied (stargenesis.radio)");
                return true;
            }
            if (args.length == 0) { help(player); return true; }
            if (closed.get()) { player.sendMessage("虚拟无线信箱已停止。"); return true; }
            try {
                switch (args[0].toLowerCase(Locale.ROOT)) {
                    case "chip" -> chip(player, args);
                    case "join" -> join(player, args);
                    case "leave" -> leave(player, args);
                    case "send" -> send(player, args);
                    case "inbox" -> inbox(player, args);
                    case "ack" -> acknowledge(player, args);
                    case "status" -> status(player, args);
                    case "contract" -> contractStatus(player, args);
                    case "rotate" -> rotate(player, args);
                    default -> help(player);
                }
            } catch (java.io.IOException failure) {
                plugin.getLogger().log(Level.WARNING, "P4 operation was not saved; previous state retained", failure);
                player.sendMessage("无线操作未保存，原状态已保留；可稍后重试。");
            } catch (IllegalArgumentException | SecurityException | IllegalStateException failure) {
                player.sendMessage("无线操作未完成：" + failure.getMessage());
            }
        }
        return true;
    }

    public void close() {
        synchronized (stateLock) {
            closed.set(true);
            ticker.cancel();
            try {
                service.close();
                shutdownSaved = true;
            } catch (java.io.IOException | RuntimeException failure) {
                plugin.getLogger().log(Level.SEVERE, "Could not persist P4 radio state during shutdown", failure);
            }
        }
    }

    public boolean isStopped() { synchronized (stateLock) { return closed.get() && shutdownSaved; } }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        synchronized (stateLock) {
            service.disconnected(event.getPlayer().getUniqueId());
        }
    }

    private void join(Player player, String[] args) {
        requireLength(args, 6);
        var installedProtocols = installedProtocols(player);
        VirtualMessageBus.Endpoint endpoint = new VirtualMessageBus.Endpoint(args[1], integer(args[2]),
                integer(args[3]), integer(args[4]), integer(args[5]));
        service.join(player.getUniqueId(), endpoint, installedProtocols);
        player.sendMessage("已加入虚拟无线网络 " + endpoint.network() + " · position=" + endpoint.x() + ","
                + endpoint.y() + "," + endpoint.z() + " · range=" + endpoint.radioRange()
                + " · protocols=" + installedProtocols.stream().map(key -> key.id().value()).sorted().toList());
    }

    private void chip(Player player, String[] args) {
        if (args.length > 2) throw new IllegalArgumentException("用法：/genesis radio chip [protocol ID]");
        ProtocolSelection selection = protocol(args.length == 2 ? args[1] : RadioProtocol.ID.value());
        ItemStack item = new ItemStack(Material.PAPER);
        var meta = item.getItemMeta();
        meta.displayName(Component.text("Knowledge Chip · " + selection.id().value()));
        meta.lore(List.of(Component.text("Protocol hash " + selection.protocol().metadata().hash().substring(0, 16)),
                Component.text("Pinned protocol schema v1"), Component.text("Simulation messaging only")));
        meta.getPersistentDataContainer().set(chipProtocolKey, PersistentDataType.STRING, selection.id().value());
        meta.getPersistentDataContainer().set(chipHashKey, PersistentDataType.STRING, selection.protocol().metadata().hash());
        item.setItemMeta(meta);
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(item);
        if (!overflow.isEmpty()) throw new IllegalArgumentException("Inventory has no room for the protocol chip");
        player.sendMessage("已生成绑定 " + selection.id().value() + " 的知识芯片；使用 /genesis radio join 安装到虚拟端点。");
    }

    private java.util.Set<VirtualMessageBus.ProtocolKey> installedProtocols(Player player) {
        java.util.Set<VirtualMessageBus.ProtocolKey> installed = new java.util.HashSet<>();
        Map<String, ProtocolSelection> catalog = protocolCatalog();
        java.util.Arrays.stream(player.getInventory().getContents())
                .filter(item -> item != null && item.hasItemMeta())
                .map(ItemStack::getItemMeta)
                .forEach(meta -> {
                    String id = meta.getPersistentDataContainer().get(chipProtocolKey, PersistentDataType.STRING);
                    String hash = meta.getPersistentDataContainer().get(chipHashKey, PersistentDataType.STRING);
                    ProtocolSelection selection = catalog.get(id);
                    if (selection != null && selection.protocol().metadata().hash().equals(hash))
                        installed.add(new VirtualMessageBus.ProtocolKey(selection.id(), hash));
                });
        if (installed.isEmpty()) throw new IllegalArgumentException("背包中需要有效协议知识芯片；使用 /genesis radio chip [protocol ID]");
        return java.util.Set.copyOf(installed);
    }

    private void leave(Player player, String[] args) {
        requireLength(args, 1);
        service.leave(player.getUniqueId());
        player.sendMessage("已离开虚拟无线网络；未确认消息保留在收件箱。");
    }

    private void send(Player player, String[] args) throws java.io.IOException {
        var command = RadioSendCommand.parse(args);
        var submission = service.send(player.getUniqueId(), command.protocol(), command.recipient(), command.sequence(), command.payload());
        player.sendMessage(submission.queued() ? "消息已排入 tick=" + (service.tick() + 1) + " 可领取的虚拟信箱。"
                : "重复消息已识别，未重复投递。");
    }

    private void inbox(Player player, String[] args) throws java.io.IOException {
        if (args.length > 2) throw new IllegalArgumentException("用法：/genesis radio inbox [limit]");
        int limit = args.length == 2 ? integer(args[1]) : 16;
        service.inbox(player.getUniqueId(), limit).forEach(player::sendMessage);
    }

    private void acknowledge(Player player, String[] args) throws java.io.IOException {
        boolean acknowledged = service.acknowledge(player.getUniqueId(), RadioAcknowledgement.parse(args));
        player.sendMessage(acknowledged ? "消息已确认。" : "消息此前已确认。");
    }

    private void status(Player player, String[] args) {
        requireLength(args, 1);
        player.sendMessage("虚拟无线 tick=" + service.tick() + " · participants=" + service.participantCount()
                + "/" + VirtualMessageBus.MAX_PARTICIPANTS + " · retained=" + service.retainedEvidenceCount()
                + "/" + VirtualMessageBus.MAX_RETAINED_EVIDENCE
                + " · archives=" + service.archiveCount() + "/" + RadioPersistence.MAX_ARCHIVES
                + " · messageBudget=" + plugin.getConfig().getInt("simulation.messages-per-step"));
        player.sendMessage("协议 " + RadioProtocol.ID.value() + " · hash=" + RadioProtocol.protocol().metadata().hash().substring(0, 12));
        player.sendMessage("Service protocol " + ServiceContractDefinition.PROTOCOL_ID.value() + " · hash="
                + ServiceContractDefinition.protocol().metadata().hash().substring(0, 12) + " · active contracts=" + service.activeContracts());
    }

    private void contractStatus(Player player, String[] args) {
        requireLength(args, 2);
        ContractRuntime.ContractState state = service.contract(player.getUniqueId(), args[1]);
        player.sendMessage("合同 " + state.id() + " · " + state.state() + " · units=" + state.fulfilledUnits()
                + "/" + state.requestedUnits() + " · opened=" + state.createdAt() + " · expires=" + state.expiresAt());
    }

    private void rotate(Player player, String[] args) throws java.io.IOException {
        requireLength(args, 1);
        if (!player.hasPermission("stargenesis.radio.manage"))
            throw new SecurityException("permission denied (stargenesis.radio.manage)");
        int count = service.rotate();
        player.sendMessage("无线日志已归档为第 " + count + " 段；旧消息和契约身份继续保留，新日志已开启。");
    }

    private void help(Player player) {
        player.sendMessage("/genesis radio chip [genesis:radio|genesis:service] | join <network> <x> <y> <z> <range> | leave | status");
        player.sendMessage("/genesis radio send <recipient UUID> <sequence> <channel> <value>");
        player.sendMessage("/genesis radio send contract <recipient UUID> <sequence> <contract> <offer|accept|fulfill|reject|cancel> <units>");
        player.sendMessage("/genesis radio inbox [limit] | ack <sender UUID> <message tick> <sequence> [genesis:radio|genesis:service] | contract <contract ID>");
        if (player.hasPermission("stargenesis.radio.manage"))
            player.sendMessage("/genesis radio rotate · 仅在无待确认消息及活跃契约时归档，旧身份不可复用。");
        player.sendMessage("虚拟无线使用 schema 校验与 tick 投递，不调用 Minecraft 聊天、世界或玩家库存。");
    }

    private int integer(String value) { return Integer.parseInt(value); }

    private ProtocolSelection protocol(String id) {
        ProtocolSelection selection = protocolCatalog().get(id);
        if (selection == null) throw new IllegalArgumentException("未知或未安装 protocol " + id);
        return selection;
    }

    private Map<String, ProtocolSelection> protocolCatalog() {
        return Map.of(
                RadioProtocol.ID.value(), new ProtocolSelection(RadioProtocol.ID, RadioProtocol.protocol()),
                ServiceContractDefinition.PROTOCOL_ID.value(), new ProtocolSelection(
                        ServiceContractDefinition.PROTOCOL_ID, ServiceContractDefinition.protocol()));
    }

    private record ProtocolSelection(dev.stargenesis.api.design.DesignId id, Protocol protocol) { }

    private void requireLength(String[] args, int expected) {
        if (args.length != expected) throw new IllegalArgumentException("参数数量不正确；使用 /genesis radio 查看用法");
    }
}
