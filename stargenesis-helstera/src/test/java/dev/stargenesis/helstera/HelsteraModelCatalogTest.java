package dev.stargenesis.helstera;

import dev.helstera.api.Helstera;
import dev.helstera.api.model.ModelDefinition;
import dev.helstera.api.model.ModelHitbox;
import dev.helstera.api.model.ModelRegistry;
import dev.stargenesis.integrations.CapabilityRegistry;
import dev.stargenesis.integrations.InstalledPlugin;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Test doubles implement the real compiled upstream API. These are not server acceptance tests. */
class HelsteraModelCatalogTest {
    private static final InstalledPlugin ENABLED = new InstalledPlugin("helsteraMobs", "1.0.0", true);
    private final Map<String, ModelDefinition> models = new HashMap<>();
    private boolean mainThread = true, paper = true;
    private InstalledPlugin provider = ENABLED;
    private String apiVersion = "1.0.0";
    private int reads, mutations;
    private ModelRegistry registry = proxy(ModelRegistry.class, (method, args) -> switch (method) {
        case "get" -> { reads++; yield Optional.ofNullable(models.get(args[0])); }
        case "count" -> { reads++; yield models.size(); }
        default -> { mutations++; throw new AssertionError("Unexpected provider operation: " + method); }
    });
    private Helstera root = root();
    private final HelsteraModelCatalog catalog = new HelsteraModelCatalog(() -> mainThread, () -> paper,
            () -> provider, () -> Optional.ofNullable(root));

    @Test void readyApiWithNoModelsEnablesOnlyInspection() {
        var verification = catalog.verify(ENABLED);
        assertTrue(verification.apiVerified());
        assertTrue(verification.paperVerified());
        assertEquals(Set.of("model.inspect"), verification.activeCapabilities());
        assertEquals(0, catalog.loadedModels());
        assertTrue(catalog.inspect("example/cube").isEmpty());
        assertEquals(0, mutations);
    }

    @Test void asyncLoadUnloadAndReplacementAreReadFresh() {
        assertTrue(catalog.inspect("example/cube").isEmpty());
        models.put("example/cube", model("example/cube", 1, List.of("idle")));
        assertEquals(1, catalog.loadedModels());
        assertEquals(1, catalog.inspect("example/cube").orElseThrow().scale());
        models.put("example/cube", model("example/cube", 2, List.of("walk")));
        assertEquals(List.of("walk"), catalog.inspect("example/cube").orElseThrow().animations());
        models.clear();
        assertTrue(catalog.inspect("example/cube").isEmpty());
        assertEquals(0, mutations);
    }

    @Test void resultsDoNotRetainMutableProviderLists() {
        var animations = new ArrayList<>(List.of("idle"));
        models.put("cube", model("cube", 1, animations));
        var result = catalog.inspect("cube").orElseThrow();
        animations.clear();
        assertEquals(List.of("idle"), result.animations());
        assertThrows(UnsupportedOperationException.class, () -> result.animations().add("walk"));
    }

    @Test void unavailableOrWrongApiCannotBeUsed() {
        root = null;
        assertThrows(IllegalStateException.class, catalog::loadedModels);
        root = root();
        apiVersion = "2.0.0";
        assertThrows(IllegalStateException.class, () -> catalog.inspect("cube"));
        apiVersion = "1.0.0";
        registry = null;
        assertThrows(NullPointerException.class, catalog::loadedModels);
        assertEquals(0, reads);
    }

    @Test void providerDisableOrVersionMismatchStopsQueriesImmediately() {
        catalog.loadedModels();
        for (var invalid : List.of(new InstalledPlugin("helsteraMobs", "1.0.0", false),
                new InstalledPlugin("helsteraMobs", "1.1.0", true), new InstalledPlugin("other", "1.0.0", true))) {
            provider = invalid;
            assertThrows(IllegalStateException.class, catalog::loadedModels);
            assertThrows(IllegalStateException.class, () -> catalog.verify(invalid));
        }
        provider = null;
        assertThrows(IllegalStateException.class, () -> catalog.inspect("cube"));
        assertEquals(1, reads);
    }

