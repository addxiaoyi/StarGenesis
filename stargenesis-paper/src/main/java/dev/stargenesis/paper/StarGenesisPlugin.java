package dev.stargenesis.paper;

import dev.stargenesis.api.sim.SimulationBudget;
import dev.stargenesis.api.sim.SimulationWorld;
import dev.stargenesis.api.design.DesignKind;
import dev.stargenesis.design.DesignCodec;
import dev.stargenesis.paper.lab.LabController;
import dev.stargenesis.paper.blueprint.BlueprintController;
import dev.stargenesis.paper.blueprint.BlueprintStore;
import dev.stargenesis.paper.program.ProgramController;
import dev.stargenesis.paper.program.ProgramStore;
import dev.stargenesis.paper.communication.RadioController;
import dev.stargenesis.paper.communication.RadioProtocol;
import dev.stargenesis.paper.communication.ServiceContractDefinition;
import dev.stargenesis.paper.ecology.EcologyController;
import dev.stargenesis.paper.factory.FactoryController;
import dev.stargenesis.paper.arena.ArenaController;
import dev.stargenesis.paper.arena.ArenaRulesetStore;
import dev.stargenesis.paper.design.DesignImportController;
import dev.stargenesis.paper.design.PublisherTrustController;
import dev.stargenesis.paper.communication.RadioProtocol;
import dev.stargenesis.paper.communication.RadioProtocol;
import dev.stargenesis.integrations.CapabilityRegistry;
import dev.stargenesis.integrations.ModelCatalog;
import dev.stargenesis.integrations.EntityProjection;
import dev.stargenesis.integrations.LabProjectionSource;
import dev.stargenesis.paper.integration.ModelCatalogController;
import dev.stargenesis.integrations.InstalledPlugin;
import dev.stargenesis.integrations.IntegrationStatus;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.entity.Player;

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
    private LabController labs;
    private BlueprintController blueprints;
    private ProgramController programs;
    private RadioController radio;
    private EcologyController ecology;
    private FactoryController factory;
    private ArenaController arenas;
    private DesignImportController designImports;
    private PublisherTrustController publisherTrust;
    private ModelCatalogController modelCatalog;

    @Override public void onEnable() {
        try {
            saveDefaultConfig();
            if (getConfig().getInt("config-version", -1) != 1) throw new IllegalArgumentException("Unsupported config-version; migrate explicitly");
            if (getConfig().getBoolean("integrations.allow-real-settlement"))
                throw new IllegalArgumentException("Real settlement requires a configured and verified provider; set integrations.allow-real-settlement=false");
            budget = new SimulationBudget(getConfig().getInt("simulation.nodes-per-agent-step"), getConfig().getInt("simulation.max-agents"),
                    getConfig().getInt("simulation.path-requests-per-step"), getConfig().getLong("simulation.max-steps"),
                    getConfig().getInt("simulation.sensor-radius"), getConfig().getInt("simulation.messages-per-step"));
            stepDuration = getConfig().getLong("simulation.step-duration-millis");
            if (stepDuration < 50 || stepDuration > 1000) throw new IllegalArgumentException("step-duration-millis must be 50..1000");
            verifyBundledDesigns();
            statuses = inspectCapabilities();
            modelCatalog = new ModelCatalogController(this::currentModelCatalog);
            labs = new LabController(this, budget, stepDuration, getDataFolder().toPath().resolve("labs"));
            getServer().getServicesManager().register(LabProjectionSource.class, labs::projectionSnapshot,
                    this, org.bukkit.plugin.ServicePriority.Normal);
            blueprints = new BlueprintController(this,
                    new BlueprintStore(getDataFolder().toPath().resolve("blueprints"), new DesignCodec()));
            programs = new ProgramController(this,
                    new ProgramStore(getDataFolder().toPath().resolve("programs"), new dev.stargenesis.design.TypedProgramCodec()), budget);
            ecology = new EcologyController(this, getDataFolder().toPath().resolve("ecology"));
            factory = new FactoryController(this, getDataFolder().toPath().resolve("factory"));
            arenas = new ArenaController(this, new ArenaRulesetStore(getDataFolder().toPath().resolve("arenas")),
                    getDataFolder().toPath().resolve("arena-matches"));
            designImports = new DesignImportController(getDataFolder().toPath());
            publisherTrust = new PublisherTrustController(getDataFolder().toPath());
            radio = new RadioController(this, budget.messagesPerStep());
            getLogger().info("Virtual radio protocol pinned | " + RadioProtocol.ID.value() + " | hash="
                    + RadioProtocol.protocol().metadata().hash());
            getLogger().info("Virtual service contract pinned | " + ServiceContractDefinition.CONTRACT_ID.value()
                    + " | hash=" + ServiceContractDefinition.contract().metadata().hash());
            PluginCommand command = Objects.requireNonNull(getCommand("genesis"), "Missing genesis command");
            command.setExecutor(this::execute);
            command.setTabCompleter((sender, cmd, alias, args) -> {
                List<String> options = args.length == 1 ? List.of("help", "status", "debug", "lab", "blueprint", "program", "radio", "ecology", "factory", "arena", "design", "model")
                        : args.length == 2 && args[0].equalsIgnoreCase("model") ? List.of("status", "inspect")
                        : args.length == 2 && args[0].equalsIgnoreCase("design") ? List.of("list", "install", "inspect", "publishers")
                        : args.length == 3 && args[0].equalsIgnoreCase("design") && args[1].equalsIgnoreCase("publishers")
                        ? List.of("list", "add", "revoke")
                        : args.length == 2 && args[0].equalsIgnoreCase("lab") ? List.of("start", "stop", "replay", "resume", "status")
                        : args.length == 2 && args[0].equalsIgnoreCase("blueprint")
                        ? List.of("create", "clone", "list", "open", "close", "add", "move", "rotate", "connect", "disconnect", "remove", "undo", "preview", "save", "access", "version")
                        : args.length == 3 && args[0].equalsIgnoreCase("blueprint") && args[1].equalsIgnoreCase("access")
                        ? List.of("public", "clone", "editor", "revoke")
                        : args.length == 4 && args[0].equalsIgnoreCase("blueprint") && args[1].equalsIgnoreCase("access")
                            && (args[2].equalsIgnoreCase("public") || args[2].equalsIgnoreCase("clone"))
                        ? List.of("on", "off")
                        : args.length == 4 && args[0].equalsIgnoreCase("blueprint") && args[1].equalsIgnoreCase("add")
                        ? List.of("genesis:core", "genesis:frame", "genesis:solar_panel", "genesis:sensor", "genesis:wheel", "genesis:thruster", "genesis:wing")
                        : args.length == 2 && args[0].equalsIgnoreCase("program")
                        ? List.of("create", "list", "open", "close", "add", "replace", "remove", "undo", "save", "status", "show", "catalog", "context", "sensor", "resource", "signal", "breakpoint", "run", "step", "continue", "simulate")
                        : args.length == 2 && args[0].equalsIgnoreCase("radio")
                        ? List.of("chip", "join", "leave", "send", "inbox", "ack", "contract", "status")
                        : args.length == 3 && args[0].equalsIgnoreCase("radio") && args[1].equalsIgnoreCase("chip")
                        ? List.of("genesis:radio", "genesis:service")
                        : args.length == 7 && args[0].equalsIgnoreCase("radio") && args[1].equalsIgnoreCase("send") && args[2].equalsIgnoreCase("contract")
                        ? List.of("offer", "accept", "fulfill", "reject", "cancel")
                        : args.length == 2 && args[0].equalsIgnoreCase("ecology")
                        ? List.of("start", "program", "step", "dashboard", "replay", "reset")
                        : args.length == 3 && args[0].equalsIgnoreCase("ecology") && args[1].equalsIgnoreCase("program")
                        ? List.of("producer", "grazer", "decomposer")
                        : args.length == 3 && args[0].equalsIgnoreCase("ecology") && args[1].equalsIgnoreCase("step")
                        ? List.of("1", "5", "10", "20")
                        : args.length == 4 && args[0].equalsIgnoreCase("ecology") && args[1].equalsIgnoreCase("start")
                        ? List.of("500", "1024", "1536")
                        : args.length == 2 && args[0].equalsIgnoreCase("factory")
                        ? List.of("invite", "accept", "leave", "start", "bid", "order", "step", "dashboard", "replay", "matches", "select", "prune", "reset")
                        : args.length == 3 && args[0].equalsIgnoreCase("factory")
                        && List.of("select", "prune", "reset").contains(args[1].toLowerCase(java.util.Locale.ROOT))
                        ? List.of("factory-")
                        : args.length == 4 && args[0].equalsIgnoreCase("factory") && args[1].equalsIgnoreCase("order")
                        ? List.of("press", "mill", "assembly")
                        : args.length == 3 && args[0].equalsIgnoreCase("factory") && args[1].equalsIgnoreCase("step")
                        ? List.of("1", "5", "10")
                        : args.length == 2 && args[0].equalsIgnoreCase("arena")
                        ? List.of("create", "clone", "list", "catalog", "open", "close", "set", "access", "dep", "undo", "preview", "save", "publish", "review", "match")
                        : args.length == 3 && args[0].equalsIgnoreCase("arena") && args[1].equalsIgnoreCase("review")
                        ? List.of("pending", "status", "show", "approve", "reject")
                        : args.length == 3 && args[0].equalsIgnoreCase("arena") && args[1].equalsIgnoreCase("match")
                        ? List.of("invite", "accept", "leave", "start", "pass", "claim", "withdraw", "dashboard", "replay", "watch", "unwatch", "select", "matches", "prune")
                        : args.length == 4 && args[0].equalsIgnoreCase("arena") && args[1].equalsIgnoreCase("match") && args[2].equalsIgnoreCase("invite")
                        ? getServer().getOnlinePlayers().stream().map(Player::getName).toList()
                        : args.length == 5 && args[0].equalsIgnoreCase("arena") && args[1].equalsIgnoreCase("match") && args[2].equalsIgnoreCase("invite")
                        ? List.of("cedar", "ember")
                        : args.length == 4 && args[0].equalsIgnoreCase("arena") && args[1].equalsIgnoreCase("match") && args[2].equalsIgnoreCase("start")
                        ? List.of("genesis:arena_ruleset")
                        : args.length == 5 && args[0].equalsIgnoreCase("arena") && args[1].equalsIgnoreCase("match") && args[2].equalsIgnoreCase("start")
                        ? List.of("1.0.0")
                        : args.length == 4 && args[0].equalsIgnoreCase("arena") && args[1].equalsIgnoreCase("match") && args[2].equalsIgnoreCase("claim")
                        ? List.of("0", "1", "2", "3")
                        : args.length == 5 && args[0].equalsIgnoreCase("arena") && args[1].equalsIgnoreCase("match") && args[2].equalsIgnoreCase("claim")
                        ? List.of("0", "1", "2", "3")
                        : args.length == 6 && args[0].equalsIgnoreCase("arena") && args[1].equalsIgnoreCase("match") && args[2].equalsIgnoreCase("claim")
                        ? List.of("1", "2", "3", "4")
                        : args.length == 4 && args[0].equalsIgnoreCase("arena") && args[1].equalsIgnoreCase("match")
                        && List.of("watch", "select", "prune").contains(args[2].toLowerCase(java.util.Locale.ROOT))
                        ? List.of("arena-")
                        : args.length == 3 && args[0].equalsIgnoreCase("arena") && args[1].equalsIgnoreCase("access")
                        ? List.of("public", "clone", "editor", "revoke")
                        : args.length == 3 && args[0].equalsIgnoreCase("arena") && args[1].equalsIgnoreCase("set")
                        ? List.of("width", "height", "duration", "units", "version")
                        : args.length == 3 && args[0].equalsIgnoreCase("arena") && args[1].equalsIgnoreCase("dep")
                        ? List.of("add", "remove")
                        : args.length == 4 && args[0].equalsIgnoreCase("program") && args[1].equalsIgnoreCase("add")
                        ? Arrays.stream(dev.stargenesis.design.PrimitiveCatalog.Operation.values()).map(Enum::name).toList()
                        : args.length == 4 && args[0].equalsIgnoreCase("program") && args[1].equalsIgnoreCase("sensor")
                        ? List.of("boolean", "integer", "vector", "resource")
                        : args.length == 4 && args[0].equalsIgnoreCase("program") && args[1].equalsIgnoreCase("breakpoint")
                        ? List.of("on", "off")
                        : args.length == 2 && args[0].equalsIgnoreCase("debug") && sender.hasPermission("stargenesis.debug")
                        ? List.of("capabilities", "budgets") : List.of();
                String prefix = args[args.length - 1].toLowerCase(Locale.ROOT);
                return options.stream().filter(s -> s.startsWith(prefix)).toList();
            });
            getLogger().info("StarGenesis ready | P0 accepted | P1/P3 simulations available, player acceptance pending | api=1 | engine="
                    + SimulationWorld.ENGINE_VERSION + " | P3 typed program workspace=available | P4 virtual radio=available | P5 ecology=available | P6 factory=available | P7 arena UGC=available | P8 territory match=available | P9 durable saga contract=available, real gateway=inactive | P10 signed design import=available | real-settlement=disabled | lab=" + labs.availabilityReason());
            statuses.forEach(s -> getLogger().info(s.toString()));
        } catch (RuntimeException | IOException failure) {
            getLogger().severe("StarGenesis startup rejected: " + failure.getMessage());
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private ModelCatalog currentModelCatalog() {
        var registration = getServer().getServicesManager().getRegistration(ModelCatalog.class);
        return registration == null || !registration.getPlugin().isEnabled() ? null : registration.getProvider();
    }

    private EntityProjection currentProjection() {
        var registration = getServer().getServicesManager().getRegistration(EntityProjection.class);
        return registration == null || !registration.getPlugin().isEnabled() ? null : registration.getProvider();
    }

    private List<IntegrationStatus> inspectCapabilities() {
        Map<String, InstalledPlugin> inventory = Arrays.stream(getServer().getPluginManager().getPlugins())
                .map(p -> new InstalledPlugin(p.getName(), p.getPluginMeta().getVersion(), p.isEnabled()))
                .collect(Collectors.toUnmodifiableMap(InstalledPlugin::name, Function.identity()));
        try (CapabilityRegistry registry = CapabilityRegistry.auditedDefaults()) {
            ModelCatalog catalog = currentModelCatalog();
            if (catalog != null && catalog.integrationId().equals("helstera"))
                // The supplying plugin owns closure; this inspection only borrows verification.
                registry.registerAdapter("helstera", plugin -> {
                    var inspection = catalog.verify(plugin);
                    EntityProjection projection = currentProjection();
                    if (projection != null) {
                        try { return projection.verify(plugin); }
                        catch (RuntimeException | LinkageError unavailable) { /* Inspection remains available. */ }
                    }
                    return inspection;
                });
            return registry.inspect(name -> Optional.ofNullable(inventory.get(name)));
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
        getLogger().info("StarGenesis design self-check passed: 5 hashed schema-v1 examples");
    }

    private boolean execute(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("model")) return modelCatalog.handle(sender, Arrays.copyOfRange(args, 1, args.length));
        if (sub.equals("lab")) {
            if (!(sender instanceof Player player)) { sender.sendMessage("StarGenesis lab requires a player; use /genesis status for server diagnostics."); return true; }
            return labs.handle(player, Arrays.copyOfRange(args, 1, args.length));
        }
        if (sub.equals("blueprint")) {
            if (!(sender instanceof Player player)) { sender.sendMessage("StarGenesis blueprint editing requires an in-game player."); return true; }
            return blueprints.handle(player, Arrays.copyOfRange(args, 1, args.length));
        }
        if (sub.equals("program")) {
            if (!(sender instanceof Player player)) { sender.sendMessage("StarGenesis program editing requires an in-game player."); return true; }
            return programs.handle(player, Arrays.copyOfRange(args, 1, args.length));
        }
        if (sub.equals("radio")) {
            if (!(sender instanceof Player player)) { sender.sendMessage("StarGenesis radio requires an in-game player."); return true; }
            return radio.handle(player, Arrays.copyOfRange(args, 1, args.length));
        }
        if (sub.equals("ecology")) {
            if (!(sender instanceof Player player)) { sender.sendMessage("StarGenesis ecology requires an in-game player."); return true; }
            return ecology.handle(player, Arrays.copyOfRange(args, 1, args.length));
        }
        if (sub.equals("factory")) {
            if (!(sender instanceof Player player)) { sender.sendMessage("StarGenesis factory requires an in-game player."); return true; }
            return factory.handle(player, Arrays.copyOfRange(args, 1, args.length));
        }
        if (sub.equals("arena")) {
            if (!(sender instanceof Player player)) { sender.sendMessage("StarGenesis arena editing requires an in-game player."); return true; }
            return arenas.handle(player, Arrays.copyOfRange(args, 1, args.length));
        }
        if (sub.equals("design")) {
            if (args.length > 1 && args[1].equalsIgnoreCase("publishers"))
                return publisherTrust.handle(sender, Arrays.copyOfRange(args, 2, args.length));
            if (!(sender instanceof Player player)) { sender.sendMessage("StarGenesis signed design import requires an in-game player."); return true; }
            return designImports.handle(player, Arrays.copyOfRange(args, 1, args.length));
        }
        String permission = "stargenesis." + (sub.equals("debug") ? "debug" : sub.equals("status") ? "status" : "help");
        if (!sender.hasPermission(permission)) { sender.sendMessage("StarGenesis: permission denied (" + permission + ")"); return true; }
        if (sub.equals("status") && args.length == 1) {
            sender.sendMessage("StarGenesis " + getPluginMeta().getVersion()
                    + " | foundation accepted; automated workflows available, human acceptance pending | engine=" + SimulationWorld.ENGINE_VERSION);
            sender.sendMessage("Design schema=1 | simulation-worker=" + (labs.isAvailable() ? "scheduled" : "inactive")
                    + " | real-settlement=disabled | integrations=" + statuses.size());
            sender.sendMessage("P1 resource lab=" + labs.availabilityReason() + " | P2 blueprint editor=available | P3 typed program editor=available");
            sender.sendMessage("P3 programs run in a replayable resource sandbox; Minecraft world mutations remain disabled.");
            sender.sendMessage("P4 virtual radio=available | /genesis radio");
            sender.sendMessage("P5 closed ecology=available | /genesis ecology dashboard");
            sender.sendMessage("P6 four-seat simulated factory=available | /genesis factory dashboard");
            sender.sendMessage("P7 licensed arena catalog=available | /genesis arena catalog");
            sender.sendMessage("P8 simultaneous territory match=available | /genesis arena match matches");
            sender.sendMessage("P9 durable saga contract=available, real gateway=inactive");
            sender.sendMessage("P10 signed Ed25519 design import=available | /genesis design list");
        } else if (sub.equals("debug") && args.length == 2 && args[1].equalsIgnoreCase("capabilities")) {
            sender.sendMessage("StarGenesis capabilities (current providers; installed != active):");
            inspectCapabilities().forEach(s -> sender.sendMessage(s.toString()));
        } else if (sub.equals("debug") && args.length == 2 && args[1].equalsIgnoreCase("budgets")) {
            sender.sendMessage("StarGenesis budgets: stepMillis=" + stepDuration + " " + budget);
        } else {
            sender.sendMessage("StarGenesis - 玩家发明规则，世界检验发明。");
            sender.sendMessage("/genesis status - current foundation and capability state");
            sender.sendMessage("/genesis lab start [seed] [agents] | stop | replay | resume | status - virtual resource experiment");
            sender.sendMessage("/genesis blueprint - create, edit, preview, undo, and save private virtual structures");
            sender.sendMessage("/genesis program - edit and debug a private typed program in a virtual context");
            sender.sendMessage("/genesis radio - install the virtual radio endpoint, send and receive protocol messages");
            sender.sendMessage("/genesis ecology - program matter, advance a private closed ecosystem, inspect and replay it");
            sender.sendMessage("/genesis factory - form a two-player organization, bid, place simulated orders, recover and replay");
            sender.sendMessage("/genesis arena - create, license, submit for review, and play approved ArenaRulesets");
            sender.sendMessage("/genesis design - install and inspect administrator-staged signed design packages");
            sender.sendMessage("/genesis design publishers - operator-managed publisher trust roots and key rotation");
            sender.sendMessage("/genesis model status | inspect <id> - optional read-only model catalog");
            sender.sendMessage("/genesis debug capabilities | /genesis debug budgets - operator diagnostics");
            sender.sendMessage("/genesis help - this help. Laboratory score cannot create real inventory or money.");
        }
        return true;
    }

    @Override public void onDisable() {
        boolean clean = true;
        EntityProjection projection = currentProjection();
        if (projection != null) clean &= stop("Model projection", projection::close, () -> projection.reservedEntities() == 0);
        getServer().getServicesManager().unregisterAll(this);
        modelCatalog = null;
        if (publisherTrust != null) { publisherTrust = null; }
        if (designImports != null) { designImports = null; }
        if (arenas != null) { clean &= stop("Arena", arenas::close, arenas::isStopped); arenas = null; }
        if (factory != null) { clean &= stop("Factory", factory::close, factory::isStopped); factory = null; }
        if (ecology != null) { clean &= stop("Ecology", ecology::close, ecology::isStopped); ecology = null; }
        if (radio != null) { clean &= stop("Radio", radio::close, radio::isStopped); radio = null; }
        if (programs != null) { clean &= stop("Program", programs::close, () -> true); programs = null; }
        if (blueprints != null) { clean &= stop("Blueprint", blueprints::close, () -> true); blueprints = null; }
        if (labs != null) { clean &= stop("Lab", labs::close, labs::isStopped); labs = null; }
        statuses = List.of();
        if (clean) getLogger().info("StarGenesis stopped cleanly; owned workers and registered services stopped; real settlement remains disabled.");
        else getLogger().severe("StarGenesis shutdown incomplete; inspect service failures. Active workers retain their storage directory locks until they exit.");
    }

    private boolean stop(String service, Runnable action, java.util.function.BooleanSupplier stopped) {
        try { action.run(); return stopped.getAsBoolean(); }
        catch (RuntimeException | LinkageError failure) {
            getLogger().log(java.util.logging.Level.SEVERE, service + " shutdown failed; continuing other service cleanup", failure);
            return false;
        }
    }
}
