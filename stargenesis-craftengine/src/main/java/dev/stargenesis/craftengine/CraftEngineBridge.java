package dev.stargenesis.craftengine;

import dev.stargenesis.integrations.AdapterVerification;
import dev.stargenesis.integrations.InstalledPlugin;
import dev.stargenesis.integrations.ItemCatalog;
import dev.stargenesis.integrations.ItemPayload;
import dev.stargenesis.paper.integration.ItemStackBridge;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/** Paper-only registry and detached item access under the provider's actual resource lease. */
public final class CraftEngineBridge implements ItemStackBridge {
    public static final String SOURCE_REVISION = "sha256:62fbfe822ced5c588a21cf81336b21ff77a8a71f5d0916ac61bca8ccd2c1708d";
    private final BooleanSupplier primaryThread, supportedServer;
    private final Supplier<InstalledPlugin> inventory;
    private final Supplier<CraftEngineAccess> provider;
    private final IntSupplier dataVersion;
    private boolean closed, reading;

    CraftEngineBridge(BooleanSupplier primaryThread, BooleanSupplier supportedServer,
                      Supplier<InstalledPlugin> inventory, Supplier<CraftEngineAccess> provider, IntSupplier dataVersion) {
        this.primaryThread = Objects.requireNonNull(primaryThread);
        this.supportedServer = Objects.requireNonNull(supportedServer);
        this.inventory = Objects.requireNonNull(inventory);
        this.provider = Objects.requireNonNull(provider);
        this.dataVersion = Objects.requireNonNull(dataVersion);
    }

    private void thread() {
        if (!primaryThread.getAsBoolean()) throw new IllegalStateException("Item bridge requires the Paper main thread");
    }
    private static void requireProvider(InstalledPlugin installed) {
        if (installed == null || !installed.enabled() || !installed.name().equals("CraftEngine")
                || !installed.version().equals("26.9.1"))
            throw new IllegalStateException("CraftEngine is missing, disabled or not audited");
    }
    private CraftEngineAccess current() {
        if (closed) throw new IllegalStateException("Item bridge is closed");
        if (!supportedServer.getAsBoolean()) throw new IllegalStateException("Item bridge requires verified Paper 1.21.11");
        requireProvider(inventory.get());
        var access = Objects.requireNonNull(provider.get(), "CraftEngine API is unavailable");
        if (!access.ready()) throw new IllegalStateException("CraftEngine has not finished loading or is stopping/reloading");
        return access;
    }
    private synchronized <T> T read(Function<CraftEngineAccess, T> action) {
        thread();
        if (reading) throw new IllegalStateException("Reentrant item bridge access is forbidden");
        reading = true;
        try {
            var access = current();
            try (var lease = access.resources().acquire()) {
                // The real lease excludes asynchronous reloads and resource-pack workflows.
                if (!access.ready()) throw new IllegalStateException("CraftEngine changed during admission");
                T result = action.apply(access);
                if (current().identity() != access.identity()) throw new IllegalStateException("CraftEngine provider changed");
                return result;
            }
        } finally { reading = false; }
    }

    @Override public String integrationId() { return "craftengine"; }
    @Override public AdapterVerification verify(InstalledPlugin installed) {
        requireProvider(installed);
        return read(access -> {
            if (access.count() < 0) throw new IllegalStateException("Invalid item count");
            return new AdapterVerification(true, true, Set.of("items.resolve", "items.create"), SOURCE_REVISION);
        });
    }
    @Override public int loadedItems() {
        return read(access -> {
            int count = access.count();
            if (count < 0) throw new IllegalStateException("Invalid item count");
            return count;
        });
    }
    @Override public Optional<ItemCatalog.Definition> inspect(String id) {
        ItemCatalog.requireId(id);
        return read(access -> definition(access, id));
    }
    private static Optional<ItemCatalog.Definition> definition(CraftEngineAccess access, String id) {
        var result = Objects.requireNonNull(access.inspect(id), "Missing item lookup result");
        if (result.isPresent() && !result.orElseThrow().id().equals(id)) throw new IllegalStateException("Item identity mismatch");
        return result;
    }

    @Override public ItemStack create(String id, int amount) {
        ItemCatalog.requireId(id);
        if (amount < 1 || amount > 64) throw new IllegalArgumentException("Item amount must be 1..64");
        return read(access -> {
            definition(access, id).orElseThrow(() -> new IllegalArgumentException("Item is not loaded"));
            ItemStack item = Objects.requireNonNull(access.create(id), "Provider returned no item").clone();
            if (item.isEmpty() || amount > item.getMaxStackSize()) throw new IllegalArgumentException("Item exceeds its actual stack limit");
            item.setAmount(amount);
            var payload = capture(access, item);
            if (!payload.itemId().equals(id)) throw new IllegalStateException("Created item identity mismatch");
            return item;
        });
    }
    @Override public ItemPayload capture(ItemStack source) {
        Objects.requireNonNull(source, "stack");
        return read(access -> capture(access, source.clone()));
    }
    private ItemPayload capture(CraftEngineAccess access, ItemStack item) {
        if (item.isEmpty() || item.getAmount() < 1 || item.getAmount() > Math.min(64, item.getMaxStackSize()))
            throw new IllegalArgumentException("An actual bounded nonempty item stack is required");
        String custom = access.customId(item);
        String id = custom == null ? item.getType().getKey().toString() : ItemCatalog.requireId(custom);
        if (custom != null) definition(access, id).orElseThrow(() -> new IllegalArgumentException("Custom item is no longer loaded"));
        var payload = new ItemPayload(id, custom != null, item.getAmount(), item.serializeAsBytes());
        if (payload.dataVersion() != dataVersion.getAsInt()) throw new IllegalStateException("Item data version mismatch");
        return payload;
    }
    @Override public ItemStack restore(ItemPayload payload) {
        Objects.requireNonNull(payload, "payload");
        return read(access -> {
            if (payload.dataVersion() != dataVersion.getAsInt())
                throw new IllegalArgumentException("Item data migration requires separate verification");
            if (payload.custom()) definition(access, payload.itemId()).orElseThrow(() -> new IllegalArgumentException("Custom item is no longer loaded"));
            byte[] original = payload.data(); // Constructor checked expansion, depth, node and allocation lengths.
            ItemStack restored = ItemStack.deserializeBytes(original);
            var roundTrip = capture(access, restored);
            if (!payload.sameStateAs(roundTrip))
                throw new IllegalArgumentException("Item state changed during decoding; exact restoration refused");
            return restored;
        });
    }
    @Override public synchronized void close() { thread(); closed = true; }
}
