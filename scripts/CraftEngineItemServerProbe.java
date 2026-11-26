import com.google.gson.GsonBuilder;
import dev.stargenesis.integrations.InstalledPlugin;
import dev.stargenesis.integrations.ItemCatalog;
import dev.stargenesis.integrations.ItemPayload;
import dev.stargenesis.paper.integration.ItemStackBridge;
import net.kyori.adventure.text.Component;
import net.momirealms.craftengine.bukkit.api.CraftEngineItems;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.core.plugin.CraftEngine;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;

/** Real provider registry/factory and Paper serialization. No inventory delivery or visual-client claim. */
public final class CraftEngineItemServerProbe extends JavaPlugin {
    private static final String ID = "stargenesis:item_probe";
    private static final byte[] STATE = new byte[]{0, 1, 2, -1, 42};
    private final List<String> checks = new ArrayList<>();
    private BukkitTask task;
    private int phase, attempts;
    private ItemStackBridge bridge;
    private BukkitCraftEngine engine;
    private ItemPayload saved;
    private ItemStack orphan;
    private Path definitionPath;
    private String definition;
    private boolean resumed;
    private CompletableFuture<CraftEngine.ReloadResult> reload;

    @Override public void onEnable() { task = Bukkit.getScheduler().runTaskTimer(this, this::advance, 20, 2); }
    private void require(boolean value, String check) {
        if (!value) throw new AssertionError(check);
        checks.add(check);
    }
    private void rejected(Runnable call, Class<? extends RuntimeException> type, String check) {
        try { call.run(); }
        catch (RuntimeException expected) { require(type.isInstance(expected), check + " exception=" + expected.getClass().getSimpleName()); return; }
        throw new AssertionError(check + " was accepted");
    }
    private void verifyState(ItemStack item) {
        require(item.getAmount() == 3, "count preserved");
        var meta = item.getItemMeta();
        require(Component.text("Exact item snapshot").equals(meta.displayName()), "custom name preserved");
        require(List.of(Component.text("line one"), Component.text("第二行")).equals(meta.lore()), "component lore preserved");
        require(Arrays.equals(STATE, meta.getPersistentDataContainer().get(new NamespacedKey(this, "state"), PersistentDataType.BYTE_ARRAY)), "PDC bytes preserved");
        var nested = meta.getPersistentDataContainer().get(new NamespacedKey(this, "nested"), PersistentDataType.TAG_CONTAINER);
        require(nested != null && "stable-test-owner".equals(nested.get(new NamespacedKey(this, "owner"), PersistentDataType.STRING)), "nested PDC preserved");
        require(ID.equals(CraftEngineItems.getCustomItemId(item).toString()), "actual CraftEngine item ID preserved");
    }
    private void beginReload() {
        reload = engine.reloadPlugin(command -> Bukkit.getScheduler().runTaskAsynchronously(this, command),
                command -> Bukkit.getScheduler().runTask(this, command), false, true);
        rejected(bridge::loadedItems, IllegalStateException.class, "actual asynchronous reload rejects bridge access");
    }
    private byte[] differentVersion(byte[] data) throws Exception {
        byte[] expanded;
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(data))) { expanded = gzip.readAllBytes(); }
        byte[] field = new byte[]{3,0,11,'D','a','t','a','V','e','r','s','i','o','n'};
        boolean found = false;
        for (int i = 0; i <= expanded.length - field.length - 4; i++) {
            if (Arrays.equals(Arrays.copyOfRange(expanded, i, i + field.length), field)) {
                ByteBuffer.wrap(expanded, i + field.length, 4).putInt(saved.dataVersion() - 1); found = true; break;
            }
        }
        if (!found) throw new AssertionError("Missing native DataVersion field");
        var output = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(output)) { gzip.write(expanded); }
        return output.toByteArray();
    }
    private byte[] unknownField(byte[] data) throws Exception {
        byte[] expanded;
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(data))) { expanded = gzip.readAllBytes(); }
        var output = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(output); var out = new DataOutputStream(gzip)) {
            out.write(expanded, 0, expanded.length - 1);
            out.writeByte(3); out.writeUTF("stargenesis:unrecognized"); out.writeInt(42); out.writeByte(0);
        }
        return output.toByteArray();
    }
    private void advance() {
        try {
            if (++attempts > 1200) throw new AssertionError("CraftEngine item probe timeout");
            if (phase == 0) {
                engine = BukkitCraftEngine.instance();
                if (engine == null || !engine.isFullyLoaded() || engine.resourceOperations().isBusy()) return;
                bridge = Bukkit.getServicesManager().load(ItemStackBridge.class);
                require(bridge != null && Bukkit.getServicesManager().load(ItemCatalog.class) == bridge, "both services registered with one provider");
                require(bridge.verify(new InstalledPlugin("CraftEngine", "26.9.1", true)).activeCapabilities()
                        .equals(java.util.Set.of("items.resolve", "items.create")), "actual query and detached factory capabilities available");
                require(bridge.inspect(ID).orElseThrow().material().equals("minecraft:paper"), "real item definition parsed");
                require(bridge.inspect("stargenesis:missing").isEmpty(), "missing complete ID is not guessed");
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "genesis item status");
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "genesis item inspect " + ID);
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "genesis debug capabilities");
                definitionPath = getDataFolder().toPath().getParent().resolve("CraftEngine/resources/sg_item_probe/configuration/items.yml");
                definition = Files.readString(definitionPath);
                int before = Bukkit.getWorlds().getFirst().getEntities().size();
                var item = bridge.create(ID, 3);
                require(item.getMaxStackSize() == 4, "provider component stack limit applied");
                rejected(() -> bridge.create(ID, 5), IllegalArgumentException.class, "actual stack limit rejects overfull creation");
                var meta = item.getItemMeta();
                meta.displayName(Component.text("Exact item snapshot"));
                meta.lore(List.of(Component.text("line one"), Component.text("第二行")));
                meta.getPersistentDataContainer().set(new NamespacedKey(this, "state"), PersistentDataType.BYTE_ARRAY, STATE);
                var nested = meta.getPersistentDataContainer().getAdapterContext().newPersistentDataContainer();
                nested.set(new NamespacedKey(this, "owner"), PersistentDataType.STRING, "stable-test-owner");
                meta.getPersistentDataContainer().set(new NamespacedKey(this, "nested"), PersistentDataType.TAG_CONTAINER, nested);
                item.setItemMeta(meta);
                var original = item.clone();
                var captured = bridge.capture(item);
                Files.createDirectories(getDataFolder().toPath());
                Files.write(getDataFolder().toPath().resolve("captured.bin"), captured.data());
                Files.write(getDataFolder().toPath().resolve("decoded.bin"), ItemStack.deserializeBytes(captured.data()).serializeAsBytes());
                item.setAmount(1);
                var restored = bridge.restore(captured);
                require(restored.isSimilar(original) && restored.getAmount() == original.getAmount(), "native full-stack similarity after exact restoration");
                verifyState(restored);
                require(captured.sameStateAs(bridge.capture(restored)), "complete typed Paper NBT state preserved");
                var vanilla = new ItemStack(Material.DIAMOND, 2);
                var vanillaPayload = bridge.capture(vanilla);
                require(!vanillaPayload.custom() && bridge.restore(vanillaPayload).isSimilar(vanilla), "vanilla state also round-trips");
                require(Bukkit.getWorlds().getFirst().getEntities().size() == before, "detached operations create no world entities");
                Path snapshot = getDataFolder().toPath().resolve("snapshot.bin");
                Files.createDirectories(snapshot.getParent());
                resumed = Files.exists(snapshot);
                if (!resumed) Files.write(snapshot, captured.data());
                saved = new ItemPayload(ID, true, 3, Files.readAllBytes(snapshot));
                verifyState(bridge.restore(saved));
                require(saved.sameStateAs(bridge.capture(bridge.restore(saved))), resumed ? "previous-process snapshot restored with exact NBT state" : "persisted snapshot restored with exact NBT state");
                orphan = bridge.restore(saved);
                var otherVersion = new ItemPayload(ID, true, 3, differentVersion(saved.data()));
                rejected(() -> bridge.restore(otherVersion), IllegalArgumentException.class, "data migration is not silently applied");
                var unknown = new ItemPayload(ID, true, 3, unknownField(saved.data()));
                rejected(() -> bridge.restore(unknown), IllegalArgumentException.class, "native decoder cannot silently drop unknown fields");
                try (var lease = engine.resourceOperations().acquire()) {
                    rejected(() -> bridge.inspect(ID), IllegalStateException.class, "actual provider resource lease excludes inspection");
                    rejected(() -> bridge.create(ID, 1), IllegalStateException.class, "actual provider resource lease excludes creation");
                }
                Files.writeString(definitionPath, "items: {}\n");
                beginReload(); phase++;
            } else if (phase == 1) {
                if (!reload.isDone()) return;
                require(reload.join().success(), "actual removal reload completed");
                require(bridge.inspect(ID).isEmpty(), "removed definition is not cached");
                require(ID.equals(CraftEngineItems.getCustomItemId(orphan).toString()), "actual custom ID survives definition removal");
                rejected(() -> bridge.capture(orphan), IllegalArgumentException.class, "removed custom definition cannot be captured as vanilla");
                rejected(() -> bridge.restore(saved), IllegalArgumentException.class, "removed custom item snapshot cannot be restored");
                Files.writeString(definitionPath, definition.replace("Template initial", "Template updated"));
                beginReload(); phase++;
            } else if (phase == 2) {
                if (!reload.isDone()) return;
                require(reload.join().success() && bridge.inspect(ID).isPresent(), "actual replacement definition reloaded");
                verifyState(bridge.restore(saved));
                require(saved.sameStateAs(bridge.capture(bridge.restore(saved))), "existing stack remains exact after template replacement");
                require(!engine.resourceOperations().isBusy(), "all bridge leases released");
                getLogger().info("CRAFTENGINE_INTENTIONAL_DISABLE_BEGIN");
                Bukkit.getPluginManager().disablePlugin(Bukkit.getPluginManager().getPlugin("CraftEngine"));
                getLogger().info("CRAFTENGINE_INTENTIONAL_DISABLE_END");
                require(Bukkit.getServicesManager().load(ItemCatalog.class) == null && Bukkit.getServicesManager().load(ItemStackBridge.class) == null, "provider disable unregisters both services");
                rejected(() -> bridge.inspect(ID), IllegalStateException.class, "retained service refuses access after disable");
                require(Bukkit.getPluginManager().getPlugin("StarGenesis").isEnabled(), "base plugin remains available");
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "genesis debug capabilities");
                var report = Map.of("passed", true, "checks", checks, "restoredFromPreviousProcess", resumed,
                        "payloadSha256", saved.sha256(), "payloadBytes", saved.size(), "dataVersion", saved.dataVersion(),
                        "scope", "Actual CraftEngine registry/factory and Paper item bytes, reload and process restart; no player inventory transfer or visual acceptance");
                int run = Integer.getInteger("stargenesis.probe.run", 1);
                Files.writeString(getDataFolder().toPath().resolve("result-" + run + ".json"), new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n");
                task.cancel(); getLogger().info("CRAFTENGINE_ITEM_PROBE_PASSED");
            }
        } catch (Throwable failed) {
            task.cancel(); getLogger().log(java.util.logging.Level.SEVERE, "CRAFTENGINE_ITEM_PROBE_FAILED", failed);
        }
    }
}
