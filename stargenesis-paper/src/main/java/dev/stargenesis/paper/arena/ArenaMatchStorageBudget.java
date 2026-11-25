package dev.stargenesis.paper.arena;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.stargenesis.api.sim.EvidenceId;
import dev.stargenesis.api.sim.EventRecord;
import dev.stargenesis.sim.ArenaAction;
import dev.stargenesis.sim.ArenaInput;
import dev.stargenesis.sim.ArenaMatchReplay;
import dev.stargenesis.sim.ArenaMatchWorld;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

/** Conservative JSON size bound for the fixed v1/v2 engines, including future inputs and observers. */
final class ArenaMatchStorageBudget {
    // At most 128 UUIDs (4,992 bytes) and eight pending UUID/action entries (<1,300 bytes).
    static final long DYNAMIC_HEADROOM = 8_192;

    private ArenaMatchStorageBudget() { }

    static Estimate estimate(ArenaMatchReplay replay, ObjectMapper mapper) throws IOException {
        if (!List.of(ArenaMatchWorld.LEGACY_ENGINE_VERSION, ArenaMatchWorld.ENGINE_VERSION).contains(replay.engineVersion()))
            throw new IOException("No storage bound is defined for this Arena engine");
        var emptyReplay = new ArenaMatchReplay(replay.schemaVersion(), replay.engineVersion(), replay.matchId(),
                replay.seed(), replay.config(), replay.teams(), replay.rulesetId(), replay.rulesetVersion(),
                replay.rulesetHash(), replay.initialStateHash(), List.of(), replay.runId());
        Map<UUID, ArenaAction> pending = new LinkedHashMap<>();
        replay.teams().keySet().forEach(player -> pending.put(player, ArenaAction.claim(player, 127, 127, 64)));
        List<UUID> observers = IntStream.range(0, 128).mapToObj(i -> new UUID(Long.MAX_VALUE, i)).toList();
        // v1 serializes runId:null here although storage omits it, making this bound slightly larger.
        long envelopeBytes = mapper.writeValueAsBytes(new ArenaMatchPersistence.MatchSession(1, emptyReplay, pending, observers)).length;
        long frameBytes = mapper.writeValueAsBytes(maximumFrame(replay)).length + 1L; // array separator
        long maxBytes = Math.addExact(envelopeBytes, Math.multiplyExact(replay.config().durationSteps(), frameBytes));
        long maxRounds = Math.max(0, (ArenaMatchPersistence.MAX_FILE_BYTES - envelopeBytes) / frameBytes);
        return new Estimate(envelopeBytes, frameBytes, maxBytes, maxRounds);
    }

    private static ArenaMatchReplay.Frame maximumFrame(ArenaMatchReplay replay) {
        long lastTick = replay.config().durationSteps() - 1;
        var evidence = new EvidenceId(replay.matchId().replace('-', '_'), lastTick, ArenaMatchWorld.MAX_EVENTS_PER_TICK - 1);
        String team = "t".repeat(32);
        String largestInt = Integer.toString(Integer.MAX_VALUE);
        List<EventRecord> events = new ArrayList<>();
        // One claim can target at most one tile. Each target emits at most a contest and a resolution.
        // Their combined bytes also dominate a player's single pass event (UUID plus team).
        for (int player = 0; player < replay.teams().size(); player++) {
            events.add(new EventRecord(evidence, "arena.territory_contested",
                    Map.of("x", "127", "y", "127", "winner", team, "margin", largestInt, "initiative", "1")));
            events.add(new EventRecord(evidence, "arena.territory_reinforced",
                    Map.of("x", "127", "y", "127", "team", team, "force", largestInt)));
        }
        // Two teams: at most one spending and one regeneration event per team.
        for (int teamIndex = 0; teamIndex < 2; teamIndex++) {
            events.add(new EventRecord(evidence, "arena.units_spent", Map.of("team", team, "amount", largestInt)));
            events.add(new EventRecord(evidence, "arena.units_regenerated", Map.of("team", team, "amount", largestInt)));
        }
        // Count settlement in every round for a conservative bound; the engine only emits it once.
        events.add(new EventRecord(evidence, "arena.match_settled",
                Map.of("winner", team, "score", largestInt, "tick", Long.toString(lastTick))));
        var actions = replay.teams().keySet().stream().map(player -> ArenaAction.claim(player, 127, 127, 64)).toList();
        return new ArenaMatchReplay.Frame(new ArenaInput(lastTick, actions), events, "f".repeat(64), "f".repeat(64));
    }

    static long reservedBytes(ArenaMatchPersistence.MatchSession session, long actualBytes, ObjectMapper mapper) throws IOException {
        // Both supported engines settle exactly at durationSteps. Completed history only needs viewer headroom.
        long bound = session.replay().frames().size() == session.replay().config().durationSteps()
                ? Math.addExact(actualBytes, DYNAMIC_HEADROOM) : estimate(session.replay(), mapper).maxBytes();
        // Legacy games admitted without a bound may exceed it; reserve the full existing file limit.
        return Math.max(actualBytes, Math.min(ArenaMatchPersistence.MAX_FILE_BYTES, bound));
    }

    record Estimate(long envelopeBytes, long frameBytes, long maxBytes, long maxAdmissibleRounds) { }
}
