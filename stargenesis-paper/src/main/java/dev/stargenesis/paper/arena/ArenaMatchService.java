package dev.stargenesis.paper.arena;

import dev.stargenesis.api.design.DesignId;
import dev.stargenesis.design.ArenaRuleset;
import dev.stargenesis.sim.ArenaAction;
import dev.stargenesis.sim.ArenaConfig;
import dev.stargenesis.sim.ArenaMatchReplay;
import dev.stargenesis.sim.ArenaMatchView;
import dev.stargenesis.sim.ArenaMatchWorld;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Worker-owned match lifecycle. Only the immutable roster snapshot may be read from other threads. */
final class ArenaMatchService {
    private final ArenaRulesetStore rulesets;
    private final ArenaMatchPersistence matches;
    private final Map<UUID, String> selections = new HashMap<>();
    private volatile Map<UUID, String> roster;

    ArenaMatchService(ArenaRulesetStore rulesets, ArenaMatchPersistence matches) throws IOException {
        this.rulesets = rulesets;
        this.matches = matches;
        roster = matches.loadTeamRoster();
    }

    Map<UUID, String> roster() { return roster; }
    void disconnected(UUID player) { selections.remove(player); }
    void retainOnline(Set<UUID> online) { selections.keySet().retainAll(online); }

    void joinTeam(UUID inviter, UUID invited, String team) throws IOException {
        if (inviter.equals(invited) || roster.containsKey(invited))
            throw new IllegalStateException("受邀玩家已加入 Arena roster。");
        if (roster.containsKey(inviter) && !roster.get(inviter).equals(team))
            throw new IllegalStateException("邀请方已加入另一个队伍。");
        if (hasActiveMatch(inviter) || hasActiveMatch(invited))
            throw new IllegalStateException("邀请方或受邀玩家正在参加 Arena match。");
        Map<UUID, String> updated = new HashMap<>(roster);
        updated.put(inviter, team);
        updated.put(invited, team);
        matches.saveTeamRoster(updated);
        roster = Map.copyOf(updated);
    }

    boolean leaveTeam(UUID player) throws IOException {
        if (hasActiveMatch(player)) throw new IllegalStateException("比赛结束后才能退出队伍。");
        if (!roster.containsKey(player)) return false;
        Map<UUID, String> updated = new HashMap<>(roster);
        updated.remove(player);
        matches.saveTeamRoster(updated);
        roster = Map.copyOf(updated);
        return true;
    }

    boolean hasActiveMatch(UUID player) throws IOException {
        for (var session : matches.findForPlayer(player)) {
            if (session.replay().teams().containsKey(player) && !view(session.replay()).terminal()) return true;
        }
        return false;
    }

    ArenaMatchPersistence.MatchSession start(UUID starter, DesignId rulesetId, String version, long seed,
                                              Set<UUID> online) throws IOException {
        Map<UUID, String> startingRoster = roster;
        Map<String, Long> counts = startingRoster.values().stream().collect(java.util.stream.Collectors.groupingBy(
                team -> team, java.util.stream.Collectors.counting()));
        if (startingRoster.size() < 4 || startingRoster.size() > ArenaMatchWorld.MAX_PLAYERS
                || counts.size() != 2 || counts.values().stream().anyMatch(count -> count < 2 || count > 4))
            throw new IllegalStateException("Arena 至少需要两队各2名玩家，最多8人。");
        if (!startingRoster.containsKey(starter)) throw new SecurityException("Arena starter must join the roster");
        if (!online.containsAll(startingRoster.keySet())) throw new IllegalStateException("比赛开始时全部参与者必须在线。");
        for (var session : matches.catalog()) {
            if (session.replay().teams().keySet().stream().anyMatch(startingRoster::containsKey)
                    && !view(session.replay()).terminal())
                throw new IllegalStateException("Roster 中有玩家正在参加另一场 Arena match。");
        }
        ArenaRuleset ruleset = rulesets.loadPublished(rulesetId, version)
                .orElseThrow(() -> new IllegalArgumentException("指定的已发布 ArenaRuleset 不存在。"));
        rulesets.validatePublishedDependencies(ruleset);
        ArenaConfig config = new ArenaConfig(ruleset.width(), ruleset.height(), ruleset.durationSteps(), ruleset.unitBudget());
        try (ArenaMatchWorld world = new ArenaMatchWorld(UUID.randomUUID(), seed, config, rulesetId.value(),
                version, ruleset.metadata().hash(), startingRoster)) {
            if (matches.load(world.matchId()).isPresent()) throw new IOException("Arena run identity collision; retry start");
            var session = ArenaMatchPersistence.MatchSession.initial(world.replay());
            matches.create(session);
            startingRoster.keySet().forEach(player -> selections.put(player, world.matchId()));
            return session;
        }
    }

