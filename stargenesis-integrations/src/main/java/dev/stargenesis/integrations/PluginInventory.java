package dev.stargenesis.integrations;

import java.util.Optional;

/**
 * Platform adapter reads the plugin manager on its permitted server thread and supplies a snapshot.
 * No class-name reflection, user code execution, or third-party methods belong in discovery.
 */
@FunctionalInterface
public interface PluginInventory {
    Optional<InstalledPlugin> find(String pluginName);
}
