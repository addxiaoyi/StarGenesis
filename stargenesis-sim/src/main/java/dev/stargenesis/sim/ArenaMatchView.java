package dev.stargenesis.sim;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** Immutable match projection for players and read-only spectators. */
public record ArenaMatchView(String matchId, String rulesetId, String rulesetVersion,
                             long tick, long durationSteps, Map<UUID, String> teams,
                             Map<String, Integer> territory, Map<String, Integer> remainingUnits,
                             Map<String, Integer> scores, Map<String, Integer> leaderboard,
                             String settledWinner, String stateHash, boolean terminal, String reason) {
    public ArenaMatchView {
        TreeMap<UUID, String> sortedTeams = new TreeMap<>(java.util.Comparator.comparing(UUID::toString));
        sortedTeams.putAll(teams);
        teams = Collections.unmodifiableMap(sortedTeams);
        territory = Collections.unmodifiableMap(new TreeMap<>(territory));
        remainingUnits = Collections.unmodifiableMap(new TreeMap<>(remainingUnits));
        scores = Collections.unmodifiableMap(new TreeMap<>(scores));
        leaderboard = Collections.unmodifiableMap(new TreeMap<>(leaderboard));
    }
}
