package dev.stargenesis.paper;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolRelaySettingsTest {
    @Test
    void peerSecretsAreCopiedAndEachPeerRequiresItsOwnKey() {
        byte[] secret = new byte[32];
        secret[0] = 7;
        ProtocolRelaySettings settings = new ProtocolRelaySettings(true, "hub_a", Map.of("hub_b", secret));
        secret[0] = 8;
        assertEquals(7, settings.secretFor("hub_b")[0]);
        byte[] retrieved = settings.secretFor("hub_b");
        retrieved[0] = 9;
        assertEquals(7, settings.secretFor("hub_b")[0]);
        assertThrows(IllegalArgumentException.class, () -> settings.secretFor("hub_c"));
    }

    @Test
    void invalidPeerNamesKeysAndSelfTrustAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProtocolRelaySettings(true, "hub_a", Map.of("hub_a", new byte[32])));
        assertThrows(IllegalArgumentException.class,
                () -> new ProtocolRelaySettings(true, "hub_a", Map.of("hub_b", new byte[31])));
        assertThrows(IllegalArgumentException.class,
                () -> new ProtocolRelaySettings(true, "hub_a", Map.of("Hub B", new byte[32])));
    }
}
