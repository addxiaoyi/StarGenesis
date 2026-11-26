package dev.stargenesis.integrations;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * Bounded, fail-closed discovery. A descriptor has no side effects; only a separately supplied
 * trusted adapter can verify and activate a capability. This module contains no Bukkit imports.
 */
public final class CapabilityRegistry implements AutoCloseable {
    private final Map<String, IntegrationDescriptor> descriptors = new TreeMap<>();
    private final Map<String, IntegrationAdapter> adapters = new LinkedHashMap<>();
    private boolean closed;
    private boolean inspecting;

    public CapabilityRegistry(Collection<IntegrationDescriptor> descriptors) {
        if (descriptors.size() > 128) {
            throw new IllegalArgumentException("At most 128 integration descriptors are permitted");
        }
        for (IntegrationDescriptor descriptor : descriptors) {
            if (this.descriptors.putIfAbsent(descriptor.id(), descriptor) != null) {
                throw new IllegalArgumentException("Duplicate integration id: " + descriptor.id());
            }
        }
    }

    public static CapabilityRegistry auditedDefaults() {
        String starcore = "f769e62799e769a18fe51305b545869a17f80cd1";
        String senluo = "6a432fe54097a3ebf22ba5757e016b602c711f28";
        List<IntegrationDescriptor> descriptors = new ArrayList<>();
        descriptors.add(new IntegrationDescriptor("starcore", "StarCore", Set.of("1.0.0"),
                starcore, Set.of("nation.read", "territory.authorize", "technology.read", "economy.settle")));
        descriptors.add(new IntegrationDescriptor("helstera", "helsteraMobs", Set.of("1.0.0"),
                "a2969dfaf83cd35e642147f4bd7743ae04d34e7a", Set.of("model.inspect", "entity.project", "behavior.register", "event.subscribe")));
        // Declared dependency version in audited SenLuo source, not a CraftEngine runtime test.
        descriptors.add(new IntegrationDescriptor("craftengine", "CraftEngine", Set.of("26.9.1"),
                senluo, Set.of("items.resolve", "items.create")));
        descriptors.add(new IntegrationDescriptor("senluo.cookery", "KaleidoscopeCookeryPlugin",
                Set.of("1.3.0"), senluo, Set.of("items.resolve", "cookery.read")));
        descriptors.add(new IntegrationDescriptor("senluo.doll", "KaleidoscopeDollPlugin",
                Set.of("0.1.0-dev"), senluo, Set.of("items.resolve")));
        descriptors.add(new IntegrationDescriptor("senluo.tavern", "KaleidoscopeTavernPlugin",
                Set.of("0.1.0-dev"), senluo, Set.of("items.resolve")));
        descriptors.add(new IntegrationDescriptor("senluo.deco", "KaleidoscopeDecoPlugin",
                Set.of("0.1.0-dev"), senluo, Set.of("items.resolve")));
        descriptors.add(new IntegrationDescriptor("senluo.music", "KaleidoscopeMusicPlugin",
                Set.of("0.1.0-dev"), senluo, Set.of("items.resolve")));
        return new CapabilityRegistry(descriptors);
    }

    /** Called only by trusted plugin bootstrap, never by a player-supplied document. */
    public void registerAdapter(String id, IntegrationAdapter adapter) {
        requireOpen();
        if (inspecting) throw new IllegalStateException("Cannot mutate adapters during inspection");
        if (!descriptors.containsKey(id)) {
            throw new IllegalArgumentException("Unknown integration: " + id);
        }
        if (adapters.putIfAbsent(id, Objects.requireNonNull(adapter, "adapter")) != null) {
            throw new IllegalStateException("Adapter already registered: " + id);
        }
    }

    public List<IntegrationStatus> inspect(PluginInventory inventory) {
        requireOpen();
        Objects.requireNonNull(inventory, "inventory");
        if (inspecting) throw new IllegalStateException("Reentrant integration inspection is forbidden");
        inspecting = true;
        try {
            List<IntegrationStatus> result = new ArrayList<>();
            for (IntegrationDescriptor descriptor : descriptors.values()) {
                result.add(inspectOne(descriptor, inventory));
            }
            return List.copyOf(result);
        } finally {
            inspecting = false;
        }
    }

    private IntegrationStatus inspectOne(IntegrationDescriptor descriptor, PluginInventory inventory) {
        Optional<InstalledPlugin> plugin;
        try {
            plugin = Objects.requireNonNull(inventory.find(descriptor.pluginName()), "inventory result");
        } catch (RuntimeException | LinkageError failure) {
            return status(descriptor, null, false, null, "INVENTORY_FAILED:" + failure.getClass().getSimpleName());
        }
        if (plugin.isEmpty()) {
            return status(descriptor, null, false, null, "NOT_INSTALLED");
        }
        InstalledPlugin installed = plugin.get();
        if (!descriptor.pluginName().equals(installed.name())) {
            return status(descriptor, null, false, null, "INVENTORY_NAME_MISMATCH");
        }
        boolean version = descriptor.auditedVersions().contains(installed.version());
        if (!installed.enabled()) {
            return status(descriptor, installed, version, null, "PLUGIN_DISABLED");
        }
        if (!version) {
            return status(descriptor, installed, false, null, "VERSION_NOT_AUDITED");
        }
        IntegrationAdapter adapter = adapters.get(descriptor.id());
        if (adapter == null) {
            return status(descriptor, installed, true, null, "ADAPTER_NOT_VERIFIED");
        }
        try {
            AdapterVerification verification = Objects.requireNonNull(adapter.verify(installed), "verification");
            if (!descriptor.capabilities().containsAll(verification.activeCapabilities())) {
                return status(descriptor, installed, true, null, "UNDECLARED_CAPABILITY_REJECTED");
            }
            return status(descriptor, installed, true, verification,
                    verification.activeCapabilities().isEmpty() ? "NO_ACTIVE_CAPABILITIES" : "ACTIVE");
        } catch (RuntimeException | LinkageError failure) {
            return status(descriptor, installed, true, null, "ADAPTER_FAILED:" + failure.getClass().getSimpleName());
        }
    }

    private static IntegrationStatus status(IntegrationDescriptor descriptor, InstalledPlugin plugin,
                                             boolean version, AdapterVerification verification, String reason) {
        Map<String, Boolean> capabilities = new TreeMap<>();
        for (String capability : descriptor.capabilities()) {
            capabilities.put(capability, verification != null && verification.activeCapabilities().contains(capability));
        }
        return new IntegrationStatus(descriptor.id(), descriptor.pluginName(), plugin != null,
                plugin != null && plugin.enabled(), plugin == null ? "" : plugin.version(), version,
                verification != null && verification.apiVerified(),
                verification != null && verification.paperVerified(), capabilities, reason,
                descriptor.sourceRevision(), verification == null ? "" : verification.evidenceId());
    }

    private void requireOpen() {
        if (closed) throw new IllegalStateException("Capability registry is closed");
    }

    /** Close every trusted adapter even if another provider fails during cleanup. */
    @Override
    public void close() {
        if (closed) return;
        if (inspecting) throw new IllegalStateException("Cannot close registry during inspection");
        closed = true;
        RuntimeException combined = null;
        for (IntegrationAdapter adapter : adapters.values()) {
            try {
                adapter.close();
            } catch (RuntimeException | LinkageError failure) {
                if (combined == null) combined = new IllegalStateException("Integration cleanup failed");
                combined.addSuppressed(failure);
            }
        }
        adapters.clear();
        if (combined != null) throw combined;
    }
}
