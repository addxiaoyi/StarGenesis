package dev.stargenesis.paper.communication;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RadioAcknowledgementTest {
    private static final UUID SENDER = new UUID(0, 811), RECIPIENT = new UUID(0, 812), OTHER = new UUID(0, 813);
    private static final Set<VirtualMessageBus.ProtocolKey> BOTH = Set.of(RadioProtocol.key(), ServiceContractDefinition.key());
    private static final VirtualMessageBus.Endpoint ENDPOINT = new VirtualMessageBus.Endpoint("ack_test", 0, 0, 0, 16);
    @TempDir Path directory;

    @Test void explicitRadioAcknowledgementLeavesTheContractForTransactionalInboxProcessing() throws Exception {
        var service = collision(false, 3);
        assertTrue(ack(service, "genesis:radio"));
        assertEquals(1, service.pendingCount(RECIPIENT));
        assertEquals(ServiceContractDefinition.PROTOCOL_ID, service.poll(RECIPIENT, 64).getFirst().protocol());
        assertTrue(service.contractSnapshot().received().isEmpty());
        service.inbox(RECIPIENT, 64);
        assertEquals(ContractRuntime.State.OFFERED, service.contract(SENDER, "collision").state());
        assertEquals(0, service.pendingCount(RECIPIENT));
        assertEquals(2, service.radioSnapshot().acknowledged().size());
        var restored = service();
        assertEquals(service.radioSnapshot(), restored.radioSnapshot());
        assertEquals(service.contractSnapshot(), restored.contractSnapshot());
    }

    @Test void explicitContractAcknowledgementCanDiscardAnInvalidContractWithoutRemovingOrdinaryRadio() throws Exception {
        var service = collision(true, 65);
        assertTrue(service.inbox(RECIPIENT, 64).stream().anyMatch(line -> line.contains("retained for review")));
        assertTrue(ack(service, "contract"));
        assertEquals(1, service.pendingCount(RECIPIENT));
        assertEquals(RadioProtocol.ID, service.poll(RECIPIENT, 64).getFirst().protocol());
        assertTrue(service.contractSnapshot().states().isEmpty());
        assertTrue(ack(service, ""));
        assertEquals(0, service.pendingCount(RECIPIENT));
    }

    @Test void ambiguousLegacySelectionRemainsRejectedAfterDiskReloadAndEitherSendOrder() throws Exception {
        for (boolean contractFirst : new boolean[]{false, true}) {
            Path isolated = directory.resolve(Boolean.toString(contractFirst));
            var service = service(isolated.resolve("radio.json"));
            join(service);
            sendCollision(service, contractFirst, 3);
            service.advance();
            service.close();
            var restored = service(isolated.resolve("radio.json"));
            join(restored);
            var before = restored.radioSnapshot();
            byte[] bytes = Files.readAllBytes(isolated.resolve("radio.json"));
            var error = assertThrows(IllegalArgumentException.class, () -> ack(restored, ""));
            assertTrue(error.getMessage().contains("genesis:radio"));
            assertEquals(before, restored.radioSnapshot());
            assertArrayEquals(bytes, Files.readAllBytes(isolated.resolve("radio.json")));
        }
    }

    @Test void selectedAcknowledgementSaveFailureRetainsBothMessagesAndRetriesOnce() throws Exception {
        var service = collision(false, 3);
        var before = service.radioSnapshot();
        byte[] bytes = Files.readAllBytes(file());
        Path blocker = file().resolveSibling("radio.json.tmp");
        Files.createDirectory(blocker);
        assertThrows(IOException.class, () -> ack(service, "genesis:radio"));
        assertEquals(before, service.radioSnapshot());
        assertArrayEquals(bytes, Files.readAllBytes(file()));
        Files.delete(blocker);
        assertTrue(ack(service, "genesis:radio"));
        var committed = service.radioSnapshot();
        assertThrows(IllegalArgumentException.class, () -> ack(service, "genesis:radio"));
        assertEquals(committed, service.radioSnapshot());
        assertEquals(1, service.pendingCount(RECIPIENT));
    }

    @Test void acknowledgementCannotSelectAnUndeliveredMessageOrAnotherRecipient() throws Exception {
        var service = service();
        join(service);
        service.join(OTHER, ENDPOINT, BOTH);
        sendCollision(service, false, 3);
        var before = service.radioSnapshot();
        assertThrows(IllegalArgumentException.class, () -> ack(service, "genesis:radio"));
        service.advance();
        assertThrows(IllegalArgumentException.class,
                () -> service.acknowledge(OTHER, parse("genesis:radio")));
        assertEquals(before.evidence(), service.radioSnapshot().evidence());
        assertTrue(service.radioSnapshot().acknowledged().isEmpty());
        assertEquals(2, service.pendingCount(RECIPIENT));
    }

    @Test void currentInstalledProtocolStillControlsWhichEnvelopeMayBeAcknowledged() throws Exception {
        var service = collision(false, 3);
        service.join(RECIPIENT, ENDPOINT, Set.of(ServiceContractDefinition.key()));
        var before = service.radioSnapshot();
        assertThrows(IllegalArgumentException.class, () -> ack(service, "genesis:radio"));
        assertEquals(before, service.radioSnapshot());
        // With only one installed protocol, the shorter form is unambiguous among accessible messages.
        assertTrue(ack(service, ""));
        assertEquals(1, service.pendingCount(RECIPIENT));
        service.join(RECIPIENT, ENDPOINT, BOTH);
        assertTrue(ack(service, ""));
    }

    @Test void distinctTicksKeepTheShortFormCompatibleAcrossProtocols() throws Exception {
        var service = service();
        join(service);
        sendRadio(service);
        service.advance();
        sendContract(service, 3);
        service.advance();
        assertTrue(ack(service, ""));
        assertEquals(1, service.pendingCount(RECIPIENT));
        assertEquals(ServiceContractDefinition.PROTOCOL_ID, service.poll(RECIPIENT, 64).getFirst().protocol());
        assertTrue(service.acknowledge(RECIPIENT, RadioAcknowledgement.parse(("ack " + SENDER + " 1 7").split(" "))));
    }

    @Test void malformedAndUnknownSelectorsCannotMutateThePersistedMailbox() throws Exception {
        var service = collision(false, 3);
        byte[] bytes = Files.readAllBytes(file());
        var before = service.radioSnapshot();
        for (String command : new String[]{"ack", "ack " + SENDER + " 0 7 genesis:missing",
                "ack " + SENDER + " -1 7", "ack " + SENDER + " 0 -1", "ack " + SENDER + " 0 2147483648",
                "ack " + SENDER + " 9223372036854775808 7", "ack 0-0-0-0-32b 0 7",
                "ack FFFFFFFF-0000-0000-0000-000000000000 0 7", "ack " + SENDER + " 0 7 genesis:radio extra"})
            assertThrows(IllegalArgumentException.class, () -> service.acknowledge(RECIPIENT,
                    RadioAcknowledgement.parse(command.split(" "))), command);
        assertEquals(before, service.radioSnapshot());
        assertArrayEquals(bytes, Files.readAllBytes(file()));
    }

    private Path file() { return directory.resolve("radio.json"); }
    private RadioService service() throws IOException { return service(file()); }
    private RadioService service(Path file) throws IOException {
        return new RadioService(new RadioPersistence(file, RadioProtocol.protocol(), ServiceContractDefinition.protocol(),
                ServiceContractDefinition.contract()), 8);
    }
    private void join(RadioService service) {
        service.join(SENDER, ENDPOINT, BOTH);
        service.join(RECIPIENT, ENDPOINT, BOTH);
    }
    private RadioService collision(boolean contractFirst, int units) throws IOException {
        var service = service();
        join(service);
        sendCollision(service, contractFirst, units);
        service.advance();
        return service;
    }
    private void sendCollision(RadioService service, boolean contractFirst, int units) throws IOException {
        if (contractFirst) sendContract(service, units);
        sendRadio(service);
        if (!contractFirst) sendContract(service, units);
    }
    private void sendRadio(RadioService service) throws IOException {
        service.send(SENDER, RadioProtocol.protocol(), RECIPIENT, 7,
                Map.of("sequence", "7", "channel", "status", "value", "ready"));
    }
    private void sendContract(RadioService service, int units) throws IOException {
        service.send(SENDER, ServiceContractDefinition.protocol(), RECIPIENT, 7,
                Map.of("contract", "collision", "action", "offer", "units", Integer.toString(units)));
    }
    private RadioAcknowledgement parse(String protocol) {
        return RadioAcknowledgement.parse(("ack " + SENDER + " 0 7" + (protocol.isEmpty() ? "" : " " + protocol)).split(" "));
    }
    private boolean ack(RadioService service, String protocol) throws IOException {
        return service.acknowledge(RECIPIENT, parse(protocol));
    }
}
