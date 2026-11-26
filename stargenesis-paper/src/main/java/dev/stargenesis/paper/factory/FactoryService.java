package dev.stargenesis.paper.factory;

import dev.stargenesis.sim.FactoryAction;
import dev.stargenesis.sim.FactoryConfig;
import dev.stargenesis.sim.FactoryInput;
import dev.stargenesis.sim.FactoryReplay;
import dev.stargenesis.sim.FactoryReplayVerifier;
import dev.stargenesis.sim.FactoryView;
import dev.stargenesis.sim.FactoryWorld;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Worker-owned organization and match lifecycle; every mutation is saved before publishing its result. */
final class FactoryService {
    private static final long DEFAULT_SEED = 20_261_123L;
    private static final int MAX_INVITATIONS = 64;
    private final FactoryPersistence persistence;
    private final Map<UUID, UUID> invitations = new HashMap<>();
    private final Map<UUID, String> selections = new HashMap<>();
    private Map<UUID, UUID> partners;

    FactoryService(FactoryPersistence persistence) throws IOException {
        this.persistence = persistence;
        partners = persistence.loadOrganizations();
    }

    Map<UUID, UUID> organizations() { return partners; }

    void invite(UUID inviter, UUID invited) throws IOException {
        if (inviter.equals(invited) || partners.containsKey(inviter) || partners.containsKey(invited))
            throw new IllegalStateException("需要两位尚未加入组织的不同玩家。");
        Set<UUID> busy = activePlayers(persistence.catalog());
        if (busy.contains(inviter) || busy.contains(invited)) throw new IllegalStateException("参与活跃比赛时不能重新组队。");
        if (invitations.containsKey(inviter) || invitations.containsValue(inviter)
                || invitations.containsKey(invited) || invitations.containsValue(invited))
            throw new IllegalStateException("邀请方或受邀玩家已有待处理的工厂邀请。");
        if (invitations.size() >= MAX_INVITATIONS) throw new IllegalStateException("工厂邀请数量已达上限。");
        invitations.put(invited, inviter);
    }

    UUID accept(UUID invited) throws IOException {
        UUID inviter = invitations.get(invited);
        if (inviter == null || partners.containsKey(inviter) || partners.containsKey(invited))
            throw new IllegalStateException("没有可接受的有效组织邀请。");
        Set<UUID> busy = activePlayers(persistence.catalog());
        if (busy.contains(inviter) || busy.contains(invited)) throw new IllegalStateException("参与活跃比赛时不能重新组队。");
        Map<UUID, UUID> updated = new HashMap<>(partners);
        updated.put(inviter, invited);
        updated.put(invited, inviter);
        persistence.saveOrganizations(updated);
        partners = Map.copyOf(updated);
        cancelInvitations(inviter);
        cancelInvitations(invited);
        return inviter;
    }

    boolean leave(UUID player) throws IOException {
        UUID partner = partners.get(player);
        Set<UUID> busy = activePlayers(persistence.catalog());
        if (busy.contains(player) || partner != null && busy.contains(partner))
            throw new IllegalStateException("组织成员的比赛结束后才能退出组织。");
        if (partner != null) {
            Map<UUID, UUID> updated = new HashMap<>(partners);
            updated.remove(player);
            updated.remove(partner);
            persistence.saveOrganizations(updated);
            partners = Map.copyOf(updated);
        }
        cancelInvitations(player);
        return partner != null;
    }

    void disconnected(UUID player) {
        selections.remove(player);
        cancelInvitations(player);
    }

    void retainOnline(Set<UUID> online) {
        selections.keySet().retainAll(online);
        invitations.entrySet().removeIf(entry -> !online.contains(entry.getKey()) || !online.contains(entry.getValue()));
    }

    private void cancelInvitations(UUID player) {
        invitations.remove(player);
        invitations.entrySet().removeIf(invitation -> invitation.getValue().equals(player));
    }

    FactoryReplay start(UUID owner, long seed, Set<UUID> online) throws IOException {
        UUID partner = partners.get(owner);
        if (partner == null || !owner.equals(partners.get(partner)))
            throw new IllegalStateException("先使用 invite/accept 组成双人组织。");
        Set<UUID> busy = activePlayers(persistence.catalog());
        if (busy.contains(owner) || busy.contains(partner)) throw new IllegalStateException("本组织已有活跃工厂比赛。");
        if (!online.contains(owner) || !online.contains(partner)) throw new IllegalStateException("两位组织成员必须在线。");
        List<List<UUID>> opponents = new ArrayList<>();
        for (var pair : partners.entrySet()) {
            UUID first = pair.getKey(), second = pair.getValue();
            if (first.toString().compareTo(second.toString()) >= 0 || first.equals(owner) || first.equals(partner)
                    || second.equals(owner) || second.equals(partner) || !online.contains(first) || !online.contains(second)
                    || busy.contains(first) || busy.contains(second)) continue;
            opponents.add(List.of(first, second));
        }
        opponents.sort(Comparator.comparing(group -> group.getFirst().toString()));
        if (opponents.isEmpty()) throw new IllegalStateException("还没有另一组在线且空闲的双人模拟组织。");
        List<List<UUID>> groups = new ArrayList<>(List.of(
                List.of(owner, partner).stream().sorted(Comparator.comparing(UUID::toString)).toList(), opponents.getFirst()));
        groups.sort(Comparator.comparing(group -> group.getFirst().toString()));
        List<UUID> roster = groups.stream().flatMap(List::stream).toList();
        try (var world = new FactoryWorld(UUID.randomUUID(), roster, seed, FactoryConfig.defaults())) {
            if (persistence.load(world.matchId()).isPresent()) throw new IOException("Factory run identity collision; retry start");
            FactoryReplay replay = world.replay();
            persistence.save(replay);
            roster.forEach(player -> selections.put(player, replay.matchId()));
            return replay;
        }
    }

