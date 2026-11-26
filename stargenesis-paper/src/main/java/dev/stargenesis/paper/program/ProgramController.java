package dev.stargenesis.paper.program;

import dev.stargenesis.api.design.DesignId;
import dev.stargenesis.api.sim.AgentId;
import dev.stargenesis.api.sim.SimulationBudget;
import dev.stargenesis.design.PrimitiveCatalog;
import dev.stargenesis.design.ProgramIR;
import dev.stargenesis.design.ProgramInterpreter;
import dev.stargenesis.design.ProgramValue;
import dev.stargenesis.design.ProgramWorkspace;
import dev.stargenesis.design.TypedProgramDocument;
import dev.stargenesis.sim.SandboxView;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/** In-game typed program editing, persistence and isolated debugger commands. */
public final class ProgramController implements Listener {
    private static final int CATALOG_PAGE_SIZE = 8;
    private final ProgramStore store;
    private final SimulationBudget budget;
    private final ProgramSessions sessions;

    public ProgramController(JavaPlugin plugin, ProgramStore store) {
        this(plugin, store, SimulationBudget.defaults());
    }

    public ProgramController(JavaPlugin plugin, ProgramStore store, SimulationBudget budget) {
        this.store = store;
        this.budget = java.util.Objects.requireNonNull(budget);
        sessions = new ProgramSessions(store, budget);
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public boolean handle(Player player, String[] args) {
        if (!player.hasPermission("stargenesis.program")) {
            player.sendMessage("StarGenesis: permission denied (stargenesis.program)");
            return true;
        }
        if (sessions.isClosed()) { player.sendMessage("程序工作区服务已停止。"); return true; }
        if (args.length == 0) { help(player); return true; }
        try {
            switch (args[0].toLowerCase(Locale.ROOT)) {
                case "create" -> create(player, args);
                case "list" -> list(player, args);
                case "open" -> open(player, args);
                case "close" -> close(player, args);
                case "add" -> add(player, args);
                case "replace" -> replace(player, args);
                case "remove" -> remove(player, args);
                case "undo" -> undo(player, args);
                case "save" -> save(player, args);
                case "status" -> status(player, args);
                case "show" -> show(player, args);
                case "catalog" -> catalog(player, args);
                case "sensor" -> setSensor(player, args);
                case "context" -> setClockContext(player, args);
                case "resource" -> setResource(player, args);
                case "signal" -> setSignal(player, args);
                case "breakpoint" -> breakpoint(player, args);
                case "run" -> run(player, args, ProgramInterpreter.MAX_QUANTUM, false);
                case "step" -> run(player, args, 1, true);
                case "continue" -> run(player, args, ProgramInterpreter.MAX_QUANTUM, true);
                case "simulate" -> simulate(player, args);
                default -> help(player);
            }
        } catch (IllegalArgumentException | IOException | SecurityException failure) {
            player.sendMessage("程序操作未完成：" + failure.getMessage());
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
        player.sendMessage("已创建并打开 " + id.value() + "，初始指令为 WAIT。");
    }

    private void list(Player player, String[] args) throws IOException {
        requireLength(args, 1);
        List<TypedProgramDocument> programs = store.list(player.getUniqueId());
        if (programs.isEmpty()) { player.sendMessage("没有已保存的个人程序。使用 /genesis program create <namespace:id>。"); return; }
        player.sendMessage("个人程序 " + programs.size() + "/" + ProgramStore.MAX_PROGRAMS_PER_PLAYER + ":");
        programs.forEach(program -> player.sendMessage("- " + program.id().value() + " · "
                + program.program().instructions().size() + " 条指令 · " + program.hash().substring(0, 12)));
    }

    private void open(Player player, String[] args) throws IOException {
        requireLength(args, 2);
        UUID owner = player.getUniqueId();
        ProgramSession session = sessions.open(owner, new DesignId(args[1]));
        player.sendMessage("已打开 " + session.id.value() + "，使用 catalog 查看原语，show 查看指令。");
    }

    private void close(Player player, String[] args) {
        if (args.length > 2 || args.length == 2 && !args[1].equalsIgnoreCase("discard"))
            throw new IllegalArgumentException("用法：/genesis program close [discard]");
        UUID owner = player.getUniqueId();
        boolean dirty = sessions.closeSession(owner, args.length == 2);
        player.sendMessage(dirty ? "未保存的程序修改已丢弃。" : "已关闭程序工作区。");
    }

    private void add(Player player, String[] args) {
        requireLength(args, 5);
        PrimitiveCatalog.Operation operation;
        try { operation = PrimitiveCatalog.Operation.valueOf(args[2].toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("未知原语；使用 /genesis program catalog"); }
        ProgramSession session = requireSession(player.getUniqueId());
        session.append(args[1], operation, parseInputs(args[3]), parseParameters(args[4]));
        player.sendMessage("已加入 " + args[1] + " · " + operation + "；执行仅读取虚拟上下文。");
    }

    private void remove(Player player, String[] args) {
        requireLength(args, 2);
        ProgramSession session = requireSession(player.getUniqueId());
        session.remove(args[1]);
        player.sendMessage("已移除指令 " + args[1] + "。");
    }

    private void replace(Player player, String[] args) {
        requireLength(args, 5);
        PrimitiveCatalog.Operation operation;
        try { operation = PrimitiveCatalog.Operation.valueOf(args[2].toUpperCase(Locale.ROOT)); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("未知原语；使用 /genesis program catalog"); }
        ProgramSession session = requireSession(player.getUniqueId());
        session.replace(args[1], operation, parseInputs(args[3]), parseParameters(args[4]));
        player.sendMessage("指令 " + args[1] + " 已替换为 " + operation + "。");
    }

    private void undo(Player player, String[] args) {
        requireLength(args, 1);
        ProgramSession session = requireSession(player.getUniqueId());
        if (!session.undo()) throw new IllegalArgumentException("没有可撤销的修改");
        player.sendMessage("已撤销一步；剩余 " + session.workspace.undoDepth() + "/" + ProgramWorkspace.MAX_UNDO_STEPS + "。");
    }

    private void save(Player player, String[] args) throws IOException {
        requireLength(args, 1);
        UUID owner = player.getUniqueId();
        ProgramSession session = sessions.save(owner);
        player.sendMessage("程序已原子保存 · hash=" + session.baseHash.substring(0, 12));
    }

    private void status(Player player, String[] args) {
        requireLength(args, 1);
        ProgramSession session = requireSession(player.getUniqueId());
        player.sendMessage("程序 " + session.id.value() + " · " + session.workspace.instructions().size()
                + "/" + ProgramIR.MAX_INSTRUCTIONS + " 指令 · undo=" + session.workspace.undoDepth()
                + " · " + (session.dirty ? "未保存" : "已保存"));
        player.sendMessage("传感器 " + session.sensors.size() + " · 资源 " + session.resources.size()
                + " · 信号 " + session.signals.size() + " · 断点 " + session.breakpoints.size()
                + " · seed=" + session.seed + " tick=" + session.tick);
        if (session.execution != null)
            player.sendMessage("调试器 " + session.execution.status() + " · next=" + session.execution.nextInstructionId()
                    + " · trace=" + session.execution.trace().size());
    }

    private void show(Player player, String[] args) {
        if (args.length < 1 || args.length > 2) throw new IllegalArgumentException("用法：/genesis program show [页码]");
        List<ProgramIR.Instruction> instructions = requireSession(player.getUniqueId()).workspace.instructions();
        int page = args.length == 2 ? integer(args[1]) : 1;
        int pageCount = Math.max(1, (instructions.size() + 7) / 8);
        if (page < 1 || page > pageCount) throw new IllegalArgumentException("页码范围：1-" + pageCount);
        player.sendMessage("typed IR 指令 " + page + "/" + pageCount + ":");
        int first = (page - 1) * 8;
        for (int index = first; index < Math.min(first + 8, instructions.size()); index++) {
            ProgramIR.Instruction instruction = instructions.get(index);
            player.sendMessage(index + " · " + instruction.id() + " · " + instruction.operation()
                    + " · inputs=" + instruction.inputs() + " · params=" + new TreeMap<>(instruction.parameters()));
        }
    }

    private void catalog(Player player, String[] args) {
        if (args.length > 2) throw new IllegalArgumentException("用法：/genesis program catalog [页码]");
        int page = args.length == 2 ? integer(args[1]) : 1;
        List<PrimitiveCatalog.Descriptor> primitives = PrimitiveCatalog.all();
        int pageCount = (primitives.size() + CATALOG_PAGE_SIZE - 1) / CATALOG_PAGE_SIZE;
        if (page < 1 || page > pageCount) throw new IllegalArgumentException("页码范围：1-" + pageCount);
        player.sendMessage("原语目录 " + page + "/" + pageCount + ":");
        int first = (page - 1) * CATALOG_PAGE_SIZE;
        for (int index = first; index < Math.min(first + CATALOG_PAGE_SIZE, primitives.size()); index++) {
            PrimitiveCatalog.Descriptor primitive = primitives.get(index);
            player.sendMessage(primitive.operation() + " · " + primitive.inputTypes() + " → "
                    + primitive.outputType() + " · cost=" + primitive.cost());
        }
    }

    private void setSensor(Player player, String[] args) {
        requireLength(args, 4);
        ProgramSession session = requireSession(player.getUniqueId());
        ProgramValue value = switch (args[2].toLowerCase(Locale.ROOT)) {
            case "boolean" -> parseBoolean(args[3]);
            case "integer" -> new ProgramValue.IntegerValue(Long.parseLong(args[3]));
            case "vector" -> parseVector(args[3]);
            case "resource" -> new ProgramValue.ResourceValue(args[3]);
            default -> throw new IllegalArgumentException("类型必须是 boolean、integer、vector 或 resource");
        };
        session.setSensor(args[1], value);
        player.sendMessage("虚拟传感器已设置：" + args[1] + "=" + value);
    }

    private void setClockContext(Player player, String[] args) {
        requireLength(args, 3);
        long seed = Long.parseLong(args[1]);
        long tick = Long.parseLong(args[2]);
        if (tick < 0) throw new IllegalArgumentException("逻辑 tick 不能为负数");
        ProgramSession session = requireSession(player.getUniqueId());
        session.setClock(seed, tick);
        player.sendMessage("虚拟上下文已设置：seed=" + seed + " tick=" + tick);
    }

    private void setResource(Player player, String[] args) {
        requireLength(args, 3);
        long amount = Long.parseLong(args[2]);
        if (amount < 0 || amount > 1_000_000) throw new IllegalArgumentException("资源量范围：0-1000000");
        ProgramSession session = requireSession(player.getUniqueId());
        session.setResource(args[1], amount);
        player.sendMessage("虚拟资源已设置：" + args[1] + "=" + amount);
    }

    private void setSignal(Player player, String[] args) {
        requireLength(args, 3);
        long value = Long.parseLong(args[2]);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE)
            throw new IllegalArgumentException("信号值超出32位整数范围");
        ProgramSession session = requireSession(player.getUniqueId());
        session.setSignal(args[1], value);
        player.sendMessage("虚拟信号已设置：" + args[1] + "=" + value);
    }

    private void breakpoint(Player player, String[] args) {
        requireLength(args, 3);
        ProgramSession session = requireSession(player.getUniqueId());
        boolean enabled = switch (args[2].toLowerCase(Locale.ROOT)) {
            case "on" -> true;
            case "off" -> false;
            default -> throw new IllegalArgumentException("状态必须为 on 或 off");
        };
        session.breakpoint(args[1], enabled);
        player.sendMessage("断点 " + args[1] + " " + (enabled ? "已启用" : "已关闭"));
    }

    private void run(Player player, String[] args, int quantum, boolean resume) {
        requireLength(args, 1);
        ProgramSession session = requireSession(player.getUniqueId());
        reportExecution(player, session.execute(quantum, resume));
    }

    private void simulate(Player player, String[] args) {
        if (args.length < 1 || args.length > 3)
            throw new IllegalArgumentException("用法：/genesis program simulate [seed] [1-20 ticks]");
        ProgramSession session = requireSession(player.getUniqueId());
        long seed = args.length >= 2 ? Long.parseLong(args[1]) : 20_261_122L;
        int ticks = args.length == 3 ? integer(args[2]) : 20;
        if (ticks < 1 || ticks > 20) throw new IllegalArgumentException("单次模拟步数范围：1-20");

        String worldId = "program-" + UUID.randomUUID();
        var result = ProgramSimulation.run(worldId, seed, session.workspace.current(), ticks, budget);
        SandboxView view = result.view();
        var last = result.last();
        boolean replayPassed = result.advanced() > 0 && result.verification().passed();
        player.sendMessage("程序沙盒 " + last.status() + " · seed=" + seed
                + " · tick=" + view.tick() + " · score=" + view.score() + " · ore="
                + view.agents().getFirst().ore() + " · biomass=" + view.agents().getFirst().biomass());
        player.sendMessage("program actions=" + result.actions() + " · replay=" + (replayPassed ? "verified" : "not verified")
                + " · hash=" + view.stateHash().substring(0, 16));
        if (!last.message().isEmpty()) player.sendMessage("! " + last.message());
        reportExecutions(player, last.executions());
    }

    private void reportExecutions(Player player, Map<AgentId, ProgramInterpreter.Execution> executions) {
        executions.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            ProgramInterpreter.Execution execution = entry.getValue();
            player.sendMessage("agent=" + entry.getKey().value() + " · " + execution.status()
                    + " · executed=" + execution.executedInstructions() + " · trace=" + execution.trace().size());
        });
    }

