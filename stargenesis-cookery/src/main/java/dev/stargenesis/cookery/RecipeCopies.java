package dev.stargenesis.cookery;

import dev.stargenesis.integrations.ItemCatalog;
import dev.stargenesis.integrations.RecipeCatalog;
import net.kaleidoscope.cookery.recipe.*;
import net.momirealms.craftengine.core.util.Key;
import java.util.*;
import java.util.function.Function;

/** Reads only public record accessors; never plans, matches, rolls or builds an output item. */
final class RecipeCopies {
    private RecipeCopies() {}
    static ApplianceType appliance(RecipeCatalog.Appliance value) { return ApplianceType.valueOf(value.name()); }

    private static List<?> records(FoodRecipeRegistry registry, RecipeCatalog.Appliance appliance, RecipeCatalog.Family family) {
        return switch (family) {
            case ACCURATE -> registry.accurateRecipes(appliance(appliance));
            case FLEX -> registry.flexRecipes(appliance(appliance));
            case POT -> registry.potRecipes();
            case STOCKPOT -> registry.stockpotRecipes();
            case CHOPPING -> registry.choppingRecipes();
            case TEAPOT -> registry.teapotRecipes();
            case BAMBOO_TRAY -> registry.bambooTrayRecipes();
        };
    }
    private static String id(Object recipe) {
        return key(switch (recipe) {
            case AccurateFoodRecipe r -> r.id();
            case FlexFoodRecipe r -> r.id();
            case PotRecipe r -> r.id();
            case StockpotRecipe r -> r.id();
            case ChoppingBoardRecipe r -> r.id();
            case TeapotRecipe r -> r.id();
            case BambooTrayRecipe r -> r.id();
            default -> throw new IllegalStateException("Unsupported recipe family");
        });
    }
    static List<String> ids(FoodRecipeRegistry registry, RecipeCatalog.Appliance appliance, RecipeCatalog.Family family) {
        var records = records(registry, appliance, family);
        if (records.size() > RecipeCatalog.MAX_RECIPES) throw new IllegalStateException("Recipe count budget exceeded");
        var ids = new TreeSet<String>();
        for (var record : records) {
            if (!ids.add(id(record))) throw new IllegalStateException("Ambiguous recipe identity within appliance and family");
        }
        return List.copyOf(ids);
    }
    static Optional<RecipeCatalog.Definition> inspect(FoodRecipeRegistry registry, RecipeCatalog.Key key) {
        var records = records(registry, key.appliance(), key.family());
        Object found = null;
        for (var record : records) {
            if (id(record).equals(key.id())) {
                if (found != null) throw new IllegalStateException("Ambiguous recipe identity within appliance and family");
                found = record;
            }
        }
        return found == null ? Optional.empty() : Optional.of(new RecipeCatalog.Definition(key, fields(found)));
    }
    private static Map<String, Object> fields(Object recipe) {
        return switch (recipe) {
            case AccurateFoodRecipe r -> fields("input", key(r.input()), "results", list(r.results(),
                    v -> fields("key", key(v.key()), "weight", v.weight())), "cook", r.cook().name(),
                    "rotations", r.rotations(), "resultCount", r.resultCount(), "lore", list(r.lore(), Function.identity()),
                    "cookingTime", r.cookingTime());
            case FlexFoodRecipe r -> fields("result", key(r.result()), "cook", r.cook().name(), "perfect", weights(r.perfect()),
                    "liquids", list(r.liquids(), RecipeCopies::key), "carrier", nullableKey(r.carrier()),
                    "useEquivalentFoods", r.useEquivalentFoods(), "useSeasonings", r.useSeasonings(),
                    "norm", r.norm(), "totalWeight", r.totalWeight(), "cookingTime", r.cookingTime(), "stirFryCount", r.stirFryCount());
            case PotRecipe r -> fields("mode", r.mode().name(), "ingredients", ingredients(r.ingredients()), "result", key(r.result()),
                    "resultCount", r.resultCount(), "stirFryCount", r.stirFryCount(), "sourceTime", r.sourceTime(), "carrier", nullableKey(r.carrier()));
            case StockpotRecipe r -> fields("mode", r.mode().name(), "ingredients", ingredients(r.ingredients()), "soupBase", key(r.soupBase()),
                    "result", key(r.result()), "resultCount", r.resultCount(), "time", r.time(), "carrier", nullableKey(r.carrier()),
                    "visuals", fields("cookingModel", key(r.visuals().cookingModel()), "finishedModel", key(r.visuals().finishedModel()),
                            "cookingColor", r.visuals().cookingColor(), "finishedColor", r.visuals().finishedColor()));
            case ChoppingBoardRecipe r -> fields("input", key(r.input()), "stage", r.stage(),
                    "values", list(r.values(), Function.identity()), "mode", r.mode().name(),
                    "results", list(r.results(), RecipeCopies::chopping), "extras", list(r.extras(), RecipeCopies::chopping));
            case TeapotRecipe r -> fields("fluid", key(r.fluid()), "input", key(r.input()), "ingredientCount", r.ingredientCount(),
                    "result", key(r.result()), "resultCount", r.resultCount(), "time", r.time());
            case BambooTrayRecipe r -> fields("input", (r.tagInput() ? "#" : "") + key(r.inputKey()),
                    "result", key(r.result()), "resultCount", r.resultCount(), "mode", r.mode().name(), "durationTicks", r.durationTicks());
            default -> throw new IllegalStateException("Unsupported recipe family");
        };
    }
    private static Map<String, Object> chopping(ChoppingResult value) {
        return fields("key", key(value.key()), "count", value.count(), "weight", value.weight());
    }
    private static String key(Key value) { return ItemCatalog.requireId(Objects.requireNonNull(value).asString()); }
    private static String nullableKey(Key value) { return value == null ? null : key(value); }
    private static <T, R> List<R> list(Collection<T> values, Function<T, R> copy) {
        if (values.size() > RecipeCatalog.MAX_MEMBERS) throw new IllegalArgumentException("Recipe field member budget exceeded");
        return values.stream().map(copy).toList();
    }
    private static List<List<String>> ingredients(List<Set<Key>> slots) {
        return list(slots, alternatives -> list(alternatives, RecipeCopies::key).stream().sorted().toList());
    }
    private static Map<String, Object> weights(Map<Key, Integer> values) {
        if (values.size() > RecipeCatalog.MAX_MEMBERS) throw new IllegalArgumentException("Recipe ingredient budget exceeded");
        Map<String, Object> result = new TreeMap<>();
        values.forEach((key, weight) -> result.put(key(key), Objects.requireNonNull(weight)));
        return result;
    }
    private static Map<String, Object> fields(Object... entries) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) result.put((String) entries[i], entries[i + 1]);
        return result;
    }
}
