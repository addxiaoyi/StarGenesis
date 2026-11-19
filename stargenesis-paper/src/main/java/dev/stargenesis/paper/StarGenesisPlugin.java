package dev.stargenesis.paper;

import dev.stargenesis.api.sim.SimulationBudget;
import dev.stargenesis.api.sim.SimulationWorld;
import dev.stargenesis.api.design.DesignKind;
import dev.stargenesis.design.DesignCodec;
import dev.stargenesis.integrations.CapabilityRegistry;
import dev.stargenesis.integrations.InstalledPlugin;
import dev.stargenesis.integrations.IntegrationStatus;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.nio.charset.StandardCharsets;
import java.io.IOException;

/** Server lifecycle owns adapters. This foundation does not access worlds or player inventories. */
public final class StarGenesisPlugin extends JavaPlugin {
    private List<IntegrationStatus> statuses = List.of();
    private SimulationBudget budget;
    private long stepDuration;

    @Override public void onEnable() {
        try {
            saveDefaultConfig();
            if (getConfig().getInt("config-version", -1) != 1) throw new IllegalArgumentException("Unsupported config-version; migrate explicitly");
            if (getConfig().getBoolean("integrations.allow-real-settlement"))
                throw new IllegalArgumentException("Real settlement is unavailable in P0; set integrations.allow-real-settlement=false");
            budget = new SimulationBudget(getConfig().getInt("simulation.nodes-per-agent-step"), getConfig().getInt("simulation.max-agents"),
                    getConfig().getInt("simulation.path-requests-per-step"), getConfig().getLong("simulation.max-steps"),
                    getConfig().getInt("simulation.sensor-radius"), getConfig().getInt("simulation.messages-per-step"));
            stepDuration = getConfig().getLong("simulation.step-duration-millis");
            if (stepDuration < 50 || stepDuration > 1000) throw new IllegalArgumentException("step-duration-millis must be 50..1000");
            verifyBundledDesigns();
            Map<String, InstalledPlugin> inventory = Arrays.stream(getServer().getPluginManager().getPlugins())
                    .map(p -> new InstalledPlugin(p.getName(), p.getPluginMeta().getVersion(), p.isEnabled()))
                    .collect(Collectors.toUnmodifiableMap(InstalledPlugin::name, Function.identity()));
            statuses = CapabilityRegistry.auditedDefaults().inspect(name -> Optional.ofNullable(inventory.get(name)));
            PluginCommand command = Objects.requireNonNull(getCommand("genesis"), "Missing genesis command");
            command.setExecutor(this::execute);
            command.setTabCompleter((sender, cmd, alias, args) -> {
                List<String> options = args.length == 1 ? List.of("help", "status", "debug")
                        : args.length == 2 && args[0].equalsIgnoreCase("debug") && sender.hasPermission("stargenesis.debug")
                        ? List.of("capabilities", "budgets") : List.of();
                String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
                return options.stream().filter(s -> s.startsWith(prefix)).toList();
            });
            getLogger().info("StarGenesis ready | phase=P0 | api=1 | engine=" + SimulationWorld.ENGINE_VERSION + " | real-settlement=disabled");
            statuses.forEach(s -> getLogger().info(s.toString()));
        } catch (RuntimeException failure) {
            getLogger().severe("StarGenesis startup rejected: " + failure.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void verifyBundledDesigns() {
        DesignCodec codec = new DesignCodec();
        for (DesignKind kind : DesignKind.values()) {
            String resource = "/examples/" + kind.name().toLowerCase(Locale.ROOT) + ".json";
            try (var input = DesignCodec.class.getResourceAsStream(resource)) {
                if (input == null || codec.load(new String(input.readAllBytes(), StandardCharsets.UTF_8)).kind() != kind)
                    throw new IllegalStateException("Missing or mismatched bundled design: " + resource);
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot verify bundled design: " + resource, failure);
            }
        }
        getLogger().info("StarGenesis design self-check passed: 5 signed schema-v1 examples");
    }

    private boolean execute(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        String permission = "stargenesis." + (sub.equals("debug") ? "debug" : sub.equals("status") ? "status" : "help");
        if (!sender.hasPermission(permission)) { sender.sendMessage("StarGenesis: permission denied (" + permission + ")"); return true; }
        if (sub.equals("status") && args.length == 1) {
            sender.sendMessage("StarGenesis " + getPluginMeta().getVersion() + " | phase=P0 foundation | engine=" + SimulationWorld.ENGINE_VERSION);
            sender.sendMessage("Design schema=1 | simulation-worker=not-started | real-settlement=disabled | integrations=" + statuses.size());
            sender.sendMessage("P1 laboratory and P2-P11 gameplay are not implemented in this build.");
        } else if (sub.equals("debug") && args.length == 2 && args[1].equalsIgnoreCase("capabilities")) {
            sender.sendMessage("StarGenesis capabilities (startup snapshot; installed != active):");
            statuses.forEach(s -> sender.sendMessage(s.toString()));
        } else if (sub.equals("debug") && args.length == 2 && args[1].equalsIgnoreCase("budgets")) {
            sender.sendMessage("StarGenesis budgets: stepMillis=" + stepDuration + " " + budget);
        } else {
            sender.sendMessage("StarGenesis - 玩家发明规则，世界检验发明。");
            sender.sendMessage("/genesis status - current foundation and capability state");
            sender.sendMessage("/genesis debug capabilities | /genesis debug budgets - operator diagnostics");
            sender.sendMessage("/genesis help - this help. P0 has no laboratory or real inventory settlement.");
        }
        return true;
    }

    @Override public void onDisable() {
        statuses = List.of();
        getLogger().info("StarGenesis stopped cleanly; no workers, entity projections or settlement jobs remain.");
    }
}
