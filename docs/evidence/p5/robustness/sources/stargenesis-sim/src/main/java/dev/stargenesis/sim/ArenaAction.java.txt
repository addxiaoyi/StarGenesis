package dev.stargenesis.sim;

import java.util.Objects;
import java.util.UUID;

public record ArenaAction(UUID actor, Type type, int x, int y, int force) {
    public ArenaAction {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(type, "type");
        if (type == Type.PASS && (x != -1 || y != -1 || force != 0)
                || type == Type.CLAIM && (x < 0 || y < 0 || force < 1 || force > 64))
            throw new IllegalArgumentException("Invalid arena action");
    }

    public static ArenaAction pass(UUID actor) { return new ArenaAction(actor, Type.PASS, -1, -1, 0); }
    public static ArenaAction claim(UUID actor, int x, int y, int force) { return new ArenaAction(actor, Type.CLAIM, x, y, force); }

    public enum Type { PASS, CLAIM }
}
