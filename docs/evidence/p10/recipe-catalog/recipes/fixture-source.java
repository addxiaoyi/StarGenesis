import com.google.gson.*;
import dev.stargenesis.integrations.*;
import net.kaleidoscope.cookery.api.KaleidoscopeCookeryAPI;
import net.kaleidoscope.cookery.recipe.*;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.core.plugin.CraftEngine;
import net.momirealms.craftengine.core.plugin.ResourceOperationCoordinator;
import net.momirealms.craftengine.core.util.Key;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import java.lang.reflect.Proxy;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/** Isolated actual-provider fixture. API registrations here are test data, not player cooking. */
public final class CookeryRecipeServerProbe extends JavaPlugin {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().serializeNulls().create();
    private final List<String> checks = new ArrayList<>();
    private final int run = Integer.getInteger("stargenesis.probe.run", 1);
    private BukkitTask task;
    private int phase, attempts;
    private int configuredRecipes;
    private RecipeCatalog bridge;
    private RecipeCatalog.Definition old;
    private BukkitCraftEngine engine;
    private Path configuration;
    private JsonObject changed;
    private CompletableFuture<CraftEngine.ReloadResult> reload;
    private CompletableFuture<Boolean> wrongThread;
    private static Key key(String value) { return Key.of("stargenesis:" + value); }
    private static RecipeCatalog.Key address(RecipeCatalog.Appliance appliance, RecipeCatalog.Family family, String id) {
        return new RecipeCatalog.Key(appliance, family, "stargenesis:" + id);
    }
    private static RecipeCatalog.Key accurate() { return address(RecipeCatalog.Appliance.MILLSTONE,RecipeCatalog.Family.ACCURATE,"flour"); }
    private static RecipeCatalog.Key bamboo() { return address(RecipeCatalog.Appliance.BAMBOO_TRAY,RecipeCatalog.Family.BAMBOO_TRAY,"dry"); }
    @Override public void onEnable() { task=Bukkit.getScheduler().runTaskTimer(this,this::advance,20,2); }
    private void require(boolean value,String name) { if(!value) throw new AssertionError(name); checks.add(name); }
    private void rejected(Runnable action,String name) {
        try { action.run(); } catch(IllegalStateException expected) { checks.add(name);return; }
        throw new AssertionError(name+" was accepted");
    }
    private void startReload() {
        reload=engine.reloadPlugin(command->Bukkit.getScheduler().runTaskAsynchronously(this,command),
                command->Bukkit.getScheduler().runTask(this,command),false,true);
        rejected(bridge::summary,"actual reload rejects metadata admission");
    }
    private void command(boolean allowed,String text,String expected) {
        var messages=new ArrayList<String>(); var console=Bukkit.getConsoleSender();
        CommandSender sender=(CommandSender)Proxy.newProxyInstance(CommandSender.class.getClassLoader(),new Class<?>[]{CommandSender.class},
                (proxy,method,args)-> {
                    if(method.getName().equals("hasPermission"))return allowed;
                    if(method.getName().equals("sendMessage")&&args.length==1&&args[0] instanceof String value){messages.add(value);return null;}
                    return method.invoke(console,args);
                });
        String[] tokens=text.split(" ");
        Bukkit.getPluginCommand(tokens[0]).execute(sender,tokens[0],Arrays.copyOfRange(tokens,1,tokens.length));
        require(messages.stream().anyMatch(m->m.contains(expected)),"command "+text+" allowed="+allowed+" expected="+expected);
    }
    private void addFamilies() {
        var r=KaleidoscopeCookeryAPI.foodRecipes();
        r.atomicUpdate(()-> {
            r.registerFlex(FlexFoodRecipe.of(key("flex"),Key.of("minecraft:bread"),ApplianceType.POT,
                    Map.of(Key.of("minecraft:wheat"),2),List.of(),null,false,false,20,3));
            r.registerPot(new PotRecipe(key("pot"),PotRecipe.Mode.EXACT,List.of(Set.of(Key.of("minecraft:wheat"))),Key.of("minecraft:bread"),1,2,3,null));
            r.registerStockpot(new StockpotRecipe(key("stock"),StockpotRecipe.Mode.EXACT,List.of(Set.of(Key.of("minecraft:carrot"))),
                    Key.of("minecraft:water"),Key.of("minecraft:mushroom_stew"),1,30,Key.of("minecraft:bowl"),
                    new StockpotVisuals(key("cooking"),key("finished"),0x123456,0xABCDEF)));
            r.registerChopping(new ChoppingBoardRecipe(key("chop"),Key.of("minecraft:carrot"),2,List.of("minecraft:carrot"),
                    ChoppingMode.MULTI_RANDOM,List.of(new ChoppingResult(Key.of("minecraft:carrot"),1,10)),List.of()));
            r.registerTeapot(new TeapotRecipe(key("tea"),Key.of("minecraft:water"),Key.of("minecraft:kelp"),2,Key.of("minecraft:potion"),1,40));
            r.registerMenuAccurate(new AccurateFoodRecipe(key("display_only"),Key.of("minecraft:wheat"),List.of(new WeightedResult(Key.of("minecraft:bread"),100)),ApplianceType.MILLSTONE,1,1,List.of()));
        });
        require(bridge.summary().total()==configuredRecipes+5,"all seven fixture families counted; menu-only recipe excluded");
        for(var a: List.of(address(RecipeCatalog.Appliance.POT,RecipeCatalog.Family.FLEX,"flex"),address(RecipeCatalog.Appliance.POT,RecipeCatalog.Family.POT,"pot"),
                address(RecipeCatalog.Appliance.STOCKPOT,RecipeCatalog.Family.STOCKPOT,"stock"),address(RecipeCatalog.Appliance.CHOPPING_BOARD,RecipeCatalog.Family.CHOPPING,"chop"),
                address(RecipeCatalog.Appliance.TEAPOT,RecipeCatalog.Family.TEAPOT,"tea"))) require(bridge.inspect(a).isPresent(),"actual family metadata "+a.family());
        require(bridge.inspect(address(RecipeCatalog.Appliance.MILLSTONE,RecipeCatalog.Family.ACCURATE,"display_only")).isEmpty(),"display recipe cannot masquerade as executable registry entry");
    }
    private List<RecipeCatalog.Definition> allConfiguredMetadata() {
        var definitions=new ArrayList<RecipeCatalog.Definition>();
        for(var appliance:RecipeCatalog.Appliance.values())for(var family:RecipeCatalog.Family.values()) {
            try {new RecipeCatalog.Key(appliance,family,"stargenesis:validation");}
            catch(IllegalArgumentException invalidPair){continue;}
            for(String id:bridge.list(appliance,family))
                definitions.add(bridge.inspect(new RecipeCatalog.Key(appliance,family,id)).orElseThrow());
        }
        require(definitions.size()==configuredRecipes,"all "+configuredRecipes+" configured recipe definitions copied within budgets");
        return definitions;
    }
    private void advance() {
        try {
            if(++attempts>1200)throw new AssertionError("probe timed out");
            if(phase==0) {
                engine=BukkitCraftEngine.instance();
                if(engine==null||!engine.isFullyLoaded()||engine.resourceOperations().isBusy()||KaleidoscopeCookeryAPI.foodRecipes().isConfigurationLoading())return;
                bridge=Bukkit.getServicesManager().load(RecipeCatalog.class);
                require(bridge!=null,"recipe service registered");
                require(bridge.verify(new InstalledPlugin("KaleidoscopeCookeryPlugin","1.3.0",true)).activeCapabilities().equals(Set.of("cookery.read")),"only recipe metadata capability activated");
                configuredRecipes=bridge.summary().total();
                require(configuredRecipes>=2,"configured recipe pack loaded");
                var all=allConfiguredMetadata();
                if(run==2)require(GSON.toJsonTree(all).equals(JsonParser.parseString(Files.readString(getDataFolder().toPath().resolve("saved-catalog.json")))),"entire configured catalog restored from previous process");
                old=bridge.inspect(accurate()).orElseThrow();
                int expected=run==1?5:13;
                require(((List<?>)old.fields().get("results")).getFirst().equals(Map.of("key","minecraft:bread","weight",expected)),"configured weight preserved across process start");
                require(bridge.inspect(bamboo()).orElseThrow().fields().get("durationTicks").equals(run==1?120:240),"configured duration preserved across process start");
                if(run==2)require(GSON.toJsonTree(old.fields()).equals(JsonParser.parseString(Files.readString(getDataFolder().toPath().resolve("saved-metadata.json")))),"metadata content restored from previous process configuration");
                configuration=getDataFolder().toPath().getParent().resolve("CraftEngine/resources/sg_recipe_probe/configuration/recipes.yml");
                changed=JsonParser.parseString(Files.readString(configuration)).getAsJsonObject();
                int entities=Bukkit.getWorlds().getFirst().getEntities().size();
                addFamilies();
                command(false,"genesis recipe status","permission denied");
                command(true,"genesis recipe status","loaded="+(configuredRecipes+5));
                require(Bukkit.dispatchCommand(Bukkit.getConsoleSender(),"genesis recipe status"),"native console command dispatch accepted");
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(),"genesis debug capabilities");
                int flourPage=bridge.list(RecipeCatalog.Appliance.MILLSTONE,RecipeCatalog.Family.ACCURATE).indexOf("stargenesis:flour")/16+1;
                command(true,"genesis recipe list MILLSTONE ACCURATE "+flourPage,"stargenesis:flour");
                command(true,"genesis recipe inspect MILLSTONE ACCURATE stargenesis:flour","weight="+expected);
                command(true,"genesis recipe inspect MILLSTONE ACCURATE stargenesis:missing","尚未加载");
                require(Bukkit.getWorlds().getFirst().getEntities().size()==entities,"metadata queries create no entities");
                wrongThread=CompletableFuture.supplyAsync(()-> {try {bridge.summary();return false;}catch(IllegalStateException expectedFailure){return true;}});
                phase=1;
            } else if(phase==1) {
                if(!wrongThread.isDone())return;
                require(wrongThread.join(),"off-main-thread query refused");
                var obtained=new CountDownLatch(1);var release=new CountDownLatch(1);
                var busy=CompletableFuture.runAsync(()-> {try(var lease=engine.resourceOperations().acquire()){obtained.countDown();try{if(!release.await(5,TimeUnit.SECONDS))throw new AssertionError("lease timeout");}catch(InterruptedException e){throw new AssertionError(e);}}});
                try {require(obtained.await(5,TimeUnit.SECONDS),"actual background resource lease held");rejected(bridge::summary,"resource workflow excludes metadata");}
                finally {release.countDown();} busy.join();
                changed.getAsJsonObject("accurate_foods").getAsJsonObject("stargenesis:flour").add("result",GSON.toJsonTree(List.of("minecraft:bread 13","minecraft:cookie 7")));
                changed.getAsJsonObject("bamboo_tray_recipes").getAsJsonObject("stargenesis:dry").addProperty("time",240);
                Files.writeString(configuration,GSON.toJson(changed));startReload();phase=2;
            } else if(phase==2) {
                if(!reload.isDone())return;
                require(reload.join().success(),"actual provider reload completed");
                require(bridge.summary().total()==configuredRecipes,"reload replaced API-only fixture registrations");
                var updated=bridge.inspect(accurate()).orElseThrow();
                require(((List<?>)updated.fields().get("results")).getFirst().equals(Map.of("key","minecraft:bread","weight",13)),"changed weighted alternatives visible");
                require(bridge.inspect(bamboo()).orElseThrow().fields().get("durationTicks").equals(240),"second family changed in same published snapshot");
                require(((List<?>)old.fields().get("results")).getFirst().equals(Map.of("key","minecraft:bread","weight",run==1?5:13)),"previously returned metadata remains detached");
                Files.createDirectories(getDataFolder().toPath());Files.writeString(getDataFolder().toPath().resolve("saved-metadata.json"),GSON.toJson(updated.fields()));
                Files.writeString(getDataFolder().toPath().resolve("saved-catalog.json"),GSON.toJson(allConfiguredMetadata()));
                var removed=changed.deepCopy();removed.getAsJsonObject("accurate_foods").remove("stargenesis:flour");
                Files.writeString(configuration,GSON.toJson(removed));startReload();phase=3;
            } else if(phase==3) {
                if(!reload.isDone())return;
                require(reload.join().success(),"actual recipe deletion reload completed");
                require(bridge.inspect(accurate()).isEmpty()&&bridge.summary().total()==configuredRecipes-1,"deleted definition not cached");
                Files.writeString(configuration,GSON.toJson(changed));startReload();phase=4;
            } else if(phase==4) {
                if(!reload.isDone())return;
                require(reload.join().success()&&bridge.summary().total()==configuredRecipes,"configured recipes restored for next process");
                Bukkit.getPluginManager().disablePlugin(KaleidoscopeCookeryAPI.plugin());
                require(Bukkit.getServicesManager().load(RecipeCatalog.class)==null,"Cookery shutdown unregisters bridge service");
                rejected(bridge::summary,"saved bridge reference refuses disabled provider");
                require(!Bukkit.getPluginManager().getPlugin("StarGenesisCookery").isEnabled(),"dependent bridge disabled");
                require(Bukkit.getPluginManager().getPlugin("StarGenesis").isEnabled(),"base plugin survives optional provider shutdown");
                command(true,"genesis recipe status","已停用");
                write(true,null);getLogger().info("COOKERY_RECIPE_PROBE_PASSED");task.cancel();phase=5;
            }
        } catch(Throwable failure) {
            try {write(false,failure.toString());}catch(Exception ignored){}
            getLogger().log(java.util.logging.Level.SEVERE,"COOKERY_RECIPE_PROBE_FAILED",failure);if(task!=null)task.cancel();
        }
    }
    private void write(boolean passed,String error)throws Exception {
        Files.createDirectories(getDataFolder().toPath());
        var result=new LinkedHashMap<String,Object>();result.put("passed",passed);result.put("run",run);result.put("configuredRecipes",configuredRecipes);result.put("checks",checks);result.put("error",error);
        Files.writeString(getDataFolder().toPath().resolve("result-"+run+".json"),GSON.toJson(result));
    }
    @Override public void onDisable(){if(task!=null)task.cancel();}
}
