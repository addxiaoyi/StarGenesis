package dev.stargenesis.paper;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/** Relays eligible member commands to the one server that owns the factory checkpoint. */
final class FactoryCommandRelayService {
    private static final long RETRY_MILLIS = 5_000;
    private static final String TRUNCATION_NOTICE = "\n远程结果过长，输出已截断。";

    private final JavaPlugin plugin;
    private final ProtocolRelaySettings settings;
    private final boolean relayEnabled;
    private final FactoryService factoryService;
    private final FactoryRelayOutboxStore outbox;
    private final FactoryRelayReceiptStore receipts;
    private final Map<UUID, Long> lastDispatch = new HashMap<>();
    private final Set<UUID> activeRequests = new HashSet<>();
    private ProtocolNetworkService transport;

    FactoryCommandRelayService(JavaPlugin plugin, ProtocolRelaySettings settings,
                               FactoryService factoryService, boolean relayEnabled) throws IOException {
        this.plugin = plugin;
        this.settings = settings;
        this.factoryService = factoryService;
        this.relayEnabled = relayEnabled;
        outbox = new FactoryRelayOutboxStore(plugin.getDataFolder().toPath());
        receipts = factoryService.relayReceipts();
    }

    boolean enabled() {
        return relayEnabled && settings.enabled();
    }

    List<String> trustedPeers() {
        return enabled() ? settings.trustedServers().stream().sorted().toList() : List.of();
    }

