package dev.stargenesis.paper;

import dev.stargenesis.sim.EcologyCheckpoint;
import dev.stargenesis.sim.EcologyCheckpointCodec;
import dev.stargenesis.sim.EcologySettings;
import dev.stargenesis.sim.EcologyStepResult;
import dev.stargenesis.sim.EcologyWorld;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Owns persistent shared ecology command state without depending on the server scheduler. */
final class SharedEcologyCommands {
    private final EcologyGroupStore store;
    private final Map<UUID, EcologyGroupRecord> groups = new HashMap<>();

    SharedEcologyCommands(Path dataDirectory) throws IOException {
        store = new EcologyGroupStore(Objects.requireNonNull(dataDirectory, "dataDirectory"));
        for (EcologyGroupRecord group : store.loadAll()) groups.put(group.id(), group);
    }

    String execute(UUID actorId, String[] arguments) throws IOException {
        Objects.requireNonNull(actorId, "actorId");
        Objects.requireNonNull(arguments, "arguments");
        if (arguments.length == 0) throw new IllegalArgumentException(usage());
        return switch (arguments[0].toLowerCase(Locale.ROOT)) {
            case "create" -> create(actorId, arguments);
            case "list" -> list(actorId, arguments);
            case "add" -> changeRoster(actorId, arguments, true);
            case "remove" -> changeRoster(actorId, arguments, false);
            case "leave" -> leave(actorId, arguments);
            case "disband" -> disband(actorId, arguments);
            case "status" -> status(actorId, arguments);
            case "step" -> step(actorId, arguments);
            default -> throw new IllegalArgumentException(usage());
        };
    }

    List<EcologyGroupRecord> groupsFor(UUID actorId) {
        Objects.requireNonNull(actorId, "actorId");
        return groups.values().stream()
                .filter(group -> group.contains(actorId))
                .sorted(Comparator.comparing(group -> group.id().toString()))
                .toList();
    }

    private String create(UUID actorId, String[] arguments) throws IOException {
        if (arguments.length != 1) throw new IllegalArgumentException("Usage: /genesis ecosystem share create");
        if (groups.size() >= EcologyGroupCodec.MAX_GROUPS)
            throw new IllegalStateException("此服务器的共享生态组已达到上限。");
        UUID groupId = UUID.randomUUID();
        EcologySettings settings = EcologySession.sharedSettings(groupId);
        EcologyGroupRecord created;
        try (EcologyWorld world = new EcologyWorld(settings)) {
            EcologyCheckpoint checkpoint = world.checkpoint();
            EcologyCheckpointCodec.encode(checkpoint);
            created = new EcologyGroupRecord(groupId, actorId, List.of(actorId), checkpoint);
        }
        store.save(created);
        groups.put(groupId, created);
        return "共享生态组已创建，组 ID " + groupId + "。你是所有者，可邀请最多 7 位成员。";
    }

    private String list(UUID actorId, String[] arguments) {
        if (arguments.length > 2) throw new IllegalArgumentException("Usage: /genesis ecosystem share list [page]");
        List<EcologyGroupRecord> joined = groupsFor(actorId);
        if (joined.isEmpty()) return "你还没有加入共享生态组。";
        int page = 1;
        if (arguments.length == 2) {
            if (!arguments[1].matches("[1-9][0-9]{0,2}"))
                throw new IllegalArgumentException("共享生态页码必须是 1 到 999 的正整数。");
            page = Integer.parseInt(arguments[1]);
        }
        int pageCount = (joined.size() + 19) / 20;
        if (page > pageCount) throw new IllegalArgumentException("页码范围是 1 到 " + pageCount + "。");
        StringBuilder message = new StringBuilder("共享生态组 | 已加入 ").append(joined.size())
                .append(" 个 | 第 ").append(page).append('/').append(pageCount).append(" 页");
        int start = (page - 1) * 20;
        for (int index = start; index < Math.min(start + 20, joined.size()); index++) {
            EcologyGroupRecord group = joined.get(index);
            message.append('\n').append(group.id()).append(" | ")
                    .append(group.ownerId().equals(actorId) ? "所有者" : "成员")
                    .append(" | 成员=").append(group.members().size()).append('/')
                    .append(EcologyGroupRecord.MAX_MEMBERS)
                    .append(" | 步数=").append(group.checkpoint().snapshot().tick());
        }
        return message.toString();
    }

