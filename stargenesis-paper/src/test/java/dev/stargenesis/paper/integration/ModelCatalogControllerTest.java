package dev.stargenesis.paper.integration;

import dev.stargenesis.integrations.AdapterVerification;
import dev.stargenesis.integrations.InstalledPlugin;
import dev.stargenesis.integrations.ModelCatalog;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ModelCatalogControllerTest {
    private final List<String> messages = new ArrayList<>();
    private boolean allowed = true;
    private final CommandSender sender = (CommandSender) Proxy.newProxyInstance(CommandSender.class.getClassLoader(),
            new Class<?>[]{CommandSender.class}, (p, method, args) -> switch (method.getName()) {
                case "hasPermission" -> allowed;
                case "sendMessage" -> { messages.add((String) args[0]); yield null; }
                default -> throw new AssertionError(method.getName());
            });

    @Test void permissionAndUsageAreCheckedBeforeLookingUpService() {
        AtomicInteger lookups = new AtomicInteger();
        var controller = new ModelCatalogController(() -> { lookups.incrementAndGet(); return null; });
        allowed = false;
        controller.handle(sender, new String[]{"status"});
        allowed = true;
        controller.handle(sender, new String[]{"inspect"});
        assertEquals(0, lookups.get());
        assertTrue(messages.getFirst().contains("permission denied"));
    }

    @Test void providerIsLookedUpOnEveryRequestAndAbsenceIsClear() {
        AtomicInteger lookups = new AtomicInteger();
        var controller = new ModelCatalogController(() -> { lookups.incrementAndGet(); return null; });
        controller.handle(sender, new String[]{"status"});
        controller.handle(sender, new String[]{"inspect", "cube"});
        assertEquals(2, lookups.get());
        assertEquals(2, messages.stream().filter(s -> s.contains("未安装或已停用")).count());
    }

    @Test void providerLinkageFailureIsIsolatedFromCommandDispatcher() {
        var controller = new ModelCatalogController(() -> { throw new NoClassDefFoundError("provider"); });
        assertTrue(controller.handle(sender, new String[]{"status"}));
        assertTrue(messages.getFirst().contains("NoClassDefFoundError"));
    }

    @Test void inspectionOutputIsBoundedAndDistinguishesUnloadedModel() {
        ModelCatalog catalog = new ModelCatalog() {
            public String integrationId() { return "helstera"; }
            public int loadedModels() { return 1; }
            public Optional<ModelInfo> inspect(String id) {
                return id.equals("cube") ? Optional.of(new ModelInfo(id, 1, 2, 1, 2,
                        java.util.stream.IntStream.range(0, 256).mapToObj(i -> "a" + i).toList())) : Optional.empty();
            }
            public AdapterVerification verify(InstalledPlugin plugin) { throw new AssertionError(); }
        };
        var controller = new ModelCatalogController(() -> catalog);
        controller.handle(sender, new String[]{"inspect", "missing"});
        controller.handle(sender, new String[]{"inspect", "cube"});
        assertTrue(messages.getFirst().contains("尚未加载"));
        assertEquals(3, messages.size());
        assertTrue(messages.getLast().contains("256"));
        assertTrue(messages.getLast().contains("a15"));
        assertFalse(messages.getLast().contains("a16"));
    }
}
