package dev.stargenesis.sim;

import java.util.Objects;

public record ProtocolDelivery(ProtocolMessage message, Status status, String reason) {
    public ProtocolDelivery {
        Objects.requireNonNull(message, "message");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(reason, "reason");
        if (reason.length() > 128) throw new IllegalArgumentException("Delivery reason is too long");
    }

    public enum Status { QUEUED, DELIVERED, REJECTED, EXPIRED }
}