    private void reportExecution(Player player, ProgramInterpreter.Execution execution) {
        player.sendMessage("调试器 " + execution.status() + " · 已执行=" + execution.executedInstructions()
                + " · next=" + execution.nextInstructionId() + " · trace=" + execution.trace().size());
        if (!execution.message().isEmpty()) player.sendMessage("! " + execution.message());
        int firstTrace = Math.max(0, execution.trace().size() - 5);
        for (int index = firstTrace; index < execution.trace().size(); index++) {
            ProgramInterpreter.TraceEntry entry = execution.trace().get(index);
            player.sendMessage(entry.instructionIndex() + " · " + entry.instructionId() + " · "
                    + entry.operation() + " → " + entry.output() + (entry.fault().isEmpty() ? "" : " · " + entry.fault()));
        }
        int actionCount = Math.min(8, execution.actions().size());
        for (int index = 0; index < actionCount; index++)
            player.sendMessage("模拟动作 · " + execution.actions().get(index));
        if (execution.actions().size() > actionCount)
            player.sendMessage("其余模拟动作 " + (execution.actions().size() - actionCount) + " 项");
    }

    private void help(Player player) {
        player.sendMessage("/genesis program create <namespace:id> | list | open <id> | close [discard] | save");
        player.sendMessage("/genesis program add <id> <operation> <inputs|-> <key=value,...|-> | replace <id> <operation> <inputs|-> <参数> | remove <id> | undo");
        player.sendMessage("/genesis program catalog [页] | show [页] | status | context <seed> <tick> | breakpoint <id> <on|off>");
        player.sendMessage("/genesis program sensor <key> <boolean|integer|vector|resource> <value> | resource <id> <量> | signal <channel> <整数>");
        player.sendMessage("/genesis program run | step | continue · isolated virtual context");
        player.sendMessage("/genesis program simulate [seed] [1-20 ticks] · replayable resource sandbox, no real-world changes");
    }

