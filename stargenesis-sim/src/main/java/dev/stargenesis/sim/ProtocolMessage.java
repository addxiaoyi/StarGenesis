package dev.stargenesis.sim;

import dev.stargenesis.api.sim.AgentId;
import java.util.Objects;

/** Immutable public envelope for one bounded, simulation-only protocol message. */
public record ProtocolMessage(long sequence, long sentAt, long expiresAt, AgentId sender,
                              AgentId recipient, String protocolId, String payload) {
    public ProtocolMessage {
        if (sequence < 0 || sentAt < 0 || expiresAt <= sentAt)
            throw new IllegalArgumentException("Invalid protocol message lifetime");
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(recipient, "recipient");
        if (sender.equals(recipient)) throw new IllegalArgumentException("Cannot message yourself");
        if (protocolId == null || !protocolId.matches("[a-z][a-z0-9_-]{0,31}:[a-z0-9][a-z0-9_./-]{0,94}"))
            throw new IllegalArgumentException("Invalid protocol identifier");
        if (payload == null || payload.isBlank()
                || payload.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > ProtocolMailboxHub.MAX_MESSAGE_BYTES
                || payload.chars().anyMatch(character -> character < 32 && character != '\n' && character != '\t'))
            throw new IllegalArgumentException("Invalid protocol payload");
    }
}
