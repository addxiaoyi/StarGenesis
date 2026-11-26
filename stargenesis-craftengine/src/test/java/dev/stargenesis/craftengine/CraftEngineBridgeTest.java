package dev.stargenesis.craftengine;

import dev.stargenesis.integrations.InstalledPlugin;
import dev.stargenesis.integrations.ItemCatalog;
import net.momirealms.craftengine.core.plugin.ResourceOperationCoordinator;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Uses the actual published resource coordinator; physical stack behavior is tested on Paper. */
class CraftEngineBridgeTest {
    private boolean main = true, paper = true;
    private InstalledPlugin installed = new InstalledPlugin("CraftEngine", "26.9.1", true);
    private Access access = new Access();
    private final CraftEngineBridge bridge = new CraftEngineBridge(() -> main, () -> paper, () -> installed, () -> access, () -> 4671);
    private static class Access implements CraftEngineAccess {
        final ResourceOperationCoordinator coordinator = new ResourceOperationCoordinator();
        final Map<String, ItemCatalog.Definition> items = new HashMap<>();
        boolean ready = true;
        int reads, creates;
        Runnable duringRead = () -> {};
        public Object identity() { return this; }
        public boolean ready() { return ready; }
        public ResourceOperationCoordinator resources() { return coordinator; }
        public int count() { reads++; duringRead.run(); return items.size(); }
        public Optional<ItemCatalog.Definition> inspect(String id) { reads++; duringRead.run(); return Optional.ofNullable(items.get(id)); }
        public ItemStack create(String id) { creates++; throw new AssertionError("No physical item creation in this unit fixture"); }
        public String customId(ItemStack stack) { throw new AssertionError("No physical item inspection in this unit fixture"); }
    }

    @Test void incompleteStartupWrongPlatformThreadAndProviderFailBeforeReading() {
        access.ready = false; assertThrows(IllegalStateException.class, bridge::loadedItems); access.ready = true;
        main = false; assertThrows(IllegalStateException.class, bridge::loadedItems); main = true;
        paper = false; assertThrows(IllegalStateException.class, bridge::loadedItems); paper = true;
        for (InstalledPlugin value : new InstalledPlugin[]{null, new InstalledPlugin("Other", "26.9.1", true),
                new InstalledPlugin("CraftEngine", "next", true), new InstalledPlugin("CraftEngine", "26.9.1", false)}) {
            installed = value; assertThrows(IllegalStateException.class, bridge::loadedItems);
        }
        assertEquals(0, access.reads);
    }
    @Test void actualResourceLeaseExcludesLookupAndCreationDuringAsyncWork() throws Exception {
        var acquired = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var thread = Thread.ofPlatform().start(() -> {
            try (var lease = access.coordinator.acquire()) {
                acquired.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("Release timeout"); }
                catch (InterruptedException interrupted) { throw new AssertionError(interrupted); }
            }
        });
        try {
            assertTrue(acquired.await(5, TimeUnit.SECONDS));
            assertThrows(ResourceOperationCoordinator.BusyException.class, () -> bridge.inspect("test:item"));
            assertThrows(ResourceOperationCoordinator.BusyException.class, () -> bridge.create("test:item", 1));
            assertEquals(0, access.reads); assertEquals(0, access.creates);
        } finally { release.countDown(); thread.join(5000); }
        assertFalse(thread.isAlive());
        assertEquals(0, bridge.loadedItems());
    }
    @Test void readFailuresReleaseTheProvidersActualLease() {
        access.duringRead = () -> { throw new NoClassDefFoundError("provider changed"); };
        assertThrows(NoClassDefFoundError.class, bridge::loadedItems);
        assertFalse(access.coordinator.isBusy());
        access.duringRead = () -> {};
        assertEquals(0, bridge.loadedItems());
    }
    @Test void providerReplacementCannotPublishTheOldResult() {
        Access old = access;
        old.duringRead = () -> access = new Access();
        assertThrows(IllegalStateException.class, bridge::loadedItems);
        assertFalse(old.coordinator.isBusy());
        assertEquals(0, bridge.loadedItems());
    }
    @Test void closeFromACallbackRefusesTheResultAndFutureAdmission() {
        access.duringRead = bridge::close;
        assertThrows(IllegalStateException.class, bridge::loadedItems);
        assertFalse(access.coordinator.isBusy());
        access.duringRead = () -> {};
        assertThrows(IllegalStateException.class, bridge::loadedItems);
        bridge.close();
    }
    @Test void reloadResultsAreReadFreshAndPreviouslyCopiedMetadataSurvives() {
        var old = new ItemCatalog.Definition("test:item", "minecraft:stone", false, List.of("test:tag"));
        access.items.put(old.id(), old);
        assertEquals(old, bridge.inspect(old.id()).orElseThrow());
        access.ready = false; assertThrows(IllegalStateException.class, () -> bridge.inspect(old.id()));
        access.items.clear(); access.ready = true;
        assertTrue(bridge.inspect(old.id()).isEmpty());
        assertEquals(List.of("test:tag"), old.tags());
    }
    @Test void identityMismatchAndInvalidCreationRequestsNeverReachTheFactory() {
        access.items.put("test:item", new ItemCatalog.Definition("test:other", "minecraft:stone", false, List.of()));
        assertThrows(IllegalStateException.class, () -> bridge.inspect("test:item"));
        assertThrows(IllegalStateException.class, () -> bridge.create("test:item", 1));
        assertThrows(IllegalArgumentException.class, () -> bridge.create("test:missing", 1));
        assertThrows(IllegalArgumentException.class, () -> bridge.create("item", 1));
        assertThrows(IllegalArgumentException.class, () -> bridge.create("test:item", 65));
        assertEquals(0, access.creates);
        assertFalse(access.coordinator.isBusy());
    }
    @Test void reentrantCallsAndLateReadinessLossReleaseTheLease() {
        access.duringRead = bridge::loadedItems;
        assertThrows(IllegalStateException.class, bridge::loadedItems);
        assertFalse(access.coordinator.isBusy());
        access.duringRead = () -> access.ready = false;
        assertThrows(IllegalStateException.class, bridge::loadedItems);
        assertFalse(access.coordinator.isBusy());
    }
    @Test void capabilityVerificationUsesCurrentLifecycleAndDeclaredCapabilities() {
        assertEquals(java.util.Set.of("items.resolve", "items.create"), bridge.verify(installed).activeCapabilities());
        installed = new InstalledPlugin("CraftEngine", "26.9.1", false);
        assertThrows(IllegalStateException.class, () -> bridge.verify(new InstalledPlugin("CraftEngine", "26.9.1", true)));
    }
}