    Optional<ArenaMatchPersistence.MatchSession> restorePlayer(UUID player) throws IOException {
        String selected = selections.get(player);
        if (selected != null) {
            var session = matches.load(selected).orElse(null);
            if (session != null && belongsTo(session, player)) {
                view(session.replay());
                return Optional.of(session);
            }
            selections.remove(player);
        }
        ArenaMatchPersistence.MatchSession best = null;
        int bestPriority = Integer.MAX_VALUE;
        for (var session : matches.findForPlayer(player)) {
            boolean terminal = view(session.replay()).terminal();
            // Completed history can never displace an active match after reconnect/restart.
            int priority = (terminal ? 2 : 0) + (session.replay().teams().containsKey(player) ? 0 : 1);
            if (priority < bestPriority) {
                best = session;
                bestPriority = priority;
            }
        }
        if (best != null) selections.put(player, best.replay().matchId());
        return Optional.ofNullable(best);
    }

    ArenaMatchPersistence.MatchSession current(UUID player) throws IOException {
        return restorePlayer(player).orElseThrow(() -> new IllegalStateException("当前玩家不属于 Arena match。"));
    }

    ArenaMatchView select(UUID player, String matchId) throws IOException {
        var session = matches.load(matchId).orElseThrow(() -> new IllegalArgumentException("Arena match 不存在。"));
        if (!belongsTo(session, player)) throw new SecurityException("Only participants and observers may select this match");
        ArenaMatchView view = view(session.replay());
        selections.put(player, matchId);
        return view;
    }

    ArenaMatchView watch(UUID player, String matchId) throws IOException {
        var session = matches.load(matchId).orElseThrow(() -> new IllegalArgumentException("Arena match 不存在。"));
        if (session.replay().teams().containsKey(player))
            throw new IllegalArgumentException("参赛玩家请使用 select 选择自己的比赛。");
        ArenaMatchView view = view(session.replay());
        if (!session.spectators().contains(player)) {
            List<UUID> observers = new ArrayList<>(session.spectators());
            observers.add(player);
            matches.save(new ArenaMatchPersistence.MatchSession(ArenaMatchPersistence.SCHEMA_VERSION,
                    session.replay(), session.pending(), observers));
        }
        selections.put(player, matchId);
        return view;
    }

    void unwatch(UUID player) throws IOException {
        var session = current(player);
        if (!session.spectators().contains(player)) throw new IllegalStateException("当前选择的比赛不是观战模式。");
        matches.save(new ArenaMatchPersistence.MatchSession(ArenaMatchPersistence.SCHEMA_VERSION,
                session.replay(), session.pending(), session.spectators().stream().filter(id -> !id.equals(player)).toList()));
        selections.remove(player);
    }

    ArenaMatchRound.Submission submit(UUID player, ArenaAction action) throws IOException {
        var session = current(player);
        var submission = ArenaMatchRound.submit(session, player, action);
        matches.save(submission.session());
        return submission;
    }

    void withdraw(UUID player) throws IOException {
        var session = current(player);
        if (!session.replay().teams().containsKey(player)) throw new SecurityException("Only participants may withdraw actions");
        if (!session.pending().containsKey(player)) throw new IllegalStateException("本轮没有可撤回的动作。");
        Map<UUID, ArenaAction> pending = new HashMap<>(session.pending());
        pending.remove(player);
        matches.save(new ArenaMatchPersistence.MatchSession(ArenaMatchPersistence.SCHEMA_VERSION,
                session.replay(), pending, session.spectators()));
    }

    void pruneCompleted(String matchId, boolean operator) throws IOException {
        if (!operator) throw new SecurityException("permission denied (stargenesis.arena.manage)");
        var session = matches.load(matchId).orElseThrow(() -> new IllegalArgumentException("Arena match 不存在。"));
        if (!view(session.replay()).terminal() || !session.pending().isEmpty())
            throw new IllegalStateException("只能清理已结束且没有待处理动作的历史比赛。");
        matches.delete(matchId);
        selections.values().removeIf(matchId::equals);
    }

    ArenaMatchView view(ArenaMatchReplay replay) throws IOException {
        ArenaRuleset ruleset = rulesets.requirePinnedPlayable(replay.rulesetId(), replay.rulesetVersion(), replay.rulesetHash());
        if (ruleset.width() != replay.config().width() || ruleset.height() != replay.config().height()
                || ruleset.durationSteps() != replay.config().durationSteps()
                || ruleset.unitBudget() != replay.config().unitBudget())
            throw new IOException("Arena replay config differs from its pinned ruleset");
        try (ArenaMatchWorld world = ArenaMatchWorld.restore(replay)) { return world.view(); }
    }

    private boolean belongsTo(ArenaMatchPersistence.MatchSession session, UUID player) {
        return session.replay().teams().containsKey(player) || session.spectators().contains(player);
    }
}
