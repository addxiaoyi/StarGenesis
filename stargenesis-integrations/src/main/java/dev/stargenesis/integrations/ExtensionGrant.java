package dev.stargenesis.integrations;

import java.util.Set;
import java.util.regex.Pattern;

/** Exact administrator trust policy for one installed Bukkit integration provider. */
public record ExtensionGrant(String id, String providerPlugin, String providerVersion,
                             String artifactSha256, Set<String> capabilities) {
    private static final Pattern ID = Pattern.compile("extension\\.[a-z0-9][a-z0-9._-]{0,62}");
    private static final Pattern HASH = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern CAPABILITY = Pattern.compile("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*");

    public ExtensionGrant {
        if (id == null || !ID.matcher(id).matches()) {
            throw new IllegalArgumentException("Extension id must use extension.<lowercase-id>");
        }
        providerPlugin = InstalledPlugin.requireText(providerPlugin, "providerPlugin");
        providerVersion = InstalledPlugin.requireText(providerVersion, "providerVersion");
        if (!HASH.matcher(artifactSha256).matches()) {
            throw new IllegalArgumentException("artifactSha256 must be a lowercase SHA-256 digest");
        }
        capabilities = Set.copyOf(capabilities);
        if (capabilities.isEmpty() || capabilities.size() > 64) {
            throw new IllegalArgumentException("An extension grant requires 1-64 capabilities");
        }
        capabilities.forEach(capability -> {
            if (capability == null || capability.length() > 128 || !CAPABILITY.matcher(capability).matches()) {
                throw new IllegalArgumentException("Invalid extension capability: " + capability);
            }
        });
    }

    public IntegrationDescriptor descriptor() {
        return new IntegrationDescriptor(id, providerPlugin, Set.of(providerVersion), artifactSha256, capabilities);
    }
}
