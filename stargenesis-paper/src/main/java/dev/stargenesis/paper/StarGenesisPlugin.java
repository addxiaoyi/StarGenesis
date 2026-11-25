package dev.stargenesis.paper;

import dev.stargenesis.api.sim.SimulationBudget;
import dev.stargenesis.api.sim.SimulationWorld;
import dev.stargenesis.api.design.DesignKind;
import dev.stargenesis.design.ArenaRuleset;
import dev.stargenesis.design.ArenaRulesetValidator;
import dev.stargenesis.design.DesignCodec;
import dev.stargenesis.design.DesignExchange;
import dev.stargenesis.design.SignedDesignArtifact;
import dev.stargenesis.integrations.CapabilityRegistry;
import dev.stargenesis.integrations.ExtensionGrant;
import dev.stargenesis.integrations.InstalledPlugin;
import dev.stargenesis.integrations.IntegrationAdapter;
import dev.stargenesis.integrations.IntegrationExtension;
import dev.stargenesis.integrations.IntegrationStatus;
import net.kyori.adventure.text.Component;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.Plugin;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginDisableEvent;
import org.bukkit.event.server.PluginEnableEvent;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.CodeSource;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.net.URISyntaxException;
import java.util.Base64;
import java.util.HexFormat;

/** Server lifecycle owns adapters. This foundation does not access worlds or player inventories. */
public final class StarGenesisPlugin extends JavaPlugin implements Listener {
    private List<IntegrationStatus> statuses = List.of();
    private List<ExtensionGrant> extensionGrants = List.of();
    private final Map<String, Plugin> activeExtensionOwners = new HashMap<>();
    private CapabilityRegistry capabilityRegistry;
    private SimulationBudget budget;
    private long stepDuration;
    private LaboratoryMenu laboratory;
    private BlueprintEditorMenu blueprintEditor;
    private BlueprintAssemblyMenu blueprintAssembly;
    private ProgramEditorMenu programEditor;
    private ProtocolMenu protocolMenu;
    private ProtocolNetworkService protocolNetwork;
    private ProtocolInboxMenu protocolInboxMenu;
    private EcosystemMenu ecosystemMenu;
    private EcologyRecipeMenu ecologyRecipeMenu;
    private SharedEcologyService sharedEcology;
    private SharedEcologyMenu sharedEcologyMenu;
    private FactoryService factoryService;
    private FactoryCommandRelayService factoryCommandRelay;
    private FactoryOrganizationMenu factoryOrganizationMenu;
    private ArenaRulesetMenu arenaRulesetMenu;
    private ArenaReviewCommands arenaReviewCommands;
    private ArenaReviewRecoveryCommands arenaReviewRecoveryCommands;
    private ArenaMatchService arenaMatches;
    private SettlementJournalCommands settlementJournal;
    private DesignSigningTrustFileStore designTrust;
    private ArenaReviewTrustFileStore arenaReviewTrust;
    private DesignExchange designExchange;
    private SignedDesignArchiveStore importedDesigns;
    private Path signedDesignImports;