    private Set<UUID> activePlayers(List<FactoryReplay> replays) throws IOException {
        Set<UUID> busy = new HashSet<>();
        for (var replay : replays) {
            try (var world = FactoryWorld.restore(replay)) {
                if (!world.view().terminal()) {
                    for (UUID player : replay.participants()) {
                        if (!busy.add(player)) throw new IOException("Player is present in multiple active factory matches");
                    }
                }
            }
        }
        return busy;
    }

    Optional<FactoryReplay> restorePlayer(UUID player) throws IOException {
        String selectedId = selections.get(player);
        if (selectedId != null) {
            FactoryReplay selected = persistence.load(selectedId).orElse(null);
            if (selected != null && selected.participants().contains(player)) {
                try (var world = FactoryWorld.restore(selected)) { world.view(); }
                return Optional.of(selected);
            }
            selections.remove(player);
        }
        List<FactoryReplay> found = persistence.findForPlayer(player);
        FactoryReplay active = null;
        for (FactoryReplay replay : found) {
            try (var world = FactoryWorld.restore(replay)) {
                if (!world.view().terminal()) {
                    if (active != null) throw new IOException("Player is present in multiple active factory matches");
                    active = replay;
                }
            }
        }
        FactoryReplay selected = active != null ? active : found.isEmpty() ? null : found.getFirst();
        if (selected != null) selections.put(player, selected.matchId());
        return Optional.ofNullable(selected);
    }

    FactoryReplay current(UUID player) throws IOException {
        return restorePlayer(player).orElseThrow(() -> new IllegalStateException("没有可用的工厂比赛；先使用 /genesis factory start。"));
    }

    FactoryReplay select(UUID player, String matchId) throws IOException {
        FactoryReplay replay = persistence.load(matchId).orElseThrow(() -> new IllegalArgumentException("工厂比赛不存在。"));
        if (!replay.participants().contains(player)) throw new SecurityException("Only participants may select this factory match");
        try (var world = FactoryWorld.restore(replay)) { world.view(); }
        selections.put(player, matchId);
        return replay;
    }

    FactoryReplay act(UUID owner, FactoryAction action) throws IOException {
        if (!owner.equals(action.actor())) throw new SecurityException("Factory players may only submit their own actions");
        try (var world = FactoryWorld.restore(current(owner))) {
            world.step(new FactoryInput(world.tick(), action));
            FactoryReplay updated = world.replay();
            persistence.save(updated);
            return updated;
        }
    }

    FactoryReplay step(UUID owner, int count) throws IOException {
        if (count < 1 || count > 10) throw new IllegalArgumentException("step 每次推进1到10 tick");
        FactoryReplay previous = current(owner);
        try (var world = FactoryWorld.restore(previous)) {
            if (world.view().terminal()) return previous;
            for (int i = 0; i < count && !world.view().terminal(); i++)
                world.step(new FactoryInput(world.tick(), FactoryAction.pass(owner)));
            FactoryReplay updated = world.replay();
            persistence.save(updated);
            return updated;
        }
    }

    void prune(String matchId, boolean manager) throws IOException {
        requireManager(manager);
        FactoryReplay replay = persistence.load(matchId).orElseThrow(() -> new IllegalArgumentException("工厂比赛不存在。"));
        try (var world = FactoryWorld.restore(replay)) {
            if (!world.view().terminal() || world.view().orders().stream()
                    .anyMatch(order -> order.state().equals("RUNNING") || order.state().equals("COMPENSATING")))
                throw new IllegalStateException("只能清理已结束且所有订单均已完成或补偿的历史工厂。");
        }
        persistence.delete(matchId);
        selections.values().removeIf(matchId::equals);
    }

    List<FactoryReplay> matches(UUID owner, boolean manager) throws IOException {
        return manager ? persistence.catalog() : persistence.findForPlayer(owner);
    }

