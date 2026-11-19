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
        assertFalse(descriptor.contains("${project.version}"));
    }
    @Test void configFailsClosedForRealSettlement() throws IOException {
        try (var input = getClass().getResourceAsStream("/config.yml")) {
            assertNotNull(input);
            assertTrue(new String(input.readAllBytes(), StandardCharsets.UTF_8).contains("allow-real-settlement: false"));
        }
    }
}
