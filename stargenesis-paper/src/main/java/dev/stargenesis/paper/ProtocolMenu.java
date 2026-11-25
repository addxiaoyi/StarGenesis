package dev.stargenesis.paper;

import dev.stargenesis.api.design.DesignDocument;
import dev.stargenesis.design.DesignCodec;
import dev.stargenesis.design.Protocol;
import dev.stargenesis.design.ProtocolPayloadCodec;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class ProtocolMenu implements Listener {
    private static final int SIZE = 27;
    private static final int CHIP_SLOT = 11;
    private static final int INBOX_SLOT = 13;
    private static final int ACTION_SLOT = 15;
    private static final int CLOSE_SLOT = 22;

    private final JavaPlugin plugin;
    private final NamespacedKey stateKey;
    private final Protocol starterProtocol;
    private final ProtocolPayloadCodec payloadCodec = new ProtocolPayloadCodec();
    private final ProtocolNetworkService network;
    private final ProtocolInboxMenu inboxMenu;

    ProtocolMenu(JavaPlugin plugin, ProtocolNetworkService network, ProtocolInboxMenu inboxMenu) {
        this.plugin = plugin;
        this.network = network;
        this.inboxMenu = inboxMenu;
        stateKey = new NamespacedKey(plugin, "protocol-chips-v1");
        starterProtocol = loadStarterProtocol();
        if (!starterProtocol.metadata().id().value().equals(ProtocolChipState.STARTER_PROTOCOL_ID))
            throw new IllegalStateException("Bundled starter protocol identifier changed unexpectedly");
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        try {
            network.playerJoined(event.getPlayer(), loadState(event.getPlayer()));
        } catch (IllegalArgumentException failure) {
            plugin.getLogger().warning("Rejected protocol chip checkpoint for " + event.getPlayer().getUniqueId()
                    + ": " + failure.getMessage());
            event.getPlayer().sendMessage(Component.text("协议芯片存档无效，请使用 /genesis protocol 重置后重新解锁。"));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        network.playerQuit(event.getPlayer().getUniqueId());
    }

    void execute(Player player, String[] args) {
        if (args.length == 1) {
            open(player);
        } else if (args.length == 2 && args[1].equalsIgnoreCase("reset")) {
            reset(player);
        } else if (args.length == 2 && args[1].equalsIgnoreCase("inbox")) {
            inboxMenu.open(player);
        } else if (args.length == 3 && args[1].equalsIgnoreCase("inbox")
                && args[2].equalsIgnoreCase("clear")) {
            inboxMenu.clear(player);
        } else if (args.length == 4 && args[1].equalsIgnoreCase("send")) {
            send(player, args[2], args[3]);
        } else if (args.length == 5 && args[1].equalsIgnoreCase("relay")) {
            relay(player, args[2], args[3], args[4]);
        } else {
            player.sendMessage("Usage: /genesis protocol [send <player> <sequence>|relay <server> <player-uuid> <sequence>|inbox [clear]]");
        }
    }

    void open(Player player) {
        if (!player.hasPermission("stargenesis.protocol")) {
            player.sendMessage(Component.text("你没有使用协议芯片的权限。"));
            return;
        }
        try {
            ProtocolChipState state = loadState(player);
            ProtocolHolder holder = new ProtocolHolder(player.getUniqueId(), state);
            Inventory inventory = Bukkit.createInventory(holder, SIZE, Component.text("协议芯片"));
            holder.inventory = inventory;
            inventory.setContents(render(state));
            player.openInventory(inventory);
        } catch (IllegalArgumentException failure) {
            player.sendMessage(Component.text("无法读取协议芯片存档：" + failure.getMessage()));
            plugin.getLogger().warning("Rejected protocol chip checkpoint for " + player.getUniqueId()
                    + ": " + failure.getMessage());
        }
    }

    private void reset(Player player) {
        ProtocolChipState state = ProtocolChipState.empty(player.getUniqueId());
        player.getPersistentDataContainer().set(stateKey, PersistentDataType.BYTE_ARRAY,
                ProtocolChipStateCodec.encode(state));
        network.playerJoined(player, state);
        player.sendMessage(Component.text("协议芯片已重置。入门协议可在界面中重新解锁。"));
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory inventory = event.getView().getTopInventory();
        if (!(inventory.getHolder() instanceof ProtocolHolder holder)) return;
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player) || !holder.playerId.equals(player.getUniqueId())) return;
        if (!player.hasPermission("stargenesis.protocol")) {
            player.closeInventory();
            return;
        }
        int slot = event.getRawSlot();
        if (slot == INBOX_SLOT) {
            inboxMenu.open(player);
            return;
        }
        if (slot == CLOSE_SLOT) {
            player.closeInventory();
            return;
        }
        if (slot != ACTION_SLOT) return;
        try {
            String protocolId = starterProtocol.metadata().id().value();
            if (!holder.state.isUnlocked(protocolId)) {
                holder.state = holder.state.unlock(protocolId);
                player.sendMessage(Component.text("已解锁公开入门协议芯片：" + protocolId));
            } else if (holder.state.equippedProtocolId().equals(protocolId)) {
                holder.state = holder.state.unequip();
                player.sendMessage(Component.text("已卸下协议芯片。"));
            } else {
                holder.state = holder.state.equip(protocolId);
                player.sendMessage(Component.text("已装备协议芯片：" + protocolId));
            }
            player.getPersistentDataContainer().set(stateKey, PersistentDataType.BYTE_ARRAY,
                    ProtocolChipStateCodec.encode(holder.state));
            inventory.setContents(render(holder.state));
        } catch (IllegalArgumentException | IllegalStateException failure) {
            player.sendMessage(Component.text("协议芯片状态未更改：" + failure.getMessage()));
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof ProtocolHolder) event.setCancelled(true);
    }

    ProtocolChipState loadState(Player player) {
        byte[] encoded = player.getPersistentDataContainer().get(stateKey, PersistentDataType.BYTE_ARRAY);
        if (encoded == null) {
            ProtocolChipState state = ProtocolChipState.empty(player.getUniqueId());
            player.getPersistentDataContainer().set(stateKey, PersistentDataType.BYTE_ARRAY,
                    ProtocolChipStateCodec.encode(state));
            return state;
        }
        return ProtocolChipStateCodec.decode(encoded, player.getUniqueId());
    }

    private void send(Player sender, String recipientName, String sequence) {
        ProtocolChipState senderState;
        String protocolId = starterProtocol.metadata().id().value();
        try {
            senderState = loadState(sender);
            if (!senderState.equippedProtocolId().equals(protocolId)) {
                sender.sendMessage(Component.text("请先在 /genesis protocol 中解锁并装备入门芯片。"));
                return;
            }
            String payload = payloadCodec.encode(starterProtocol, Map.of("sequence", sequence));
            Player recipient = plugin.getServer().getPlayerExact(recipientName);
            if (recipient == null) {
                sender.sendMessage(Component.text("目标玩家当前不在线。"));
                return;
            }
            UUID senderId = sender.getUniqueId();
            String senderDisplayName = sender.getName();
            recipient.getScheduler().execute(plugin, () -> {
                try {
                    if (!recipient.hasPermission("stargenesis.protocol")) {
                        network.feedback(senderId, "目标玩家没有接收协议消息的权限。");
                        return;
                    }
                    ProtocolChipState recipientState = loadState(recipient);
                    if (!recipientState.equippedProtocolId().equals(protocolId)) {
                        network.feedback(senderId, "目标玩家尚未装备兼容的协议芯片。");
                        return;
                    }
                    network.send(senderId, senderDisplayName, senderState, recipient.getUniqueId(),
                            recipientState, protocolId, payload);
                } catch (IllegalArgumentException failure) {
                    network.feedback(senderId, "目标玩家的协议芯片存档无效。");
                }
            }, () -> network.feedback(senderId, "目标玩家已离线，消息未发送。"), 1L);
        } catch (IllegalArgumentException failure) {
            sender.sendMessage(Component.text("协议消息无效：" + failure.getMessage()));
        }
    }

    private void relay(Player sender, String targetServer, String recipientValue, String sequence) {
        if (!ProtocolRelayPacket.validServerId(targetServer)) {
            sender.sendMessage(Component.text("目标服务器 ID 格式无效。"));
            return;
        }
        final UUID recipientId;
        try {
            recipientId = UUID.fromString(recipientValue);
            if (!recipientId.toString().equalsIgnoreCase(recipientValue))
                throw new IllegalArgumentException("Non-canonical UUID");
        } catch (IllegalArgumentException failure) {
            sender.sendMessage(Component.text("请提供目标玩家的完整 UUID。"));
            return;
        }
        try {
            String protocolId = starterProtocol.metadata().id().value();
            ProtocolChipState senderState = loadState(sender);
            if (!senderState.equippedProtocolId().equals(protocolId)) {
                sender.sendMessage(Component.text("请先在 /genesis protocol 中解锁并装备入门芯片。"));
                return;
            }
            String payload = payloadCodec.encode(starterProtocol, Map.of("sequence", sequence));
            network.sendRelay(sender.getUniqueId(), sender.getName(), senderState, recipientId,
                    targetServer, protocolId, payload);
        } catch (IllegalArgumentException | IllegalStateException failure) {
            sender.sendMessage(Component.text("跨服协议消息无效：" + failure.getMessage()));
        }
    }

    boolean receiveRelay(Player recipient, ProtocolRelayPacket packet) {
        if (!recipient.getUniqueId().equals(packet.recipientId())
                || !recipient.hasPermission("stargenesis.protocol")
                || !starterProtocol.metadata().id().value().equals(packet.protocolId())) return false;
        try {
            ProtocolChipState state = loadState(recipient);
            if (!state.equippedProtocolId().equals(packet.protocolId())) return false;
            payloadCodec.decode(starterProtocol, packet.payload());
            long now = System.currentTimeMillis();
            long sentAt = Math.max(0, packet.createdAtMillis());
            long expiresAt = Math.max(sentAt + 1, packet.expiresAtMillis());
            long sequence = packet.messageId().getLeastSignificantBits() & Long.MAX_VALUE;
            ProtocolInboxEntry entry = new ProtocolInboxEntry(
                    new dev.stargenesis.sim.ProtocolMessage(sequence, sentAt, expiresAt,
                            new dev.stargenesis.api.sim.AgentId(packet.senderId()),
                            new dev.stargenesis.api.sim.AgentId(packet.recipientId()), packet.protocolId(), packet.payload()),
                    packet.senderName(), packet.messageId());
            return inboxMenu.appendRelay(recipient, entry, packet.messageId(), packet.expiresAtMillis(), now);
        } catch (IllegalArgumentException | IllegalStateException failure) {
            plugin.getLogger().warning("Rejected relayed protocol message for " + recipient.getUniqueId()
                    + ": " + failure.getMessage());
            return false;
        }
    }

    private ItemStack[] render(ProtocolChipState state) {
        ItemStack[] contents = new ItemStack[SIZE];
        String protocolId = starterProtocol.metadata().id().value();
        boolean unlocked = state.isUnlocked(protocolId);
        boolean equipped = state.equippedProtocolId().equals(protocolId);
        List<String> lore = new ArrayList<>();
        lore.add("最大载荷 " + starterProtocol.maxPayloadBytes() + " 字节");
        starterProtocol.fields().stream().map(field -> field.name() + " · " + field.type()
                + (field.required() ? " · 必需" : " · 可选")).forEach(lore::add);
        contents[CHIP_SLOT] = item(Material.ENCHANTED_BOOK, "入门协议芯片 · " + protocolId, lore);
        contents[INBOX_SLOT] = item(Material.WRITABLE_BOOK, "打开协议收件箱", List.of("/genesis protocol inbox"));
        contents[ACTION_SLOT] = item(equipped ? Material.LIME_DYE : unlocked ? Material.WRITABLE_BOOK : Material.KNOWLEDGE_BOOK,
                equipped ? "卸下芯片" : unlocked ? "装备芯片" : "解锁公开芯片",
                List.of(equipped ? "当前装备 " + protocolId : unlocked ? "当前未装备" : "公开入门协议无需消耗物品"));
        contents[CLOSE_SLOT] = item(Material.OAK_DOOR, "关闭", List.of());
        return contents;
    }

    private static Protocol loadStarterProtocol() {
        try (var input = ProtocolMenu.class.getResourceAsStream("/examples/protocol.json")) {
            if (input == null) throw new IllegalStateException("Missing bundled starter protocol");
            DesignDocument document = new DesignCodec().load(new String(input.readAllBytes(), StandardCharsets.UTF_8));
            if (document instanceof Protocol protocol) return protocol;
            throw new IllegalStateException("Bundled starter design is not a protocol");
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot read bundled starter protocol", failure);
        }
    }

    private static ItemStack item(Material material, String title, List<String> lore) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(Component.text(title));
        meta.lore(lore.stream().map(Component::text).toList());
        stack.setItemMeta(meta);
        return stack;
    }

    private static final class ProtocolHolder implements InventoryHolder {
        private final UUID playerId;
        private ProtocolChipState state;
        private Inventory inventory;

        private ProtocolHolder(UUID playerId, ProtocolChipState state) {
            this.playerId = playerId;
            this.state = state;
        }

        @Override public Inventory getInventory() { return inventory; }
    }
}
