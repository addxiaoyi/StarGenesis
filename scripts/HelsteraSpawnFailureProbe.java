import dev.helstera.api.event.HelsteraEventBus;
import dev.helstera.api.instance.SpawnOptions;
import dev.helstera.api.model.ModelHitbox;
import dev.helstera.api.model.ModelRegistry;
import dev.helstera.core.model.ModelDefinitionImpl;
import dev.helstera.runtime.instance.InstanceManagerImpl;
import dev.helstera.runtime.instance.InstanceRenderer;
import dev.helstera.runtime.instance.ModelInstanceImpl;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Diagnostic against the actual upstream runtime, not a replacement InstanceManager.
 * The renderer injects failure at the renderer boundary. This is not a server entity leak test.
 */
public final class HelsteraSpawnFailureProbe {
    public static void main(String[] args) throws Exception {
        var model = new ModelDefinitionImpl("probe", "probe", "1", "test", 1, null,
                List.of(), new ModelHitbox(1, 1), Path.of("."), "fixture", Map.of(), List.of());
        var registry = (ModelRegistry) Proxy.newProxyInstance(ModelRegistry.class.getClassLoader(),
                new Class<?>[]{ModelRegistry.class}, (proxy, method, values) -> {
                    if (method.getName().equals("get")) return Optional.of(model);
                    throw new AssertionError(method.getName());
                });
        var renderer = new InstanceRenderer() {
            public void createVisuals(ModelInstanceImpl instance, Location location, SpawnOptions options) {
                throw new IllegalStateException("injected-renderer-failure");
            }
            public void updateTransforms(ModelInstanceImpl instance) { }
            public void updateLocation(ModelInstanceImpl instance) { }
            public void updateAttachments(ModelInstanceImpl instance) { }
            public void destroyVisuals(ModelInstanceImpl instance) { }
            public void applyVisibility(ModelInstanceImpl instance, Collection<Player> visible, Collection<Player> hidden) { }
        };
        var manager = new InstanceManagerImpl(registry, new HelsteraEventBus(), renderer, null);
        try {
            manager.spawn("probe", new Location(null, 0, 0, 0), SpawnOptions.defaults());
            throw new AssertionError("Expected injected failure");
        } catch (IllegalStateException expected) {
            if (!"injected-renderer-failure".equals(expected.getMessage())) throw expected;
        }
        int retained = manager.activeCount();
        if (retained != 1) throw new AssertionError("Upstream behavior changed; retained=" + retained);
        boolean valid = manager.allInstances().iterator().next().isValid();
        System.out.println("{\"reproduced\":true,\"retainedInstances\":" + retained
                + ",\"retainedValid\":" + valid + ",\"scope\":\"actual upstream runtime with injected renderer; no server entity claim\"}");
    }
}
