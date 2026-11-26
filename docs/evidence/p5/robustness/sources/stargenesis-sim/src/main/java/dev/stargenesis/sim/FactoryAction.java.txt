package dev.stargenesis.sim;

import java.util.Objects;
import java.util.UUID;

/** One authenticated seat action advances one logical factory tick. */
public record FactoryAction(UUID actor, Type type, String reference, long amount) {
    public FactoryAction {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(type, "type");
        if (reference == null || reference.length() > 64
                || !reference.matches("[a-z0-9][a-z0-9_-]{0,63}") || amount < 0)
            throw new IllegalArgumentException("Invalid factory action");
        if (type == Type.PASS && (amount != 0 || !reference.equals("pass"))
                || type == Type.BID && (!reference.equals("ore_1") || amount < 1 || amount > 100_000)
                || type == Type.PLACE_ORDER && (amount < 1 || amount > 4))
            throw new IllegalArgumentException("Factory action parameters outside their bounds");
    }

    public static FactoryAction pass(UUID actor) { return new FactoryAction(actor, Type.PASS, "pass", 0); }

    public enum Type { PASS, BID, PLACE_ORDER }
}
