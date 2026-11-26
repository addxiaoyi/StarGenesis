package dev.stargenesis.integrations;

import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ItemCatalogTest {
    @Test void completeIdsPreventProviderNamespaceGuessing() {
        assertEquals("kaleidoscope_doll:doll_0", ItemCatalog.requireId("kaleidoscope_doll:doll_0"));
        for (String id : new String[]{"stone", "Bad:Case", "minecraft:", ":stone", "x:y\n", "x:" + "a".repeat(128)})
            assertThrows(IllegalArgumentException.class, () -> ItemCatalog.requireId(id));
    }
    @Test void metadataCopiesAndOrdersProviderTags() {
        var tags = new ArrayList<>(List.of("tag:b", "tag:a"));
        var definition = new ItemCatalog.Definition("test:item", "minecraft:stone", false, tags);
        tags.clear();
        assertEquals(List.of("tag:a", "tag:b"), definition.tags());
        assertThrows(UnsupportedOperationException.class, () -> definition.tags().clear());
    }
    @Test void duplicateOrExcessiveTagsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new ItemCatalog.Definition("t:i", "minecraft:stone", false, List.of("t:a", "t:a")));
        var many = java.util.stream.IntStream.range(0,129).mapToObj(i -> "t:a" + i).toList();
        assertThrows(IllegalArgumentException.class, () -> new ItemCatalog.Definition("t:i", "minecraft:stone", false, many));
    }
}
