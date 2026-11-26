package dev.stargenesis.paper.communication;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RadioSendCommandTest {
    private static final UUID REQUESTER = new UUID(0, 301), PROVIDER = new UUID(0, 302);
    @TempDir Path directory;

    @Test void documentedContractSyntaxCreatesAcceptsAndFulfillsADurableContract() throws Exception {
        var store = new RadioPersistence(directory.resolve("radio-state.json"), RadioProtocol.protocol(),
                ServiceContractDefinition.protocol(), ServiceContractDefinition.contract());
        var service = new RadioService(store, 8);
        var endpoint = new VirtualMessageBus.Endpoint("commands", 0, 0, 0, 16);
        var installed = Set.of(ServiceContractDefinition.key());
        service.join(REQUESTER, endpoint, installed);
        service.join(PROVIDER, endpoint, installed);
        send(service, REQUESTER, "send contract " + PROVIDER + " 1 documented offer 3");
        service.advance();
        service.inbox(PROVIDER, 8);
        send(service, PROVIDER, "send contract " + REQUESTER + " 1 documented accept 3");
        service.advance();
        service.inbox(REQUESTER, 8);
        send(service, PROVIDER, "send contract " + REQUESTER + " 2 documented fulfill 3");
        service.advance();
        service.inbox(REQUESTER, 8);
        var restored = new RadioService(store, 8);
        assertEquals(ContractRuntime.State.COMPLETED, restored.contract(REQUESTER, "documented").state());
        assertEquals(3, restored.contract(PROVIDER, "documented").fulfilledUnits());
        assertEquals(3, restored.radioSnapshot().acknowledged().size());
        assertEquals(3, restored.contractSnapshot().received().size());
    }

    @Test void documentedAliasMatchesThePreviouslyAcceptedExplicitPinnedProtocol() {
        var alias = parse("send contract " + PROVIDER + " 7 order offer 3");
        var explicit = parse("send genesis:service " + PROVIDER + " 7 order offer 3");
        assertEquals(explicit, alias);
        assertEquals(ServiceContractDefinition.protocol().metadata().hash(), alias.protocol().metadata().hash());
    }

    @Test void plainRadioSyntaxKeepsTheOriginalWireIdentityAndPayload() {
        var parsed = parse("send " + PROVIDER + " 17 supply ore_offer");
        var codec = new dev.stargenesis.design.ProtocolMessageCodec();
        var message = codec.decode(parsed.protocol(), codec.encode(parsed.protocol(), REQUESTER, parsed.recipient(),
                23, parsed.sequence(), parsed.payload()));
        assertEquals(RadioProtocol.ID, message.protocol());
        assertEquals(RadioProtocol.protocol().metadata().hash(), message.protocolHash());
        assertEquals(PROVIDER, message.recipient());
        assertEquals(17, message.sequence());
        assertEquals(Map.of("sequence", "17", "channel", "supply", "value", "ore_offer"), message.payload());
    }

    @Test void malformedSyntaxUnknownProtocolsAndNoncanonicalRecipientsAreRejected() {
        for (String command : new String[]{"send", "send contract " + PROVIDER + " 1 order offer",
                "send contract " + PROVIDER + " 1 order offer 1 extra",
                "send genesis:missing " + PROVIDER + " 1 order offer 1",
                "send genesis:radio " + PROVIDER + " 1 order offer 1",
                "send contract 0-0-0-0-12e 1 order offer 1",
                "send contract FFFFFFFF-0000-0000-0000-000000000000 1 order offer 1",
                "send contract " + PROVIDER + " 2147483648 order offer 1"})
            assertThrows(IllegalArgumentException.class, () -> parse(command), command);
    }

    @Test void aliasStillEnforcesInstalledPinnedProtocolAndWireValidationBeforeSaving() throws Exception {
        var service = new RadioService(new RadioPersistence(directory.resolve("radio-state.json"), RadioProtocol.protocol(),
                ServiceContractDefinition.protocol(), ServiceContractDefinition.contract()), 8);
        var endpoint = new VirtualMessageBus.Endpoint("commands", 0, 0, 0, 16);
        service.join(REQUESTER, endpoint, Set.of(ServiceContractDefinition.key()));
        service.join(PROVIDER, endpoint, Set.of(RadioProtocol.key()));
        var before = service.radioSnapshot();
        assertThrows(SecurityException.class, () -> send(service, REQUESTER, "send contract " + PROVIDER + " 1 order offer 1"));
        service.join(PROVIDER, endpoint, Set.of(ServiceContractDefinition.key()));
        for (String command : new String[]{"send contract " + PROVIDER + " -1 order offer 1",
                "send contract " + PROVIDER + " 1 order offer nonnumeric",
                "send contract " + PROVIDER + " 1 invalid! offer 1"})
            assertThrows(IllegalArgumentException.class, () -> send(service, REQUESTER, command));
        assertEquals(before, service.radioSnapshot());
        assertFalse(java.nio.file.Files.exists(directory.resolve("radio-state.json")));
        send(service, REQUESTER, "send contract " + PROVIDER + " 1 order offer 1");
        assertEquals(1, service.retainedEvidenceCount());
    }

    private RadioSendCommand parse(String command) { return RadioSendCommand.parse(command.split(" ")); }

    private void send(RadioService service, UUID sender, String command) throws Exception {
        var parsed = parse(command);
        assertTrue(service.send(sender, parsed.protocol(), parsed.recipient(), parsed.sequence(), parsed.payload()).queued());
    }
}
