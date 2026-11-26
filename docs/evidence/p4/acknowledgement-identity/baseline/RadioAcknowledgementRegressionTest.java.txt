package dev.stargenesis.paper.communication;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RadioAcknowledgementRegressionTest {
    private static final UUID SENDER = new UUID(0, 801), RECIPIENT = new UUID(0, 802);

    @Test void sharedSenderTickAndSequenceAcrossProtocolsCannotSilentlyAcknowledgeTheWrongMessage(@TempDir Path directory)
            throws Exception {
        Path file = directory.resolve("radio-state.json");
        var store = new RadioPersistence(file, RadioProtocol.protocol(), ServiceContractDefinition.protocol(),
                ServiceContractDefinition.contract());
        var service = new RadioService(store, 8);
        var endpoint = new VirtualMessageBus.Endpoint("ack_regression", 0, 0, 0, 16);
        var protocols = Set.of(RadioProtocol.key(), ServiceContractDefinition.key());
        service.join(SENDER, endpoint, protocols);
        service.join(RECIPIENT, endpoint, protocols);
        service.send(SENDER, RadioProtocol.protocol(), RECIPIENT, 7,
                Map.of("sequence", "7", "channel", "status", "value", "ready"));
        service.send(SENDER, ServiceContractDefinition.protocol(), RECIPIENT, 7,
                Map.of("contract", "ambiguous", "action", "offer", "units", "3"));
        service.advance();
        byte[] saved = Files.readAllBytes(file);
        var before = service.radioSnapshot();
        var pending = service.poll(RECIPIENT, VirtualMessageBus.MAX_UNACKNOWLEDGED_PER_RECIPIENT);
        assertEquals(2, pending.size());
        assertThrows(IllegalArgumentException.class, () -> service.acknowledge(RECIPIENT,
                new RadioAcknowledgement(SENDER, 0, 7, null).select(pending)));
        assertEquals(before, service.radioSnapshot());
        assertArrayEquals(saved, Files.readAllBytes(file));
        assertEquals(2, service.pendingCount(RECIPIENT));
        assertTrue(service.radioSnapshot().acknowledged().isEmpty());
    }
}