    private String changeRoster(UUID actorId, String[] arguments, boolean add) throws IOException {
        if (arguments.length != 3)
            throw new IllegalArgumentException(add
                    ? "Usage: /genesis ecosystem share add <group-id> <player-uuid>"
                    : "Usage: /genesis ecosystem share remove <group-id> <player-uuid>");
        EcologyGroupRecord current = requireGroup(parseUuid(arguments[1]));
        EcologyGroupRecord updated = add
                ? current.addMember(actorId, parseUuid(arguments[2]))
                : current.removeMember(actorId, parseUuid(arguments[2]));
        if (updated != current) {
            store.save(updated);
            groups.put(updated.id(), updated);
        }
        return add ? "共享生态成员已添加。成员数 " + updated.members().size() + "/" + EcologyGroupRecord.MAX_MEMBERS + "。"
                : "共享生态成员已移除。成员数 " + updated.members().size() + "/" + EcologyGroupRecord.MAX_MEMBERS + "。";
    }

    private String leave(UUID actorId, String[] arguments) throws IOException {
        if (arguments.length != 2) throw new IllegalArgumentException("Usage: /genesis ecosystem share leave <group-id>");
        EcologyGroupRecord current = requireGroup(parseUuid(arguments[1]));
        EcologyGroupRecord updated = current.leave(actorId);
        store.save(updated);
        groups.put(updated.id(), updated);
        return "你已离开共享生态组。";
    }

    private String disband(UUID actorId, String[] arguments) throws IOException {
        if (arguments.length != 2)
            throw new IllegalArgumentException("Usage: /genesis ecosystem share disband <group-id>");
        EcologyGroupRecord group = requireGroup(parseUuid(arguments[1]));
        group.requireOwner(actorId);
        store.delete(group.id());
        groups.remove(group.id());
        return "共享生态组及其检查点已删除。";
    }

    private String status(UUID actorId, String[] arguments) {
        if (arguments.length != 2) throw new IllegalArgumentException("Usage: /genesis ecosystem share status <group-id>");
        EcologyGroupRecord group = requireMember(actorId, parseUuid(arguments[1]));
        try (EcologyWorld world = EcologyWorld.restore(group.checkpoint())) {
            var snapshot = world.snapshot();
            return "共享生态 " + group.id() + " | 步数=" + snapshot.tick()
                    + " | 种群=" + snapshot.statistics().population()
                    + " | 成员=" + group.members().size() + "/" + EcologyGroupRecord.MAX_MEMBERS
                    + " | 哈希=" + snapshot.stateHash();
        }
    }

    private String step(UUID actorId, String[] arguments) throws IOException {
        if (arguments.length != 2) throw new IllegalArgumentException("Usage: /genesis ecosystem share step <group-id>");
        EcologyGroupRecord current = requireMember(actorId, parseUuid(arguments[1]));
        EcologyGroupRecord updated;
        EcologyStepResult result;
        try (EcologyWorld world = EcologyWorld.restore(current.checkpoint())) {
            result = world.step();
            EcologyCheckpoint checkpoint = world.checkpoint();
            EcologyCheckpointCodec.encode(checkpoint);
            updated = current.withCheckpoint(checkpoint);
        }
        store.save(updated);
        groups.put(updated.id(), updated);
        return "共享生态已推进到第 " + result.tick() + " 步，种群=" + result.statistics().population()
                + (result.terminal() ? "，生态已结束：" + result.terminalReason() : "。");
    }

    private EcologyGroupRecord requireGroup(UUID groupId) {
        EcologyGroupRecord group = groups.get(groupId);
        if (group == null) throw new IllegalArgumentException("共享生态组不存在。");
        return group;
    }

    private EcologyGroupRecord requireMember(UUID actorId, UUID groupId) {
        EcologyGroupRecord group = requireGroup(groupId);
        if (!group.contains(actorId)) throw new SecurityException("你不是该共享生态组成员。");
        return group;
    }

    private static UUID parseUuid(String value) {
        try {
            UUID parsed = UUID.fromString(value);
            if (!parsed.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException("Non-canonical UUID");
            return parsed;
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("组 ID 和玩家身份必须使用完整 UUID。", failure);
        }
    }

    private static String usage() {
        return "Usage: /genesis ecosystem share <list|create|add|remove|leave|disband|status|step> ...";
    }
}
