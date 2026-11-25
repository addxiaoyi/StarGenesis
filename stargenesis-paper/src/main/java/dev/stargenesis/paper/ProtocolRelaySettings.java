package dev.stargenesis.paper;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

record ProtocolRelaySettings(boolean enabled, String serverId, Map<String, byte[]> peerSecrets) {
    ProtocolRelaySettings {
        Map<String, byte[]> copied = new HashMap<>();
        peerSecrets.forEach((peer, secret) -> copied.put(peer, secret.clone()));
        peerSecrets = Map.copyOf(copied);
        if (enabled && (!ProtocolRelayPacket.validServerId(serverId) || peerSecrets.isEmpty() || peerSecrets.size() > 32
                || peerSecrets.entrySet().stream().anyMatch(entry -> !ProtocolRelayPacket.validServerId(entry.getKey())
                || entry.getKey().equals(serverId) || entry.getValue().length < 32 || entry.getValue().length > 128)))
            throw new IllegalArgumentException("Invalid enabled protocol relay configuration");
    }

    @Override public Map<String, byte[]> peerSecrets() {
        Map<String, byte[]> copied = new HashMap<>();
        peerSecrets.forEach((peer, secret) -> copied.put(peer, secret.clone()));
        return Map.copyOf(copied);
    }

    Set<String> trustedServers() { return peerSecrets.keySet(); }

    byte[] secretFor(String peer) {
        byte[] secret = peerSecrets.get(peer);
        if (secret == null) throw new IllegalArgumentException("Protocol relay peer is not configured: " + peer);
        return secret.clone();
    }

    static ProtocolRelaySettings from(org.bukkit.configuration.ConfigurationSection config) {
        boolean enabled = config.getBoolean("protocol-relay.enabled", false);
        if (!enabled) return new ProtocolRelaySettings(false, "disabled", Map.of());
        String serverId = config.getString("protocol-relay.server-id", "");
        org.bukkit.configuration.ConfigurationSection peers = config.getConfigurationSection("protocol-relay.peers");
        if (peers == null || peers.getKeys(false).isEmpty() || peers.getKeys(false).size() > 32)
            throw new IllegalArgumentException("protocol-relay.peers must define 1..32 peer keys");
        Map<String, byte[]> secrets = new HashMap<>();
        for (String peer : peers.getKeys(false)) {
            String encoded = peers.getString(peer, "");
            try {
                if (secrets.putIfAbsent(peer, Base64.getDecoder().decode(encoded)) != null)
                    throw new IllegalArgumentException("Duplicate protocol relay peer " + peer);
            } catch (IllegalArgumentException failure) {
                throw new IllegalArgumentException("Invalid base64 relay secret for peer " + peer, failure);
            }
        }
        return new ProtocolRelaySettings(true, serverId, secrets);
    }
}
