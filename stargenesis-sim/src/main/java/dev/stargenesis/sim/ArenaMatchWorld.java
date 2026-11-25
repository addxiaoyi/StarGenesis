package dev.stargenesis.sim;

import dev.stargenesis.api.sim.EvidenceId;
import dev.stargenesis.api.sim.EventRecord;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/** Deterministic two-team, multi-player territory contest powered by one published ArenaRuleset. */
public final class ArenaMatchWorld implements AutoCloseable {
    public static final String ENGINE_VERSION = "genesis-arena-match/1";
    public static final int MAX_PLAYERS = 8;
    public static final int MAX_EVENTS_PER_TICK = 128;
    private static final String ZERO_HASH = "0".repeat(64);
    private static final Comparator<UUID> UUID_ORDER = Comparator.comparing(UUID::toString);
    private final OwnerThread owner = new OwnerThread();
    private final String matchId;
    private final long seed;
    private final ArenaConfig config;
    private final String rulesetId;
    private final String rulesetVersion;
    private final String rulesetHash;
    private final TreeMap<UUID, String> teams = new TreeMap<>(UUID_ORDER);
    private final TreeMap<String, TeamState> teamStates = new TreeMap<>();
    private final Tile[] tiles;
    private final List<ArenaMatchReplay.Frame> history = new ArrayList<>();
    private final String initialStateHash;
    private long tick;
    private boolean terminal;
    private boolean closed;
    private String reason = "RUNNING";
    private String settledWinner = "";
    private long contestedClaims;
    private String eventHistoryHash = ZERO_HASH;
    private int evidenceSequence;
    private List<EventRecord> stepEvents = new ArrayList<>();

    public ArenaMatchWorld(String matchId, long seed, ArenaConfig config, String rulesetId,
                           String rulesetVersion, String rulesetHash, Map<UUID, String> roster) {
        new EvidenceId(matchId.replace("-", "_"), 0, 0);
        if (matchId == null || !matchId.matches("arena-[0-9a-f]{32}")
                || !validHash(rulesetHash) || !validRulesetId(rulesetId)
                || rulesetVersion == null || !rulesetVersion.matches("(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})\\.(0|[1-9][0-9]{0,5})"))
            throw new IllegalArgumentException("Invalid arena match or ruleset identity");
        this.matchId = matchId;
        this.seed = seed;
        this.config = Objects.requireNonNull(config, "config");
        this.rulesetId = rulesetId;
        this.rulesetVersion = rulesetVersion;
        this.rulesetHash = rulesetHash;
        validateRoster(roster);
        Map<String, Integer> members = new TreeMap<>();
        teams.values().forEach(team -> members.merge(team, 1, Integer::sum));
        for (Map.Entry<String, Integer> entry : members.entrySet()) {
            int maximum = Math.multiplyExact(Math.multiplyExact(config.unitBudget(), entry.getValue()), 4);
            int startingUnits = Math.multiplyExact(config.unitBudget(), entry.getValue());
            teamStates.put(entry.getKey(), new TeamState(startingUnits, maximum));
        }
        tiles = new Tile[Math.multiplyExact(config.width(), config.height())];
        for (int i = 0; i < tiles.length; i++) tiles[i] = new Tile();
        List<String> teamNames = List.copyOf(teamStates.keySet());
        claimBase(teamNames.getFirst(), 0, 0);
        claimBase(teamNames.getLast(), config.width() - 1, config.height() - 1);
        initialStateHash = stateHash();
    }

    public static String matchIdFor(String rulesetHash, Map<UUID, String> roster) {
        if (!validHash(rulesetHash)) throw new IllegalArgumentException("Invalid ruleset hash");
        StringBuilder input = new StringBuilder();
        token(input, rulesetHash);
        new TreeMap<>(roster).forEach((player, team) -> { token(input, player.toString()); token(input, team); });
        return "arena-" + sha256(input.toString()).substring(0, 32);
    }

