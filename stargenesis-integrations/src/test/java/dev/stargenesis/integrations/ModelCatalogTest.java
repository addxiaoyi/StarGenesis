package dev.stargenesis.integrations;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class ModelCatalogTest {
    @Test void dimensionsAndNamesHaveFiniteBounds() {
        assertEquals("_pack/_model", ModelCatalog.requireId("_pack/_model"));
        assertThrows(IllegalArgumentException.class, () -> ModelCatalog.requireId("pack/model/extra"));
        for (double scale : new double[]{0, -1, Double.NaN, Double.POSITIVE_INFINITY, 1025})
            assertThrows(IllegalArgumentException.class, () -> info(scale, 1, 1, 1, List.of()));
        assertThrows(IllegalArgumentException.class, () -> info(1, 4097, 1, 1, List.of()));
        assertThrows(IllegalArgumentException.class, () -> info(1, -1, 1, 1, List.of()));
        assertThrows(IllegalArgumentException.class, () -> info(1, 1, 0, 1, List.of()));
        assertThrows(IllegalArgumentException.class, () -> info(1, 1, 1, Double.NaN, List.of()));
        for (String name : List.of("", "a\nb", "a".repeat(129), "§aunsafe"))
            assertThrows(IllegalArgumentException.class, () -> info(1, 1, 1, 1, List.of(name)));
        assertEquals(4096, info(1024, 4096, 1024, 1024, java.util.Collections.nCopies(256, "idle")).bones());
    }

    private static ModelCatalog.ModelInfo info(double scale, int bones, double width, double height, List<String> names) {
        return new ModelCatalog.ModelInfo("example/cube", scale, bones, width, height, names);
    }
}
