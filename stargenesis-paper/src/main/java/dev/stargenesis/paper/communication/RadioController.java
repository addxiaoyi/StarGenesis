package dev.stargenesis.paper.communication;

import dev.stargenesis.design.ProtocolMessageCodec;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/** Paper command adapter for a virtual, protocol-bound tick radio. */
public final class RadioController implements Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final VirtualMessageBus bus;
    private ContractRuntime contracts;
    private final RadioPersistence persistence;
    private final NamespacedKey chipProtocolKey;
    private final NamespacedKey chipHashKey;
    private final Object stateLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ScheduledTask ticker;
    private boolean dirty;

    public RadioController(JavaPlugin plugin, int messagesPerTick) throws java.io.IOException {
        this.plugin = plugin;
        persistence = new RadioPersistence(plugin.getDataFolder().toPath().resolve("radio-state.json"),
                RadioProtocol.protocol(), ServiceContractDefinition.protocol(), ServiceContractDefinition.contract());
        Optional<RadioPersistence.Snapshot> saved = persistence.load();
        long initialTick = saved.map(snapshot -> snapshot.radio().tick()).orElse(0L);
        bus = new VirtualMessageBus(initialTick, messagesPerTick);
        if (saved.isPresent()) {
            RadioPersistence.Snapshot snapshot = saved.get();
            bus.restore(snapshot.radio(), pinnedProtocols());
            contracts = ContractRuntime.restore(ServiceContractDefinition.contract(), ServiceContractDefinition.protocol(),
                    ContractRuntime.MAX_RETAINED_CONTRACTS, snapshot.contracts());
            if (bus.tick() != contracts.tick()) throw new java.io.IOException("Radio and contract ticks are inconsistent");
            plugin.getLogger().info("Restored P4 radio state | tick=" + bus.tick() + " | pending="
                    + snapshot.radio().pending().size() + " | contracts=" + snapshot.contracts().states().size());
        } else {
            contracts = new ContractRuntime(ServiceContractDefinition.contract(), ServiceContractDefinition.protocol(), 0,
                    ContractRuntime.MAX_RETAINED_CONTRACTS);
        }
        chipProtocolKey = new NamespacedKey(plugin, "knowledge_chip_protocol");
        chipHashKey = new NamespacedKey(plugin, "knowledge_chip_hash");
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        ticker = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, task -> {
            synchronized (stateLock) {
                if (closed.get()) { task.cancel(); return; }
                try {
                    long nextTick = Math.addExact(bus.tick(), 1);
                    bus.advance(nextTick);
                    contracts.advance(nextTick);
                    dirty = true;
                    if (nextTick % 20 == 0) persist();
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
                    default -> help(player);
                }
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
            if (!dirty) return;
            try {
                persist();
            } catch (RuntimeException failure) {
                plugin.getLogger().log(Level.SEVERE, "Could not persist P4 radio state during shutdown", failure);
            }
        }
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        synchronized (stateLock) {
            bus.unregisterIfRegistered(event.getPlayer().getUniqueId());
        }
    }

    private void join(Player player, String[] args) {
        requireLength(args, 6);
        var installedProtocols = installedProtocols(player);
        VirtualMessageBus.Endpoint endpoint = new VirtualMessageBus.Endpoint(args[1], integer(args[2]),
                integer(args[3]), integer(args[4]), integer(args[5]));
        bus.register(player.getUniqueId(), endpoint, installedProtocols);
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
        bus.unregister(player.getUniqueId());
        player.sendMessage("已离开虚拟无线网络；未确认消息保留在收件箱。");
    }

    private void send(Player player, String[] args) {
        boolean radioMessage = args.length == 5;
        if (!radioMessage && args.length != 7)
            throw new IllegalArgumentException("用法：/genesis radio send <recipient> <sequence> <channel> <value> | send contract <recipient> <sequence> <contract> <action> <units>");
        int offset = radioMessage ? 0 : 1;
        ProtocolSelection selection = protocol(radioMessage ? RadioProtocol.ID.value() : args[1]);
        UUID recipient = uuid(args[1 + offset]);
        int sequence = integer(args[2 + offset]);
        Map<String, String> payload;
        if (radioMessage) {
            payload = Map.of("sequence", Integer.toString(sequence), "channel", args[3], "value", args[4]);
        } else {
            if (!selection.id().equals(ServiceContractDefinition.PROTOCOL_ID))
                throw new IllegalArgumentException("该发送格式用于 genesis:service 协议");
            payload = Map.of("contract", args[4], "action", args[5], "units", args[6]);
        }
        var submission = bus.sendNow(player.getUniqueId(), selection.protocol(), recipient, sequence, payload);
        if (submission.queued()) {
            dirty = true;
            persist();
        }
        player.sendMessage(submission.queued() ? "消息已排入 tick=" + (bus.tick() + 1) + " 可领取的虚拟信箱。"
                : "重复消息已识别，未重复投递。");
    }

    private void inbox(Player player, String[] args) {
        if (args.length > 2) throw new IllegalArgumentException("用法：/genesis radio inbox [limit]");
        int limit = args.length == 2 ? integer(args[1]) : 16;
        List<ProtocolMessageCodec.Message> messages = bus.poll(player.getUniqueId(), limit);
        List<String> output = new java.util.ArrayList<>();
        for (ProtocolMessageCodec.Message message : messages) {
            boolean serviceMessage = message.protocol().equals(ServiceContractDefinition.PROTOCOL_ID)
                    && message.protocolHash().equals(ServiceContractDefinition.protocol().metadata().hash());
            if (serviceMessage) {
                ContractRuntime.Snapshot before = contracts.snapshot();
                boolean received = false;
                try {
                    boolean changed = contracts.receive(message);
                    received = true;
                    boolean acked = bus.acknowledge(player.getUniqueId(), message);
                    dirty |= changed || acked;
                    ContractRuntime.ContractState state = contracts.state(message.payload().get("contract"));
                    output.add("Contract " + message.payload().get("contract") + " · "
                            + (state == null ? "unknown" : state.state()) + " · units="
                            + (state == null ? 0 : state.fulfilledUnits()) + "/" + (state == null ? 0 : state.requestedUnits())
                            + " · " + (changed ? "applied" : "duplicate") + " · " + (acked ? "acknowledged" : "already acknowledged"));
                } catch (RuntimeException invalid) {
                    if (received) contracts = ContractRuntime.restore(ServiceContractDefinition.contract(),
                            ServiceContractDefinition.protocol(), ContractRuntime.MAX_RETAINED_CONTRACTS, before);
                    output.add("Contract message retained for review · " + invalid.getMessage());
                }
            } else output.add("from=" + message.sender() + " · protocol=" + message.protocol().value()
                    + " · tick=" + message.tick() + " · seq=" + message.sequence() + " · " + message.payload());
        }
        if (dirty) persist();
        player.sendMessage("虚拟信箱 " + messages.size() + " 条 · tick=" + bus.tick()
                + " · 未确认=" + bus.pendingCount(player.getUniqueId()));
        output.forEach(player::sendMessage);
    }

    private void acknowledge(Player player, String[] args) {
        requireLength(args, 4);
        UUID sender = uuid(args[1]);
        long tick = Long.parseLong(args[2]);
        int sequence = integer(args[3]);
        ProtocolMessageCodec.Message message = bus.poll(player.getUniqueId(), VirtualMessageBus.MAX_UNACKNOWLEDGED_PER_RECIPIENT)
                .stream().filter(candidate -> candidate.sender().equals(sender) && candidate.tick() == tick
                        && candidate.sequence() == sequence).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("找不到已投递的消息，请先 inbox"));
        boolean acknowledged = bus.acknowledge(player.getUniqueId(), message);
        if (acknowledged) {
            dirty = true;
            persist();
        }
        player.sendMessage(acknowledged ? "消息已确认。" : "消息此前已确认。");
    }

    private void status(Player player, String[] args) {
        requireLength(args, 1);
        player.sendMessage("虚拟无线 tick=" + bus.tick() + " · participants=" + bus.participantCount()
                + "/" + VirtualMessageBus.MAX_PARTICIPANTS + " · retained=" + bus.retainedEvidenceCount()
                + "/" + VirtualMessageBus.MAX_RETAINED_EVIDENCE
                + " · messageBudget=" + plugin.getConfig().getInt("simulation.messages-per-step"));
        player.sendMessage("协议 " + RadioProtocol.ID.value() + " · hash=" + RadioProtocol.protocol().metadata().hash().substring(0, 12));
        player.sendMessage("Service protocol " + ServiceContractDefinition.PROTOCOL_ID.value() + " · hash="
                + ServiceContractDefinition.protocol().metadata().hash().substring(0, 12) + " · active contracts=" + contracts.activeCount());
    }

    private void contractStatus(Player player, String[] args) {
        requireLength(args, 2);
        ContractRuntime.ContractState state = contracts.state(args[1]);
        if (state == null) throw new IllegalArgumentException("未知合同 " + args[1]);
        UUID viewer = player.getUniqueId();
        if (!viewer.equals(state.requester()) && !viewer.equals(state.provider()))
            throw new SecurityException("只有合同参与者可以查看状态");
        player.sendMessage("合同 " + state.id() + " · " + state.state() + " · units=" + state.fulfilledUnits()
                + "/" + state.requestedUnits() + " · opened=" + state.createdAt() + " · expires=" + state.expiresAt());
    }

    private void help(Player player) {
        player.sendMessage("/genesis radio chip [genesis:radio|genesis:service] | join <network> <x> <y> <z> <range> | leave | status");
        player.sendMessage("/genesis radio send <recipient UUID> <sequence> <channel> <value>");
        player.sendMessage("/genesis radio send contract <recipient UUID> <sequence> <contract> <offer|accept|fulfill|reject|cancel> <units>");
        player.sendMessage("/genesis radio inbox [limit] | ack <sender UUID> <message tick> <sequence> | contract <contract ID>");
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

    private Map<VirtualMessageBus.ProtocolKey, Protocol> pinnedProtocols() {
        return Map.of(RadioProtocol.key(), RadioProtocol.protocol(), ServiceContractDefinition.key(),
                ServiceContractDefinition.protocol());
    }

    private void persist() {
        try {
            persistence.save(bus, contracts);
            dirty = false;
        } catch (java.io.IOException failure) {
            closed.set(true);
            ticker.cancel();
            plugin.getLogger().log(Level.SEVERE, "Radio state persistence failed; radio stopped", failure);
            throw new IllegalStateException("Radio stopped because durable state could not be saved", failure);
        }
    }

    private record ProtocolSelection(dev.stargenesis.api.design.DesignId id, Protocol protocol) { }

    private UUID uuid(String value) {
        UUID parsed = UUID.fromString(value);
        if (!parsed.toString().equals(value)) throw new IllegalArgumentException("UUID 必须为规范小写格式");
        return parsed;
    }

    private void requireLength(String[] args, int expected) {
        if (args.length != expected) throw new IllegalArgumentException("参数数量不正确；使用 /genesis radio 查看用法");
    }
}