    @Override public void onEnable() {
        try {
            saveDefaultConfig();
            if (getConfig().getInt("config-version", -1) != 1) throw new IllegalArgumentException("Unsupported config-version; migrate explicitly");
            if (getConfig().getBoolean("integrations.allow-real-settlement"))
                throw new IllegalArgumentException("No verified settlement adapter is available; set integrations.allow-real-settlement=false");
            extensionGrants = loadExtensionGrants();
            budget = new SimulationBudget(getConfig().getInt("simulation.nodes-per-agent-step"), getConfig().getInt("simulation.max-agents"),
                    getConfig().getInt("simulation.path-requests-per-step"), getConfig().getLong("simulation.max-steps"),
                    getConfig().getInt("simulation.sensor-radius"), getConfig().getInt("simulation.messages-per-step"));
            stepDuration = getConfig().getLong("simulation.step-duration-millis");
            if (stepDuration < 50 || stepDuration > 1000) throw new IllegalArgumentException("step-duration-millis must be 50..1000");
            verifyBundledDesigns();
            capabilityRegistry = CapabilityRegistry.withExtensions(extensionGrants);
            getServer().getPluginManager().registerEvents(this, this);
            registerTrustedExtensions(extensionGrants, null);
            refreshIntegrationStatuses();
            laboratory = new LaboratoryMenu(this, budget, stepDuration);
            getServer().getPluginManager().registerEvents(laboratory, this);
            blueprintEditor = new BlueprintEditorMenu(this);
            getServer().getPluginManager().registerEvents(blueprintEditor, this);
            blueprintAssembly = new BlueprintAssemblyMenu(this, blueprintEditor);
            getServer().getPluginManager().registerEvents(blueprintAssembly, this);
            programEditor = new ProgramEditorMenu(this, laboratory);
            getServer().getPluginManager().registerEvents(programEditor, this);
            ProtocolInboxStore inboxStore = new ProtocolInboxStore(this);
            protocolInboxMenu = new ProtocolInboxMenu(inboxStore);
            getServer().getPluginManager().registerEvents(protocolInboxMenu, this);
            ProtocolRelaySettings relaySettings = ProtocolRelaySettings.from(getConfig());
            boolean factoryRelayEnabled = getConfig().getBoolean("factory-relay.enabled", false);
            if (factoryRelayEnabled && !relaySettings.enabled())
                throw new IllegalArgumentException("factory-relay.enabled requires an enabled protocol-relay peer configuration");
            protocolNetwork = new ProtocolNetworkService(this, inboxStore, relaySettings);
            protocolNetwork.start((player, packet) -> protocolMenu != null && protocolMenu.receiveRelay(player, packet));
            protocolMenu = new ProtocolMenu(this, protocolNetwork, protocolInboxMenu);
            getServer().getPluginManager().registerEvents(protocolMenu, this);
            ecosystemMenu = new EcosystemMenu(this);
            ecologyRecipeMenu = new EcologyRecipeMenu(ecosystemMenu);
            ecosystemMenu.setRecipeMenu(ecologyRecipeMenu);
            getServer().getPluginManager().registerEvents(ecosystemMenu, this);
            getServer().getPluginManager().registerEvents(ecologyRecipeMenu, this);
            try {
                sharedEcology = new SharedEcologyService(this);
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot restore shared ecology checkpoints", failure);
            }
            sharedEcologyMenu = new SharedEcologyMenu(this, sharedEcology);
            getServer().getPluginManager().registerEvents(sharedEcologyMenu, this);
            try {
                factoryService = new FactoryService(this);
                factoryCommandRelay = new FactoryCommandRelayService(
                        this, relaySettings, factoryService, factoryRelayEnabled);
                protocolNetwork.setFactoryCommandRelay(factoryCommandRelay);
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot load virtual factory or relay checkpoints", failure);
            }
            factoryOrganizationMenu = new FactoryOrganizationMenu(this, factoryService);
            getServer().getPluginManager().registerEvents(factoryOrganizationMenu, this);
            settlementJournal = new SettlementJournalCommands(getDataFolder().toPath());
            getLogger().info(settlementJournal.startupReport());
            designTrust = new DesignSigningTrustFileStore(getDataFolder().toPath());
            arenaReviewTrust = new ArenaReviewTrustFileStore(getDataFolder().toPath());
            designExchange = new DesignExchange(new DesignCodec());
            importedDesigns = new SignedDesignArchiveStore(getDataFolder().toPath(), designExchange);
            signedDesignImports = getDataFolder().toPath().resolve("design-imports").toAbsolutePath().normalize();
            try {
                getLogger().info("Design signing trust verified | authors=" + designTrust.loadAll().size()
                        + " | signed-archive=" + importedDesigns.list().size());
                getLogger().info("Arena reviewer signing trust verified | reviewers=" + arenaReviewTrust.loadAll().size());
                Files.createDirectories(signedDesignImports);
                if (!Files.isDirectory(signedDesignImports, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("Design import path is not a directory");
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot load the design signing trust store", failure);
            }
            PublishedDesignStore publishedDesigns = new PublishedDesignStore(
                    getDataFolder().toPath().resolve("published-designs"), new DesignCodec());
            ArenaReviewQueue arenaReviews = new ArenaReviewQueue(getDataFolder().toPath(), new DesignCodec(),
                    publishedDesigns, arenaReviewTrust);
            arenaReviewCommands = new ArenaReviewCommands(arenaReviews);
            arenaReviewRecoveryCommands = new ArenaReviewRecoveryCommands(arenaReviews);
            arenaMatches = new ArenaMatchService(this, publishedDesigns);
            arenaRulesetMenu = new ArenaRulesetMenu(this, publishedDesigns, arenaReviews, arenaMatches);
            try {
                arenaRulesetMenu.seedBundledRelease();
            } catch (IOException failure) {
                throw new IllegalStateException("Cannot initialize the immutable design release catalog", failure);
            }
            arenaMatches.start();
            getServer().getPluginManager().registerEvents(arenaRulesetMenu, this);
            PluginCommand command = Objects.requireNonNull(getCommand("genesis"), "Missing genesis command");
            command.setExecutor(this::execute);
            command.setTabCompleter((sender, cmd, alias, args) -> {
                List<String> options = args.length == 1 ? List.of("help", "status", "debug", "lab", "blueprint", "assemble", "program", "protocol", "ecosystem", "factory", "arena")
                        : args.length == 2 && args[0].equalsIgnoreCase("debug") && sender.hasPermission("stargenesis.debug")
                        ? List.of("capabilities", "budgets")
                        : args.length == 2 && args[0].equalsIgnoreCase("lab") && sender.hasPermission("stargenesis.lab")
                        ? List.of("reset", "close")
                        : args.length == 2 && args[0].equalsIgnoreCase("blueprint") && sender.hasPermission("stargenesis.blueprint")
                        ? List.of("reset") : List.of();
                if (args.length == 2 && args[0].equalsIgnoreCase("assemble") && sender.hasPermission("stargenesis.blueprint"))
                    options = List.of("reset");
                if (args.length == 2 && args[0].equalsIgnoreCase("program") && sender.hasPermission("stargenesis.program"))
                    options = List.of("reset", "run");
                if (args.length == 2 && args[0].equalsIgnoreCase("protocol") && sender.hasPermission("stargenesis.protocol"))
                    options = List.of("send", "inbox", "reset");
                if (args.length == 2 && args[0].equalsIgnoreCase("ecosystem") && sender.hasPermission("stargenesis.ecosystem"))
                    options = List.of("reset", "share");
                if (args.length == 3 && args[0].equalsIgnoreCase("ecosystem")
                        && args[1].equalsIgnoreCase("share") && sender.hasPermission("stargenesis.ecosystem"))
                    options = List.of("menu", "list", "create", "add", "remove", "leave", "disband", "status", "step");
                if (args.length == 2 && args[0].equalsIgnoreCase("factory") && sender.hasPermission("stargenesis.factory"))
                    options = List.of("identity", "org", "create", "status", "orders", "order", "bid", "contribute", "fulfill", "advance", "station", "remote");
                if (args.length == 3 && args[0].equalsIgnoreCase("factory")
                        && args[1].equalsIgnoreCase("remote") && sender.hasPermission("stargenesis.factory"))
                    options = factoryCommandRelay.trustedPeers();
                if (args.length == 4 && args[0].equalsIgnoreCase("factory")
                        && args[1].equalsIgnoreCase("remote") && sender.hasPermission("stargenesis.factory"))
                    options = List.of("status", "orders", "bid", "contribute", "fulfill", "advance", "station");
                if (args.length == 5 && args[0].equalsIgnoreCase("factory")
                        && args[1].equalsIgnoreCase("remote") && args[3].equalsIgnoreCase("station")
                        && sender.hasPermission("stargenesis.factory"))
                    options = List.of("status", "queue", "cancel", "collect");
                if (args.length == 6 && args[0].equalsIgnoreCase("factory")
                        && args[1].equalsIgnoreCase("remote") && args[3].equalsIgnoreCase("station")
                        && args[4].equalsIgnoreCase("queue") && sender.hasPermission("stargenesis.factory"))
                    options = dev.stargenesis.sim.FactoryWorld.recipes().stream()
                            .map(recipe -> recipe.id().value()).toList();
                if (args.length == 3 && args[0].equalsIgnoreCase("factory")
                        && args[1].equalsIgnoreCase("station") && sender.hasPermission("stargenesis.factory"))
                    options = List.of("status", "queue", "cancel", "collect");
                if (args.length == 4 && args[0].equalsIgnoreCase("factory")
                        && args[1].equalsIgnoreCase("station") && args[2].equalsIgnoreCase("queue")
                        && sender.hasPermission("stargenesis.factory"))
                    options = dev.stargenesis.sim.FactoryWorld.recipes().stream()
                            .map(recipe -> recipe.id().value()).toList();
                if (args.length == 3 && args[0].equalsIgnoreCase("factory")
                        && args[1].equalsIgnoreCase("org") && sender.hasPermission("stargenesis.factory"))
                    options = List.of("menu", "status", "create", "add", "remove");
                if (args.length == 4 && args[0].equalsIgnoreCase("factory")
                        && args[1].equalsIgnoreCase("org")
                        && (args[2].equalsIgnoreCase("add") || args[2].equalsIgnoreCase("remove"))
                        && sender.hasPermission("stargenesis.factory"))
                    options = getServer().getOnlinePlayers().stream().map(player -> player.getUniqueId().toString()).toList();
                if (sender.hasPermission("stargenesis.settlement.review")) {
                    if (args.length == 1) options = java.util.stream.Stream.concat(options.stream(), java.util.stream.Stream.of("settlement")).toList();
                    if (args.length == 2 && args[0].equalsIgnoreCase("settlement")) options = List.of("list", "inspect");
                }
                if (sender.hasPermission("stargenesis.design.admin")) {
                    if (args.length == 1) options = java.util.stream.Stream.concat(options.stream(), java.util.stream.Stream.of("design")).toList();
                    if (args.length == 2 && args[0].equalsIgnoreCase("design"))
                        options = List.of("keys", "trust", "revoke", "reviewers", "import", "imports", "inspect");
                    if (args.length == 3 && args[0].equalsIgnoreCase("design")
                            && args[1].equalsIgnoreCase("reviewers"))
                        options = List.of("list", "trust", "revoke");
                    if (args.length == 3 && args[0].equalsIgnoreCase("design")
                            && args[1].equalsIgnoreCase("imports"))
                        options = java.util.stream.IntStream.rangeClosed(1,
                                (SignedDesignArchiveStore.MAX_ARCHIVED_DESIGNS + 19) / 20)
                                .mapToObj(Integer::toString).toList();
                }
                if (args.length == 2 && args[0].equalsIgnoreCase("arena") && sender.hasPermission("stargenesis.arena"))
                    options = List.of("reset", "publish", "releases", "review", "approve", "reject", "grant", "revoke", "map", "play", "rooms", "join", "leave", "ready", "act", "match", "spectate", "kick", "stop");
                if (args.length == 2 && args[0].equalsIgnoreCase("arena")) {
                    options = options.stream().filter(option -> !ArenaReviewCommands.requiresReviewPermission(option)).toList();
                    if (sender.hasPermission("stargenesis.arena.review"))
                        options = java.util.stream.Stream.concat(options.stream(),
                                java.util.stream.Stream.of("review", "approve", "reject", "decisions", "decision", "recover")).toList();
                }
                if (args.length == 3 && args[0].equalsIgnoreCase("arena")
                        && args[1].equalsIgnoreCase("decisions") && sender.hasPermission("stargenesis.arena.review"))
                    options = java.util.stream.IntStream.rangeClosed(1,
                            Math.ceilDiv(ArenaReviewQueue.MAX_DECISIONS, ArenaReviewQueue.DECISION_PAGE_SIZE))
                            .mapToObj(Integer::toString).toList();
                if (args.length == 3 && args[0].equalsIgnoreCase("arena")
                        && args[1].equalsIgnoreCase("rooms") && sender.hasPermission("stargenesis.arena"))
                    options = java.util.stream.IntStream.rangeClosed(1, (ArenaRoomStore.MAX_ROOMS + 19) / 20)
                            .mapToObj(Integer::toString).toList();
                if (args.length == 3 && args[0].equalsIgnoreCase("arena")
                        && args[1].equalsIgnoreCase("join") && sender.hasPermission("stargenesis.arena"))
                    options = List.of("red:1", "red:2", "blue:1", "blue:2");
                if (args.length == 4 && args[0].equalsIgnoreCase("arena")
                        && args[1].equalsIgnoreCase("act") && sender.hasPermission("stargenesis.arena"))
                    options = List.of("wait", "north", "east", "south", "west");
                if (args.length == 3 && args[0].equalsIgnoreCase("arena")
                        && (args[1].equalsIgnoreCase("grant") || args[1].equalsIgnoreCase("revoke"))
                        && sender.hasPermission("stargenesis.arena"))
                    options = getServer().getOnlinePlayers().stream().map(player -> player.getUniqueId().toString()).toList();
                if (args.length == 3 && args[0].equalsIgnoreCase("protocol")
                        && sender.hasPermission("stargenesis.protocol")) {
                    options = args[1].equalsIgnoreCase("send")
                            ? getServer().getOnlinePlayers().stream().map(Player::getName).toList()
                            : args[1].equalsIgnoreCase("inbox") ? List.of("clear") : List.of();
                }
                String prefix = args.length == 0 ? "" : args[args.length - 1].toLowerCase(Locale.ROOT);
                return options.stream().filter(s -> s.startsWith(prefix)).toList();
            });
            getLogger().info("StarGenesis ready | phases=P1-P10 development | api=1 | engine=" + SimulationWorld.ENGINE_VERSION
                    + " | real-settlement=disabled");
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
        String arenaV2 = "/examples/arena_ruleset-v2.json";
        try (var input = getClass().getResourceAsStream(arenaV2)) {
            if (input == null || !(codec.load(new String(input.readAllBytes(), StandardCharsets.UTF_8)) instanceof ArenaRuleset ruleset)
                    || ruleset.metadata().schemaVersion() != ArenaRuleset.SCHEMA_VERSION)
                throw new IllegalStateException("Missing or mismatched bundled arena schema-v2 example");
            new ArenaRulesetValidator().requireValid(ruleset);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot verify bundled arena schema-v2 example", failure);
        }
        getLogger().info("StarGenesis design self-check passed: 5 signed schema-v1 examples and arena schema-v2");
    }

    private List<ExtensionGrant> loadExtensionGrants() {
        ConfigurationSection extensions = getConfig().getConfigurationSection("integrations.extensions");
        if (extensions == null || !extensions.getBoolean("enabled", false)) return List.of();
        Object configuredProviders = extensions.get("providers");
        if (!(configuredProviders instanceof List<?> providers) || providers.isEmpty() || providers.size() > 32) {
            throw new IllegalArgumentException("integrations.extensions.providers must contain 1..32 trusted providers");
        }
        List<ExtensionGrant> grants = new ArrayList<>();
        Set<String> ids = new HashSet<>();
        for (Object configuredProvider : providers) {
            if (!(configuredProvider instanceof Map<?, ?> provider)) {
                throw new IllegalArgumentException("Each trusted extension provider must be a configuration map");
            }
            Set<String> allowedKeys = Set.of("id", "plugin", "version", "sha256", "capabilities");
            Set<String> keys = provider.keySet().stream().filter(String.class::isInstance)
                    .map(String.class::cast).collect(Collectors.toSet());
            if (keys.size() != provider.size() || !allowedKeys.containsAll(keys) || !keys.containsAll(allowedKeys)) {
                throw new IllegalArgumentException("A trusted extension provider must define only id, plugin, version, sha256 and capabilities");
            }
            String id = providerString(provider, "id");
            Object configuredCapabilities = provider.get("capabilities");
            if (!(configuredCapabilities instanceof List<?> capabilityList) || capabilityList.isEmpty()
                    || capabilityList.size() > 64 || capabilityList.stream().anyMatch(value -> !(value instanceof String))) {
                throw new IllegalArgumentException("Extension capabilities must contain 1..64 strings");
            }
            Set<String> capabilities = capabilityList.stream().map(String.class::cast).collect(Collectors.toSet());
            if (capabilities.size() != capabilityList.size()) {
                throw new IllegalArgumentException("Duplicate capability in extension grant " + id);
            }
            ExtensionGrant grant = new ExtensionGrant(id, providerString(provider, "plugin"),
                    providerString(provider, "version"), providerString(provider, "sha256"), capabilities);
            if (!ids.add(grant.id())) throw new IllegalArgumentException("Duplicate extension id: " + grant.id());
            grants.add(grant);
        }
        return List.copyOf(grants);
    }

    private static String providerString(Map<?, ?> provider, String key) {
        Object value = provider.get(key);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Extension provider field " + key + " must be a non-empty string");
        }
        return text;
    }

    private void registerTrustedExtensions(List<ExtensionGrant> grants, Plugin onlyPlugin) {
        if (grants.isEmpty()) return;
        java.util.Collection<RegisteredServiceProvider<IntegrationExtension>> registrations =
                getServer().getServicesManager().getRegistrations(IntegrationExtension.class);
        if (registrations.size() > 128) {
            getLogger().warning("More than 128 StarGenesis extension services are registered; extension activation refused");
            return;
        }
        for (ExtensionGrant grant : grants) {
            if (activeExtensionOwners.containsKey(grant.id())) continue;
            List<RegisteredExtensionProvider> trustedProviders = new ArrayList<>();
            for (RegisteredServiceProvider<IntegrationExtension> registration : registrations) {
                Plugin providerPlugin = registration.getPlugin();
                if ((onlyPlugin != null && providerPlugin != onlyPlugin) || !providerPlugin.isEnabled()
                        || !grant.providerPlugin().equals(providerPlugin.getName())
                        || !grant.providerVersion().equals(providerPlugin.getPluginMeta().getVersion())) continue;
                try {
                    Path pluginArtifact = codeSourceFile(providerPlugin.getClass());
                    Path providerArtifact = codeSourceFile(registration.getProvider().getClass());
                    if (!Files.isSameFile(pluginArtifact, providerArtifact)
                            || !grant.artifactSha256().equals(artifactSha256(pluginArtifact))) {
                        getLogger().warning("Extension provider failed its pinned artifact check: " + grant.id());
                        continue;
                    }
                    IntegrationExtension extension = registration.getProvider();
                    if (extension.apiVersion() != IntegrationExtension.API_VERSION
                            || !grant.id().equals(extension.integrationId())) continue;
                    trustedProviders.add(new RegisteredExtensionProvider(providerPlugin, extension));
                } catch (IOException | RuntimeException | LinkageError failure) {
                    getLogger().warning("Extension provider rejected: " + grant.id() + " | "
                            + failure.getClass().getSimpleName());
                }
            }
            if (trustedProviders.size() == 1) {
                RegisteredExtensionProvider provider = trustedProviders.getFirst();
                try {
                    IntegrationAdapter adapter = Objects.requireNonNull(provider.extension().adapter(), "extension adapter");
                    capabilityRegistry.registerAdapter(grant.id(), adapter);
                    activeExtensionOwners.put(grant.id(), provider.plugin());
                } catch (RuntimeException | LinkageError failure) {
                    getLogger().warning("Extension adapter initialization failed: " + grant.id() + " | "
                            + failure.getClass().getSimpleName());
                }
            } else if (trustedProviders.size() > 1) {
                getLogger().warning("Multiple trusted services matched extension id; activation refused: " + grant.id());
            }
        }
    }

    private void refreshIntegrationStatuses() {
        if (capabilityRegistry == null) return;
        Map<String, InstalledPlugin> inventory = Arrays.stream(getServer().getPluginManager().getPlugins())
                .map(plugin -> new InstalledPlugin(plugin.getName(), plugin.getPluginMeta().getVersion(), plugin.isEnabled()))
                .collect(Collectors.toUnmodifiableMap(InstalledPlugin::name, Function.identity()));
        statuses = capabilityRegistry.inspect(name -> Optional.ofNullable(inventory.get(name)));
    }

    @EventHandler
    public void onPluginEnable(PluginEnableEvent event) {
        if (capabilityRegistry == null || event.getPlugin() == this) return;
        boolean trustedProvider = extensionGrants.stream()
                .anyMatch(grant -> grant.providerPlugin().equals(event.getPlugin().getName()));
        if (!trustedProvider) return;
        registerTrustedExtensions(extensionGrants, event.getPlugin());
        refreshIntegrationStatuses();
    }

    @EventHandler
    public void onPluginDisable(PluginDisableEvent event) {
        if (capabilityRegistry == null) return;
        List<String> removed = activeExtensionOwners.entrySet().stream()
                .filter(entry -> entry.getValue() == event.getPlugin()).map(Map.Entry::getKey).toList();
        for (String id : removed) {
            activeExtensionOwners.remove(id);
            try {
                capabilityRegistry.unregisterAdapter(id);
            } catch (RuntimeException failure) {
                getLogger().severe("Integration adapter cleanup failed: " + id + " | " + failure.getMessage());
            }
        }
        if (!removed.isEmpty()) refreshIntegrationStatuses();
    }

    private record RegisteredExtensionProvider(Plugin plugin, IntegrationExtension extension) { }

    private static Path codeSourceFile(Class<?> type) throws IOException {
        CodeSource source = type.getProtectionDomain().getCodeSource();
        if (source == null) throw new IOException("Extension code source is unavailable");
        try {
            Path path = Path.of(source.getLocation().toURI()).toAbsolutePath().normalize();
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 128L * 1024 * 1024) {
                throw new IOException("Extension provider must be packaged in a regular JAR no larger than 128 MiB");
            }
            return path;
        } catch (URISyntaxException failure) {
            throw new IOException("Extension code source is not a local file", failure);
        }
    }

    private static String artifactSha256(Path artifact) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
        try (InputStream input = Files.newInputStream(artifact, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[16 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) digest.update(buffer, 0, count);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private boolean execute(CommandSender sender, Command command, String label, String[] args) {
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        String permission = sub.equals("arena") && args.length >= 2
                && ArenaReviewCommands.requiresReviewPermission(args[1])
                ? "stargenesis.arena.review" : "stargenesis." + (sub.equals("debug") ? "debug"
                : sub.equals("status") ? "status" : sub.equals("lab") ? "lab"
                : sub.equals("blueprint") || sub.equals("assemble") ? "blueprint"
                : sub.equals("program") ? "program" : sub.equals("protocol") ? "protocol"
                : sub.equals("ecosystem") ? "ecosystem" : sub.equals("factory") ? "factory"
                : sub.equals("arena") ? "arena" : sub.equals("settlement") ? "settlement.review"
                : sub.equals("design") ? "design.admin" : "help");
        if (!sender.hasPermission(permission)) { sender.sendMessage("StarGenesis: permission denied (" + permission + ")"); return true; }
        if (sub.equals("design")) {
            executeDesignCommand(sender, args);
        } else if (sub.equals("settlement")) {
            settlementJournal.execute(sender, args);
        } else if (sub.equals("arena")) {
            if (ArenaReviewCommands.isQuery(args)) {
                arenaReviewCommands.execute(sender, args);
            } else if (args.length >= 2 && args[1].equalsIgnoreCase("recover")) {
                arenaReviewRecoveryCommands.execute(sender, args);
            } else if (!(sender instanceof Player player)) {
                sender.sendMessage("竞技规则集编辑器仅能由玩家使用。");
            } else if (args.length == 1) {
                arenaRulesetMenu.open(player, false);
            } else if (args.length == 2 && args[1].equalsIgnoreCase("reset")) {
                arenaRulesetMenu.open(player, true);
            } else if (args.length == 2 && args[1].equalsIgnoreCase("publish")) {
                arenaRulesetMenu.publishSaved(player);
            } else if (args.length == 2 && args[1].equalsIgnoreCase("releases")) {
                arenaRulesetMenu.listReleases(player);
            } else if (args.length == 2 && args[1].equalsIgnoreCase("review")) {
                arenaRulesetMenu.listReviews(player);
            } else if (args.length == 3 && args[1].equalsIgnoreCase("approve")) {
                arenaRulesetMenu.approveLegacyReviewRecovery(player, args[2]);
            } else if (args.length == 4 && args[1].equalsIgnoreCase("approve")) {
                arenaRulesetMenu.approveReview(player, args[2], args[3]);
            } else if (args.length >= 5 && args[1].equalsIgnoreCase("reject")) {
                arenaRulesetMenu.rejectReview(player, args[2],
                        String.join(" ", Arrays.copyOfRange(args, 4, args.length)), args[3]);
            } else if (args.length >= 4 && args[1].equalsIgnoreCase("reject")) {
                arenaRulesetMenu.rejectLegacyReviewRecovery(player, args[2],
                        String.join(" ", Arrays.copyOfRange(args, 3, args.length)));
            } else if (args.length == 2 && (args[1].equalsIgnoreCase("play") || args[1].equalsIgnoreCase("create"))) {
                arenaRulesetMenu.startMatch(player);
            } else if (args.length == 4 && args[1].equalsIgnoreCase("play")) {
                arenaRulesetMenu.startMatch(player, args[2], args[3]);
            } else if ((args.length == 2 || args.length == 3) && args[1].equalsIgnoreCase("rooms")) {
                int page = 1;
                if (args.length == 3) {
                    try {
                        if (!args[2].matches("[1-9][0-9]{0,1}")) throw new NumberFormatException();
                        page = Integer.parseInt(args[2]);
                    } catch (NumberFormatException failure) {
                        player.sendMessage(Component.text("房间页码须为 1..13 的整数。"));
                        return true;
                    }
                }
                arenaRulesetMenu.listMatches(player, page);
            } else if (args.length == 4 && args[1].equalsIgnoreCase("join")) {
                UUID roomId = readArenaRoomId(player, args[2]);
                if (roomId != null) arenaRulesetMenu.joinMatch(player, roomId, args[3]);
            } else if (args.length == 3 && args[1].equalsIgnoreCase("leave")) {
                UUID roomId = readArenaRoomId(player, args[2]);
                if (roomId != null) arenaRulesetMenu.leaveMatch(player, roomId);
            } else if (args.length == 3 && args[1].equalsIgnoreCase("ready")) {
                UUID roomId = readArenaRoomId(player, args[2]);
                if (roomId != null) arenaRulesetMenu.startMatch(player, roomId);
            } else if (args.length == 5 && args[1].equalsIgnoreCase("act")) {
                long tick;
                try {
                    if (!args[3].matches("0|[1-9][0-9]{0,4}")) throw new NumberFormatException();
                    tick = Long.parseLong(args[3]);
                } catch (NumberFormatException failure) {
                    player.sendMessage(Component.text("回合号须为 0..72000 的非负整数。"));
                    return true;
                }
                try {
                    UUID roomId = readArenaRoomId(player, args[2]);
                    if (roomId != null) arenaRulesetMenu.actMatch(player, roomId, tick,
                            ArenaMatchService.parseAction(args[4]));
                } catch (IllegalArgumentException failure) {
                    player.sendMessage(Component.text("动作须为 wait、north、east、south 或 west。"));
                }
            } else if (args.length == 3 && args[1].equalsIgnoreCase("match")) {
                UUID roomId = readArenaRoomId(player, args[2]);
                if (roomId != null) arenaRulesetMenu.matchStatus(player, roomId);
            } else if (args.length == 3 && args[1].equalsIgnoreCase("spectate")) {
                UUID roomId = readArenaRoomId(player, args[2]);
                if (roomId != null) arenaRulesetMenu.spectateMatch(player, roomId);
            } else if (args.length == 4 && args[1].equalsIgnoreCase("kick")) {
                try {
                    arenaRulesetMenu.kickMatch(player, parseArenaRoomId(args[2]), UUID.fromString(args[3]));
                } catch (IllegalArgumentException failure) {
                    player.sendMessage(Component.text("请提供有效的竞技房间和玩家 UUID。"));
                }
            } else if (args.length == 3 && args[1].equalsIgnoreCase("stop")) {
                UUID roomId = readArenaRoomId(player, args[2]);
                if (roomId != null) arenaRulesetMenu.stopMatch(player, roomId);
            } else if (args.length >= 2 && args[1].equalsIgnoreCase("map")) {
                arenaRulesetMenu.openMap(player);
            } else if (args.length == 3 && (args[1].equalsIgnoreCase("grant") || args[1].equalsIgnoreCase("revoke"))) {
                try {
                    arenaRulesetMenu.changeEditor(player, java.util.UUID.fromString(args[2]), args[1].equalsIgnoreCase("grant"));
                } catch (IllegalArgumentException failure) {
                    player.sendMessage(Component.text("请提供完整的玩家 UUID。"));
                }
            } else {
                sender.sendMessage("Usage: /genesis arena [reset|map|publish|releases|review|decisions [page]|decision <sha256>|recover <sha256> <reviewer-uuid> <signature>|approve <sha256> <signature>|reject <sha256> <signature> <reason>|play [design-id version]|rooms [page]|join <room> <slot>|ready <room>|act <room> <tick> <action>|match <room>|spectate <room>|leave <room>|kick <room> <uuid>|stop <room>]");
            }
        } else if (sub.equals("factory")) {
            if (args.length == 2 && args[1].equalsIgnoreCase("identity")) {
                if (sender instanceof Player player) {
                    player.sendMessage(Component.text("你的 UUID " + player.getUniqueId()));
                } else {
                    sender.sendMessage("Use /genesis factory identity in-game to view a player UUID.");
                }
            } else if (sender instanceof Player player && args.length == 3
                    && args[1].equalsIgnoreCase("org") && args[2].equalsIgnoreCase("menu")) {
                factoryOrganizationMenu.open(player);
            } else if (sender instanceof Player player && args.length >= 2
                    && args[1].equalsIgnoreCase("remote")) {
                if (args.length < 4) {
                    player.sendMessage(Component.text("用法：/genesis factory remote <server> <工厂命令...>"));
                } else {
                    factoryCommandRelay.execute(player.getUniqueId(), args[2],
                            Arrays.copyOfRange(args, 3, args.length), message -> player.getScheduler().run(this,
                                    task -> player.sendMessage(Component.text(message)), () -> { }));
                }
            } else if (sender instanceof Player player) {
                factoryService.execute(player.getUniqueId(), Arrays.copyOfRange(args, 1, args.length),
                        message -> player.getScheduler().run(this,
                                task -> player.sendMessage(Component.text(message)), () -> { }));
            } else if (sender instanceof ConsoleCommandSender) {
                factoryService.execute(null, Arrays.copyOfRange(args, 1, args.length), sender::sendMessage);
            } else {
                sender.sendMessage("Factory commands are available to players and the server console.");
            }
        } else if (sub.equals("program")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("The program editor is available to players in-game.");
            } else if (args.length == 1) {
                programEditor.open(player, false);
            } else if (args.length == 2 && args[1].equalsIgnoreCase("reset")) {
                programEditor.open(player, true);
            } else if (args.length == 2 && args[1].equalsIgnoreCase("run")) {
                programEditor.runSaved(player);
            } else {
                sender.sendMessage("Usage: /genesis program [reset|run]");
            }
        } else if (sub.equals("protocol")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Protocol chips are available to players in-game.");
            } else {
                protocolMenu.execute(player, args);
            }
        } else if (sub.equals("ecosystem")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("The ecology laboratory is available to players in-game.");
            } else if (args.length == 1) {
                ecosystemMenu.open(player, false);
            } else if (args.length == 2 && args[1].equalsIgnoreCase("reset")) {
                ecosystemMenu.open(player, true);
            } else if (args.length >= 3 && args[1].equalsIgnoreCase("share")) {
                if (args.length == 3 && args[2].equalsIgnoreCase("menu")) {
                    sharedEcologyMenu.open(player);
                } else {
                    sharedEcology.execute(player.getUniqueId(), Arrays.copyOfRange(args, 2, args.length),
                        message -> player.getScheduler().run(this,
                                task -> player.sendMessage(Component.text(message)), () -> { }));
                }
            } else {
                sender.sendMessage("Usage: /genesis ecosystem [reset|share menu|share <list|create|add|remove|leave|disband|status|step> ...]");
            }
        } else if (sub.equals("blueprint")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("The blueprint editor is available to players in-game.");
            } else if (args.length == 1) {
                blueprintEditor.open(player, false);
            } else if (args.length == 2 && args[1].equalsIgnoreCase("reset")) {
                blueprintEditor.open(player, true);
            } else {
                sender.sendMessage("Usage: /genesis blueprint [reset]");
            }
        } else if (sub.equals("assemble")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Blueprint assembly is available to players in-game.");
            } else if (args.length == 1) {
                blueprintAssembly.open(player, false);
            } else if (args.length == 2 && args[1].equalsIgnoreCase("reset")) {
                blueprintAssembly.open(player, true);
            } else {
                sender.sendMessage("Usage: /genesis assemble [reset]");
            }
        } else if (sub.equals("lab")) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("The laboratory is available to players in-game.");
            } else if (args.length == 1) {
                laboratory.open(player, false);
            } else if (args.length == 2 && args[1].equalsIgnoreCase("reset")) {
                laboratory.open(player, true);
            } else if (args.length == 2 && args[1].equalsIgnoreCase("close")) {
                laboratory.close(player);
            } else {
                sender.sendMessage("Usage: /genesis lab [reset|close]");
            }
        } else if (sub.equals("status") && args.length == 1) {
            sender.sendMessage("StarGenesis " + getPluginMeta().getVersion() + " | phase=P1-P10 development | engine=" + SimulationWorld.ENGINE_VERSION);
            sender.sendMessage("Grid=grid/1 | player-checkpoints=persistent-data | real-settlement=disabled | integrations=" + statuses.size());
            sender.sendMessage("P1-P10 tools are under development; P11 remains outstanding.");
        } else if (sub.equals("debug") && args.length == 2 && args[1].equalsIgnoreCase("capabilities")) {
            sender.sendMessage("StarGenesis capabilities (startup snapshot; installed != active):");
            statuses.forEach(s -> sender.sendMessage(s.toString()));
        } else if (sub.equals("debug") && args.length == 2 && args[1].equalsIgnoreCase("budgets")) {
            sender.sendMessage("StarGenesis budgets: stepMillis=" + stepDuration + " " + budget);
        } else {
            sender.sendMessage("StarGenesis - 玩家发明规则，世界检验发明。");
            sender.sendMessage("/genesis status - current foundation and capability state");
            sender.sendMessage("/genesis lab [reset|close] - open or reset the private simulation laboratory");
            sender.sendMessage("/genesis blueprint [reset] - edit, preview and save your private blueprint");
            sender.sendMessage("/genesis assemble [reset] - assemble the saved blueprint in a private virtual workbench");
            sender.sendMessage("/genesis program [reset|run] - edit, compile, save or run your private typed behavior graph");
            sender.sendMessage("/genesis protocol - unlock and equip a public protocol chip");
            sender.sendMessage("/genesis ecosystem [reset] - inspect and advance your private closed ecosystem");
            sender.sendMessage("/genesis ecosystem share menu - open the shared ecology Inventory");
            sender.sendMessage("/genesis ecosystem share <list|create|add|remove|leave|disband|status|step> - manage a shared ecology group");
            sender.sendMessage("/genesis factory - manage a four-member virtual factory, production stations and orders");
            sender.sendMessage("/genesis arena [reset|map|publish|releases|review|decisions|decision|recover|approve|reject|play|rooms|join|ready|act|match|spectate|leave|kick|stop] - edit and play shared arena matches");
            sender.sendMessage("/genesis debug capabilities | /genesis debug budgets - operator diagnostics");
            if (sender.hasPermission("stargenesis.settlement.review"))
                sender.sendMessage("/genesis settlement list [page] | inspect <saga-uuid> - review the settlement journal");
            sender.sendMessage("/genesis help - this help. Laboratory resources never enter real inventory.");
        }
        return true;
    }

    private void executeDesignCommand(CommandSender sender, String[] args) {
        try {
            if (args.length == 2 && args[1].equalsIgnoreCase("keys")) {
                List<DesignSigningTrustFileStore.TrustedKey> keys = designTrust.loadAll();
                sender.sendMessage("Trusted design publishers | count=" + keys.size());
                for (DesignSigningTrustFileStore.TrustedKey key : keys)
                    sender.sendMessage(key.author() + " | Ed25519=" + key.fingerprint());
            } else if (args.length == 4 && args[1].equalsIgnoreCase("trust")) {
                UUID author = parseDesignAuthor(args[2]);
                byte[] publicKey = Base64.getDecoder().decode(args[3]);
                designTrust.trust(author, publicKey);
                sender.sendMessage("Trusted Ed25519 publisher " + author + " | key="
                        + DesignExchange.fingerprint(publicKey));
            } else if (args.length == 3 && args[1].equalsIgnoreCase("revoke")) {
                UUID author = parseDesignAuthor(args[2]);
                sender.sendMessage(designTrust.revoke(author)
                        ? "Revoked design signing trust for " + author : "No trusted design key for " + author);
            } else if (args.length == 3 && args[1].equalsIgnoreCase("reviewers")
                    && args[2].equalsIgnoreCase("list")) {
                List<ArenaReviewTrustFileStore.TrustedReviewer> reviewers = arenaReviewTrust.loadAll();
                sender.sendMessage("Trusted arena reviewers | count=" + reviewers.size());
                for (ArenaReviewTrustFileStore.TrustedReviewer reviewer : reviewers)
                    sender.sendMessage(reviewer.reviewer() + " | Ed25519=" + reviewer.fingerprint());
            } else if (args.length == 5 && args[1].equalsIgnoreCase("reviewers")
                    && args[2].equalsIgnoreCase("trust")) {
                UUID reviewer = parseDesignAuthor(args[3]);
                byte[] publicKey = Base64.getDecoder().decode(args[4]);
                arenaReviewTrust.trust(reviewer, publicKey);
                sender.sendMessage("Trusted arena reviewer " + reviewer + " | key="
                        + DesignExchange.fingerprint(publicKey));
            } else if (args.length == 4 && args[1].equalsIgnoreCase("reviewers")
                    && args[2].equalsIgnoreCase("revoke")) {
                UUID reviewer = parseDesignAuthor(args[3]);
                sender.sendMessage(arenaReviewTrust.revoke(reviewer)
                        ? "Revoked arena reviewer signing trust for " + reviewer
                        : "No trusted arena reviewer key for " + reviewer);
            } else if ((args.length == 2 || args.length == 3) && args[1].equalsIgnoreCase("imports")) {
                int page = 1;
                if (args.length == 3) {
                    if (!args[2].matches("[1-9][0-9]{0,1}"))
                        throw new IllegalArgumentException("Import page must be a positive integer");
                    page = Integer.parseInt(args[2]);
                }
                listImportedDesigns(sender, page);
            } else if (args.length == 3 && args[1].equalsIgnoreCase("inspect")) {
                inspectImportedDesign(sender, args[2]);
            } else if (args.length == 3 && args[1].equalsIgnoreCase("import")) {
                importSignedDesign(sender, args[2]);
            } else {
                sender.sendMessage("Usage: /genesis design keys | trust <author-uuid> <base64-ed25519-key>"
                        + " | revoke <author-uuid> | reviewers list | reviewers trust <reviewer-uuid> <base64-ed25519-key>"
                        + " | reviewers revoke <reviewer-uuid> | import <file-key> | imports [page] | inspect <artifact-sha256>");
            }
        } catch (IOException | IllegalArgumentException | SecurityException | IllegalStateException failure) {
            sender.sendMessage("Design signing operation failed: " + failure.getMessage());
        }
    }

    private void importSignedDesign(CommandSender sender, String fileKey) throws IOException {
        if (fileKey == null || !fileKey.matches("[a-z0-9][a-z0-9._-]{0,63}"))
            throw new IllegalArgumentException("Use a file key with up to 64 lowercase letters, digits, dots, dashes or underscores");
        Path target = signedDesignImports.resolve(fileKey + ".sgdesign").normalize();
        if (!target.getParent().equals(signedDesignImports) || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Place a regular .sgdesign file in the plugin design-imports directory first");
        byte[] encoded;
        try (var input = Files.newInputStream(target, LinkOption.NOFOLLOW_LINKS)) {
            encoded = input.readNBytes(SignedDesignArtifact.MAX_ARTIFACT_BYTES + 1);
        }
        var verified = designExchange.verify(encoded, designTrust);
        if (verified.document() instanceof ArenaRuleset ruleset) {
            arenaRulesetMenu.submitImported(ruleset, verified.keyFingerprint(), sender);
            return;
        }
        var archived = importedDesigns.importSigned(encoded, designTrust);
        sender.sendMessage(Component.text("签名已验证，设计已归档 | kind=" + archived.document().kind()
                + " | artifact-sha256=" + archived.artifactHash()));
    }

    private void listImportedDesigns(CommandSender sender, int page) throws IOException {
        List<SignedDesignArchiveStore.ImportedDesign> imported = importedDesigns.list();
        int pageCount = Math.max(1, (imported.size() + 19) / 20);
        if (page > pageCount) throw new IllegalArgumentException("Import page must be in 1.." + pageCount);
        sender.sendMessage("Signed design archive | records=" + imported.size() + " | page=" + page + "/" + pageCount);
        int start = (page - 1) * 20;
        for (int index = start; index < Math.min(start + 20, imported.size()); index++) {
            var entry = imported.get(index);
            var metadata = entry.document().metadata();
            sender.sendMessage(entry.artifactHash() + " | " + entry.document().kind() + " | "
                    + metadata.id().value() + "@" + metadata.version() + " | author=" + metadata.author());
        }
    }

    private void inspectImportedDesign(CommandSender sender, String artifactHash) throws IOException {
        var imported = importedDesigns.find(artifactHash);
        if (imported.isEmpty()) {
            sender.sendMessage("No signed design matches that artifact hash.");
            return;
        }
        var entry = imported.get();
        var metadata = entry.document().metadata();
        sender.sendMessage("Signed design | artifact-sha256=" + entry.artifactHash());
        sender.sendMessage(entry.document().kind() + " | " + metadata.id().value() + "@" + metadata.version()
                + " | author=" + metadata.author());
        sender.sendMessage("content-sha256=" + metadata.hash() + " | Ed25519=" + entry.keyFingerprint()
                + " | dependencies=" + metadata.dependencies().size());
    }

    private static UUID parseDesignAuthor(String value) {
        UUID author = UUID.fromString(value);
        if (!author.toString().equalsIgnoreCase(value))
            throw new IllegalArgumentException("Supply the complete canonical author UUID");
        return author;
    }

    @Override public void onDisable() {
        statuses = List.of();
        if (protocolNetwork != null) protocolNetwork.stop();
        protocolNetwork = null;
        factoryCommandRelay = null;
        if (capabilityRegistry != null) {
            try {
                capabilityRegistry.close();
            } catch (RuntimeException failure) {
                getLogger().severe("Integration adapter cleanup failed: " + failure.getMessage());
            }
        }
        capabilityRegistry = null;
        activeExtensionOwners.clear();
        extensionGrants = List.of();
        ecosystemMenu = null;
        ecologyRecipeMenu = null;
        sharedEcology = null;
        factoryService = null;
        settlementJournal = null;
        designTrust = null;
        designExchange = null;
        importedDesigns = null;
        signedDesignImports = null;
        if (arenaMatches != null) arenaMatches.closeAll();
        arenaMatches = null;
        arenaRulesetMenu = null;
        getLogger().info("StarGenesis stopped cleanly; no workers, entity projections or settlement jobs remain.");
    }

    private static UUID parseArenaRoomId(String value) {
        try {
            UUID id = UUID.fromString(value);
            if (!id.toString().equalsIgnoreCase(value)) throw new IllegalArgumentException("Non-canonical UUID");
            return id;
        } catch (IllegalArgumentException failure) {
            throw new IllegalArgumentException("请提供完整的竞技房间 UUID。", failure);
        }
    }

    private static UUID readArenaRoomId(Player player, String value) {
        try {
            return parseArenaRoomId(value);
        } catch (IllegalArgumentException failure) {
            player.sendMessage(Component.text(failure.getMessage()));
            return null;
        }
    }
}
