package dev.stargenesis.integrations;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class ExtensionGrantTest {
    private static ExtensionGrant grant(String id) {
        return new ExtensionGrant(id, "ExampleAdapter", "1.2.3", "a".repeat(64), Set.of("items.read"));
    }

    @Test
    void grantBuildsAnExactDescriptorAndStartsInactive() {
        ExtensionGrant grant = grant("extension.example");
        IntegrationDescriptor descriptor = grant.descriptor();
        assertEquals(grant.id(), descriptor.id());
        assertEquals(grant.providerPlugin(), descriptor.pluginName());
        assertEquals(Set.of(grant.providerVersion()), descriptor.auditedVersions());
        assertEquals(grant.artifactSha256(), descriptor.sourceRevision());
        assertEquals(grant.capabilities(), descriptor.capabilities());

        try (CapabilityRegistry registry = CapabilityRegistry.withExtensions(List.of(grant))) {
            IntegrationStatus status = registry.inspect(name -> name.equals("ExampleAdapter")
                    ? java.util.Optional.of(new InstalledPlugin(name, "1.2.3", true))
                    : java.util.Optional.empty()).stream()
                    .filter(candidate -> candidate.id().equals(grant.id())).findFirst().orElseThrow();
            assertFalse(status.active());
            assertEquals("ADAPTER_NOT_VERIFIED", status.reason());
        }
    }

    @Test
    void invalidIdsHashesCapabilitiesAndExcessiveGrantsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> grant("starcore"));
        assertThrows(IllegalArgumentException.class, () -> new ExtensionGrant(
                "extension.example", "ExampleAdapter", "1.2.3", "A".repeat(64), Set.of("items.read")));
        assertThrows(IllegalArgumentException.class, () -> new ExtensionGrant(
                "extension.example", "ExampleAdapter", "1.2.3", "a".repeat(64), Set.of("items read")));
        List<ExtensionGrant> tooMany = java.util.stream.IntStream.range(0, 33)
                .mapToObj(index -> grant("extension.provider" + index)).toList();
        assertThrows(IllegalArgumentException.class, () -> CapabilityRegistry.withExtensions(tooMany));
    }

    @Test
    void duplicateExtensionGrantIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> CapabilityRegistry.withExtensions(List.of(grant("extension.example"),
                        new ExtensionGrant("extension.example", "OtherAdapter", "1", "b".repeat(64), Set.of("read")))));
    }
}
