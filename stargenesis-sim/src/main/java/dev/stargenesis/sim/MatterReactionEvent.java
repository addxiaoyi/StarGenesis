package dev.stargenesis.sim;

import dev.stargenesis.api.design.DesignId;

import java.util.Objects;

public record MatterReactionEvent(DesignId reactionId, int firings) {
    public MatterReactionEvent {
        Objects.requireNonNull(reactionId, "reactionId");
        if (firings < 1 || firings > MatterReactionNetwork.MAX_FIRINGS_PER_STEP) {
            throw new IllegalArgumentException("Invalid reaction event firing count");
        }
    }
}
