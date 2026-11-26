package dev.stargenesis.cookery;

import net.kaleidoscope.cookery.api.KaleidoscopeCookeryAPI;
import net.kaleidoscope.cookery.recipe.FoodRecipeRegistry;
import net.momirealms.craftengine.bukkit.plugin.BukkitCraftEngine;
import net.momirealms.craftengine.core.plugin.ResourceOperationCoordinator;

/** Typed actual provider calls. Test injection does not replace third-party classes. */
interface CookeryAccess {
    Object identity();
    Object resourceIdentity();
    boolean ready();
    FoodRecipeRegistry registry();
    ResourceOperationCoordinator resources();

    static CookeryAccess current() {
        var plugin = KaleidoscopeCookeryAPI.plugin();
        var registry = KaleidoscopeCookeryAPI.foodRecipes();
        var resources = BukkitCraftEngine.instance();
        if (plugin == null || registry == null || resources == null) throw new IllegalStateException("Cookery API is unavailable");
        return new CookeryAccess() {
            public Object identity() { return plugin; }
            public Object resourceIdentity() { return resources; }
            public FoodRecipeRegistry registry() { return registry; }
            public ResourceOperationCoordinator resources() { return resources.resourceOperations(); }
            public boolean ready() {
                return plugin.isEnabled() && KaleidoscopeCookeryAPI.plugin() == plugin
                        && KaleidoscopeCookeryAPI.foodRecipes() == registry && !registry.isConfigurationLoading()
                        && BukkitCraftEngine.instance() == resources && resources.isFullyLoaded()
                        && !resources.isEnabling() && !resources.isReloading() && !resources.isStopping() && !resources.isDisabled();
            }
        };
    }
}
