package dev.stargenesis.sim;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** One simultaneous action from each rostered player for the next logical arena round. */
public record ArenaInput(long tick, List<ArenaAction> actions) {
    public ArenaInput {
        if (tick < 0 || actions == null || actions.size() < 2 || actions.size() > ArenaMatchWorld.MAX_PLAYERS)
            throw new IllegalArgumentException("Arena input is outside its action budget");
        List<ArenaAction> sorted = actions.stream().map(action -> Objects.requireNonNull(action, "action"))
                .sorted(Comparator.comparing(action -> action.actor().toString())).toList();
        if (sorted.stream().map(ArenaAction::actor).distinct().count() != sorted.size())
            throw new IllegalArgumentException("Arena input contains duplicate player actions");
        actions = sorted;
    }
}
