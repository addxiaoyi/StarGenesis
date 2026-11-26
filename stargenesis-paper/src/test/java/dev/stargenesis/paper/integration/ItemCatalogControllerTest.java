package dev.stargenesis.paper.integration;

import dev.stargenesis.integrations.AdapterVerification;
import dev.stargenesis.integrations.InstalledPlugin;
import dev.stargenesis.integrations.ItemCatalog;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ItemCatalogControllerTest {
    private final List<String> messages = new ArrayList<>();
    private boolean allowed = true;
    private final CommandSender sender = (CommandSender) Proxy.newProxyInstance(CommandSender.class.getClassLoader(),
            new Class<?>[]{CommandSender.class}, (p,m,a) -> switch (m.getName()) {
                case "hasPermission" -> allowed;
                case "sendMessage" -> { messages.add((String) a[0]); yield null; }
                default -> throw new AssertionError(m.getName());
            });
    @Test void permissionsAndUsagePrecedeEveryProviderOrInventoryAccess() {
        var calls = new AtomicInteger();
        var controller = new ItemCatalogController(() -> { calls.incrementAndGet(); return null; }, p -> { throw new AssertionError(); });
        allowed = false; controller.handle(sender, new String[]{"held"});
        allowed = true; controller.handle(sender, new String[]{"inspect"});
        controller.handle(sender, new String[]{"held"});
        assertEquals(0, calls.get());
        assertTrue(messages.getFirst().contains("permission denied"));
        assertTrue(messages.getLast().contains("在线玩家"));
    }
    @Test void missingServiceIsQueriedAgainOnTheNextRequest() {
        var calls = new AtomicInteger();
        var controller = new ItemCatalogController(() -> { calls.incrementAndGet(); return null; }, p -> { throw new AssertionError(); });
        controller.handle(sender, new String[]{"status"}); controller.handle(sender, new String[]{"inspect", "test:item"});
        assertEquals(2, calls.get()); assertEquals(2, messages.size());
        assertTrue(messages.getLast().contains("已停用"));
    }
    @Test void apiLinkageFailureIsIsolated() {
        var controller = new ItemCatalogController(() -> { throw new NoSuchMethodError("old API"); }, p -> { throw new AssertionError(); });
        assertTrue(controller.handle(sender, new String[]{"status"}));
        assertTrue(messages.getLast().contains("NoSuchMethodError"));
    }
    @Test void outputBoundsTagsAndDistinguishesMissingDefinitions() {
        var tags = java.util.stream.IntStream.range(0,128).mapToObj(i -> "test:tag" + String.format("%03d", i)).toList();
        ItemCatalog catalog = new ItemCatalog() {
            public String integrationId() { return "craftengine"; }
            public int loadedItems() { return 1; }
            public Optional<Definition> inspect(String id) { return id.equals("test:item") ? Optional.of(new Definition(id,"minecraft:stone",false,tags)) : Optional.empty(); }
            public AdapterVerification verify(InstalledPlugin plugin) { throw new AssertionError(); }
        };
        var controller = new ItemCatalogController(() -> catalog, p -> { throw new AssertionError(); });
        controller.handle(sender, new String[]{"inspect", "test:missing"});
        controller.handle(sender, new String[]{"inspect", "test:item"});
        assertTrue(messages.getFirst().contains("尚未加载"));
        assertTrue(messages.getLast().contains("128"));
        assertTrue(messages.getLast().contains("tag015"));
        assertFalse(messages.getLast().contains("tag016"));
    }
}
