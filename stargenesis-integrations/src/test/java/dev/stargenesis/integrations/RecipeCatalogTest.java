package dev.stargenesis.integrations;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static dev.stargenesis.integrations.RecipeCatalog.*;

class RecipeCatalogTest {
    private static final Key KEY = new Key(Appliance.MILLSTONE, Family.ACCURATE, "test:flour");
    @Test void deeplyCopiesNestedMutableMapsListsAndNullableCarriers() {
        var weighted = new LinkedHashMap<String, Object>(); weighted.put("weight", 3); weighted.put("key", "test:a");
        var values = new ArrayList<>(List.of(weighted));
        var fields = new LinkedHashMap<String, Object>(); fields.put("results", values); fields.put("carrier", null);
        var definition = new Definition(KEY, fields);
        weighted.put("weight", 99); values.clear(); fields.clear();
        var copied = (List<?>) definition.fields().get("results");
        assertEquals(3, ((Map<?, ?>) copied.getFirst()).get("weight"));
        assertTrue(definition.fields().containsKey("carrier")); assertNull(definition.fields().get("carrier"));
        assertThrows(UnsupportedOperationException.class, definition.fields()::clear);
        assertThrows(UnsupportedOperationException.class, copied::clear);
        assertThrows(UnsupportedOperationException.class, ((Map<?, ?>) copied.getFirst())::clear);
    }
    @Test void rejectsCyclesDeepGraphsAndNodeAmplification() {
        var cycle = new HashMap<String, Object>(); cycle.put("cycle", cycle);
        assertThrows(IllegalArgumentException.class, () -> new Definition(KEY, cycle));
        List<Object> many = new ArrayList<>();
        for (int i=0;i<128;i++) many.add(Collections.nCopies(128, 1));
        assertThrows(IllegalArgumentException.class, () -> new Definition(KEY, Map.of("many", many)));
    }
    @Test void rejectsMemberTextAndAggregateTextOverflowBeforePublishing() {
        assertThrows(IllegalArgumentException.class, () -> new Definition(KEY, Map.of("list", Collections.nCopies(129, 0))));
        assertThrows(IllegalArgumentException.class, () -> new Definition(KEY, Map.of("text", "a".repeat(1025))));
        assertThrows(IllegalArgumentException.class, () -> new Definition(KEY, Map.of("list", Collections.nCopies(33, "a".repeat(1024)))));
        var oversized = new HashMap<String,Object>(); for(int i=0;i<129;i++) oversized.put("k"+i, i);
        assertThrows(IllegalArgumentException.class, () -> new Definition(KEY, oversized));
    }
    @Test void permitsOnlyFiniteExplicitMetadataTypes() {
        for(Object value : List.of(Double.NaN, Double.POSITIVE_INFINITY, 1L, new Object(), new int[]{1}, Set.of("a")))
            assertThrows(IllegalArgumentException.class, () -> new Definition(KEY, Map.of("value", value)));
        assertEquals(3.25, new Definition(KEY, Map.of("norm", 3.25)).fields().get("norm"));
    }
    @Test void familyIdentityAndSummaryHaveIndependentBounds() {
        assertThrows(IllegalArgumentException.class, () -> new Key(Appliance.POT, Family.TEAPOT, "test:tea"));
        assertThrows(IllegalArgumentException.class, () -> new Key(Appliance.TEAPOT, Family.TEAPOT, "tea"));
        var counts = new EnumMap<Appliance,Integer>(Appliance.class);
        for(var a:Appliance.values()) counts.put(a,0);
        var summary = new Summary(0, counts); counts.put(Appliance.POT, 1);
        assertEquals(0,summary.byAppliance().get(Appliance.POT));
        assertThrows(IllegalArgumentException.class, () -> new Summary(0,counts));
        counts.put(Appliance.POT, MAX_RECIPES + 1);
        assertThrows(IllegalArgumentException.class, () -> new Summary(MAX_RECIPES + 1,counts));
        counts.remove(Appliance.POT);
        assertThrows(IllegalArgumentException.class, () -> new Summary(0,counts));
    }
}