    @Test void rootAndRegistryAreNeverReusedAfterProviderRestart() {
        catalog.loadedModels();
        registry = proxy(ModelRegistry.class, (name, args) -> {
            if (name.equals("count")) return 7;
            throw new AssertionError(name);
        });
        root = root();
        assertEquals(7, catalog.loadedModels());
    }

    @Test void closeIsIdempotentAndNeverCallsStoppedProvider() {
        root = null;
        catalog.close();
        catalog.close();
        root = root();
        assertThrows(IllegalStateException.class, catalog::loadedModels);
        assertThrows(IllegalStateException.class, () -> catalog.inspect("cube"));
        assertThrows(IllegalStateException.class, () -> catalog.verify(ENABLED));
        assertEquals(0, reads);
    }

    @Test void wrongThreadAndFoliaNeverTouchProvider() {
        mainThread = false;
        assertThrows(IllegalStateException.class, catalog::loadedModels);
        mainThread = true;
        paper = false;
        assertThrows(IllegalStateException.class, () -> catalog.verify(ENABLED));
        assertThrows(IllegalStateException.class, () -> catalog.inspect("cube"));
        assertEquals(0, reads);
    }

    @Test void illegalIdsAreRejectedBeforeLookup() {
        for (String id : List.of("../cube", "Example/Cube", "a//b", "a\\b", "x".repeat(129), "cube\n"))
            assertThrows(IllegalArgumentException.class, () -> catalog.inspect(id));
        assertEquals(0, reads);
    }

    @Test void mismatchedAndMalformedModelsAreRejected() {
        models.put("cube", model("other", 1, List.of()));
        assertThrows(IllegalStateException.class, () -> catalog.inspect("cube"));
        models.put("cube", model("cube", Double.NaN, List.of()));
        assertThrows(IllegalArgumentException.class, () -> catalog.inspect("cube"));
        models.put("cube", model("cube", 1, java.util.Collections.nCopies(257, "idle")));
        assertThrows(IllegalArgumentException.class, () -> catalog.inspect("cube"));
    }

    @Test void capabilityDiscoveryIsolatesProviderErrorsAndNeverActivatesProjection() {
        try (var capabilities = CapabilityRegistry.auditedDefaults()) {
            capabilities.registerAdapter("helstera", catalog::verify);
            var status = capabilities.inspect(name -> name.equals("helsteraMobs") ? Optional.of(ENABLED) : Optional.empty())
                    .stream().filter(s -> s.id().equals("helstera")).findFirst().orElseThrow();
            assertTrue(status.capabilities().get("model.inspect"));
            assertFalse(status.capabilities().get("entity.project"));
            root = null;
            var stopped = capabilities.inspect(name -> name.equals("helsteraMobs") ? Optional.of(ENABLED) : Optional.empty())
                    .stream().filter(s -> s.id().equals("helstera")).findFirst().orElseThrow();
            assertFalse(stopped.active());
        }
        root = root();
        assertEquals(0, catalog.loadedModels()); // Registry inspection does not own the service.
    }

    private Helstera root() {
        return proxy(Helstera.class, (method, args) -> switch (method) {
            case "apiVersion" -> apiVersion;
            case "getModelRegistry" -> registry;
            default -> throw new AssertionError("Unexpected root operation: " + method);
        });
    }

    private static ModelDefinition model(String id, double scale, List<String> animations) {
        return proxy(ModelDefinition.class, (method, args) -> switch (method) {
            case "id" -> id;
            case "scale" -> scale;
            case "allBones" -> List.of();
            case "hitbox" -> new ModelHitbox(1, 2);
            case "animationNames" -> animations;
            default -> throw new AssertionError(method);
        });
    }

    private interface Call { Object invoke(String method, Object[] args); }
    private static <T> T proxy(Class<T> type, Call call) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (p, method, args) -> call.invoke(method.getName(), args)));
    }
}
