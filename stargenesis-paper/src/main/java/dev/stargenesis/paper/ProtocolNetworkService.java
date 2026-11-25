package dev.stargenesis.paper;

import dev.stargenesis.api.sim.AgentId;
import dev.stargenesis.sim.ProtocolDelivery;
import dev.stargenesis.sim.ProtocolMailboxHub;
import dev.stargenesis.sim.ProtocolMessage;
import net.kyori.adventure.text.Component;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Comparator;
import java.util.function.BiPredicate;

/** Owns the shared queue on Paper's global scheduler and persists inboxes on each recipient scheduler. */
final class ProtocolNetworkService implements PluginMessageListener {
    private static final String BUNGEE_CHANNEL = ProtocolRelayProxyFrame.BUNGEE_CHANNEL;
    private static final long MESSAGE_TTL_STEPS = 1_200;
    private static final long RELAY_RETRY_MILLIS = 5_000;

    private final JavaPlugin plugin;
    private final ProtocolInboxStore inboxStore;
    private final UUID sessionId = UUID.randomUUID();
    private final ProtocolRelaySettings relaySettings;
    private final ProtocolRelayOutboxStore relayOutbox;
    private final Map<AgentId, Player> onlinePlayers = new HashMap<>();
    private final Map<MessageIdentity, String> senderNames = new HashMap<>();
    private final Set<AgentId> flushing = new HashSet<>();
    private final Map<UUID, Long> lastRelayAttempts = new HashMap<>();
    private ProtocolMailboxHub hub;
    private BiPredicate<Player, ProtocolRelayPacket> relayReceiver;
    private FactoryCommandRelayService factoryCommandRelay;

    ProtocolNetworkService(JavaPlugin plugin, ProtocolInboxStore inboxStore, ProtocolRelaySettings relaySettings) {
        this.plugin = plugin;
        this.inboxStore = inboxStore;
        this.relaySettings = relaySettings;
        relayOutbox = relaySettings.enabled() ? new ProtocolRelayOutboxStore(plugin.getDataFolder().toPath()) : null;
    }

