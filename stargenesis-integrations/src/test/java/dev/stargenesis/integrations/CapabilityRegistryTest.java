package dev.stargenesis.integrations;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CapabilityRegistryTest {
    private static IntegrationDescriptor descriptor(String id) {
        return new IntegrationDescriptor(id, id, Set.of("1.0"), "audited-source-sha", Set.of("read", "write"));
    }

    private static CapabilityRegistry registry() {
        return new CapabilityRegistry(List.of(descriptor("test")));
    }

    private static PluginInventory inventory(String version, boolean enabled) {
        return name -> Optional.of(new InstalledPlugin(name, version, enabled));
    }

    @Test
    void missingPluginHasNoCapabilityAndNoVerification() {
        try (CapabilityRegistry registry = registry()) {
            IntegrationStatus status = registry.inspect(name -> Optional.empty()).getFirst();
            assertFalse(status.installed());
            assertFalse(status.versionCompatible());
            assertFalse(status.apiVerified());
            assertFalse(status.paperVerified());
            assertFalse(status.active());
            assertEquals("NOT_INSTALLED", status.reason());
        }
    }

    @Test
    void matchingInstalledVersionDoesNotActivateAnything() {
        try (CapabilityRegistry registry = registry()) {
            IntegrationStatus status = registry.inspect(inventory("1.0", true)).getFirst();
            assertTrue(status.installed());
            assertTrue(status.enabled());
            assertTrue(status.versionCompatible());
            assertFalse(status.apiVerified());
            assertFalse(status.paperVerified());
            assertFalse(status.active());
            assertEquals("ADAPTER_NOT_VERIFIED", status.reason());
            assertTrue(status.toString().contains("apiVerified=false"));
        }
    }

    @Test
    void disabledOrUnknownVersionNeverExecutesAdapter() {
        try (CapabilityRegistry registry = registry()) {
            AtomicInteger calls = new AtomicInteger();
            registry.registerAdapter("test", plugin -> {
                calls.incrementAndGet();
                return new AdapterVerification(true, true, Set.of("read"), "fixture:test");
            });
            assertEquals("PLUGIN_DISABLED", registry.inspect(inventory("1.0", false)).getFirst().reason());
            assertEquals("VERSION_NOT_AUDITED", registry.inspect(inventory("1.1", true)).getFirst().reason());
            assertEquals(0, calls.get());
        }
    }

    @Test
    void activeCapabilityRequiresExplicitTrustedEvidence() {
        try (CapabilityRegistry registry = registry()) {
            registry.registerAdapter("test", plugin ->
                    new AdapterVerification(true, true, Set.of("read"), "fixture:paper-evidence"));
            IntegrationStatus status = registry.inspect(inventory("1.0", true)).getFirst();
            assertTrue(status.active());
            assertTrue(status.apiVerified());
            assertTrue(status.paperVerified());
            assertEquals("fixture:paper-evidence", status.evidenceId());
            assertTrue(status.capabilities().get("read"));
            assertFalse(status.capabilities().get("write"));
            assertThrows(UnsupportedOperationException.class, () -> status.capabilities().put("other", true));
        }
    }

    @Test
    void apiEvidenceAloneRemainsInactiveAndDistinctFromPaperEvidence() {
        try (CapabilityRegistry registry = registry()) {
            registry.registerAdapter("test", plugin -> new AdapterVerification(true, false, Set.of(), "fixture:abi"));
            IntegrationStatus status = registry.inspect(inventory("1.0", true)).getFirst();
            assertTrue(status.apiVerified());
            assertFalse(status.paperVerified());
            assertFalse(status.active());
        }
    }

    @Test
    void verificationCannotActivateWithoutApiAndPaperEvidence() {
        assertThrows(IllegalArgumentException.class, () -> new AdapterVerification(false, true, Set.of(), "x"));
        assertThrows(IllegalArgumentException.class, () -> new AdapterVerification(true, false, Set.of("read"), "x"));
        assertThrows(IllegalArgumentException.class, () -> new AdapterVerification(true, true, Set.of("read"), " "));
    }

    @Test
    void maliciousOrBuggyAdapterCannotInventCapability() {
        try (CapabilityRegistry registry = registry()) {
            registry.registerAdapter("test", plugin -> new AdapterVerification(true, true, Set.of("command.execute"), "fixture:x"));
            IntegrationStatus status = registry.inspect(inventory("1.0", true)).getFirst();
            assertFalse(status.active());
            assertFalse(status.apiVerified());
            assertEquals("UNDECLARED_CAPABILITY_REJECTED", status.reason());
        }
    }

    @Test
    void adapterFailureIsIsolatedFromOtherProviders() {
        try (CapabilityRegistry registry = new CapabilityRegistry(List.of(descriptor("a"), descriptor("b")))) {
            registry.registerAdapter("a", plugin -> { throw new NoClassDefFoundError("optional dependency"); });
            registry.registerAdapter("b", plugin -> new AdapterVerification(true, true, Set.of("read"), "fixture:b"));
            List<IntegrationStatus> results = registry.inspect(inventory("1.0", true));
            assertEquals("a", results.getFirst().id());
            assertFalse(results.getFirst().active());
            assertEquals("ADAPTER_FAILED:NoClassDefFoundError", results.getFirst().reason());
            assertTrue(results.get(1).active());
        }
    }

    @Test
    void inventoryFailureAndNameMismatchFailClosed() {
        try (CapabilityRegistry registry = registry()) {
            assertEquals("INVENTORY_FAILED:IllegalStateException", registry.inspect(name -> {
                throw new IllegalStateException("do not expose plugin exception message");
            }).getFirst().reason());
            assertEquals("INVENTORY_NAME_MISMATCH", registry.inspect(name ->
                    Optional.of(new InstalledPlugin("wrong", "1.0", true))).getFirst().reason());
        }
    }

    @Test
    void refreshDoesNotReusePreviousSuccessAfterPluginRemovalOrVersionChange() {
        try (CapabilityRegistry registry = registry()) {
            registry.registerAdapter("test", plugin -> new AdapterVerification(true, true, Set.of("read"), "fixture:refresh"));
            assertTrue(registry.inspect(inventory("1.0", true)).getFirst().active());
            assertFalse(registry.inspect(inventory("2.0", true)).getFirst().active());
            assertFalse(registry.inspect(inventory("1.0", false)).getFirst().active());
            assertFalse(registry.inspect(name -> Optional.empty()).getFirst().active());
        }
    }

    @Test
    void duplicateUnknownAndUnboundedDescriptorsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new CapabilityRegistry(List.of(descriptor("x"), descriptor("x"))));
        List<IntegrationDescriptor> many = new ArrayList<>();
        for (int i = 0; i < 129; i++) many.add(descriptor("x" + i));
        assertThrows(IllegalArgumentException.class, () -> new CapabilityRegistry(many));
        try (CapabilityRegistry registry = registry()) {
            assertThrows(IllegalArgumentException.class, () -> registry.registerAdapter("unknown", plugin -> null));
            registry.registerAdapter("test", plugin -> null);
            assertThrows(IllegalStateException.class, () -> registry.registerAdapter("test", plugin -> null));
        }
    }

    @Test
    void cleanupRunsForEveryAdapterAndIsIdempotentEvenAfterFailure() {
        CapabilityRegistry registry = new CapabilityRegistry(List.of(descriptor("a"), descriptor("b")));
        AtomicInteger closed = new AtomicInteger();
        registry.registerAdapter("a", new IntegrationAdapter() {
            public AdapterVerification verify(InstalledPlugin plugin) { return null; }
            public void close() { closed.incrementAndGet(); throw new IllegalStateException("fixture"); }
        });
        registry.registerAdapter("b", new IntegrationAdapter() {
            public AdapterVerification verify(InstalledPlugin plugin) { return null; }
            public void close() { closed.incrementAndGet(); }
        });
        assertThrows(IllegalStateException.class, registry::close);
        assertEquals(2, closed.get());
        registry.close();
        assertEquals(2, closed.get());
        assertThrows(IllegalStateException.class, () -> registry.inspect(inventory("1.0", true)));
    }

    @Test
    void unregisterClosesAdapterAndAllowsAReplacement() {
        try (CapabilityRegistry registry = registry()) {
            AtomicInteger closed = new AtomicInteger();
            registry.registerAdapter("test", new IntegrationAdapter() {
                public AdapterVerification verify(InstalledPlugin plugin) {
                    return new AdapterVerification(true, true, Set.of("read"), "fixture:first");
                }
                public void close() { closed.incrementAndGet(); }
            });
            assertTrue(registry.inspect(inventory("1.0", true)).getFirst().active());
            assertTrue(registry.unregisterAdapter("test"));
            assertFalse(registry.unregisterAdapter("test"));
            assertEquals(1, closed.get());
            assertEquals("ADAPTER_NOT_VERIFIED", registry.inspect(inventory("1.0", true)).getFirst().reason());
            registry.registerAdapter("test", plugin ->
                    new AdapterVerification(true, true, Set.of("read"), "fixture:replacement"));
            assertTrue(registry.inspect(inventory("1.0", true)).getFirst().active());
        }
    }

    @Test
    void auditedDefaultsHaveSourceRevisionsAndAllRemainInactive() {
        try (CapabilityRegistry registry = CapabilityRegistry.auditedDefaults()) {
            List<IntegrationStatus> statuses = registry.inspect(name -> Optional.empty());
            assertEquals(8, statuses.size());
            assertTrue(statuses.stream().noneMatch(IntegrationStatus::active));
            assertTrue(statuses.stream().allMatch(status -> status.sourceRevision().matches("[0-9a-f]{40}")));
            assertTrue(statuses.stream().anyMatch(status -> status.pluginName().equals("helsteraMobs")));
        }
    }

    @Test
    void recursiveProviderAndLifecycleMutationFailWithoutUnboundedRecursion() {
        try (CapabilityRegistry registry = registry()) {
            registry.registerAdapter("test", plugin -> {
                registry.inspect(inventory("1.0", true));
                throw new AssertionError("Recursive inspection should be rejected before returning");
            });
            assertEquals("ADAPTER_FAILED:IllegalStateException", registry.inspect(inventory("1.0", true)).getFirst().reason());
            assertEquals("INVENTORY_FAILED:IllegalStateException", registry.inspect(name -> {
                registry.close();
                return Optional.empty();
            }).getFirst().reason());
            // Reentrancy guards reset even after a failed provider call.
            assertEquals("NOT_INSTALLED", registry.inspect(name -> Optional.empty()).getFirst().reason());
        }
    }

    @Test
    void oversizedAndInvalidMetadataAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> new InstalledPlugin(" ", "1", true));
        assertThrows(IllegalArgumentException.class, () -> new InstalledPlugin("x", "a".repeat(1025), true));
        Set<String> tooMany = java.util.stream.IntStream.range(0, 65).mapToObj(i -> "c" + i).collect(java.util.stream.Collectors.toSet());
        assertThrows(IllegalArgumentException.class, () -> new IntegrationDescriptor("x", "x", Set.of("1"), "source", tooMany));
    }
}
