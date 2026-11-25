package dev.stargenesis.paper.factory;

import dev.stargenesis.sim.FactoryAction;
import dev.stargenesis.sim.FactoryConfig;
import dev.stargenesis.sim.FactoryInput;
import dev.stargenesis.sim.FactoryReplay;
import dev.stargenesis.sim.FactoryReplayVerifier;
import dev.stargenesis.sim.FactoryView;
import dev.stargenesis.sim.FactoryWorld;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.stream.Collectors;

/** Paper/Folia command adapter for simulated organizations and four-seat factory matches. */
public final class FactoryController implements Listener, AutoCloseable {
    private static final int MAX_QUEUE = 128;
    private static final long DEFAULT_SEED = 20_261_123L;
    private final JavaPlugin plugin;
    private final FactoryPersistence persistence;
    private final ThreadPoolExecutor worker;
    private final ConcurrentMap<UUID, UUID> invitations = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, UUID> partners = new ConcurrentHashMap<>();
    private final ConcurrentMap<UUID, String> activeMatches = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public FactoryController(JavaPlugin plugin, Path directory) throws IOException {
        this.plugin = plugin;
        persistence = new FactoryPersistence(directory);
        partners.putAll(persistence.loadOrganizations());
        worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(MAX_QUEUE), task -> {
            Thread thread = new Thread(task, "StarGenesis-Factory");
            thread.setDaemon(true);
            return thread;
        }, new ThreadPoolExecutor.AbortPolicy());
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public boolean handle(Player player, String[] args) {
        if (!player.hasPermission("stargenesis.factory")) {
            player.sendMessage("StarGenesis: permission denied (stargenesis.factory)");
            return true;
        }
        if (closed.get()) {
            player.sendMessage("工厂模拟已停止。");
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("invite")) return invite(player, args);
        if (args.length > 0 && args[0].equalsIgnoreCase("accept")) return accept(player, args);
        UUID owner = player.getUniqueId();
        String[] request = args.clone();
        if (args.length > 0 && args[0].equalsIgnoreCase("start")) {
            plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
                Set<UUID> online = plugin.getServer().getOnlinePlayers().stream()
                        .map(Player::getUniqueId).collect(Collectors.toUnmodifiableSet());
                enqueue(owner, player, request, online);
            });
            return true;
        }
        enqueue(owner, player, request, Set.of());
        return true;
    }

    private void enqueue(UUID owner, Player player, String[] request, Set<UUID> onlineAtStart) {
        try {
            worker.execute(() -> run(owner, player, request, onlineAtStart));
        } catch (RejectedExecutionException full) {
            send(owner, player, List.of("工厂命令队列已满，请稍后重试。"));
        }
    }

    @EventHandler public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID owner = player.getUniqueId();
        try {
            worker.execute(() -> {
                try {
                    List<FactoryReplay> matches = persistence.findForPlayer(owner);
                    if (!matches.isEmpty()) {
                        FactoryReplay match = matches.getFirst();
                        activeMatches.put(owner, match.matchId());
                        registerPair(match.participants().get(0), match.participants().get(1));
                        registerPair(match.participants().get(2), match.participants().get(3));
                        persistence.saveOrganizations(partners);
                        send(owner, player, List.of("已恢复工厂 " + match.matchId().substring(8, 20)
                                + "，使用 /genesis factory dashboard 查看状态。"));
                    }
                } catch (IOException failure) {
                    plugin.getLogger().log(Level.SEVERE, "Could not restore factory match for " + owner, failure);
                }
            });
        } catch (RejectedExecutionException full) {
            plugin.getLogger().warning("Factory recovery queue is full for " + owner);
        }
    }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        UUID leaving = event.getPlayer().getUniqueId();
        invitations.remove(leaving);
        invitations.entrySet().removeIf(invitation -> invitation.getValue().equals(leaving));
    }

    private boolean invite(Player inviter, String[] args) {
        if (args.length != 2) {
            inviter.sendMessage("用法：/genesis factory invite <online player>");
            return true;
        }
        UUID owner = inviter.getUniqueId();
        if (partners.containsKey(owner)) {
            inviter.sendMessage("你已属于一个双人模拟组织。");
            return true;
        }
        Player invited = Bukkit.getPlayerExact(args[1]);
        if (invited == null || invited.equals(inviter) || partners.containsKey(invited.getUniqueId())) {
            inviter.sendMessage("需要指定一位在线且尚未加入组织的其他玩家。");
            return true;
        }
        invitations.put(invited.getUniqueId(), owner);
        send(owner, inviter, List.of("已邀请 " + invited.getName() + " 加入双人模拟组织。"));
        send(invited.getUniqueId(), invited, List.of(inviter.getName() + " 邀请你组成模拟工厂组织。使用 /genesis factory accept。"));
        return true;
    }

    private boolean accept(Player player, String[] args) {
        if (args.length != 1) {
            player.sendMessage("用法：/genesis factory accept");
            return true;
        }
        UUID invited = player.getUniqueId();
        String invitedName = player.getName();
        try {
            worker.execute(() -> acceptInvitation(invited, invitedName, player));
        } catch (RejectedExecutionException full) {
            player.sendMessage("工厂命令队列已满，请稍后重试。");
        }
        return true;
    }

    private void acceptInvitation(UUID invited, String invitedName, Player player) {
        UUID inviter = invitations.remove(invited);
        if (inviter == null || partners.containsKey(invited) || partners.containsKey(inviter)) {
            send(invited, player, List.of("没有可接受的有效组织邀请。"));
            return;
        }
        partners.put(inviter, invited);
        partners.put(invited, inviter);
        try {
            persistence.saveOrganizations(partners);
        } catch (IOException | RuntimeException failure) {
            partners.remove(invited, inviter);
            partners.remove(inviter, invited);
            invitations.put(invited, inviter);
            plugin.getLogger().log(Level.SEVERE, "Could not save factory organization for " + invited, failure);
            send(invited, player, List.of("组织状态未能持久保存，请重试接受邀请。"));
            return;
        }
        send(invited, player, List.of("已组成双人模拟组织。集齐另一个双人组织后即可开始四人工厂。"));
        notifyOnline(inviter, invitedName + " 已接受组织邀请。");
    }

    private void registerPair(UUID first, UUID second) throws IOException {
        UUID existingFirst = partners.get(first);
        UUID existingSecond = partners.get(second);
        if (existingFirst != null && !existingFirst.equals(second)
                || existingSecond != null && !existingSecond.equals(first))
            throw new IOException("Factory replay organization conflicts with saved membership");
        partners.put(first, second);
        partners.put(second, first);
    }

    private void run(UUID owner, Player player, String[] args, Set<UUID> onlineAtStart) {
        List<String> lines;
        try {
            lines = run(owner, args, onlineAtStart);
            if (args.length > 0 && args[0].equalsIgnoreCase("start")) broadcastMatch(owner);
        }
        catch (IOException failure) {
            plugin.getLogger().log(Level.SEVERE, "Factory persistence failed for " + owner, failure);
            lines = List.of("工厂回放未保存：" + failure.getMessage());
        } catch (RuntimeException failure) {
            lines = List.of("工厂操作未完成：" + failure.getMessage());
        }
        send(owner, player, lines);
    }

    private void broadcastMatch(UUID owner) throws IOException {
        FactoryReplay replay = persistence.findForPlayer(owner).stream().findFirst().orElse(null);
        if (replay == null) return;
        plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
            for (UUID participant : replay.participants()) {
                Player player = plugin.getServer().getPlayer(participant);
                if (player != null) send(participant, player,
                        List.of("四人工厂已开始 · match=" + replay.matchId().substring(8, 20)));
            }
        });
    }

    private List<String> run(UUID owner, String[] args, Set<UUID> onlineAtStart) throws IOException {
        if (args.length == 0) return help();
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "start" -> start(owner, args, onlineAtStart);
            case "bid" -> bid(owner, args);
            case "order" -> order(owner, args);
            case "step" -> step(owner, args);
            case "dashboard" -> dashboard(owner, args);
            case "replay" -> replay(owner, args);
            case "reset" -> reset(owner, args);
            default -> help();
        };
    }

    private List<String> start(UUID owner, String[] args, Set<UUID> onlineAtStart) throws IOException {
        if (args.length > 2) throw new IllegalArgumentException("用法：/genesis factory start [seed]");
        List<FactoryReplay> previous = persistence.findForPlayer(owner);
        if (!previous.isEmpty()) {
            activeMatches.put(owner, previous.getFirst().matchId());
            throw new IllegalStateException("你已有工厂回放，先使用 dashboard、replay 或 reset。");
        }
        List<UUID> roster = roster(owner);
        if (!onlineAtStart.containsAll(roster))
            throw new IllegalStateException("四名工厂参与者必须同时在线才能开始比赛。");
        String matchId = FactoryWorld.matchIdFor(roster);
        if (persistence.load(matchId).isPresent()) throw new IllegalStateException("该四人工厂已有回放，先 replay 或 reset。");
        long seed = args.length == 2 ? Long.parseLong(args[1]) : DEFAULT_SEED;
        try (FactoryWorld world = new FactoryWorld(matchId, roster, seed, FactoryConfig.defaults())) {
            persistence.save(world.replay());
            activeMatches.putAll(roster.stream().collect(java.util.stream.Collectors.toMap(id -> id, id -> matchId)));
            return dashboardLines(world.view());
        }
    }

    private List<String> bid(UUID owner, String[] args) throws IOException {
        if (args.length != 2) throw new IllegalArgumentException("用法：/genesis factory bid <credits>");
        return mutate(owner, new FactoryAction(owner, FactoryAction.Type.BID, "ore_1", Long.parseLong(args[1])));
    }

    private List<String> order(UUID owner, String[] args) throws IOException {
        if (args.length != 3) throw new IllegalArgumentException("用法：/genesis factory order <orderId> <quantity>");
        return mutate(owner, new FactoryAction(owner, FactoryAction.Type.PLACE_ORDER, args[1], Long.parseLong(args[2])));
    }

    private List<String> mutate(UUID owner, FactoryAction action) throws IOException {
        try (FactoryWorld world = load(owner)) {
            world.step(new FactoryInput(world.tick(), action));
            persistence.save(world.replay());
            return dashboardLines(world.view());
        }
    }

    private List<String> step(UUID owner, String[] args) throws IOException {
        if (args.length > 2) throw new IllegalArgumentException("用法：/genesis factory step [1-10]");
        int count = args.length == 2 ? Integer.parseInt(args[1]) : 1;
        if (count < 1 || count > 10) throw new IllegalArgumentException("step 每次推进1到10 tick");
        try (FactoryWorld world = load(owner)) {
            for (int i = 0; i < count && !world.view().terminal(); i++)
                world.step(new FactoryInput(world.tick(), FactoryAction.pass(owner)));
            persistence.save(world.replay());
            return dashboardLines(world.view());
        }
    }

    private List<String> dashboard(UUID owner, String[] args) throws IOException {
        requireLength(args, 1);
        try (FactoryWorld world = load(owner)) { return dashboardLines(world.view()); }
    }

    private List<String> replay(UUID owner, String[] args) throws IOException {
        requireLength(args, 1);
        FactoryReplay saved = persistence.load(matchIdFor(owner))
                .orElseThrow(() -> new IllegalStateException("没有可验证的工厂回放。"));
        FactoryReplayVerifier.Verification verification = FactoryReplayVerifier.verify(saved);
        if (!verification.passed()) throw new IllegalStateException("工厂回放验证失败：" + verification.reason());
        return List.of("四人工厂回放通过 " + saved.frames().size() + " ticks · hash="
                + saved.initialStateHash().substring(0, 12));
    }

    private List<String> reset(UUID owner, String[] args) throws IOException {
        requireLength(args, 1);
        String matchId = matchIdFor(owner);
        boolean deleted = persistence.delete(matchId);
        activeMatches.entrySet().removeIf(entry -> entry.getValue().equals(matchId));
        return List.of(deleted ? "已删除该四人工厂及其回放。" : "没有可删除的工厂回放。");
    }

    private FactoryWorld load(UUID owner) throws IOException {
        String matchId = matchIdFor(owner);
        FactoryReplay replay = persistence.load(matchId)
                .orElseThrow(() -> new IllegalStateException("先使用 /genesis factory start [seed]。"));
        if (!replay.participants().contains(owner)) throw new SecurityException("工厂成员身份不匹配");
        return FactoryWorld.restore(replay);
    }

    private String matchIdFor(UUID owner) throws IOException {
        String active = activeMatches.get(owner);
        if (active != null && persistence.load(active).isPresent()) return active;
        List<FactoryReplay> saved = persistence.findForPlayer(owner);
        if (!saved.isEmpty()) {
            activeMatches.put(owner, saved.getFirst().matchId());
            return saved.getFirst().matchId();
        }
        return FactoryWorld.matchIdFor(roster(owner));
    }

    private List<UUID> roster(UUID owner) throws IOException {
        UUID partner = partners.get(owner);
        if (partner == null || !owner.equals(partners.get(partner)))
            throw new IllegalStateException("先使用 invite/accept 组成双人组织。");
        List<UUID> ownOrganization = sorted(List.of(owner, partner));
        List<List<UUID>> otherOrganizations = new ArrayList<>();
        for (Map.Entry<UUID, UUID> entry : partners.entrySet()) {
            if (entry.getKey().compareTo(entry.getValue()) >= 0
                    || entry.getKey().equals(owner) || entry.getValue().equals(owner)
                    || !entry.getKey().equals(partners.get(entry.getValue()))) continue;
            List<UUID> group = sorted(List.of(entry.getKey(), entry.getValue()));
            boolean hasMatch = activeMatches.containsKey(group.get(0)) || activeMatches.containsKey(group.get(1))
                    || !persistence.findForPlayer(group.get(0)).isEmpty()
                    || !persistence.findForPlayer(group.get(1)).isEmpty();
            if (!hasMatch) otherOrganizations.add(group);
        }
        otherOrganizations.sort(Comparator.comparing(group -> group.getFirst().toString()));
        if (otherOrganizations.isEmpty()) throw new IllegalStateException("还没有另一个空闲的双人模拟组织。");
        List<List<UUID>> groups = new ArrayList<>(List.of(ownOrganization, otherOrganizations.getFirst()));
        groups.sort(Comparator.comparing(group -> group.getFirst().toString()));
        List<UUID> seats = new ArrayList<>(FactoryWorld.PLAYER_COUNT);
        groups.forEach(seats::addAll);
        return List.copyOf(seats);
    }

    private List<UUID> sorted(List<UUID> players) {
        return players.stream().sorted(Comparator.comparing(UUID::toString)).toList();
    }

    private List<String> dashboardLines(FactoryView view) {
        List<String> lines = new ArrayList<>();
        lines.add("工厂 " + view.tick() + "/" + FactoryConfig.defaults().maxTicks() + " · auction="
                + view.auction().state() + " · winner=" + (view.auction().winner().isEmpty() ? "pending" : view.auction().winner()));
        view.organizations().values().forEach(org -> lines.add(org.id() + " members=" + org.members()
                + " · credits=" + org.credits() + " · ore=" + org.ore() + " · parts=" + org.parts()));
        lines.add("orders=" + view.orders().size() + " · completed=" + view.completedOrders()
                + " · recovered=" + view.recoveredOrders() + " · producedParts=" + view.producedParts());
        view.orders().forEach(order -> lines.add(order.id() + " " + order.state() + " · due=" + order.completesAt()
                + (order.failure().isEmpty() ? "" : " · " + order.failure())));
        lines.add("state=" + view.stateHash().substring(0, 12) + " · " + view.reason());
        return List.copyOf(lines);
    }

    private List<String> help() {
        return List.of(
                "/genesis factory invite <player> · accept · start [seed]",
                "/genesis factory bid <credits> · order <orderId> <quantity> · step [1-10] · dashboard · replay · reset",
                "工厂只使用模拟 credits/ore/parts，不连接真实库存或经济服务。");
    }

    private void requireLength(String[] args, int count) {
        if (args.length != count) throw new IllegalArgumentException("参数数量不正确；使用 /genesis factory 查看用法");
    }

    private void send(UUID owner, Player player, List<String> lines) {
        if (player == null) return;
        try {
            player.getScheduler().run(plugin, task -> {
                if (player.isOnline()) lines.forEach(player::sendMessage);
            }, () -> { });
        } catch (RuntimeException schedulingFailure) {
            plugin.getLogger().log(Level.FINE, "Factory response could not reach player " + owner, schedulingFailure);
        }
    }

    private void notifyOnline(UUID owner, String message) {
        plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
            Player player = plugin.getServer().getPlayer(owner);
            if (player != null) send(owner, player, List.of(message));
        });
    }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        worker.shutdown();
        try {
            if (!worker.awaitTermination(5, TimeUnit.SECONDS)) worker.shutdownNow();
        } catch (InterruptedException interrupted) {
            worker.shutdownNow();
            Thread.currentThread().interrupt();
        }
        invitations.clear();
    }
}
