package dev.stargenesis.paper.arena;

import dev.stargenesis.sim.ArenaAction;
import dev.stargenesis.sim.ArenaInput;
import dev.stargenesis.sim.ArenaMatchView;
import dev.stargenesis.sim.ArenaMatchWorld;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Computes one submission without changing the durable session until the caller saves it. */
final class ArenaMatchRound {
    private ArenaMatchRound() { }

    static Submission submit(ArenaMatchPersistence.MatchSession session, UUID actor, ArenaAction action) {
        if (!session.replay().teams().containsKey(actor) || !actor.equals(action.actor()))
            throw new SecurityException("Only rostered players may submit their own Arena actions");
        if (session.pending().containsKey(actor))
            throw new IllegalStateException("本轮动作已提交，请等待其他玩家。");
        Map<UUID, ArenaAction> pending = new HashMap<>(session.pending());
        pending.put(actor, action);
        try (ArenaMatchWorld world = ArenaMatchWorld.restore(session.replay())) {
            if (pending.size() < session.replay().teams().size()) {
                world.validatePendingActions(List.copyOf(pending.values()));
                return new Submission(new ArenaMatchPersistence.MatchSession(ArenaMatchPersistence.SCHEMA_VERSION,
                        session.replay(), pending, session.spectators()), world.view());
            }
            world.step(new ArenaInput(world.view().tick(), List.copyOf(pending.values())));
            return new Submission(new ArenaMatchPersistence.MatchSession(ArenaMatchPersistence.SCHEMA_VERSION,
                    world.replay(), Map.of(), session.spectators()), world.view());
        }
    }

    record Submission(ArenaMatchPersistence.MatchSession session, ArenaMatchView view) { }
}