    void attach(ProtocolNetworkService network) {
        transport = network;
        if (enabled()) {
            plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin,
                    task -> dispatchPending(), 20, 20);
        }
    }

    void execute(UUID actorId, String targetServer, String[] arguments, Consumer<String> reply) {
        if (!enabled()) {
            reply.accept("跨服工厂命令需要启用 protocol-relay 并配置受信 peer。");
            return;
        }
        if (!settings.trustedServers().contains(targetServer)) {
            reply.accept("目标服务器不在受信 peer 列表中。");
            return;
        }
        final List<String> command;
        try {
            command = FactoryRelayCommandRules.validate(arguments);
        } catch (IllegalArgumentException failure) {
            reply.accept("远程工厂命令无效：" + failure.getMessage());
            return;
        }
        if (actorId == null) {
            reply.accept("跨服工厂命令需要玩家身份。");
            return;
        }
        plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
            UUID requestId = UUID.randomUUID();
            FactoryRelayPacket request = FactoryRelayPacket.request(settings.serverId(), targetServer,
                    requestId, actorId, command);
            try {
                byte[] encoded = FactoryRelayPacketCodec.encode(request, settings.secretFor(targetServer));
                outbox.save(requestId, encoded);
                reply.accept("跨服工厂请求已持久排队，requestId=" + requestId + "。等待目标服务器处理。");
                dispatchPending();
            } catch (IOException | IllegalArgumentException | IllegalStateException failure) {
                reply.accept("跨服工厂请求未能排队：" + failure.getMessage());
            }
        });
    }

    void receive(byte[] encoded) {
        if (!enabled() || !FactoryRelayPacketCodec.isPacket(encoded)) return;
        final AuthenticatedPacket authenticated;
        try {
            authenticated = authenticate(encoded);
        } catch (IllegalArgumentException failure) {
            plugin.getLogger().warning("Rejected cross-server factory packet: " + failure.getMessage());
            return;
        }
        FactoryRelayPacket packet = authenticated.packet();
        if (packet.kind() == FactoryRelayPacket.Kind.REQUEST) receiveRequest(packet, authenticated.peer());
        else receiveResponse(packet, authenticated.peer());
    }

    private void receiveRequest(FactoryRelayPacket request, String peer) {
        try {
            FactoryRelayCommandRules.validate(request.arguments().toArray(String[]::new));
        } catch (IllegalArgumentException failure) {
            sendResponse(peer, request, "远程工厂命令无效：" + failure.getMessage());
            return;
        }
        String fingerprint = FactoryRelayPacketCodec.fingerprint(request);
        FactoryRelayReceiptStore.Receipt receipt = receipts.find(request.requestId()).orElse(null);
        if (receipt != null && !receipt.digest().equals(fingerprint)) {
            sendResponse(peer, request, "远程请求 ID 已绑定到另一组命令。");
            return;
        }
        if (receipt != null && receipt.state() == FactoryRelayReceiptStore.State.DONE) {
            sendResponse(peer, request, receipt.response());
            return;
        }
        if (!activeRequests.add(request.requestId())) return;
        factoryService.executeRelayed(request, result -> completeRequest(request, peer, result));
    }

    private void completeRequest(FactoryRelayPacket request, String peer, String result) {
        activeRequests.remove(request.requestId());
        if (result != null) sendResponse(peer, request, result);
    }

    private void receiveResponse(FactoryRelayPacket response, String peer) {
        try {
            FactoryRelayOutboxStore.PendingRequest pending = outbox.load(response.requestId()).orElse(null);
            if (pending == null) return;
            FactoryRelayPacket request = decodePending(pending);
            if (!request.targetServer().equals(peer) || !response.sourceServer().equals(peer)
                    || !response.targetServer().equals(settings.serverId())
                    || !response.actorId().equals(request.actorId())) return;
            if (transport == null || !transport.hasOnlinePlayer(response.actorId())) return;
            outbox.remove(response.requestId());
            lastDispatch.remove(response.requestId());
            transport.feedback(response.actorId(), response.response());
        } catch (IOException | IllegalArgumentException failure) {
            plugin.getLogger().warning("Could not process a factory relay response: " + failure.getMessage());
        }
    }

    private void sendResponse(String peer, FactoryRelayPacket request, String message) {
        if (transport == null) return;
        FactoryRelayPacket response = FactoryRelayPacket.response(settings.serverId(), peer,
                request.requestId(), request.actorId(), boundResponse(message));
        transport.forwardTrustedServer(peer,
                FactoryRelayPacketCodec.encode(response, settings.secretFor(peer)));
    }

    private AuthenticatedPacket authenticate(byte[] encoded) {
        for (String peer : settings.trustedServers()) {
            try {
                FactoryRelayPacket packet = FactoryRelayPacketCodec.decode(encoded, settings.secretFor(peer),
                        settings.serverId(), Set.of(peer));
                return new AuthenticatedPacket(peer, packet);
            } catch (IllegalArgumentException rejectedForThisPeer) {
                // The packet must authenticate as exactly one configured server peer.
            }
        }
        throw new IllegalArgumentException("No configured peer authenticated the factory relay packet");
    }

    private void dispatchPending() {
        if (!enabled() || transport == null) return;
        final List<FactoryRelayOutboxStore.PendingRequest> pending;
        try {
            pending = outbox.loadAll();
        } catch (IOException failure) {
            plugin.getLogger().warning("Could not read factory relay outbox: " + failure.getMessage());
            return;
        }
        long now = System.currentTimeMillis();
        for (FactoryRelayOutboxStore.PendingRequest record : pending) {
            if (now - lastDispatch.getOrDefault(record.requestId(), 0L) < RETRY_MILLIS) continue;
            try {
                FactoryRelayPacket request = decodePending(record);
                if (transport.forwardTrustedServer(request.targetServer(), record.packet()))
                    lastDispatch.put(record.requestId(), now);
            } catch (IllegalArgumentException invalid) {
                plugin.getLogger().warning("Retained an invalid factory relay outbox record "
                        + record.requestId() + ": " + invalid.getMessage());
            }
        }
    }

    private FactoryRelayPacket decodePending(FactoryRelayOutboxStore.PendingRequest record) {
        for (String target : settings.trustedServers()) {
            try {
                FactoryRelayPacket packet = FactoryRelayPacketCodec.decode(record.packet(), settings.secretFor(target),
                        target, Set.of(settings.serverId()));
                if (!packet.requestId().equals(record.requestId())
                        || packet.kind() != FactoryRelayPacket.Kind.REQUEST)
                    throw new IllegalArgumentException("Factory relay outbox identity does not match its packet");
                return packet;
            } catch (IllegalArgumentException rejectedForThisPeer) {
                // Try the next configured destination without discarding pending work.
            }
        }
        throw new IllegalArgumentException("No configured peer authenticates the pending factory request");
    }

    static String boundResponse(String response) {
        if (response == null || response.isBlank()) return "远程工厂操作未返回结果。";
        if (response.getBytes(StandardCharsets.UTF_8).length <= FactoryRelayPacket.MAX_RESPONSE_BYTES) return response;
        int byteBudget = FactoryRelayPacket.MAX_RESPONSE_BYTES
                - TRUNCATION_NOTICE.getBytes(StandardCharsets.UTF_8).length;
        StringBuilder bounded = new StringBuilder();
        int bytes = 0;
        for (int offset = 0; offset < response.length();) {
            int codePoint = response.codePointAt(offset);
            int codePointBytes = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + codePointBytes > byteBudget) break;
            bounded.appendCodePoint(codePoint);
            bytes += codePointBytes;
            offset += Character.charCount(codePoint);
        }
        return bounded.append(TRUNCATION_NOTICE).toString();
    }

    private record AuthenticatedPacket(String peer, FactoryRelayPacket packet) { }
}