    void start(BiPredicate<Player, ProtocolRelayPacket> relayReceiver) {
        this.relayReceiver = relayReceiver;
        if (relaySettings.enabled()) {
            plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, BUNGEE_CHANNEL);
            plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, BUNGEE_CHANNEL, this);
            plugin.getLogger().info("Cross-server protocol relay enabled | server=" + relaySettings.serverId()
                    + " | trusted-peers=" + relaySettings.trustedServers().size());
        }
        plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, task -> tick(), 20, 20);
    }

    void stop() {
        if (!relaySettings.enabled()) return;
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, BUNGEE_CHANNEL, this);
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, BUNGEE_CHANNEL);
    }

    void setFactoryCommandRelay(FactoryCommandRelayService relay) {
        factoryCommandRelay = relay;
        relay.attach(this);
    }

    void playerJoined(Player player, ProtocolChipState state) {
        AgentId participant = new AgentId(player.getUniqueId());
        Set<String> protocols = state.unlockedProtocolIds();
        plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
            ProtocolMailboxHub queue = queue();
            onlinePlayers.put(participant, player);
            try {
                queue.register(participant, protocols);
                flush(participant);
            } catch (IllegalArgumentException | IllegalStateException failure) {
                sendTo(participant, "协议服务暂时无法登记玩家：" + failure.getMessage());
            }
        });
    }

    void playerQuit(UUID playerId) {
        AgentId participant = new AgentId(playerId);
        plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
            onlinePlayers.remove(participant);
            if (hub != null) hub.retireIfIdle(participant);
        });
    }

    void send(UUID senderId, String senderName, ProtocolChipState senderState,
              UUID recipientId, ProtocolChipState recipientState, String protocolId, String payload) {
        if (!senderState.ownerId().equals(senderId) || !recipientState.ownerId().equals(recipientId))
            throw new IllegalArgumentException("Protocol chip state belongs to another player");
        AgentId senderAgent = new AgentId(senderId);
        AgentId recipientAgent = new AgentId(recipientId);
        Set<String> senderProtocols = senderState.unlockedProtocolIds();
        Set<String> recipientProtocols = recipientState.unlockedProtocolIds();
        plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
            ProtocolMailboxHub queue = queue();
            try {
                if (!onlinePlayers.containsKey(senderAgent) || !onlinePlayers.containsKey(recipientAgent)) {
                    sendTo(senderAgent, "收件人已离线，请重新发送消息。");
                    return;
                }
                queue.register(senderAgent, senderProtocols);
                queue.register(recipientAgent, recipientProtocols);
                ProtocolDelivery delivery = queue.send(senderAgent, recipientAgent, protocolId, payload,
                        MESSAGE_TTL_STEPS);
                if (delivery.status() != ProtocolDelivery.Status.QUEUED) {
                    sendTo(senderAgent, "协议消息未发送：" + delivery.reason());
                    return;
                }
                senderNames.put(MessageIdentity.of(delivery.message()), senderName);
                sendTo(senderAgent, "消息已排队，等待收件箱保存。");
                flush(recipientAgent);
            } catch (IllegalArgumentException | IllegalStateException failure) {
                sendTo(senderAgent, "协议消息未发送：" + failure.getMessage());
            }
        });
    }

    void sendRelay(UUID senderId, String senderName, ProtocolChipState senderState,
                   UUID recipientId, String targetServer, String protocolId, String payload) {
        if (!relaySettings.enabled()) throw new IllegalStateException("Cross-server protocol relay is disabled");
        if (!senderState.ownerId().equals(senderId) || !senderState.equippedProtocolId().equals(protocolId))
            throw new IllegalArgumentException("Equipped protocol chip does not authorize this relay");
        if (!relaySettings.trustedServers().contains(targetServer))
            throw new IllegalArgumentException("Target server is not in the trusted relay list");
        plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
            long now = System.currentTimeMillis();
            UUID messageId = UUID.randomUUID();
            ProtocolRelayPacket packet;
            try {
                packet = new ProtocolRelayPacket(relaySettings.serverId(), targetServer, messageId,
                        senderId, senderName, recipientId, protocolId, payload, now,
                        Math.addExact(now, ProtocolRelayPacketCodec.MAX_LIFETIME_MILLIS));
                relayOutbox.save(messageId, ProtocolRelayPacketCodec.encode(packet, relaySettings.secretFor(targetServer)));
                sendTo(new AgentId(senderId), "跨服协议消息已进入持久发件箱，等待目标服务器确认。");
                dispatchPendingRelays();
            } catch (IllegalArgumentException | IllegalStateException | IOException failure) {
                sendTo(new AgentId(senderId), "跨服协议消息未能排队：" + failure.getMessage());
            }
        });
    }

    private ProtocolMailboxHub queue() {
        if (hub == null) hub = new ProtocolMailboxHub();
        return hub;
    }

    private void tick() {
        if (hub != null) {
            for (ProtocolDelivery delivery : hub.advance()) {
                senderNames.remove(MessageIdentity.of(delivery.message()));
                sendTo(delivery.message().sender(), "发给 " + delivery.message().recipient().value()
                        + " 的协议消息已过期。");
            }
            for (AgentId recipient : hub.pendingRecipients()) flush(recipient);
            for (AgentId participant : hub.participants()) {
                if (!onlinePlayers.containsKey(participant)) hub.retireIfIdle(participant);
            }
        }
        if (relaySettings.enabled()) dispatchPendingRelays();
    }

    @Override
    public void onPluginMessageReceived(String channel, Player carrier, byte[] message) {
        if (!BUNGEE_CHANNEL.equals(channel) || !relaySettings.enabled()) return;
        try {
            byte[] payload = ProtocolRelayProxyFrame.decode(message);
            plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> receiveRelay(payload));
        } catch (IllegalArgumentException failure) {
            plugin.getLogger().warning("Rejected cross-server protocol proxy frame: " + failure.getMessage());
        }
    }

    private void receiveRelay(byte[] encoded) {
        if (ProtocolRelayAcknowledgementCodec.isAcknowledgement(encoded)) {
            receiveAcknowledgement(encoded);
        } else if (ProtocolRelayPacketCodec.isPacket(encoded)) {
            receivePacket(encoded);
        } else if (FactoryRelayPacketCodec.isPacket(encoded) && factoryCommandRelay != null) {
            factoryCommandRelay.receive(encoded);
        } else {
            plugin.getLogger().warning("Rejected unknown cross-server protocol message frame");
        }
    }

    private void receivePacket(byte[] encoded) {
        final ProtocolRelayPacket packet;
        try {
            packet = decodeFromTrustedPeer(encoded, System.currentTimeMillis());
        } catch (IllegalArgumentException failure) {
            plugin.getLogger().warning("Rejected cross-server protocol packet: " + failure.getMessage());
            return;
        }
        Player recipient = onlinePlayers.get(new AgentId(packet.recipientId()));
        if (recipient == null || relayReceiver == null) return;
        boolean scheduled = recipient.getScheduler().execute(plugin, () -> {
            boolean accepted;
            try {
                accepted = relayReceiver.test(recipient, packet);
            } catch (RuntimeException failure) {
                plugin.getLogger().warning("Cross-server protocol inbox rejected a message: "
                        + failure.getClass().getSimpleName());
                accepted = false;
            }
            boolean result = accepted;
            plugin.getServer().getGlobalRegionScheduler().execute(plugin, () ->
                    sendAcknowledgement(packet, result));
        }, () -> { }, 1L);
        if (!scheduled) return;
    }

    private ProtocolRelayPacket decodeFromTrustedPeer(byte[] encoded, long nowMillis) {
        for (String peer : relaySettings.trustedServers()) {
            try {
                return ProtocolRelayPacketCodec.decode(encoded, relaySettings.secretFor(peer),
                        relaySettings.serverId(), Set.of(peer), nowMillis);
            } catch (IllegalArgumentException rejectedForThisPeer) {
                // The authenticated packet must match exactly one configured peer key.
            }
        }
        throw new IllegalArgumentException("No configured peer authenticated the relay packet");
    }

    private ProtocolRelayAcknowledgement decodeAcknowledgementFromTrustedPeer(byte[] encoded, long nowMillis) {
        for (String peer : relaySettings.trustedServers()) {
            try {
                return ProtocolRelayAcknowledgementCodec.decode(encoded, relaySettings.secretFor(peer),
                        relaySettings.serverId(), Set.of(peer), nowMillis);
            } catch (IllegalArgumentException rejectedForThisPeer) {
                // The authenticated acknowledgement must match exactly one configured peer key.
            }
        }
        throw new IllegalArgumentException("No configured peer authenticated the relay acknowledgement");
    }

    private void sendAcknowledgement(ProtocolRelayPacket packet, boolean accepted) {
        ProtocolRelayAcknowledgement acknowledgement = new ProtocolRelayAcknowledgement(
                relaySettings.serverId(), packet.sourceServer(), packet.messageId(), accepted,
                System.currentTimeMillis());
        sendForward(packet.sourceServer(), ProtocolRelayAcknowledgementCodec.encode(
                acknowledgement, relaySettings.secretFor(packet.sourceServer())));
    }

    private void receiveAcknowledgement(byte[] encoded) {
        final ProtocolRelayAcknowledgement acknowledgement;
        try {
            acknowledgement = decodeAcknowledgementFromTrustedPeer(encoded, System.currentTimeMillis());
        } catch (IllegalArgumentException failure) {
            plugin.getLogger().warning("Rejected cross-server protocol acknowledgement: " + failure.getMessage());
            return;
        }
        try {
            ProtocolRelayOutboxStore.PendingRelay pending = relayOutbox.load(acknowledgement.messageId()).orElse(null);
            if (pending == null) return;
            ProtocolRelayPacket packet = decodePending(pending);
            if (!packet.sourceServer().equals(relaySettings.serverId())
                    || !packet.targetServer().equals(acknowledgement.sourceServer())) return;
            relayOutbox.remove(acknowledgement.messageId());
            lastRelayAttempts.remove(acknowledgement.messageId());
            sendTo(new AgentId(packet.senderId()), acknowledgement.accepted()
                    ? "跨服协议消息已写入目标收件箱。" : "目标服务器拒绝了跨服协议消息。");
        } catch (IOException | IllegalArgumentException failure) {
            plugin.getLogger().warning("Could not acknowledge cross-server protocol packet: " + failure.getMessage());
        }
    }

    private void dispatchPendingRelays() {
        List<Player> carriers = onlinePlayers.values().stream().filter(Player::isOnline)
                .sorted(Comparator.comparing(player -> player.getUniqueId().toString())).toList();
        if (carriers.isEmpty()) return;
        final List<ProtocolRelayOutboxStore.PendingRelay> pending;
        try {
            pending = relayOutbox.loadAll();
        } catch (IOException failure) {
            plugin.getLogger().warning("Could not read cross-server protocol outbox: " + failure.getMessage());
            return;
        }
        long now = System.currentTimeMillis();
        for (ProtocolRelayOutboxStore.PendingRelay record : pending) {
            if (now - lastRelayAttempts.getOrDefault(record.messageId(), 0L) < RELAY_RETRY_MILLIS) continue;
            try {
                ProtocolRelayPacket packet = decodePending(record);
                sendForward(packet.targetServer(), record.packet(), carriers.get(0));
                lastRelayAttempts.put(record.messageId(), now);
            } catch (IllegalArgumentException expiredOrInvalid) {
                try {
                    relayOutbox.remove(record.messageId());
                    lastRelayAttempts.remove(record.messageId());
                } catch (IOException failure) {
                    plugin.getLogger().warning("Could not remove expired relay packet: " + failure.getMessage());
                }
            }
        }
    }

    private ProtocolRelayPacket decodePending(ProtocolRelayOutboxStore.PendingRelay record) {
        for (String target : relaySettings.trustedServers()) {
            try {
                ProtocolRelayPacket packet = ProtocolRelayPacketCodec.decode(record.packet(), relaySettings.secretFor(target),
                        target, Set.of(relaySettings.serverId()), System.currentTimeMillis());
                if (!packet.messageId().equals(record.messageId()))
                    throw new IllegalArgumentException("Outbox identity does not match its signed packet");
                return packet;
            } catch (IllegalArgumentException ignored) {
                // Try the next configured destination. Invalid or expired records are removed if none match.
            }
        }
        throw new IllegalArgumentException("Relay packet is invalid or expired");
    }

    boolean forwardTrustedServer(String targetServer, byte[] payload) {
        if (!relaySettings.enabled() || !relaySettings.trustedServers().contains(targetServer)) return false;
        List<? extends Player> carriers = plugin.getServer().getOnlinePlayers().stream().filter(Player::isOnline)
                .sorted(Comparator.comparing(player -> player.getUniqueId().toString())).toList();
        if (carriers.isEmpty()) return false;
        sendForward(targetServer, payload, carriers.get(0));
        return true;
    }

    private void sendForward(String targetServer, byte[] payload) {
        forwardTrustedServer(targetServer, payload);
    }

    private void sendForward(String targetServer, byte[] payload, Player carrier) {
        byte[] forwarding = ProtocolRelayProxyFrame.encodeBungeeForward(targetServer, payload);
        carrier.getScheduler().execute(plugin,
                () -> carrier.sendPluginMessage(plugin, BUNGEE_CHANNEL, forwarding), () -> { }, 1L);
    }

    private void flush(AgentId recipient) {
        Player player = onlinePlayers.get(recipient);
        if (player == null || !flushing.add(recipient)) return;
        List<ProtocolMessage> messages = hub.pendingMessages(recipient);
        if (messages.isEmpty()) {
            flushing.remove(recipient);
            return;
        }
        List<ProtocolInboxEntry> entries = messages.stream().map(message -> new ProtocolInboxEntry(message,
                senderNames.getOrDefault(MessageIdentity.of(message), shortId(message.sender().value())), sessionId)).toList();
        boolean scheduled = player.getScheduler().execute(plugin, () -> {
            try {
                ProtocolInboxCheckpoint current = inboxStore.load(player);
                ProtocolInboxCheckpoint updated = current.append(entries);
                if (!updated.equals(current)) inboxStore.save(player, updated);
                plugin.getServer().getGlobalRegionScheduler().execute(plugin,
                        () -> finishFlush(recipient, messages, null));
            } catch (IllegalArgumentException | IllegalStateException failure) {
                plugin.getServer().getGlobalRegionScheduler().execute(plugin,
                        () -> finishFlush(recipient, List.of(), failure.getMessage()));
            }
        }, () -> plugin.getServer().getGlobalRegionScheduler().execute(plugin,
                () -> finishFlush(recipient, List.of(), null)), 1L);
        if (!scheduled) {
            flushing.remove(recipient);
            onlinePlayers.remove(recipient, player);
        }
    }

    private void finishFlush(AgentId recipient, List<ProtocolMessage> persisted, String failure) {
        flushing.remove(recipient);
        if (failure == null && !persisted.isEmpty()) {
            try {
                hub.acknowledgePersisted(recipient, persisted);
                persisted.forEach(message -> senderNames.remove(MessageIdentity.of(message)));
                sendTo(recipient, "收件箱新增 " + persisted.size() + " 条协议消息。");
            } catch (IllegalArgumentException | IllegalStateException stateFailure) {
                plugin.getLogger().warning("Could not acknowledge persisted protocol messages for "
                        + recipient.value() + ": " + stateFailure.getMessage());
            }
        } else if (failure != null) {
            plugin.getLogger().warning("Could not persist protocol inbox for " + recipient.value() + ": " + failure);
            sendTo(recipient, "收件箱无法保存待投递消息。请清理或修复 /genesis protocol inbox。");
        }
        if (failure == null && onlinePlayers.containsKey(recipient)) flush(recipient);
        else if (hub != null) hub.retireIfIdle(recipient);
    }

    private void sendTo(AgentId recipient, String message) {
        Player player = onlinePlayers.get(recipient);
        if (player == null) return;
        player.getScheduler().execute(plugin, () -> player.sendMessage(Component.text(message)), () -> { }, 1L);
    }

    void feedback(UUID recipientId, String message) {
        plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
            Player player = plugin.getServer().getPlayer(recipientId);
            if (player == null || !player.isOnline()) return;
            player.getScheduler().execute(plugin,
                    () -> player.sendMessage(Component.text(message)), () -> { }, 1L);
        });
    }

    boolean hasOnlinePlayer(UUID playerId) {
        Player player = plugin.getServer().getPlayer(playerId);
        return player != null && player.isOnline();
    }

    private static String shortId(UUID uuid) {
        return uuid.toString().substring(0, 8);
    }

    private record MessageIdentity(AgentId sender, long sentAt, long sequence) {
        private static MessageIdentity of(ProtocolMessage message) {
            return new MessageIdentity(message.sender(), message.sentAt(), message.sequence());
        }
    }
}