    public static ArenaMatchWorld restore(ArenaMatchReplay replay) {
        Objects.requireNonNull(replay, "replay");
        if (!ENGINE_VERSION.equals(replay.engineVersion())) throw new IllegalArgumentException("Unsupported arena match engine");
        ArenaMatchWorld world = new ArenaMatchWorld(replay.matchId(), replay.seed(), replay.config(), replay.rulesetId(),
                replay.rulesetVersion(), replay.rulesetHash(), replay.teams());
        try {
            if (!world.initialStateHash.equals(replay.initialStateHash()))
                throw new IllegalArgumentException("Arena match initial state commitment mismatch");
            for (ArenaMatchReplay.Frame frame : replay.frames()) {
                ArenaMatchStepResult result = world.step(frame.input());
                if (!result.events().equals(frame.events()) || !result.stateHash().equals(frame.stateHash())
                        || !result.eventHash().equals(frame.eventHash()))
                    throw new IllegalArgumentException("Arena match replay commitment mismatch at tick " + frame.input().tick());
            }
            return world;
        } catch (RuntimeException failure) {
            world.close();
            throw failure;
        }
    }

    public ArenaMatchStepResult step(ArenaInput input) {
        owner.check();
        requireOpen();
        Objects.requireNonNull(input, "input");
        if (terminal) throw new IllegalStateException("Arena match ended: " + reason);
        if (input.tick() != tick) throw new IllegalArgumentException("Expected arena round " + tick);
        List<ArenaAction> actions = validateInput(input);
        evidenceSequence = 0;
        stepEvents = new ArrayList<>();
        Map<String, Map<Integer, Integer>> attacks = new TreeMap<>();
        Map<String, Integer> spent = new TreeMap<>();
        for (ArenaAction action : actions) {
            if (action.type() == ArenaAction.Type.PASS) {
                emit("arena.player_passed", Map.of("actor", action.actor().toString(), "team", teams.get(action.actor())));
                continue;
            }
            String team = teams.get(action.actor());
            int index = index(action.x(), action.y());
            attacks.computeIfAbsent(team, ignored -> new TreeMap<>()).merge(index, action.force(), Math::addExact);
            spent.merge(team, action.force(), Math::addExact);
        }
        spent.forEach((team, amount) -> {
            TeamState state = teamStates.get(team);
            state.units = Math.subtractExact(state.units, amount);
            emit("arena.units_spent", Map.of("team", team, "amount", Integer.toString(amount)));
        });
        resolve(attacks);
        regenerate();
        if (tick + 1 >= config.durationSteps()) settle();
        tick = Math.addExact(tick, 1);
        String hash = stateHash();
        history.add(new ArenaMatchReplay.Frame(input, stepEvents, hash, eventHistoryHash));
        return new ArenaMatchStepResult(tick, stepEvents, hash, eventHistoryHash, terminal, reason);
    }

    private List<ArenaAction> validateInput(ArenaInput input) {
        if (input.actions().size() != teams.size()) throw new IllegalArgumentException("Every rostered player submits exactly one action");
        if (input.actions().stream().map(ArenaAction::actor).distinct().count() != teams.size()
                || !input.actions().stream().map(ArenaAction::actor).collect(java.util.stream.Collectors.toSet())
                .equals(teams.keySet())) throw new SecurityException("Arena actions do not match the authenticated roster");
        Map<String, Integer> costs = new HashMap<>();
        for (ArenaAction action : input.actions()) {
            String team = teams.get(action.actor());
            if (action.type() == ArenaAction.Type.PASS) continue;
            if (action.x() >= config.width() || action.y() >= config.height() || action.force() > config.unitBudget())
                throw new IllegalArgumentException("Arena claim exceeds bounds or ruleset unit budget");
            if (!adjacentToTeam(team, action.x(), action.y()))
                throw new IllegalArgumentException("Arena claim must expand from existing team territory");
            costs.merge(team, action.force(), Math::addExact);
        }
        for (Map.Entry<String, Integer> cost : costs.entrySet())
            if (cost.getValue() > teamStates.get(cost.getKey()).units)
                throw new IllegalArgumentException("Team does not have enough simulation units");
        return input.actions();
    }

    private void resolve(Map<String, Map<Integer, Integer>> attacks) {
        java.util.TreeSet<Integer> targets = new java.util.TreeSet<>();
        attacks.values().forEach(byTile -> targets.addAll(byTile.keySet()));
        for (int target : targets) resolveTile(target, attacks);
    }

