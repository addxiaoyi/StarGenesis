package dev.stargenesis.helstera;

import dev.helstera.api.Helstera;
import dev.helstera.api.model.ModelRegistry;
import dev.stargenesis.integrations.AdapterVerification;
import dev.stargenesis.integrations.InstalledPlugin;
import dev.stargenesis.integrations.ModelCatalog;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Uses the compiled upstream API; retains no engine, registry, model or listener across calls. */
public final class HelsteraModelCatalog implements ModelCatalog {
    public static final String SOURCE_REVISION = "9298292538dc9d7c5ee800ff7e03f4c10e34ba97";
    private final BooleanSupplier primaryThread;
    private final BooleanSupplier supportedServer;
    private final Supplier<InstalledPlugin> inventory;
    private final Supplier<Optional<Helstera>> api;
    private boolean closed;

    public HelsteraModelCatalog(BooleanSupplier primaryThread, BooleanSupplier supportedServer,
                               Supplier<InstalledPlugin> inventory, Supplier<Optional<Helstera>> api) {
        this.primaryThread = Objects.requireNonNull(primaryThread);
        this.supportedServer = Objects.requireNonNull(supportedServer);
        this.inventory = Objects.requireNonNull(inventory);
        this.api = Objects.requireNonNull(api);
    }

    @Override public String integrationId() { return "helstera"; }

    @Override public synchronized AdapterVerification verify(InstalledPlugin plugin) {
        requireProvider(plugin);
        registry();
        return new AdapterVerification(true, true, Set.of("model.inspect"),
                "helstera-model-catalog/" + SOURCE_REVISION);
    }

    @Override public synchronized int loadedModels() {
        int count = registry().count();
        if (count < 0) throw new IllegalStateException("Invalid model count");
        return count;
    }

    @Override public synchronized Optional<ModelInfo> inspect(String modelId) {
        ModelCatalog.requireId(modelId);
        return registry().get(modelId).map(model -> {
            if (!modelId.equals(model.id())) throw new IllegalStateException("Model identity mismatch");
            var hitbox = Objects.requireNonNull(model.hitbox(), "hitbox");
            return new ModelInfo(model.id(), model.scale(), model.allBones().size(),
                    hitbox.width(), hitbox.height(), model.animationNames());
        });
    }

    private ModelRegistry registry() {
        if (closed) throw new IllegalStateException("Model bridge is closed");
        if (!primaryThread.getAsBoolean()) throw new IllegalStateException("Model bridge requires the Paper main thread");
        if (!supportedServer.getAsBoolean()) throw new IllegalStateException("Model bridge requires verified Paper 1.21.11");
        requireProvider(inventory.get());
        Helstera root = api.get().orElseThrow(() -> new IllegalStateException("Helstera API is not ready"));
        if (!"1.0.0".equals(root.apiVersion())) throw new IllegalStateException("Helstera API version is not audited");
        return Objects.requireNonNull(root.getModelRegistry(), "Helstera model registry is not ready");
    }

    private static void requireProvider(InstalledPlugin plugin) {
        if (plugin == null || !plugin.enabled() || !"helsteraMobs".equals(plugin.name())
                || !"1.0.0".equals(plugin.version()))
            throw new IllegalStateException("Helstera provider is missing, disabled or not audited");
    }

    /** No provider calls during close: the provider may already have unregistered its API. */
    @Override public synchronized void close() { closed = true; }
}
