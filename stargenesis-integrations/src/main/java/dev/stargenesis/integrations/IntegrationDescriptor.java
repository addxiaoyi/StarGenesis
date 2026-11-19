package dev.stargenesis.integrations;

import java.util.Set;

/** Exact audited versions are candidates for a probe, not a claim of runtime compatibility. */
public record IntegrationDescriptor(String id, String pluginName, Set<String> auditedVersions,
                                    String sourceRevision, Set<String> capabilities) {
    public IntegrationDescriptor {
        id = InstalledPlugin.requireText(id, "id");
        pluginName = InstalledPlugin.requireText(pluginName, "pluginName");
        sourceRevision = InstalledPlugin.requireText(sourceRevision, "sourceRevision");
        auditedVersions = Set.copyOf(auditedVersions);
        capabilities = Set.copyOf(capabilities);
        auditedVersions.forEach(version -> InstalledPlugin.requireText(version, "auditedVersion"));
        capabilities.forEach(capability -> InstalledPlugin.requireText(capability, "capability"));
        if (auditedVersions.isEmpty() || capabilities.isEmpty()) {
            throw new IllegalArgumentException("Descriptor requires audited versions and capabilities");
        }
        if (auditedVersions.size() > 32 || capabilities.size() > 64) {
            throw new IllegalArgumentException("At most 32 versions and 64 capabilities per descriptor");
        }
    }
}