    private void resolveTile(int index, Map<String, Map<Integer, Integer>> attacks) {
        Map<String, Integer> forces = new TreeMap<>();
        attacks.forEach((team, byTile) -> {
            Integer force = byTile.get(index);
            if (force != null) forces.put(team, force);
        });
        Tile tile = tiles[index];
        String previous = tile.team;
        int previousForce = tile.force;
        if (previous != null) forces.merge(previous, previousForce, Math::addExact);
        if (forces.isEmpty()) return;
        List<Map.Entry<String, Integer>> ranked = forces.entrySet().stream()
                .sorted(Comparator.<Map.Entry<String, Integer>>comparingInt(Map.Entry::getValue).reversed()
                        .thenComparingInt(entry -> initiativeRank(entry.getKey())))
                .toList();
        Map.Entry<String, Integer> first = ranked.getFirst();
        boolean contested = forces.size() > 1;
        int margin = ranked.size() > 1 ? first.getValue() - ranked.get(1).getValue() : first.getValue();
        if (contested) {
            contestedClaims++;
            emit("arena.territory_contested", Map.of("x", Integer.toString(x(index)), "y", Integer.toString(y(index)),
                    "winner", first.getKey(), "margin", Integer.toString(margin),
                    "initiative", Integer.toString(initiativeRank(first.getKey()))));
        }
        tile.team = first.getKey();
        tile.force = Math.max(1, margin);
        emit(previous == null ? "arena.territory_claimed" : previous.equals(tile.team)
                        ? "arena.territory_reinforced" : "arena.territory_captured",
                Map.of("x", Integer.toString(x(index)), "y", Integer.toString(y(index)),
                        "team", tile.team, "force", Integer.toString(tile.force)));
    }

    private void regenerate() {
        Map<String, Integer> owned = territoryCounts();
        teamStates.forEach((team, state) -> {
            int amount = owned.getOrDefault(team, 0);
            int before = state.units;
            state.units = Math.min(state.maximum, Math.addExact(state.units, amount));
            int generated = state.units - before;
            if (generated > 0) emit("arena.units_regenerated", Map.of("team", team, "amount", Integer.toString(generated)));
        });
    }

    private void settle() {
        Map<String, Integer> scores = territoryCounts();
        int best = scores.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        List<String> winners = scores.entrySet().stream().filter(entry -> entry.getValue() == best)
                .map(Map.Entry::getKey).sorted().toList();
        settledWinner = winners.size() == 1 ? winners.getFirst() : "";
        reason = winners.size() == 1 ? "TERRITORY_SCORE" : "TERRITORY_TIE";
        terminal = true;
        emit("arena.match_settled", Map.of("winner", settledWinner.isEmpty() ? "tie" : settledWinner,
                "score", Integer.toString(best), "tick", Long.toString(tick)));
    }

    private boolean adjacentToTeam(String team, int x, int y) {
        return (x > 0 && team.equals(tiles[index(x - 1, y)].team))
                || (x + 1 < config.width() && team.equals(tiles[index(x + 1, y)].team))
                || (y > 0 && team.equals(tiles[index(x, y - 1)].team))
                || (y + 1 < config.height() && team.equals(tiles[index(x, y + 1)].team));
    }

    private Map<String, Integer> territoryCounts() {
        Map<String, Integer> scores = new TreeMap<>();
        teamStates.keySet().forEach(team -> scores.put(team, 0));
        for (Tile tile : tiles) if (tile.team != null) scores.merge(tile.team, 1, Integer::sum);
        return scores;
    }

    private int initiativeRank(String team) {
        List<String> sorted = List.copyOf(teamStates.keySet());
        int start = (int) Long.remainderUnsigned(mix64(seed ^ tick), sorted.size());
        return Math.floorMod(sorted.indexOf(team) - start, sorted.size());
    }

    private void claimBase(String team, int x, int y) {
        Tile tile = tiles[index(x, y)];
        tile.team = team;
        tile.force = 1;
    }

    private int index(int x, int y) { return y * config.width() + x; }
    private int x(int index) { return index % config.width(); }
    private int y(int index) { return index / config.width(); }

    private void validateRoster(Map<UUID, String> roster) {
        if (roster == null || roster.size() < 2 || roster.size() > MAX_PLAYERS
                || roster.keySet().stream().anyMatch(Objects::isNull)
                || roster.values().stream().anyMatch(team -> team == null || !team.matches("[a-z][a-z0-9_-]{0,31}"))
                || roster.values().stream().distinct().count() != 2
                || !matchId.equals(matchIdFor(rulesetHash, roster)))
            throw new IllegalArgumentException("Arena match requires a bounded two-team authenticated roster");
        teams.putAll(roster);
    }

