package dev.stargenesis.paper.integration;

import dev.stargenesis.integrations.*;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class RecipeCatalogControllerTest {
    private final List<String> messages=new ArrayList<>();
    private boolean allowed=true;
    private final CommandSender sender=(CommandSender)Proxy.newProxyInstance(CommandSender.class.getClassLoader(),new Class<?>[]{CommandSender.class},
            (p,m,a)->switch(m.getName()) {
                case "hasPermission" -> { assertEquals("stargenesis.recipes",a[0]); yield allowed; }
                case "sendMessage" -> { messages.add((String)a[0]); yield null; }
                default -> throw new AssertionError(m.getName());
            });
    @Test void permissionUsageAndInvalidKeysNeverTouchTheProvider() {
        var calls=new AtomicInteger(); var controller=new RecipeCatalogController(()-> { calls.incrementAndGet();return null; });
        allowed=false; controller.handle(sender,new String[]{"status"}); allowed=true;
        for(var args:List.of(new String[]{"inspect"},new String[]{"list","POT","TEAPOT"},new String[]{"inspect","TEAPOT","TEAPOT","missing_namespace"},
                new String[]{"list","POT","FLEX","0"},new String[]{"list","UNKNOWN","FLEX"},new String[]{"list","POT","FLEX","129"})) controller.handle(sender,args);
        assertEquals(0,calls.get()); assertTrue(messages.getFirst().contains("permission denied"));
    }
    @Test void providerIsDiscoveredAgainAndMissingOrLinkageFailureIsReported() {
        var calls=new AtomicInteger(); var controller=new RecipeCatalogController(()-> { if(calls.incrementAndGet()==1) return null;throw new NoSuchMethodError("old API"); });
        controller.handle(sender,new String[]{"status"});controller.handle(sender,new String[]{"status"});
        assertEquals(2,calls.get());assertTrue(messages.getFirst().contains("已停用"));assertTrue(messages.getLast().contains("NoSuchMethodError"));
    }
    @Test void pagesAndMetadataOutputAreBoundedAndMissingDefinitionsStayMissing() {
        var calls=new AtomicInteger();
        RecipeCatalog catalog=new RecipeCatalog() {
            public String integrationId(){return "senluo.cookery";}
            public Summary summary(){throw new AssertionError();}
            public List<String> list(Appliance a,Family f){return java.util.stream.IntStream.range(0,2048).mapToObj(i->"test:r"+i).toList();}
            public Optional<Definition> inspect(Key key){ calls.incrementAndGet();return key.id().equals("test:missing")?Optional.empty():Optional.of(new Definition(key,Map.of("lore",List.of("a".repeat(1024))))); }
            public AdapterVerification verify(InstalledPlugin p){throw new AssertionError();}
        };
        var controller=new RecipeCatalogController(()->catalog);
        controller.handle(sender,new String[]{"list","pot","flex","2"});assertEquals(17,messages.size());
        assertEquals("test:r16",messages.get(1));assertEquals("test:r31",messages.getLast()); messages.clear();
        controller.handle(sender,new String[]{"inspect","pot","flex","test:missing"});assertTrue(messages.getLast().contains("尚未加载"));
        controller.handle(sender,new String[]{"inspect","pot","flex","test:present"});assertEquals(2,calls.get());
        assertTrue(messages.getLast().length()<260);
    }
}
