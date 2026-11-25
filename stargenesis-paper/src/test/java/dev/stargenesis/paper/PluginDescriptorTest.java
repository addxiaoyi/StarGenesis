package dev.stargenesis.paper;
import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;
class PluginDescriptorTest {
    @Test void descriptorHasRealEntrypointAndPermissionBoundary() throws IOException {
        String descriptor;
        try (var input = getClass().getResourceAsStream("/plugin.yml")) {
            assertNotNull(input); descriptor = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(descriptor.contains("main: dev.stargenesis.paper.StarGenesisPlugin"));
        assertTrue(descriptor.contains("api-version: '1.21.11'"));
        assertTrue(descriptor.contains("stargenesis.debug:"));
        assertTrue(descriptor.contains("default: op"));
        assertTrue(descriptor.contains("stargenesis.lab:"));
        assertTrue(descriptor.contains("stargenesis.blueprint:"));
        assertTrue(descriptor.contains("assemble"));
        assertTrue(descriptor.contains("stargenesis.program:"));
        assertTrue(descriptor.contains("Edit and run bounded StarGenesis behavior programs"));
        assertTrue(descriptor.contains("default: true"));
        assertTrue(descriptor.contains("stargenesis.protocol:"));
        assertTrue(descriptor.contains("Manage bounded StarGenesis protocol chips and private messages"));
        assertTrue(descriptor.contains("stargenesis.ecosystem:"));
        assertTrue(descriptor.contains("Use private and shared bounded StarGenesis ecologies"));
        assertTrue(descriptor.contains("stargenesis.factory:"));
        assertTrue(descriptor.contains("four-member virtual StarGenesis factory"));
        assertTrue(descriptor.contains("stargenesis.arena:"));
        assertTrue(descriptor.contains("submit reviews, join and play bounded arena matches"));
        assertTrue(descriptor.contains("stargenesis.arena.review:"));
        assertTrue(descriptor.contains("Review and publish community arena rulesets"));
        assertTrue(descriptor.contains("P1-P11 development"));
        assertTrue(descriptor.contains("stargenesis.settlement.review:"));
        assertTrue(descriptor.contains("stargenesis.design.admin:"));
        assertTrue(descriptor.contains("stargenesis.factory:"));
        assertTrue(descriptor.contains("four-member virtual StarGenesis factory"));
        assertTrue(descriptor.contains("P1-P11 development"));
        assertFalse(descriptor.contains("${project.version}"));
    }
    @Test void configFailsClosedForRealSettlement() throws IOException {
        try (var input = getClass().getResourceAsStream("/config.yml")) {
            assertNotNull(input);
            String config = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(config.contains("allow-real-settlement: false"));
            assertTrue(config.contains("extensions:\n    enabled: false\n    providers: []"));
            assertTrue(config.contains("protocol-relay:\n  enabled: false\n  server-id: ''\n  peers: {}"));
            assertTrue(config.contains("factory-relay:\n  enabled: false"));
        }
    }
}
