package dev.stargenesis.integrations;

import java.util.List;
import java.util.Optional;

/** Copied item definitions. Registry entries do not authorize an inventory transfer. */
public interface ItemCatalog extends IntegrationAdapter {
    String integrationId();
    int loadedItems();
    Optional<Definition> inspect(String id);

    record Definition(String id, String material, boolean vanillaOverride, List<String> tags) {
        public Definition {
            id = requireId(id);
            material = requireId(material);
            if (tags == null || tags.size() > 128) throw new IllegalArgumentException("Item tag budget exceeded");
            tags = tags.stream().map(ItemCatalog::requireId).sorted().toList();
            if (tags.stream().distinct().count() != tags.size()) throw new IllegalArgumentException("Duplicate item tag");
        }
    }

    static String requireId(String id) {
        if (id == null || id.length() > 128 || !id.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
            throw new IllegalArgumentException("A complete lowercase namespace:item ID is required (max 128 characters)");
        return id;
    }
}
