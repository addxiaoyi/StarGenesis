package dev.stargenesis.cookery;

import dev.stargenesis.integrations.InstalledPlugin;
import dev.stargenesis.integrations.RecipeCatalog;
import net.kaleidoscope.cookery.recipe.*;
import net.momirealms.craftengine.core.plugin.ResourceOperationCoordinator;
import net.momirealms.craftengine.core.util.Key;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static dev.stargenesis.integrations.RecipeCatalog.Appliance;
import static dev.stargenesis.integrations.RecipeCatalog.Family;

/** Actual upstream records, registry and resource coordinator; no third-party API stubs. */
class CookeryBridgeTest {
    private final FoodRecipeRegistry registry = FoodRecipeRegistry.instance();
    private boolean main = true, paper = true;
    private InstalledPlugin installed = new InstalledPlugin("KaleidoscopeCookeryPlugin", "1.3.0", true);
    private InstalledPlugin ce = new InstalledPlugin("CraftEngine", "26.9.1", true);
    private Access access = new Access();
    private final CookeryBridge bridge = new CookeryBridge(() -> main, () -> paper, () -> installed, () -> ce, () -> access);
    private final class Access implements CookeryAccess {
        final ResourceOperationCoordinator coordinator = new ResourceOperationCoordinator();
        boolean enabled = true;
        int checks;
        Runnable duringReady = () -> {};
        public Object identity() { return this; }
        public Object resourceIdentity() { return coordinator; }
        public FoodRecipeRegistry registry() { return registry; }
        public ResourceOperationCoordinator resources() { return coordinator; }
        public boolean ready() { checks++; duringReady.run(); return enabled; }
    }
    private static Key k(String id) { return Key.of(id.contains(":") ? id : "test:" + id); }
    private static AccurateFoodRecipe accurate(String id, ApplianceType cook, int weight) {
        return new AccurateFoodRecipe(k(id), k("input"), List.of(new WeightedResult(k("a"), weight),
                new WeightedResult(k("b"), 7), new WeightedResult(k("a"), 2)), cook, 0, 3, List.of("lore"), 0);
    }
    private static RecipeCatalog.Key address(Appliance appliance, Family family, String id) {
        return new RecipeCatalog.Key(appliance, family, k(id).asString());
    }
    @BeforeEach @AfterEach void clear() {
        registry.atomicUpdate(() -> {
            registry.clearAccurate(); for(var a:ApplianceType.values()) registry.clearFlex(a);
            registry.clearPot(); registry.clearStockpot(); registry.clearChopping(); registry.clearTeapot(); registry.clearBambooTray();
        });
    }
    @Test void allSevenFamiliesPreserveEveryRecordFieldAndWeightedAlternative() {
        registry.atomicUpdate(() -> {
            registry.registerAccurate(accurate("same", ApplianceType.MILLSTONE, 5));
            registry.registerFlex(FlexFoodRecipe.of(k("same"), k("result"), ApplianceType.POT, Map.of(k("a"), 2, k("b"), 3),
                    List.of(k("water")), null, false, true, 30, 4));
            registry.registerPot(new PotRecipe(k("same"), PotRecipe.Mode.DISTINCT, List.of(Set.of(k("a"), k("b"))), k("result"), 2, 3, 4, k("bowl")));
            registry.registerStockpot(new StockpotRecipe(k("same"), StockpotRecipe.Mode.EXACT, List.of(Set.of(k("a"))), k("water"),
                    k("soup"), 3, 20, null, new StockpotVisuals(k("cooking"),k("finished"),0x123456,0xABCDEF)));
            registry.registerChopping(new ChoppingBoardRecipe(k("same"), k("input"), 2, List.of("model:a","model:b"), ChoppingMode.SINGLE_EXTRA,
                    List.of(new ChoppingResult(k("a"),2,15),new ChoppingResult(k("b"),1,3)), List.of(new ChoppingResult(k("c"),1,25))));
            registry.registerTeapot(new TeapotRecipe(k("same"), k("water"), k("tea"), 2,k("drink"),3,40));
            registry.registerBambooTray(new BambooTrayRecipe(k("same"), "minecraft:kelp", k("dried"),2,BambooTrayMode.DRYING,60));
        });
        assertEquals(7,bridge.summary().total());
        var accurate = bridge.inspect(address(Appliance.MILLSTONE,Family.ACCURATE,"same")).orElseThrow().fields();
        assertEquals(Set.of("input","results","cook","rotations","resultCount","lore","cookingTime"), accurate.keySet());
        assertEquals(List.of(Map.of("key","test:a","weight",5),Map.of("key","test:b","weight",7),Map.of("key","test:a","weight",2)),accurate.get("results"));
        assertEquals(0,accurate.get("rotations")); assertEquals(0,accurate.get("cookingTime"));
        var flex=bridge.inspect(address(Appliance.POT,Family.FLEX,"same")).orElseThrow().fields();
        assertEquals(Map.of("test:a",2,"test:b",3), flex.get("perfect")); assertNull(flex.get("carrier"));
        assertEquals(Math.sqrt(13),flex.get("norm")); assertEquals(false,flex.get("useEquivalentFoods"));
        assertEquals(4,flex.get("stirFryCount")); assertEquals(30,flex.get("cookingTime")); assertEquals(5,flex.get("totalWeight"));
        var pot=bridge.inspect(address(Appliance.POT,Family.POT,"same")).orElseThrow().fields();
        assertEquals(List.of(List.of("test:a","test:b")),pot.get("ingredients")); assertEquals(4,pot.get("sourceTime"));
        assertEquals("test:bowl",pot.get("carrier")); assertEquals("DISTINCT",pot.get("mode"));
        var stock=bridge.inspect(address(Appliance.STOCKPOT,Family.STOCKPOT,"same")).orElseThrow().fields();
        assertEquals(Map.of("cookingModel","test:cooking","finishedModel","test:finished","cookingColor",0x123456,"finishedColor",0xABCDEF),stock.get("visuals"));
        assertEquals("test:water",stock.get("soupBase")); assertEquals(20,stock.get("time"));
        var chop=bridge.inspect(address(Appliance.CHOPPING_BOARD,Family.CHOPPING,"same")).orElseThrow().fields();
        assertEquals("SINGLE_EXTRA",chop.get("mode")); assertEquals(List.of(Map.of("key","test:c","count",1,"weight",25)),chop.get("extras"));
        assertEquals(List.of("model:a","model:b"),chop.get("values"));
        assertEquals(2,bridge.inspect(address(Appliance.TEAPOT,Family.TEAPOT,"same")).orElseThrow().fields().get("ingredientCount"));
        assertEquals(60,bridge.inspect(address(Appliance.BAMBOO_TRAY,Family.BAMBOO_TRAY,"same")).orElseThrow().fields().get("durationTicks"));
        assertEquals(List.of("test:same"), bridge.list(Appliance.POT,Family.POT));
        assertTrue(bridge.inspect(address(Appliance.STEAMER,Family.ACCURATE,"same")).isEmpty());
    }
    @Test void editsAndDeletionAreObservedWithoutChangingPreviouslyCopiedDefinitions() {
        var key=address(Appliance.MILLSTONE,Family.ACCURATE,"same"); registry.registerAccurate(accurate("same",ApplianceType.MILLSTONE,5));
        var copied=bridge.inspect(key).orElseThrow(); registry.removeAccurate(k("same"));
        assertTrue(bridge.inspect(key).isEmpty());
        registry.registerAccurate(accurate("same",ApplianceType.MILLSTONE,99));
        assertNotEquals(copied,bridge.inspect(key).orElseThrow());
        assertEquals(5,((Map<?,?>)((List<?>)copied.fields().get("results")).getFirst()).get("weight"));
    }
    @Test void nestedProviderSnapshotRetainsItsDocumentedOlderContextWithoutInventingGeneration() {
        registry.registerAccurate(accurate("old",ApplianceType.MILLSTONE,5));
        registry.readSnapshot(() -> {
            registry.atomicUpdate(() -> { registry.clearAccurate(); registry.registerAccurate(accurate("new",ApplianceType.MILLSTONE,7)); });
            assertEquals(List.of("test:old"),bridge.list(Appliance.MILLSTONE,Family.ACCURATE));
            return null;
        });
        assertEquals(List.of("test:new"),bridge.list(Appliance.MILLSTONE,Family.ACCURATE));
    }
    @Test void ambiguousSameFamilyIdFailsButDifferentApplianceAndFamilyRemainDistinct() {
        registry.registerAccurate(accurate("same",ApplianceType.MILLSTONE,5));
        registry.registerAccurate(accurate("same",ApplianceType.STEAMER,8));
        assertEquals(1,bridge.list(Appliance.MILLSTONE,Family.ACCURATE).size());
        registry.registerAccurate(accurate("same",ApplianceType.MILLSTONE,9));
        assertThrows(IllegalStateException.class,()->bridge.inspect(address(Appliance.MILLSTONE,Family.ACCURATE,"same")));
        assertThrows(IllegalStateException.class,()->bridge.list(Appliance.MILLSTONE,Family.ACCURATE));
        assertEquals(1,bridge.list(Appliance.STEAMER,Family.ACCURATE).size());
    }
    @Test void oversizedRegistryAndPerRecipeListsFailAndReleaseActualResourceLease() {
        registry.atomicUpdate(() -> { for(int i=0;i<=RecipeCatalog.MAX_RECIPES;i++) registry.registerAccurate(accurate("r"+i,ApplianceType.MILLSTONE,1)); });
        assertThrows(IllegalStateException.class,bridge::summary);
        assertThrows(IllegalStateException.class,()->bridge.inspect(address(Appliance.MILLSTONE,Family.ACCURATE,"r0")));
        registry.clearAccurate();
        registry.registerAccurate(new AccurateFoodRecipe(k("large"),k("input"),Collections.nCopies(129,new WeightedResult(k("a"),1)),ApplianceType.MILLSTONE,0,1,List.of()));
        assertThrows(IllegalArgumentException.class,()->bridge.inspect(address(Appliance.MILLSTONE,Family.ACCURATE,"large")));
        assertFalse(access.coordinator.isBusy());
    }
    @Test void wrongThreadPlatformVersionAndIncompleteLoadRefuseAdmission() {
        main=false; assertThrows(IllegalStateException.class,bridge::summary); main=true;
        paper=false; assertThrows(IllegalStateException.class,bridge::summary); paper=true;
        for(var plugin : new InstalledPlugin[]{null,new InstalledPlugin("Other","1.3.0",true),new InstalledPlugin("KaleidoscopeCookeryPlugin","next",true),new InstalledPlugin("KaleidoscopeCookeryPlugin","1.3.0",false)}) {
            installed=plugin; assertThrows(IllegalStateException.class,bridge::summary);
        }
        installed=new InstalledPlugin("KaleidoscopeCookeryPlugin","1.3.0",true);
        ce=new InstalledPlugin("CraftEngine","26.9.1",false); assertThrows(IllegalStateException.class,bridge::summary);
        ce=new InstalledPlugin("CraftEngine","26.9.1",true); access.enabled=false; assertThrows(IllegalStateException.class,bridge::summary);
    }
    @Test void actualResourceLeaseExcludesAsynchronousResourceOperations() throws Exception {
        var acquired=new CountDownLatch(1); var release=new CountDownLatch(1);
        var worker=Thread.ofPlatform().start(()-> { try(var lease=access.coordinator.acquire()) {
            acquired.countDown(); try { if(!release.await(5,TimeUnit.SECONDS)) throw new AssertionError("timeout"); }
            catch(InterruptedException e) { throw new AssertionError(e); }
        }});
        try { assertTrue(acquired.await(5,TimeUnit.SECONDS)); assertThrows(ResourceOperationCoordinator.BusyException.class,bridge::summary); }
        finally { release.countDown(); worker.join(5000); }
        assertFalse(worker.isAlive()); assertEquals(0,bridge.summary().total());
    }
    @Test void concurrentPublicRegistryMutationRejectsTheCopiedResult() throws Exception {
        var copied=new CountDownLatch(1); var changed=new CountDownLatch(1);
        access.duringReady=()-> { if(access.checks==3) { copied.countDown(); try { if(!changed.await(5,TimeUnit.SECONDS)) throw new AssertionError("timeout"); }
            catch(InterruptedException e) { throw new AssertionError(e); } } };
        var worker=Thread.ofPlatform().start(()-> { try { if(!copied.await(5,TimeUnit.SECONDS)) throw new AssertionError("timeout");
            registry.registerAccurate(accurate("late",ApplianceType.MILLSTONE,1)); } catch(InterruptedException e) { throw new AssertionError(e); } finally { changed.countDown(); } });
        try { assertThrows(IllegalStateException.class,bridge::summary); } finally { worker.join(5000); }
        assertFalse(access.coordinator.isBusy()); assertEquals(1,bridge.summary().total());
    }
    @Test void replacementDisableCloseAndReentrancyCannotPublishAResult() {
        var old=access;
        old.duringReady=()-> { if(old.checks==3) access=new Access(); };
        assertThrows(IllegalStateException.class,bridge::summary); assertFalse(old.coordinator.isBusy());
        access.duringReady=()-> { if(access.checks==3) access.enabled=false; };
        assertThrows(IllegalStateException.class,bridge::summary); assertFalse(access.coordinator.isBusy());
        access=new Access(); access.duringReady=bridge::summary;
        assertThrows(IllegalStateException.class,bridge::summary);
        access=new Access(); access.duringReady=()-> { if(access.checks==3) bridge.close(); };
        assertThrows(IllegalStateException.class,bridge::summary); assertFalse(access.coordinator.isBusy());
        assertThrows(IllegalStateException.class,bridge::summary);
    }
    @Test void verificationOnlyActivatesMetadataAndLinkageFailureDoesNotRetainLease() {
        assertEquals(Set.of("cookery.read"),bridge.verify(installed).activeCapabilities());
        var calls=new AtomicInteger(); access.duringReady=()-> { if(calls.incrementAndGet()==2) throw new NoClassDefFoundError("changed API"); };
        assertThrows(NoClassDefFoundError.class,bridge::summary); assertFalse(access.coordinator.isBusy());
    }
}