    List<String> run(UUID owner, String[] args, Set<UUID> online, boolean manager) throws IOException {
        if (args.length == 0) return help();
        return switch (args[0].toLowerCase(Locale.ROOT)) {
            case "restore" -> {
                requireLength(args, 1);
                var replay = restorePlayer(owner).orElse(null);
                yield replay == null ? List.of() : List.of("已恢复工厂 " + replay.matchId()
                        + "，使用 /genesis factory dashboard 查看状态。");
            }
            case "start" -> {
                if (args.length > 2) throw new IllegalArgumentException("用法：/genesis factory start [seed]");
                yield dashboard(start(owner, args.length == 2 ? Long.parseLong(args[1]) : DEFAULT_SEED, online));
            }
            case "bid" -> {
                requireLength(args, 2);
                yield dashboard(act(owner, new FactoryAction(owner, FactoryAction.Type.BID, "ore_1", Long.parseLong(args[1]))));
            }
            case "order" -> {
                requireLength(args, 3);
                yield dashboard(act(owner, new FactoryAction(owner, FactoryAction.Type.PLACE_ORDER, args[1], Long.parseLong(args[2]))));
            }
            case "step" -> {
                if (args.length > 2) throw new IllegalArgumentException("用法：/genesis factory step [1-10]");
                yield dashboard(step(owner, args.length == 2 ? Integer.parseInt(args[1]) : 1));
            }
            case "dashboard" -> { requireLength(args, 1); yield dashboard(current(owner)); }
            case "select" -> { requireLength(args, 2); yield dashboard(select(owner, args[1])); }
            case "matches" -> {
                requireLength(args, 1);
                List<String> lines = new ArrayList<>();
                for (var replay : matches(owner, manager)) {
                    try (var world = FactoryWorld.restore(replay)) {
                        lines.add(replay.matchId() + " · tick=" + world.tick() + "/" + replay.config().maxTicks()
                                + " · " + world.view().reason());
                    }
                }
                yield lines.isEmpty() ? List.of("没有可查看的工厂比赛记录。") : List.copyOf(lines);
            }
            case "replay" -> {
                requireLength(args, 1);
                FactoryReplay replay = current(owner);
                var result = FactoryReplayVerifier.verify(replay);
                if (!result.passed()) throw new IllegalStateException("工厂回放验证失败：" + result.reason());
                yield List.of("四人工厂回放通过 " + replay.frames().size() + " ticks · hash="
                        + replay.initialStateHash().substring(0, 12));
            }
            case "leave" -> {
                requireLength(args, 1);
                yield List.of(leave(owner) ? "已退出双人组织；双方可重新组队。" : "当前没有双人组织；待处理邀请已取消。");
            }
            case "prune", "reset" -> {
                requireManager(manager);
                if (args.length > 2 || args[0].equalsIgnoreCase("prune") && args.length != 2)
                    throw new IllegalArgumentException("用法：/genesis factory prune <matchId> | reset [matchId]");
                String id = args.length == 2 ? args[1] : current(owner).matchId();
                prune(id, manager);
                yield List.of("已清理结束的工厂比赛及其回放 " + id);
            }
            default -> help();
        };
    }

    private List<String> dashboard(FactoryReplay replay) {
        try (var world = FactoryWorld.restore(replay)) {
            FactoryView view = world.view();
            List<String> lines = new ArrayList<>();
            lines.add("工厂 " + view.tick() + "/" + replay.config().maxTicks() + " · match=" + replay.matchId()
                    + " · auction=" + view.auction().state() + " · winner="
                    + (view.auction().winner().isEmpty() ? "pending" : view.auction().winner()));
            view.organizations().values().forEach(org -> lines.add(org.id() + " members=" + org.members()
                    + " · credits=" + org.credits() + " · ore=" + org.ore() + " · parts=" + org.parts()));
            lines.add("orders=" + view.orders().size() + " · completed=" + view.completedOrders()
                    + " · recovered=" + view.recoveredOrders() + " · producedParts=" + view.producedParts());
            view.orders().forEach(order -> lines.add(order.id() + " " + order.state() + " · due=" + order.completesAt()
                    + (order.failure().isEmpty() ? "" : " · " + order.failure())));
            lines.add("state=" + view.stateHash().substring(0, 12) + " · " + view.reason());
            return List.copyOf(lines);
        }
    }

    private List<String> help() {
        return List.of("/genesis factory invite <player> · accept · leave · start [seed]",
                "/genesis factory bid <credits> · order <orderId> <quantity> · step [1-10] · dashboard · replay",
                "/genesis factory matches · select <matchId>；管理员使用 prune <matchId> 或 reset [matchId] 清理已结束历史。",
                "工厂只使用模拟 credits/ore/parts，不连接真实库存或经济服务。");
    }

    private void requireManager(boolean manager) {
        if (!manager) throw new SecurityException("permission denied (stargenesis.factory.manage)");
    }

    private void requireLength(String[] args, int count) {
        if (args.length != count) throw new IllegalArgumentException("参数数量不正确；使用 /genesis factory 查看用法");
    }
}
