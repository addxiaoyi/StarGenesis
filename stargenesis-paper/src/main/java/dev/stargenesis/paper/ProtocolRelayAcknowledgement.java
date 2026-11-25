package dev.stargenesis.paper;

import java.util.Objects;
import java.util.UUID;

record ProtocolRelayAcknowledgement(String sourceServer, String targetServer, UUID messageId,
                                    boolean accepted, long acknowledgedAtMillis) {
    ProtocolRelayAcknowledgement {
        if (!ProtocolRelayPacket.validServerId(sourceServer) || !ProtocolRelayPacket.validServerId(targetServer)
                || sourceServer.equals(targetServer))
            throw new IllegalArgumentException("Invalid protocol relay acknowledgement server identity");
        Objects.requireNonNull(messageId, "messageId");
        if (acknowledgedAtMillis < 0) throw new IllegalArgumentException("Invalid acknowledgement time");
    }
}
