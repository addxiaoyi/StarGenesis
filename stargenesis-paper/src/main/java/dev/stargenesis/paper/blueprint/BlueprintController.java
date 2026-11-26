package dev.stargenesis.paper.blueprint;

import dev.stargenesis.api.design.DesignId;
import dev.stargenesis.api.design.DesignPermissions;
import dev.stargenesis.design.Blueprint;
import dev.stargenesis.design.BlueprintEvaluator;
import dev.stargenesis.design.BlueprintWorkspace;
import dev.stargenesis.paper.blueprint.BlueprintSessions.EditSession;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Locale;
import java.util.UUID;

/** In-game command workflow for creating, editing, previewing and saving a private blueprint. */
public final class BlueprintController implements Listener {
    private final BlueprintStore store;
    private final BlueprintEvaluator evaluator = BlueprintEvaluator.standard();
    private final BlueprintSessions sessions;

    public BlueprintController(JavaPlugin plugin, BlueprintStore store) {
        this.store = store;
        sessions = new BlueprintSessions(store);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public boolean handle(Player player, String[] args) {
        if (!player.hasPermission("stargenesis.blueprint")) {
            player.sendMessage("StarGenesis: permission denied (stargenesis.blueprint)");
            return true;
        }
        if (sessions.isClosed()) { player.sendMessage("蓝图工作区服务已停止。"); return true; }
        if (args.length == 0) { help(player); return true; }
        try {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "create" -> create(player, args);
                case "clone" -> cloneBlueprint(player, args);
                case "list" -> list(player, args);
                case "open" -> open(player, args);
                case "close" -> close(player, args);
                case "add" -> add(player, args);
                case "move" -> move(player, args);
                case "rotate" -> rotate(player, args);
                case "connect" -> connect(player, args, true);
                case "disconnect" -> connect(player, args, false);
                case "remove" -> remove(player, args);
                case "undo" -> undo(player, args);
                case "preview" -> preview(player, args);
                case "save" -> save(player, args);
                case "access" -> access(player, args);
                case "version" -> version(player, args);
                default -> help(player);
            }
        } catch (IllegalArgumentException | IOException | SecurityException failure) {
            player.sendMessage("蓝图操作未完成：" + failure.getMessage());
        }
        return true;
    }

    public void close() { sessions.close(); }

    @EventHandler public void onQuit(PlayerQuitEvent event) {
        sessions.disconnected(event.getPlayer().getUniqueId());
    }

    private void create(Player player, String[] args) throws IOException {
        requireLength(args, 2);
        UUID owner = player.getUniqueId();
        DesignId id = new DesignId(args[1]);
        sessions.create(owner, id);
        player.sendMessage("已创建并打开 " + id.value() + "，初始造物核已保存。");
    }

    private void list(Player player, String[] args) throws IOException {
        if (args.length > 2) throw new IllegalArgumentException("用法：/genesis blueprint list [author UUID]");
        UUID author = args.length == 2 ? UUID.fromString(args[1]) : player.getUniqueId();
        var designs = store.listFor(player.getUniqueId(), author);
        if (designs.isEmpty()) { player.sendMessage("没有可查看的已保存蓝图。使用 /genesis blueprint create <namespace:id>。"); return; }
        player.sendMessage("可查看蓝图 " + designs.size() + "/" + BlueprintStore.MAX_BLUEPRINTS_PER_PLAYER + " · author=" + author + ":");
        designs.forEach(blueprint -> player.sendMessage("- " + blueprint.metadata().id().value()
                + " v" + blueprint.metadata().version() + " · " + blueprint.components().size() + " 个组件"));
    }

    private void open(Player player, String[] args) throws IOException {
        if (args.length < 2 || args.length > 3)
            throw new IllegalArgumentException("用法：/genesis blueprint open <id> [author UUID]");
        DesignId id = new DesignId(args[1]);
        UUID author = args.length == 3 ? UUID.fromString(args[2]) : player.getUniqueId();
        Blueprint blueprint = sessions.open(player.getUniqueId(), author, id);
        player.sendMessage("已打开 " + id.value() + " · author=" + author
                + (blueprint.metadata().permissions().mayEdit(player.getUniqueId(), author) ? " · 可编辑" : " · 只读")
                + "。使用 /genesis blueprint preview 查看结构评估。");
    }

