package dev.stargenesis.paper;

import dev.stargenesis.api.design.DesignDependency;
import dev.stargenesis.design.ArenaRuleset;
import dev.stargenesis.sim.ArenaMatchWorld;
import dev.stargenesis.sim.ArenaMatchWorld.Action;
import dev.stargenesis.sim.ArenaMatchWorld.Outcome;
import dev.stargenesis.sim.ArenaMatchWorld.Snapshot;
import net.kyori.adventure.text.Component;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;

/** Serializes room, roster and turn changes and publishes them only after durable storage succeeds. */
final class ArenaMatchService {
    private final JavaPlugin plugin;
    private final PublishedDesignStore releases;
    private final ArenaRoomStore store;
    private final Map<UUID, ArenaRoom> rooms = new HashMap<>();
    private volatile String restoreError;
    private volatile boolean restored;
    private volatile boolean closing;

    ArenaMatchService(JavaPlugin plugin, PublishedDesignStore releases) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.releases = Objects.requireNonNull(releases, "releases");
        store = new ArenaRoomStore(Path.of(plugin.getDataFolder().getPath()));
    }

    void start() {
        CompletableFuture.supplyAsync(this::readPendingRooms).whenComplete((pending, failure) ->
                plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> restore(pending, failure)));
        plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, task -> expireTurns(), 20, 20);
    }

    void createRoom(UUID host, DesignDependency reference, Consumer<String> reply) {
        execute(reply, () -> {
            requireNoActiveRoom(host);
            ArenaRuleset published = releases.findExact(reference)
                    .filter(ArenaRuleset.class::isInstance).map(ArenaRuleset.class::cast)
                    .orElseThrow(() -> new IllegalArgumentException("先发布此精确版本的竞技规则集，再创建公开对局"));
            if (!published.metadata().permissions().publicRead())
                throw new SecurityException("此竞技版本不允许加入公开对局");
            if (published.layout() == null)
                throw new IllegalArgumentException("创建房间需要 schema 2 竞技规则集");
            if (rooms.size() >= ArenaRoomStore.MAX_ROOMS)
                throw new IllegalStateException("服务器竞技房间达到存档上限，请先停止已结束的房间");
            ArenaRoom room = new ArenaRoom(UUID.randomUUID(), host, published);
            store.save(room.checkpoint());
            rooms.put(room.id(), room);
            return "竞技房间已创建 " + room.id() + "，规则 " + reference.id().value() + "@"
                    + reference.version() + "。其他玩家可用 /genesis arena join 加入。";
        });
    }

    void join(UUID roomId, UUID player, String slot, Consumer<String> reply) {
        execute(reply, () -> {
            requireNoActiveRoom(player, roomId);
            ArenaRoom room = requireRoom(roomId);
            ArenaRoom updated = room.copy();
            updated.join(player, slot);
            persist(updated);
            return "已加入竞技房间 " + roomId + " 的 " + slot + "。";
        });
    }

    void leave(UUID roomId, UUID player, Consumer<String> reply) {
        execute(reply, () -> {
            ArenaRoom room = requireRoom(roomId);
            if (room.host().equals(player)) {
                if (room.started()) throw new IllegalStateException("房主需等待对局结束，或使用 stop 停止对局");
                store.delete(roomId);
                rooms.remove(roomId);
                return "房间已关闭。";
            }
            ArenaRoom updated = room.copy();
            updated.leave(player);
            persist(updated);
            return "已离开竞技房间 " + roomId + "。";
        });
    }

    void kick(UUID roomId, UUID host, UUID target, Consumer<String> reply) {
        execute(reply, () -> {
            ArenaRoom updated = requireRoom(roomId).copy();
            updated.kick(host, target);
            persist(updated);
            return "已移除玩家 " + target + " 的座位。";
        });
    }

    void startRoom(UUID roomId, UUID host, Consumer<String> reply) {
        execute(reply, () -> {
            ArenaRoom updated = requireRoom(roomId).copy();
            updated.start(host, System.nanoTime());
            persist(updated);
            String message = "竞技对局开始。使用 /genesis arena act " + roomId + " " + updated.tick()
                    + " wait 提交单位动作；未提交动作将在 30 秒后视为等待。";
            notifyParticipants(updated, host, message);
            return message;
        });
    }

    void act(UUID roomId, UUID player, long expectedTick, Action action, Consumer<String> reply) {
        execute(reply, () -> {
            ArenaRoom updated = requireRoom(roomId).copy();
            if (updated.submit(player, expectedTick, action, System.nanoTime())) persist(updated);
            Snapshot snapshot = updated.snapshot();
            if (snapshot.tick() != expectedTick) notifyParticipants(updated, player, format(updated));
            return snapshot.outcome() == Outcome.ACTIVE
                    ? format(snapshot) + " | 已提交本回合动作 " + updated.submittedCount() + "/" + updated.seats().size()
                    : format(snapshot);
        });
    }

    void status(UUID roomId, Consumer<String> reply) {
        execute(reply, () -> format(requireRoom(roomId)));
    }

    void spectate(UUID roomId, Consumer<String> reply) {
        execute(reply, () -> formatSpectator(requireRoom(roomId)));
    }

    void listRooms(int page, Consumer<String> reply) {
        execute(reply, () -> {
            List<ArenaRoom> ordered = rooms.values().stream()
                    .sorted((first, second) -> first.id().toString().compareTo(second.id().toString())).toList();
            int pages = Math.max(1, (ordered.size() + 19) / 20);
            if (page < 1 || page > pages) throw new IllegalArgumentException("房间页码须为 1.." + pages);
            List<String> listed = ordered.stream().skip((long) (page - 1) * 20).limit(20)
                    .map(room -> room.finished()
                            ? room.id() + " | " + format(room.snapshot()) + " | 已结束"
                            : formatLobby(room))
                    .toList();
            return listed.isEmpty() ? "当前没有竞技房间。"
                    : "竞技房间 " + page + "/" + pages + "\n" + String.join("\n", listed);
        });
    }

    void stop(UUID roomId, UUID host, Consumer<String> reply) {
        execute(reply, () -> {
            ArenaRoom room = requireRoom(roomId);
            room.requireHost(host);
            store.delete(roomId);
            rooms.remove(roomId);
            notifyParticipants(room, host, "竞技房间已停止。");
            return "竞技房间已停止并删除。";
        });
    }

    void closeAll() {
        closing = true;
    }

    private List<PendingRoom> readPendingRooms() {
        try {
            List<PendingRoom> pending = new ArrayList<>();
            for (ArenaRoom.Data data : store.loadAll()) {
                ArenaRuleset ruleset = releases.findExact(data.release())
                        .filter(ArenaRuleset.class::isInstance).map(ArenaRuleset.class::cast)
                        .orElseThrow(() -> new IOException("Published arena release is missing: " + data.release().id()));
                pending.add(new PendingRoom(data, ruleset));
            }
            return List.copyOf(pending);
        } catch (IOException | IllegalStateException failure) {
            throw new CompletionException(failure);
        }
    }

    private void restore(List<PendingRoom> pending, Throwable loadFailure) {
        try {
            if (loadFailure != null) {
                Throwable cause = loadFailure.getCause() == null ? loadFailure : loadFailure.getCause();
                throw new IllegalStateException(cause.getMessage(), cause);
            }
            rooms.putAll(restoreRooms(pending, System.nanoTime()));
        } catch (IOException | IllegalArgumentException | IllegalStateException failure) {
            restoreError = failure.getMessage();
            plugin.getLogger().severe("Cannot restore arena rooms: " + restoreError);
        } finally {
            restored = true;
        }
    }

    static Map<UUID, ArenaRoom> restoreRooms(List<PendingRoom> pending, long now) throws IOException {
        Map<UUID, ArenaRoom> restoredRooms = new HashMap<>();
        var participants = new HashSet<UUID>();
        for (PendingRoom roomData : pending) {
            ArenaRoom room = ArenaRoom.restore(roomData.data(), roomData.ruleset(), now);
            if (restoredRooms.putIfAbsent(room.id(), room) != null)
                throw new IOException("Duplicate arena match ID");
            if (!room.finished()) {
                for (UUID player : room.participants()) {
                    if (!participants.add(player))
                        throw new IOException("A player was assigned to multiple active arena rooms");
                }
            }
        }
        return restoredRooms;
    }

    private void expireTurns() {
        if (closing || !restored || restoreError != null) return;
        long now = System.nanoTime();
        for (ArenaRoom room : List.copyOf(rooms.values())) {
            if (!room.due(now)) continue;
            try {
                ArenaRoom updated = room.copy();
                updated.expire(now);
                persist(updated);
                notifyParticipants(updated, null, format(updated));
            } catch (IOException | IllegalArgumentException | IllegalStateException failure) {
                plugin.getLogger().warning("Arena turn timeout was not committed for " + room.id()
                        + ": " + failure.getMessage());
            }
        }
    }

    private void requireNoActiveRoom(UUID player) {
        requireNoActiveRoom(player, null);
    }

    private void requireNoActiveRoom(UUID player, UUID exceptRoom) {
        if (rooms.values().stream().anyMatch(room -> !room.id().equals(exceptRoom)
                && !room.finished() && room.includes(player)))
            throw new IllegalStateException("你已经参加另一场竞技对局");
    }

    private ArenaRoom requireRoom(UUID id) {
        ArenaRoom room = rooms.get(id);
        if (room == null) throw new IllegalArgumentException("找不到该竞技房间");
        return room;
    }

    private void persist(ArenaRoom room) throws IOException {
        store.save(room.checkpoint());
        rooms.put(room.id(), room);
    }

    private void execute(Consumer<String> reply, Operation operation) {
        plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
            String message;
            if (closing) {
                message = "竞技房间服务正在关闭。";
            } else if (!restored) {
                message = "竞技房间仍在恢复，请稍后重试。";
            } else if (restoreError != null) {
                message = "竞技房间未能恢复：" + restoreError;
            } else {
                try {
                    message = operation.run();
                } catch (IOException failure) {
                    plugin.getLogger().warning("Arena room state was not committed: " + failure.getMessage());
                    message = "竞技房间未保存，旧状态仍有效：" + failure.getMessage();
                } catch (IllegalArgumentException | IllegalStateException | SecurityException failure) {
                    message = "竞技操作未完成：" + failure.getMessage();
                }
            }
            reply.accept(message);
        });
    }

    private void notifyParticipants(ArenaRoom room, UUID caller, String message) {
        for (UUID playerId : room.participants()) {
            if (playerId.equals(caller)) continue;
            var player = plugin.getServer().getPlayer(playerId);
            if (player != null) player.getScheduler().run(plugin, task -> {
                if (player.hasPermission("stargenesis.arena")) player.sendMessage(Component.text(message));
            }, () -> { });
        }
    }

    private static String formatLobby(ArenaRoom room) {
        Snapshot snapshot = room.snapshot();
        return room.id() + " | " + snapshot.rulesetId() + "@" + snapshot.rulesetVersion()
                + " | " + room.occupied().size() + "/" + room.slots().size() + " 座位"
                + (room.started() ? " | tick " + snapshot.tick() + " 对局中" : " | 等待房主开始");
    }

    private static String format(ArenaRoom room) {
        String turn = room.started() && !room.finished()
                ? " | 动作 " + room.submittedCount() + "/" + room.seats().size() : "";
        return room.id() + " | " + format(room.snapshot()) + turn;
    }

    static String formatSpectator(ArenaRoom room) {
        Snapshot snapshot = room.snapshot();
        int width = Math.min(5, snapshot.width());
        int height = Math.min(3, snapshot.height());
        int centerX = snapshot.units().isEmpty() ? snapshot.width() / 2 : snapshot.units().getFirst().x();
        int centerY = snapshot.units().isEmpty() ? snapshot.height() / 2 : snapshot.units().getFirst().y();
        int left = Math.max(0, Math.min(snapshot.width() - width, centerX - width / 2));
        int top = Math.max(0, Math.min(snapshot.height() - height, centerY - height / 2));
        StringBuilder output = new StringBuilder("观战 | ").append(snapshot.rulesetId()).append('@')
                .append(snapshot.rulesetVersion()).append(" | ").append(format(snapshot))
                .append(" | 座位 ").append(room.occupied().size()).append('/').append(room.slots().size());
        for (int y = top; y < top + height; y++) {
            output.append('\n');
            for (int x = left; x < left + width; x++) output.append(cellGlyph(snapshot, x, y));
        }
        output.append("\n图例 . 空格 # 障碍 o 目标 r/b 单位");
        return output.toString();
    }

    private static char cellGlyph(Snapshot snapshot, int x, int y) {
        for (ArenaMatchWorld.Unit unit : snapshot.units())
            if (unit.x() == x && unit.y() == y) return unit.teamId().equals("red") ? 'R' : 'B';
        if (snapshot.obstacles().stream().anyMatch(cell -> cell.x() == x && cell.y() == y)) return '#';
        if (snapshot.objectives().stream().anyMatch(objective -> objective.cell().x() == x && objective.cell().y() == y)) return 'o';
        return '.';
    }

    private static String format(Snapshot snapshot) {
        return "tick " + snapshot.tick() + "/" + snapshot.durationSteps() + " | " + snapshot.outcome()
                + (snapshot.winners().isEmpty() ? "" : " " + snapshot.winners())
                + " | " + snapshot.teams().stream().map(ArenaMatchService::formatTeam).toList()
                + " | 单位 " + snapshot.units().stream().map(ArenaMatchService::formatUnit).toList()
                + " | hash " + snapshot.stateHash().substring(0, 12);
    }

    private static String formatTeam(ArenaMatchWorld.TeamScore team) {
        return team.id() + " 控点=" + team.controlledObjectives() + " 积分=" + team.objectivePoints();
    }

    private static String formatUnit(ArenaMatchWorld.Unit unit) {
        return unit.id() + "@(" + unit.x() + "," + unit.y() + ")";
    }

    static Action parseAction(String value) {
        try {
            return Action.valueOf(Objects.requireNonNull(value, "action").toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException failure) {
            throw new IllegalArgumentException("动作须为 wait、north、east、south 或 west。", failure);
        }
    }

    @FunctionalInterface
    private interface Operation { String run() throws IOException; }

    record PendingRoom(ArenaRoom.Data data, ArenaRuleset ruleset) { }
}
