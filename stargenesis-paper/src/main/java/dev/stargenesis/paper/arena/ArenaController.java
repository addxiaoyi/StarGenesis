package dev.stargenesis.paper.arena;

import dev.stargenesis.api.design.DesignDependency;
import dev.stargenesis.api.design.DesignId;
import dev.stargenesis.design.ArenaRuleset;
import dev.stargenesis.design.ArenaRulesetWorkspace;
import dev.stargenesis.sim.ArenaAction;
import dev.stargenesis.sim.ArenaConfig;
import dev.stargenesis.sim.ArenaMatchReplay;
import dev.stargenesis.sim.ArenaMatchReplayVerifier;
import dev.stargenesis.sim.ArenaMatchView;
import dev.stargenesis.sim.ArenaMatchWorld;
import org.bukkit.entity.Player;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

/** Player-authorized arena ruleset draft, licensing and immutable publication workflow. */
public final class ArenaController implements Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final ArenaRulesetStore store;
    private final ArenaMatchPersistence matches;
    private final ConcurrentMap<UUID, Session> sessions = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, Invitation> invitations = new ConcurrentHashMap<>();
    private final Object invitationLock = new Object();
    private final ConcurrentMap<UUID, String> teamRoster = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, String> activeMatches = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor worker;
    private final AtomicBoolean closed = new AtomicBoolean();

    public ArenaController(JavaPlugin plugin, ArenaRulesetStore store, java.nio.file.Path dataDirectory) throws IOException {
        this.plugin = plugin;
        this.store = store;
        matches = new ArenaMatchPersistence(dataDirectory);
        teamRoster.putAll(matches.loadTeamRoster());
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(128), task -> {
            Thread thread = new Thread(task, "StarGenesis-Arena");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public boolean handle(Player player, String[] args) {
        if (!player.hasPermission("stargenesis.arena")) {
            player.sendMessage("StarGenesis: permission denied (stargenesis.arena)");
            return true;
        }
        if (args.length == 0) { help(player); return true; }
        if (args[0].equalsIgnoreCase("match")) return match(player, args);
        if (closed.get()) { player.sendMessage("Arena 服务已停止。"); return true; }
        try {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "create" -> create(player, args);
                case "clone" -> clone(player, args);
                case "list" -> list(player, args);
                case "catalog" -> catalog(player, args);
                case "open" -> open(player, args);
                case "close" -> close(player, args);
                case "set" -> set(player, args);
                case "access" -> access(player, args);
                case "dep" -> dependency(player, args);
                case "undo" -> undo(player, args);
                case "preview" -> preview(player, args);
                case "save" -> save(player, args);
                case "publish" -> publish(player, args);
                case "review" -> review(player, args);
                default -> help(player);
            }
        } catch (IOException | IllegalArgumentException | SecurityException | IllegalStateException failure) {
            player.sendMessage("Arena 操作未完成：" + failure.getMessage());
        }
        return true;
    }

    public void close() {
        if (!closed.compareAndSet(false, true)) return;
        worker.shutdown();
        try {
            if (!worker.awaitTermination(5, TimeUnit.SECONDS)) worker.shutdownNow();
        } catch (InterruptedException interrupted) {
            worker.shutdownNow();
            Thread.currentThread().interrupt();
        }
        sessions.clear();
        invitations.clear();
        teamRoster.clear();
        activeMatches.clear();
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID owner = player.getUniqueId();
        enqueue(owner, player, new String[]{"restore"});
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        UUID leaving = event.getPlayer().getUniqueId();
        synchronized (invitationLock) {
            invitations.remove(leaving);
            invitations.entrySet().removeIf(entry -> entry.getValue().inviter().equals(leaving));
        }
    }

    private boolean match(Player player, String[] args) {
        if (closed.get()) { player.sendMessage("Arena 服务已停止。"); return true; }
        UUID owner = player.getUniqueId();
        String[] request = java.util.Arrays.copyOfRange(args, 1, args.length);
        if (request.length == 0) {
            send(owner, player, matchHelp());
            return true;
        }
        if (request[0].equalsIgnoreCase("invite")) {
            scheduleGlobal(() -> invite(player, request));
            return true;
        }
        if (request[0].equalsIgnoreCase("accept")) {
            scheduleGlobal(() -> accept(player, request));
            return true;
        }
        if (request[0].equalsIgnoreCase("start")) {
            scheduleGlobal(() -> enqueue(owner, player, request, Bukkit.getOnlinePlayers().stream()
                    .map(Player::getUniqueId).collect(java.util.stream.Collectors.toUnmodifiableSet())));
            return true;
        }
        enqueue(owner, player, request, Set.of());
        return true;
    }

    private void invite(Player inviter, String[] args) {
        UUID owner = inviter.getUniqueId();
        if (args.length != 3) { send(owner, inviter, List.of("用法：/genesis arena match invite <online player> <team>")); return; }
        if (!args[2].matches("[a-z][a-z0-9_-]{0,31}")) {
            send(owner, inviter, List.of("team ID 须为 1-32 位小写字母、数字、下划线或连字符。")); return;
        }
        String currentTeam = teamRoster.get(owner);
        if (currentTeam != null && !currentTeam.equals(args[2])) {
            send(owner, inviter, List.of("你已加入另一个 Arena 队伍，不能跨队邀请。")); return;
        }
        Player invited = Bukkit.getPlayerExact(args[1]);
        if (invited == null || invited.equals(inviter) || teamRoster.containsKey(invited.getUniqueId())
                || activeMatchPlayer(owner) || activeMatchPlayer(invited.getUniqueId())) {
            send(owner, inviter, List.of("需要指定一位在线且尚未加入 roster 的其他玩家。")); return;
        }
        Invitation invitation = new Invitation(owner, args[2]);
        boolean added;
        synchronized (invitationLock) {
            boolean inviterHasPending = invitations.values().stream()
                    .anyMatch(existing -> existing.inviter().equals(owner));
            added = !invitations.containsKey(invited.getUniqueId()) && !inviterHasPending;
            if (added) invitations.put(invited.getUniqueId(), invitation);
        }
        if (!added) {
            send(owner, inviter, List.of("你或受邀玩家已有待处理的 Arena 邀请。"));
            return;
        }
        send(owner, inviter, List.of("已邀请 " + invited.getName() + " 加入队伍 " + args[2] + "。"));
        send(invited.getUniqueId(), invited, List.of(inviter.getName() + " 邀请你加入 Arena 队伍 "
                + args[2] + "。使用 /genesis arena match accept 接受。"));
    }

    private void accept(Player player, String[] args) {
        UUID owner = player.getUniqueId();
        String name = player.getName();
        try { worker.execute(() -> acceptInvitation(owner, name, player)); }
        catch (RejectedExecutionException full) { send(owner, player, List.of("Arena 命令队列已满。")); }
    }

    private void acceptInvitation(UUID owner, String name, Player player) {
        Invitation invitation;
        synchronized (invitationLock) {
            invitation = invitations.remove(owner);
        }
        if (invitation == null || teamRoster.containsKey(owner) || activeMatchPlayer(owner)) {
            send(owner, player, List.of("没有可接受的有效 Arena 邀请。")); return;
        }
        long currentTeamSize = teamRoster.values().stream().filter(team -> team.equals(invitation.team())).count();
        String inviterTeam = teamRoster.get(invitation.inviter());
        if (inviterTeam != null && !inviterTeam.equals(invitation.team())) {
            send(owner, player, List.of("邀请方已加入另一个队伍。")); return;
        }
        if (activeMatchPlayer(invitation.inviter())) {
            send(owner, player, List.of("邀请方已进入另一场 Arena match。")); return;
        }
        int newMembers = inviterTeam == null ? 2 : 1;
        if (currentTeamSize + newMembers > 4 || teamRoster.size() + newMembers > ArenaMatchWorld.MAX_PLAYERS) {
            send(owner, player, List.of("该队伍或 Arena roster 已满。")); return;
        }
        if (inviterTeam == null) teamRoster.put(invitation.inviter(), invitation.team());
        teamRoster.put(owner, invitation.team());
        try {
            matches.saveTeamRoster(teamRoster);
        } catch (IOException | RuntimeException failure) {
            teamRoster.remove(owner, invitation.team());
            if (inviterTeam == null) teamRoster.remove(invitation.inviter(), invitation.team());
            synchronized (invitationLock) {
                invitations.put(owner, invitation);
            }
            plugin.getLogger().log(Level.SEVERE, "Arena team roster persistence failed for " + owner, failure);
            send(owner, player, List.of("Arena 组织状态未能持久保存；请重试接受邀请。"));
            return;
        }
        send(owner, player, List.of("已加入队伍 " + invitation.team() + "。"));
        scheduleGlobal(() -> {
            Player inviter = Bukkit.getPlayer(invitation.inviter());
            if (inviter != null) send(invitation.inviter(), inviter, List.of(name + " 已加入你的 Arena 队伍。"));
        });
    }

    private boolean activeMatchPlayer(UUID player) {
        return activeMatches.containsKey(player);
    }

    private boolean hasActiveMatch(UUID player) throws IOException {
        for (ArenaMatchPersistence.MatchSession session : matches.findForPlayer(player)) {
            if (!session.replay().teams().containsKey(player)) continue;
            try (ArenaMatchWorld world = restoreWorld(session.replay())) {
                if (!world.view().terminal()) return true;
            }
        }
        return false;
    }

    private void enqueue(UUID owner, Player player, String[] args) {
        try { worker.execute(() -> executeMatch(owner, player, args, Set.of())); }
        catch (RejectedExecutionException full) { send(owner, player, List.of("Arena 命令队列已满。")); }
    }

    private void enqueue(UUID owner, Player player, String[] args, Set<UUID> onlineAtStart) {
        try { worker.execute(() -> executeMatch(owner, player, args, onlineAtStart)); }
        catch (RejectedExecutionException full) { send(owner, player, List.of("Arena 命令队列已满。")); }
    }

    private void executeMatch(UUID owner, Player player, String[] args, Set<UUID> onlineAtStart) {
        List<String> response;
        try {
            response = executeMatch(owner, args, onlineAtStart);
            if (args.length > 0 && args[0].equalsIgnoreCase("start")) broadcastStarted(owner);
        }
        catch (IOException failure) {
            plugin.getLogger().log(Level.SEVERE, "Arena match persistence failed for " + owner, failure);
            response = List.of("Arena match 未能持久保存：" + failure.getMessage());
        } catch (RuntimeException failure) {
            plugin.getLogger().log(Level.WARNING, "Arena match operation failed for " + owner
                    + " while running " + String.join(" ", args), failure);
            response = List.of("Arena match 操作未完成：" + failure.getClass().getSimpleName()
                    + (failure.getMessage() == null ? "" : " · " + failure.getMessage()));
        }
        send(owner, player, response);
    }

    private List<String> executeMatch(UUID owner, String[] args, Set<UUID> onlineAtStart) throws IOException {
        if (args.length == 0) return List.of("/genesis arena match invite <player> <team> | accept | start <rulesetId> <version> [seed]",
                "/genesis arena match pass | claim <x> <y> <force> | dashboard | replay | watch <matchId> | matches");
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "restore" -> restorePlayer(owner);
            case "start" -> startMatch(owner, args, onlineAtStart);
            case "pass" -> submit(owner, ArenaAction.pass(owner));
            case "claim" -> claim(owner, args);
            case "dashboard" -> dashboardMatch(owner);
            case "replay" -> verifyMatch(owner);
            case "watch" -> watch(owner, args);
            case "matches" -> matchList(owner, args);
            default -> List.of("/genesis arena match invite <player> <team> | accept | start <rulesetId> <version> [seed]",
                    "/genesis arena match pass | claim <x> <y> <force> | dashboard | replay | watch <matchId> | matches");
        };
    }

    private List<String> restorePlayer(UUID owner) throws IOException {
        List<ArenaMatchPersistence.MatchSession> saved = matches.findForPlayer(owner);
        if (saved.isEmpty()) return List.of();
        ArenaMatchPersistence.MatchSession session = saved.stream()
                .filter(match -> match.replay().teams().containsKey(owner))
                .max(Comparator.comparingLong(match -> match.replay().frames().size())).orElse(saved.getFirst());
        requirePinnedRuleset(session.replay());
        try (ArenaMatchWorld restored = restoreWorld(session.replay())) {
            if (!restored.view().terminal()) activeMatches.put(owner, session.replay().matchId());
        }
        session.replay().teams().forEach(teamRoster::putIfAbsent);
        return List.of("已恢复 Arena match " + session.replay().matchId()
                + " · round=" + session.replay().frames().size() + " · pending=" + session.pending().size());
    }

    private List<String> startMatch(UUID owner, String[] args, Set<UUID> onlineAtStart) throws IOException {
        if (args.length < 3 || args.length > 4)
            throw new IllegalArgumentException("用法：/genesis arena match start <rulesetId> <version> [seed]");
        Map<UUID, String> roster = new TreeMap<>(Comparator.comparing(UUID::toString));
        teamRoster.forEach(roster::put);
        Map<String, Long> teamCounts = roster.values().stream()
                .collect(java.util.stream.Collectors.groupingBy(team -> team, TreeMap::new, java.util.stream.Collectors.counting()));
        if (roster.size() < 4 || roster.size() > ArenaMatchWorld.MAX_PLAYERS
                || teamCounts.size() != 2 || teamCounts.values().stream().anyMatch(count -> count < 2 || count > 4))
            throw new IllegalStateException("Arena 至少需要两队各2名玩家，最多8人。");
        for (UUID participant : roster.keySet()) {
            if (hasActiveMatch(participant))
                throw new IllegalStateException("Roster 中有玩家正在参加另一场 Arena match。");
        }
        if (!onlineAtStart.containsAll(roster.keySet())) throw new IllegalStateException("比赛开始时全部参与者必须在线。");
        if (!roster.containsKey(owner)) throw new SecurityException("Arena starter must join the roster");
        ArenaRuleset ruleset = store.loadPublished(new DesignId(args[1]), args[2])
                .orElseThrow(() -> new IllegalArgumentException("指定的已发布 ArenaRuleset 不存在。"));
        store.validatePublishedDependencies(ruleset);
        String matchId = ArenaMatchWorld.matchIdFor(ruleset.metadata().hash(), roster);
        if (matches.load(matchId).isPresent()) throw new IllegalStateException("此 roster/ruleset 已有比赛记录。");
        long seed = args.length == 4 ? Long.parseLong(args[3]) : 20_261_122L;
        ArenaConfig config = new ArenaConfig(ruleset.width(), ruleset.height(), ruleset.durationSteps(), ruleset.unitBudget());
        try (ArenaMatchWorld world = new ArenaMatchWorld(matchId, seed, config, ruleset.metadata().id().value(),
                ruleset.metadata().version(), ruleset.metadata().hash(), roster)) {
            matches.save(ArenaMatchPersistence.MatchSession.initial(world.replay()));
            roster.keySet().forEach(player -> activeMatches.put(player, matchId));
            List<String> result = new ArrayList<>(viewLines(world.view()));
            result.add(0, "Arena match 已创建；每个回合所有玩家各提交一次动作，matchId=" + matchId);
            return List.copyOf(result);
        }
    }

    private List<String> claim(UUID owner, String[] args) throws IOException {
        if (args.length != 4) throw new IllegalArgumentException("用法：/genesis arena match claim <x> <y> <force>");
        return submit(owner, ArenaAction.claim(owner, Integer.parseInt(args[1]), Integer.parseInt(args[2]),
                Integer.parseInt(args[3])));
    }

    private List<String> submit(UUID owner, ArenaAction action) throws IOException {
        String matchId = matchIdFor(owner);
        ArenaMatchPersistence.MatchSession session = matches.load(matchId).orElseThrow();
        requirePinnedRuleset(session.replay());
        ArenaMatchRound.Submission submission = ArenaMatchRound.submit(session, owner, action);
        matches.save(submission.session());
        if (!submission.session().pending().isEmpty()) {
            return List.of("动作已封存 · round=" + session.replay().frames().size()
                    + " · waiting=" + (session.replay().teams().size() - submission.session().pending().size()));
        }
        if (submission.view().terminal()) session.replay().teams().keySet().forEach(activeMatches::remove);
        return viewLines(submission.view());
    }

    private List<String> dashboardMatch(UUID owner) throws IOException {
        String matchId = matchIdFor(owner);
        ArenaMatchPersistence.MatchSession session = matches.load(matchId).orElseThrow();
        if (!session.replay().teams().containsKey(owner) && !session.spectators().contains(owner))
            throw new SecurityException("Arena dashboard is restricted to participants and observers");
        try (ArenaMatchWorld world = restoreWorld(session.replay())) {
            return viewLines(world.observe(owner));
        }
    }

    private List<String> verifyMatch(UUID owner) throws IOException {
        String matchId = matchIdFor(owner);
        ArenaMatchPersistence.MatchSession session = matches.load(matchId).orElseThrow();
        if (!session.replay().teams().containsKey(owner) && !session.spectators().contains(owner))
            throw new SecurityException("Only participants and observers may replay this match");
        requirePinnedRuleset(session.replay());
        ArenaMatchReplayVerifier.Verification result = ArenaMatchReplayVerifier.verify(session.replay());
        if (!result.passed()) throw new IllegalStateException("Arena replay rejected: " + result.reason());
        return List.of("Arena replay verified " + session.replay().frames().size() + " rounds · ruleset="
                + session.replay().rulesetId() + " v" + session.replay().rulesetVersion()
                + " · initial=" + session.replay().initialStateHash().substring(0, 12));
    }

    private List<String> watch(UUID owner, String[] args) throws IOException {
        if (args.length != 2) throw new IllegalArgumentException("用法：/genesis arena match watch <matchId>");
        ArenaMatchPersistence.MatchSession session = matches.load(args[1])
                .orElseThrow(() -> new IllegalArgumentException("Arena match 不存在。"));
        if (session.replay().teams().containsKey(owner)) throw new IllegalArgumentException("参赛玩家无需登记为 observer。");
        if (!session.spectators().contains(owner) && session.spectators().size() >= 128)
            throw new IllegalStateException("Arena observer budget exhausted");
        List<UUID> observers = new ArrayList<>(session.spectators());
        if (!observers.contains(owner)) observers.add(owner);
        matches.save(new ArenaMatchPersistence.MatchSession(ArenaMatchPersistence.SCHEMA_VERSION,
                session.replay(), session.pending(), observers));
        try (ArenaMatchWorld world = restoreWorld(session.replay())) {
            List<String> lines = new ArrayList<>();
            lines.add("Observer mode is read-only · match=" + session.replay().matchId());
            lines.addAll(viewLines(world.observe(owner)));
            return List.copyOf(lines);
        }
    }

    private List<String> matchList(UUID owner, String[] args) throws IOException {
        if (args.length != 1) throw new IllegalArgumentException("用法：/genesis arena match matches");
        List<ArenaMatchPersistence.MatchSession> entries = matches.catalog().stream()
                .filter(session -> session.replay().teams().containsKey(owner)
                        || session.spectators().contains(owner)).toList();
        if (entries.isEmpty()) return List.of("没有参与或观察的 Arena matches。");
        return entries.stream().map(session -> session.replay().matchId()
                + " · round=" + session.replay().frames().size() + " · pending=" + session.pending().size()
                + " · ruleset=" + session.replay().rulesetId() + " v" + session.replay().rulesetVersion()).toList();
    }

    private List<String> viewLines(ArenaMatchView view) {
        List<String> lines = new ArrayList<>();
        lines.add("Arena " + view.rulesetId() + " v" + view.rulesetVersion() + " · round="
                + view.tick() + "/" + view.durationSteps());
        lines.add("Territory=" + view.territory() + " · units=" + view.remainingUnits());
        lines.add("Leaderboard=" + view.leaderboard() + (view.settledWinner().isEmpty()
                ? "" : " · winner=" + view.settledWinner()));
        lines.add("state=" + view.stateHash().substring(0, 12) + " · " + view.reason());
        return List.copyOf(lines);
    }

    private List<String> matchHelp() {
        return List.of("/genesis arena match invite <player> <team> | accept | start <rulesetId> <version> [seed]",
                "/genesis arena match pass | claim <x> <y> <force> | dashboard | replay | watch <matchId> | matches");
    }

    private String matchIdFor(UUID owner) throws IOException {
        String active = activeMatches.get(owner);
        if (active != null) {
            ArenaMatchPersistence.MatchSession current = matches.load(active).orElse(null);
            if (current != null && current.replay().teams().containsKey(owner)) return active;
        }
        List<ArenaMatchPersistence.MatchSession> found = matches.findForPlayer(owner);
        if (found.isEmpty()) throw new IllegalStateException("当前玩家不属于 Arena match。");
        ArenaMatchPersistence.MatchSession selected = found.stream()
                .filter(match -> match.replay().teams().containsKey(owner))
                .max(Comparator.comparingLong(match -> match.replay().frames().size())).orElse(found.getFirst());
        try (ArenaMatchWorld restored = restoreWorld(selected.replay())) {
            if (!restored.view().terminal()) activeMatches.put(owner, selected.replay().matchId());
        }
        return selected.replay().matchId();
    }

    private ArenaMatchWorld restoreWorld(ArenaMatchReplay replay) throws IOException {
        requirePinnedRuleset(replay);
        return ArenaMatchWorld.restore(replay);
    }

    private void requirePinnedRuleset(ArenaMatchReplay replay) throws IOException {
        ArenaRuleset ruleset = store.requirePinnedPlayable(replay.rulesetId(), replay.rulesetVersion(), replay.rulesetHash());
        if (ruleset.width() != replay.config().width() || ruleset.height() != replay.config().height()
                || ruleset.durationSteps() != replay.config().durationSteps()
                || ruleset.unitBudget() != replay.config().unitBudget())
            throw new IOException("Arena replay config differs from its pinned ruleset");
    }

    private void scheduleGlobal(Runnable task) {
        plugin.getServer().getGlobalRegionScheduler().execute(plugin, task);
    }

    private void broadcastStarted(UUID owner) throws IOException {
        List<ArenaMatchPersistence.MatchSession> sessions = matches.findForPlayer(owner);
        if (sessions.isEmpty()) return;
        ArenaMatchPersistence.MatchSession started = sessions.stream()
                .filter(session -> session.replay().teams().containsKey(owner)).findFirst().orElse(sessions.getFirst());
        scheduleGlobal(() -> started.replay().teams().keySet().forEach(participant -> {
            Player online = Bukkit.getPlayer(participant);
            if (online != null) send(participant, online, List.of("Arena match started · matchId=" + started.replay().matchId()));
        }));
    }

    private void send(UUID owner, Player player, List<String> lines) {
        if (player == null) return;
        try {
            player.getScheduler().run(plugin, task -> {
                if (player.isOnline()) lines.forEach(player::sendMessage);
            }, () -> { });
        } catch (RuntimeException ignored) { }
    }

    private void create(Player player, String[] args) throws IOException {
        requireLength(args, 2);
        UUID owner = player.getUniqueId();
        requireNoOpenSession(owner);
        ArenaRuleset draft = store.create(owner, new DesignId(args[1]));
        putSession(owner, draft);
        player.sendMessage("已创建私有 ArenaRuleset 草稿 " + draft.metadata().id().value() + "。编辑后 save，再 publish。hash="
                + draft.metadata().hash().substring(0, 12));
    }

    private void clone(Player player, String[] args) throws IOException {
        requireLength(args, 3);
        UUID owner = player.getUniqueId();
        requireNoOpenSession(owner);
        ArenaRuleset draft = store.clonePublished(owner, new DesignId(args[1]), new DesignId(args[2]));
        putSession(owner, draft);
        player.sendMessage("已克隆可复用规则为私有草稿 " + draft.metadata().id().value() + "。hash="
                + draft.metadata().hash().substring(0, 12));
    }

    private void list(Player player, String[] args) throws IOException {
        requireLength(args, 1);
        List<ArenaRuleset> drafts = store.listDrafts(player.getUniqueId());
        if (drafts.isEmpty()) { player.sendMessage("没有个人 ArenaRuleset 草稿。使用 /genesis arena create <namespace:id>。"); return; }
        player.sendMessage("个人 arena 草稿 " + drafts.size() + "/" + ArenaRulesetStore.MAX_DRAFTS_PER_AUTHOR + ":");
        drafts.forEach(draft -> player.sendMessage(draft.metadata().id().value() + " v" + draft.metadata().version()
                + " · " + draft.width() + "x" + draft.height() + " · hash=" + draft.metadata().hash().substring(0, 12)));
    }

    private void catalog(Player player, String[] args) throws IOException {
        requireLength(args, 1);
        List<ArenaRuleset> entries = store.catalog();
        player.sendMessage("公开规则库 " + entries.size() + "/" + (ArenaRulesetStore.MAX_PUBLISHED_ARENAS + 1) + ":");
        entries.forEach(entry -> player.sendMessage(entry.metadata().id().value() + " v" + entry.metadata().version()
                + " · " + entry.width() + "x" + entry.height() + " · clone="
                + entry.metadata().permissions().allowClone() + " · hash=" + entry.metadata().hash().substring(0, 12)));
    }

    private void open(Player player, String[] args) throws IOException {
        requireLength(args, 2);
        UUID owner = player.getUniqueId();
        requireNoOpenSession(owner);
        ArenaRuleset draft = store.loadDraft(owner, new DesignId(args[1]))
                .orElseThrow(() -> new IllegalArgumentException("找不到可编辑的 ArenaRuleset 草稿 " + args[1]));
        putSession(owner, draft);
        player.sendMessage("已打开 " + draft.metadata().id().value() + " v" + draft.metadata().version()
                + " · hash=" + draft.metadata().hash().substring(0, 12));
    }

    private void close(Player player, String[] args) {
        if (args.length > 2 || args.length == 2 && !args[1].equalsIgnoreCase("discard"))
            throw new IllegalArgumentException("用法：/genesis arena close [discard]");
        UUID owner = player.getUniqueId();
        Session session = requireSession(owner);
        if (session.dirty() && args.length == 1) throw new IllegalArgumentException("草稿尚未保存；先 save 或 close discard。");
        sessions.remove(owner, session);
        player.sendMessage(session.dirty() ? "未保存的 arena 修改已丢弃。" : "已关闭 ArenaRuleset 草稿。");
    }

    private void set(Player player, String[] args) {
        requireLength(args, 3);
        Session session = requireSession(player.getUniqueId());
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "width" -> session.workspace.resize(integer(args[2]), session.workspace.current().height());
            case "height" -> session.workspace.resize(session.workspace.current().width(), integer(args[2]));
            case "duration" -> session.workspace.duration(Long.parseLong(args[2]));
            case "units" -> session.workspace.unitBudget(integer(args[2]));
            case "version" -> {
                requireAuthor(player, session);
                session.workspace.version(args[2]);
            }
            default -> throw new IllegalArgumentException("字段须为 width、height、duration、units 或 version");
        }
        player.sendMessage("已修改 " + args[1] + "，保存后新 hash 才会发布。");
    }

    private void access(Player player, String[] args) {
        requireLength(args, 3);
        Session session = requireSession(player.getUniqueId());
        requireAuthor(player, session);
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "public" -> session.workspace.publicRead(enabled(args[2]));
            case "clone" -> session.workspace.allowClone(enabled(args[2]));
            case "editor" -> session.workspace.editor(uuid(args[2]), true);
            case "revoke" -> session.workspace.editor(uuid(args[2]), false);
            default -> throw new IllegalArgumentException("字段须为 public、clone、editor 或 revoke");
        }
        if (args[1].equalsIgnoreCase("editor") || args[1].equalsIgnoreCase("revoke"))
            player.sendMessage("已更新草稿编辑者许可。");
        else player.sendMessage("已更新 " + args[1] + " 许可。");
    }

    private void requireAuthor(Player player, Session session) {
        if (!player.getUniqueId().equals(session.workspace.current().metadata().author()))
            throw new SecurityException("Only the Arena author may change permissions, version or provenance");
    }

    private void dependency(Player player, String[] args) {
        if (args.length < 3) throw new IllegalArgumentException("用法：/genesis arena dep add <id> <version> <sha256> | dep remove <id>");
        Session session = requireSession(player.getUniqueId());
        requireAuthor(player, session);
        if (args[1].equalsIgnoreCase("add")) {
            requireLength(args, 5);
            session.workspace.addDependency(new DesignDependency(new DesignId(args[2]), args[3], args[4]));
            player.sendMessage("已固定依赖 " + args[2] + " v" + args[3] + " hash=" + args[4].substring(0, 12));
        } else if (args[1].equalsIgnoreCase("remove")) {
            requireLength(args, 3);
            session.workspace.removeDependency(args[2]);
            player.sendMessage("已移除依赖 " + args[2]);
        } else throw new IllegalArgumentException("用法：/genesis arena dep add|remove ...");
    }

    private void undo(Player player, String[] args) {
        requireLength(args, 1);
        Session session = requireSession(player.getUniqueId());
        if (!session.workspace.undo()) throw new IllegalArgumentException("没有可撤销的 arena 修改");
        player.sendMessage("已撤销一步；剩余 " + session.workspace.undoDepth() + "/" + ArenaRulesetWorkspace.MAX_UNDO_STEPS);
    }

    private void preview(Player player, String[] args) {
        requireLength(args, 1);
        ArenaRuleset ruleset = requireSession(player.getUniqueId()).workspace.current();
        player.sendMessage("ArenaRuleset " + ruleset.metadata().id().value() + " v" + ruleset.metadata().version());
        player.sendMessage("Bounds=" + ruleset.width() + "x" + ruleset.height() + " · duration="
                + ruleset.durationSteps() + " · unitBudget=" + ruleset.unitBudget());
        player.sendMessage("Dependencies=" + ruleset.metadata().dependencies().size() + " · publicRead="
                + ruleset.metadata().permissions().publicRead() + " · allowClone="
                + ruleset.metadata().permissions().allowClone());
    }

    private void save(Player player, String[] args) throws IOException {
        requireLength(args, 1);
        UUID owner = player.getUniqueId();
        Session session = requireSession(owner);
        ArenaRuleset saved = store.saveDraft(owner, session.workspace.current(), session.baseHash);
        putSession(owner, saved);
        player.sendMessage("ArenaRuleset 草稿已原子保存 · hash=" + saved.metadata().hash().substring(0, 12));
    }

    private void publish(Player player, String[] args) throws IOException {
        requireLength(args, 1);
        Session session = requireSession(player.getUniqueId());
        if (session.dirty()) throw new IllegalArgumentException("先保存草稿再发布。");
        ArenaRulesetStore.ReviewRecord submission = store.submitForReview(player.getUniqueId(),
                session.workspace.current().metadata().id(), session.baseHash);
        player.sendMessage("ArenaRuleset 已提交审核 " + submission.id().value() + " v" + submission.version()
                + " · hash=" + submission.contentHash());
    }

    private void review(Player player, String[] args) throws IOException {
        if (args.length == 4 && args[1].equalsIgnoreCase("status")) {
            ArenaRulesetStore.ReviewRecord record = store.reviewRecord(new DesignId(args[2]), args[3])
                    .orElseThrow(() -> new IllegalArgumentException("Arena review record does not exist"));
            if (!record.author().equals(player.getUniqueId()) && !player.hasPermission("stargenesis.arena.review")) {
                player.sendMessage("StarGenesis: permission denied (arena review status belongs to its author)");
                return;
            }
            player.sendMessage("Arena review " + record.id().value() + " v" + record.version()
                    + " · status=" + record.status() + " · hash=" + record.contentHash());
            if (!record.reason().isEmpty()) player.sendMessage("review reason=" + record.reason());
            return;
        }
        if (!player.hasPermission("stargenesis.arena.review")) {
            player.sendMessage("StarGenesis: permission denied (stargenesis.arena.review)");
            return;
        }
        if (args.length == 2 && args[1].equalsIgnoreCase("pending")) {
            List<ArenaRulesetStore.ReviewRecord> pending = store.pendingReviews();
            player.sendMessage("Arena 审核队列 " + pending.size() + "/" + ArenaRulesetStore.MAX_REVIEW_RECORDS + ":");
            pending.forEach(entry -> player.sendMessage(entry.id().value() + " v" + entry.version()
                    + " · author=" + entry.author() + " · hash=" + entry.contentHash()));
            return;
        }
        if (args.length == 4 && args[1].equalsIgnoreCase("show")) {
            ArenaRulesetStore.ReviewRecord record = store.reviewRecord(new DesignId(args[2]), args[3])
                    .orElseThrow(() -> new IllegalArgumentException("Arena review record does not exist"));
            player.sendMessage("Arena review " + record.id().value() + " v" + record.version()
                    + " · status=" + record.status() + " · author=" + record.author()
                    + " · hash=" + record.contentHash());
            if (record.reviewer() != null) player.sendMessage("reviewer=" + record.reviewer()
                    + " · reason=" + record.reason() + " · reviewed=" + record.reviewedAt());
            return;
        }
        if (args.length == 4 && args[1].equalsIgnoreCase("approve")) {
            ArenaRulesetStore.ReviewRecord approved = store.approveReview(player.getUniqueId(),
                    new DesignId(args[2]), args[3]);
            player.sendMessage("已批准并发布 " + approved.id().value() + " v" + approved.version()
                    + " · hash=" + approved.contentHash());
            return;
        }
        if (args.length >= 5 && args[1].equalsIgnoreCase("reject")) {
            ArenaRulesetStore.ReviewRecord rejected = store.rejectReview(player.getUniqueId(),
                    new DesignId(args[2]), args[3], String.join(" ", java.util.Arrays.copyOfRange(args, 4, args.length)));
            player.sendMessage("已拒绝 " + rejected.id().value() + " v" + rejected.version()
                    + " · reason=" + rejected.reason());
            return;
        }
        player.sendMessage("/genesis arena review pending | status <id> <version> | show <id> <version> | approve <id> <version> | reject <id> <version> <reason>");
    }

    private void putSession(UUID owner, ArenaRuleset document) {
        sessions.put(owner, new Session(document, document.metadata().hash()));
    }

    private void requireNoOpenSession(UUID owner) {
        if (sessions.containsKey(owner)) throw new IllegalArgumentException("先关闭当前 ArenaRuleset 草稿。");
    }

    private Session requireSession(UUID owner) {
        Session session = sessions.get(owner);
        if (session == null) throw new IllegalArgumentException("没有打开的 ArenaRuleset 草稿");
        return session;
    }

    private void help(Player player) {
        player.sendMessage("/genesis arena create <id> | clone <publishedId> <newId> | list | catalog");
        player.sendMessage("/genesis arena open <id> | set <width|height|duration|units|version> <value> | dep add|remove ...");
        player.sendMessage("/genesis arena access <public|clone> <on|off> | access <editor|revoke> <UUID> | undo | preview | save | publish | close [discard]");
        player.sendMessage("publish 提交公开审核；审核人使用 /genesis arena review pending|approve|reject。公开比赛只加载已批准版本。");
    }

    private int integer(String value) { return Integer.parseInt(value); }

    private boolean enabled(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "on", "true" -> true;
            case "off", "false" -> false;
            default -> throw new IllegalArgumentException("访问开关须为 on 或 off");
        };
    }

    private UUID uuid(String value) {
        UUID result = UUID.fromString(value);
        if (!result.toString().equals(value)) throw new IllegalArgumentException("UUID 必须为规范小写格式");
        return result;
    }

    private void requireLength(String[] args, int expected) {
        if (args.length != expected) throw new IllegalArgumentException("参数数量不正确；使用 /genesis arena 查看用法");
    }

    private static final class Session {
        private final ArenaRulesetWorkspace workspace;
        private final String baseHash;
        private final ArenaRuleset saved;

        private Session(ArenaRuleset saved, String baseHash) {
            this.saved = saved;
            this.baseHash = baseHash;
            workspace = new ArenaRulesetWorkspace(saved);
        }

        private boolean dirty() { return !workspace.current().equals(saved); }
    }

    private record Invitation(UUID inviter, String team) { }
}