    private void cloneBlueprint(Player player, String[] args) throws IOException {
        if (args.length != 4)
            throw new IllegalArgumentException("用法：/genesis blueprint clone <author UUID> <sourceId> <newId>");
        UUID actor = player.getUniqueId();
        UUID author = UUID.fromString(args[1]);
        DesignId source = new DesignId(args[2]);
        DesignId target = new DesignId(args[3]);
        sessions.cloneBlueprint(actor, author, source, target);
        player.sendMessage("已克隆并打开 " + target.value() + "；副本默认私有并记录原作者、版本与哈希。");
    }

    private void access(Player player, String[] args) {
        requireLength(args, 3);
        EditSession session = requireEditableSession(player);
        if (!player.getUniqueId().equals(session.workspace.current().metadata().author()))
            throw new SecurityException("仅原作者可修改蓝图许可。");
        Blueprint before = session.workspace.current();
        DesignPermissions old = before.metadata().permissions();
        DesignPermissions next;
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "public" -> next = new DesignPermissions(onOff(args[2]), old.allowClone(), old.editors());
            case "clone" -> next = new DesignPermissions(old.publicRead(), onOff(args[2]), old.editors());
            case "editor", "revoke" -> {
                var editors = new HashSet<>(old.editors());
                UUID editor = UUID.fromString(args[2]);
                if (args[1].equalsIgnoreCase("editor")) editors.add(editor);
                else editors.remove(editor);
                next = new DesignPermissions(old.publicRead(), old.allowClone(), editors);
            }
            default -> throw new IllegalArgumentException("用法：access public|clone on|off，或 access editor|revoke <UUID>");
        }
        session.workspace.permissions(next);
        markChanged(session, before);
        player.sendMessage("蓝图许可已修改；使用 save 后生效，undo 可撤销。");
    }

    private void version(Player player, String[] args) {
        requireLength(args, 2);
        EditSession session = requireEditableSession(player);
        if (!player.getUniqueId().equals(session.workspace.current().metadata().author()))
            throw new SecurityException("仅原作者可修改蓝图版本。");
        Blueprint before = session.workspace.current();
        session.workspace.version(args[1]);
        markChanged(session, before);
        player.sendMessage("蓝图版本已修改；使用 save 后生效。");
    }

    private boolean onOff(String value) {
        if (value.equalsIgnoreCase("on")) return true;
        if (value.equalsIgnoreCase("off")) return false;
        throw new IllegalArgumentException("许可开关须为 on 或 off");
    }

    private void close(Player player, String[] args) {
        if (args.length > 2 || args.length == 2 && !args[1].equalsIgnoreCase("discard"))
            throw new IllegalArgumentException("用法：/genesis blueprint close [discard]");
        boolean dirty = sessions.closeSession(player.getUniqueId(), args.length == 2);
        player.sendMessage(dirty ? "未保存的蓝图修改已丢弃。" : "已关闭蓝图工作区。");
    }

    private void add(Player player, String[] args) {
        requireLength(args, 7);
        if (!evaluator.hasModule(args[2])) throw new IllegalArgumentException("未知模块；可用模块：" + moduleIds());
        EditSession session = requireEditableSession(player);
        Blueprint before = session.workspace.current();
        session.workspace.add(new Blueprint.Component(args[1], args[2], integer(args[3]), integer(args[4]),
                integer(args[5]), 0), args[6]);
        markChanged(session, before);
        player.sendMessage("已加入 " + args[1] + "，连接至 " + args[6] + "。使用 preview 查看代价。");
    }

    private void move(Player player, String[] args) {
        requireLength(args, 5);
        EditSession session = requireEditableSession(player);
        Blueprint before = session.workspace.current();
        session.workspace.move(args[1], integer(args[2]), integer(args[3]), integer(args[4]));
        markChanged(session, before);
        player.sendMessage("组件位置已更新。");
    }

    private void rotate(Player player, String[] args) {
        requireLength(args, 3);
        EditSession session = requireEditableSession(player);
        Blueprint before = session.workspace.current();
        session.workspace.rotate(args[1], integer(args[2]));
        markChanged(session, before);
        player.sendMessage("组件朝向已更新。");
    }

    private void connect(Player player, String[] args, boolean connect) {
        requireLength(args, 3);
        EditSession session = requireEditableSession(player);
        Blueprint before = session.workspace.current();
        if (connect) session.workspace.connect(args[1], args[2]);
        else session.workspace.disconnect(args[1], args[2]);
        markChanged(session, before);
        player.sendMessage(connect ? "组件连接已更新。" : "组件连接已移除。");
    }

    private void remove(Player player, String[] args) {
        requireLength(args, 2);
        EditSession session = requireEditableSession(player);
        Blueprint before = session.workspace.current();
        session.workspace.remove(args[1]);
        markChanged(session, before);
        player.sendMessage("组件及其连接已移除。");
    }

    private void undo(Player player, String[] args) {
        requireLength(args, 1);
        EditSession session = requireEditableSession(player);
        if (!session.workspace.undo()) throw new IllegalArgumentException("没有可撤销的修改");
        session.dirty = !session.workspace.current().equals(session.saved);
        player.sendMessage("已撤销一步；剩余撤销步数 " + session.workspace.undoDepth() + "/" + BlueprintWorkspace.MAX_UNDO_STEPS + "。");
    }

    private void preview(Player player, String[] args) {
        if (args.length < 1 || args.length > 2) throw new IllegalArgumentException("用法：/genesis blueprint preview [页码]");
        Blueprint blueprint = requireSession(player).workspace.current();
        BlueprintEvaluator.Evaluation result = evaluator.evaluate(blueprint);
        int page = args.length == 2 ? integer(args[1]) : 1;
        int pageCount = Math.max(1, (blueprint.components().size() + 11) / 12);
        if (page < 1 || page > pageCount) throw new IllegalArgumentException("页码范围：1-" + pageCount);
        player.sendMessage("蓝图 " + blueprint.metadata().id().value() + " · " + (result.ready() ? "结构就绪" : "存在阶段门问题"));
        player.sendMessage("组件 " + blueprint.components().size() + " · 质量 " + result.mass() + "/" + result.supportCapacity()
                + " · 材料代价 " + result.materialCost());
        player.sendMessage("供能 " + result.powerGeneration() + "/" + result.powerDemand() + " · 推力 " + result.thrust()
                + " · 升力 " + result.lift());
        player.sendMessage("安装能力 " + (result.installedCapabilities().isEmpty() ? "无" : String.join(", ", result.installedCapabilities().stream().sorted().toList())));
        var components = blueprint.components().stream().sorted(Comparator.comparing(Blueprint.Component::id)).toList();
        int first = (page - 1) * 12;
        player.sendMessage("结构坐标页 " + page + "/" + pageCount + " · 连接 " + blueprint.connections().size());
        for (int index = first; index < Math.min(first + 12, components.size()); index++) {
            Blueprint.Component component = components.get(index);
            player.sendMessage(component.id() + " · " + component.type() + " @ " + component.x() + "," + component.y()
                    + "," + component.z() + " · rotation=" + component.rotation());
        }
        result.issues().forEach(issue -> player.sendMessage("! " + issue.code() + " · " + issue.detail()));
        if (result.issues().isEmpty()) player.sendMessage("未发现已实现的结构/供能问题。能力仅用于预览，不会生成实体或物品。");
    }

    private void save(Player player, String[] args) throws IOException {
        requireLength(args, 1);
        Blueprint saved = sessions.save(player.getUniqueId());
        player.sendMessage("蓝图已原子保存 · hash=" + saved.metadata().hash().substring(0, 12));
    }

    private void help(Player player) {
        player.sendMessage("/genesis blueprint create <namespace:id> | list [author UUID] | open <id> [author UUID] | close [discard]");
        player.sendMessage("/genesis blueprint clone <author UUID> <sourceId> <newId> | access public|clone on|off | access editor|revoke <UUID> | version <x.y.z>");
        player.sendMessage("/genesis blueprint add <componentId> <module> <x> <y> <z> <anchorId> | move <componentId> <x> <y> <z> | rotate <componentId> <0-3>");
        player.sendMessage("/genesis blueprint connect <from> <to> | disconnect <from> <to> | remove <componentId> | undo | preview [page] | save");
        player.sendMessage("蓝图默认私有；作者可授权编辑、公开查看和克隆。所有修改只作用于虚拟设计。");
    }

    private String moduleIds() {
        return String.join(", ", evaluator.availableModules().stream().map(BlueprintEvaluator.ComponentModule::id).toList());
    }

    private EditSession requireSession(Player player) {
        return sessions.require(player.getUniqueId());
    }

    private EditSession requireEditableSession(Player player) {
        return sessions.requireEditable(player.getUniqueId());
    }

    private int integer(String value) { return Integer.parseInt(value); }

    private void requireLength(String[] args, int expected) {
        if (args.length != expected) throw new IllegalArgumentException("参数数量不正确；使用 /genesis blueprint 查看用法");
    }

    private void markChanged(EditSession session, Blueprint before) {
        if (!before.equals(session.workspace.current())) session.dirty = !session.workspace.current().equals(session.saved);
    }

}
