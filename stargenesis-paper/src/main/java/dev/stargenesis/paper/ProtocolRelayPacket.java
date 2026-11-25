package dev.stargenesis.paper;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;

record ProtocolRelayPacket(String sourceServer, String targetServer, UUID messageId,
                           UUID senderId, String senderName, UUID recipientId,
                           String protocolId, String payload, long createdAtMillis,
                           long expiresAtMillis) {
    static final int MAX_PAYLOAD_BYTES = 4096;

    ProtocolRelayPacket {
        if (!validServerId(sourceServer) || !validServerId(targetServer) || sourceServer.equals(targetServer))
            throw new IllegalArgumentException("Invalid protocol relay server identity");
        Objects.requireNonNull(messageId, "messageId");
        Objects.requireNonNull(senderId, "senderId");
        Objects.requireNonNull(recipientId, "recipientId");
        if (senderId.equals(recipientId)) throw new IllegalArgumentException("Cannot relay a message to its sender");
        if (senderName == null || !senderName.matches("[A-Za-z0-9_]{1,16}"))
            throw new IllegalArgumentException("Invalid protocol relay sender name");
        if (protocolId == null || !protocolId.matches("[a-z][a-z0-9_-]{0,31}:[a-z0-9][a-z0-9_./-]{0,94}"))
            throw new IllegalArgumentException("Invalid protocol relay identifier");
        if (payload == null || payload.isBlank()
                || payload.getBytes(StandardCharsets.UTF_8).length > MAX_PAYLOAD_BYTES
                || payload.chars().anyMatch(character -> character < 32 && character != '\n' && character != '\t'))
            throw new IllegalArgumentException("Invalid protocol relay payload");
        if (createdAtMillis < 0 || expiresAtMillis <= createdAtMillis
                || expiresAtMillis - createdAtMillis > ProtocolRelayPacketCodec.MAX_LIFETIME_MILLIS)
            throw new IllegalArgumentException("Invalid protocol relay lifetime");
    }

    static boolean validServerId(String value) {
        return value != null && value.matches("[a-z0-9][a-z0-9_-]{0,31}");
    }
}
