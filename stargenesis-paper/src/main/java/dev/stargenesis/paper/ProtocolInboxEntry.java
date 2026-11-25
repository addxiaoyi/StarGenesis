package dev.stargenesis.paper;

import dev.stargenesis.sim.ProtocolMessage;

import java.util.Objects;
import java.util.UUID;

record ProtocolInboxEntry(ProtocolMessage message, String senderName, UUID sessionId) {
    ProtocolInboxEntry {
        Objects.requireNonNull(message, "message");
        if (senderName == null || !senderName.matches("[A-Za-z0-9_]{1,16}"))
            throw new IllegalArgumentException("Invalid protocol sender name");
        Objects.requireNonNull(sessionId, "sessionId");
    }
}
