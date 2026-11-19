package dev.stargenesis.integrations;

import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** Installation, audited version, API verification and Paper evidence remain separate dimensions. */
public record IntegrationStatus(String id, String pluginName, boolean installed, boolean enabled,
                                String installedVersion, boolean versionCompatible,
                                boolean apiVerified, boolean paperVerified,
                                Map<String, Boolean> capabilities, String reason,
                                String sourceRevision, String evidenceId) {
    public IntegrationStatus {
        capabilities = Collections.unmodifiableMap(new TreeMap<>(capabilities));
    }

    public boolean active() {
        return capabilities.values().stream().anyMatch(Boolean.TRUE::equals);
    }
}
