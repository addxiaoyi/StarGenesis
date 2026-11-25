package dev.stargenesis.paper;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.UUID;

final class ProtocolInboxStore {
    private final NamespacedKey key;

    ProtocolInboxStore(JavaPlugin plugin) {
        key = new NamespacedKey(plugin, "protocol-inbox-v1");
    }

    ProtocolInboxCheckpoint load(Player player) {
        UUID owner = player.getUniqueId();
        byte[] encoded = player.getPersistentDataContainer().get(key, PersistentDataType.BYTE_ARRAY);
        return encoded == null ? ProtocolInboxCheckpoint.empty(owner)
                : ProtocolInboxCheckpointCodec.decode(encoded, owner);
    }

    void save(Player player, ProtocolInboxCheckpoint checkpoint) {
        if (!player.getUniqueId().equals(checkpoint.ownerId()))
            throw new IllegalArgumentException("Protocol inbox belongs to another player");
        player.getPersistentDataContainer().set(key, PersistentDataType.BYTE_ARRAY,
                ProtocolInboxCheckpointCodec.encode(checkpoint));
    }

    void clear(Player player) {
        save(player, load(player).clearMessages());
    }

    boolean appendRelay(Player player, ProtocolInboxEntry entry, UUID messageId,
                        long expiresAtMillis, long nowMillis) {
        ProtocolInboxCheckpoint current = load(player);
        ProtocolInboxCheckpoint updated = current.appendRelay(entry, messageId, expiresAtMillis, nowMillis);
        if (!updated.equals(current)) save(player, updated);
        return updated.relayReceipts().containsKey(messageId);
    }
}