    private List<String> parseInputs(String value) {
        if (value.equals("-")) return List.of();
        return List.of(value.split(",", -1));
    }

    private Map<String, String> parseParameters(String value) {
        if (value.equals("-")) return Map.of();
        Map<String, String> parameters = new HashMap<>();
        for (String pair : value.split(",", -1)) {
            String[] parts = pair.split("=", 2);
            if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty() || parameters.putIfAbsent(parts[0], parts[1]) != null)
                throw new IllegalArgumentException("参数需为不重复的 key=value 列表");
        }
        return Map.copyOf(parameters);
    }

    private ProgramValue.BooleanValue parseBoolean(String value) {
        if (!value.equals("true") && !value.equals("false")) throw new IllegalArgumentException("布尔值必须是 true 或 false");
        return new ProgramValue.BooleanValue(Boolean.parseBoolean(value));
    }

    private ProgramValue.VectorValue parseVector(String value) {
        String[] coordinates = value.split(",", -1);
        if (coordinates.length != 3) throw new IllegalArgumentException("向量格式为 x,y,z");
        return new ProgramValue.VectorValue(integer(coordinates[0]), integer(coordinates[1]), integer(coordinates[2]));
    }

    private int integer(String value) { return Integer.parseInt(value); }

    private void requireLength(String[] args, int expected) {
        if (args.length != expected) throw new IllegalArgumentException("参数数量不正确；使用 /genesis program 查看用法");
    }

    private ProgramSession requireSession(UUID owner) {
        return sessions.require(owner);
    }

}