    private void emit(String type, Map<String, String> data) {
        emit(new EvidenceId(matchId.replace('-', '_'), tick, evidenceSequence++), type, data);
    }

    private void emit(EvidenceId evidence, String type, Map<String, String> data) {
        if (stepEvents.size() >= MAX_EVENTS_PER_TICK) throw new IllegalStateException("Arena event budget exhausted");
        EventRecord event = new EventRecord(evidence, type, data);
        stepEvents.add(event);
        StringBuilder encoded = new StringBuilder();
        token(encoded, evidence.toString()); token(encoded, type);
        new TreeMap<>(data).forEach((key, value) -> { token(encoded, key); token(encoded, value); });
        eventHistoryHash = sha256(eventHistoryHash + encoded);
    }

    private ArenaMatchView viewFor() {
        Map<String, Integer> territory = territoryCounts();
        Map<String, Integer> units = new TreeMap<>();
        teamStates.forEach((team, state) -> units.put(team, state.units));
        List<Map.Entry<String, Integer>> ranking = territory.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed().thenComparing(Map.Entry::getKey)).toList();
        Map<String, Integer> leaderboard = new TreeMap<>();
        int rank = 0;
        int previousScore = -1;
        for (int i = 0; i < ranking.size(); i++) {
            Map.Entry<String, Integer> entry = ranking.get(i);
            if (entry.getValue() != previousScore) rank = i + 1;
            leaderboard.put(entry.getKey(), rank);
            previousScore = entry.getValue();
        }
        return new ArenaMatchView(matchId, rulesetId, rulesetVersion, tick, config.durationSteps(), teams,
                territory, units, territory, leaderboard, settledWinner, stateHash(), terminal, reason);
    }

    public ArenaMatchView view() { owner.check(); return viewFor(); }

    /** Observer and participant reads share an immutable projection. Observer identity grants no actions. */
    public ArenaMatchView observe(UUID observer) {
        owner.check();
        Objects.requireNonNull(observer, "observer");
        return viewFor();
    }

    public ArenaMatchReplay replay() {
        owner.check();
        return new ArenaMatchReplay(1, ENGINE_VERSION, matchId, seed, config, teams, rulesetId,
                rulesetVersion, rulesetHash, initialStateHash, history);
    }

    public String matchId() { owner.check(); return matchId; }
    public String stateHash() {
        owner.check();
        StringBuilder state = new StringBuilder();
        token(state, ENGINE_VERSION); token(state, matchId); token(state, Long.toString(seed));
        token(state, Integer.toString(config.width())); token(state, Integer.toString(config.height()));
        token(state, Long.toString(config.durationSteps())); token(state, Integer.toString(config.unitBudget()));
        token(state, rulesetId); token(state, rulesetVersion); token(state, rulesetHash); token(state, Long.toString(tick));
        token(state, Boolean.toString(terminal)); token(state, reason); token(state, settledWinner);
        token(state, Long.toString(contestedClaims)); token(state, eventHistoryHash);
        teams.forEach((player, team) -> { token(state, player.toString()); token(state, team); });
        teamStates.forEach((team, value) -> { token(state, team); token(state, Integer.toString(value.units)); token(state, Integer.toString(value.maximum)); });
        for (int i = 0; i < tiles.length; i++) {
            Tile tile = tiles[i]; token(state, Integer.toString(i));
            token(state, tile.team == null ? "" : tile.team); token(state, Integer.toString(tile.force));
        }
        return sha256(state.toString());
    }

    private static boolean validRulesetId(String value) {
        return value != null && value.matches("[a-z][a-z0-9_-]{0,31}:[a-z0-9][a-z0-9_./-]{0,94}")
                && !value.contains("..") && !value.endsWith("/");
    }

    private static boolean validHash(String value) { return value != null && value.matches("[0-9a-f]{64}"); }

    private static String sha256(String value) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }

    private long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xbf58476d1ce4e5b9L;
        value = (value ^ (value >>> 27)) * 0x94d049bb133111ebL;
        return value ^ (value >>> 31);
    }

    private static void token(StringBuilder target, String value) { target.append(value.length()).append(':').append(value); }

    private void requireOpen() { if (closed) throw new IllegalStateException("Arena match is closed"); }
    @Override public void close() { owner.check(); closed = true; }

    private static final class Tile { private String team; private int force; }
    private static final class TeamState { private int units; private final int maximum; private TeamState(int units, int maximum) { this.units=units; this.maximum=maximum; } }
}
