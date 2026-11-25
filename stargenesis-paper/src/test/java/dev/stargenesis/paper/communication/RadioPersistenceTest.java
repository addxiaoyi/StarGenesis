package dev.stargenesis.paper.communication;

import dev.stargenesis.design.Contract;
import dev.stargenesis.design.Protocol;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class RadioPersistenceTest {
    private static final UUID REQUESTER = new UUID(0, 81);
    private static final UUID PROVIDER = new UUID(0, 82);
    private final Protocol radioProtocol = RadioProtocol.protocol();
    private final Protocol serviceProtocol = ServiceContractDefinition.protocol();
    private final Contract contract = ServiceContractDefinition.contract();
    private final Set<VirtualMessageBus.ProtocolKey> installed = Set.of(
            RadioProtocol.key(), ServiceContractDefinition.key());
    private final Map<VirtualMessageBus.ProtocolKey, Protocol> protocols = Map.of(
            RadioProtocol.key(), radioProtocol, ServiceContractDefinition.key(), serviceProtocol);

    @Test void pendingOutboxAndContractStateSurviveRestartWithoutDuplicateDelivery(@TempDir Path directory) throws Exception {
        Path stateFile = directory.resolve("radio-state.json");
        RadioPersistence firstStore = store(stateFile);
        VirtualMessageBus firstBus = new VirtualMessageBus(0, 8);
        ContractRuntime firstContracts = runtime(0);
        firstBus.register(REQUESTER, endpoint(0), installed);
        firstBus.register(PROVIDER, endpoint(1), installed);

        assertTrue(firstBus.sendNow(REQUESTER, serviceProtocol, PROVIDER, 7,
                payload("offer", "service_01", 3)).queued());
        firstStore.save(firstBus, firstContracts);

        RadioPersistence.Snapshot firstSnapshot = store(stateFile).load().orElseThrow();
        VirtualMessageBus secondBus = restoredBus(firstSnapshot);
        ContractRuntime secondContracts = restoredContracts(firstSnapshot);
        secondBus.register(PROVIDER, endpoint(1), installed);
        secondBus.advance(1);
        secondContracts.advance(1);
        var offer = secondBus.poll(PROVIDER, 8).getFirst();
        assertTrue(secondContracts.receive(offer));
        assertTrue(secondBus.acknowledge(PROVIDER, offer));
        firstStore.save(secondBus, secondContracts);

        RadioPersistence.Snapshot secondSnapshot = store(stateFile).load().orElseThrow();
        VirtualMessageBus thirdBus = restoredBus(secondSnapshot);
        ContractRuntime thirdContracts = restoredContracts(secondSnapshot);
        thirdBus.register(PROVIDER, endpoint(1), installed);
        thirdBus.register(REQUESTER, endpoint(0), installed);

        assertEquals(ContractRuntime.State.OFFERED, thirdContracts.state("service_01").state());
        assertEquals(0, thirdBus.pendingCount(PROVIDER));
        assertTrue(thirdBus.poll(PROVIDER, 8).isEmpty());
        assertFalse(thirdBus.sendNow(REQUESTER, serviceProtocol, PROVIDER, 7,
                payload("offer", "service_01", 3)).queued());
        assertEquals(2, thirdBus.events().size());
        assertEquals(1, thirdContracts.events().size());
    }

    @Test void snapshotPinsRejectAChangedServiceDefinition(@TempDir Path directory) throws Exception {
        Path stateFile = directory.resolve("radio-state.json");
        RadioPersistence persistence = store(stateFile);
        VirtualMessageBus bus = new VirtualMessageBus(0, 8);
        ContractRuntime contracts = runtime(0);
        persistence.save(bus, contracts);

        String encoded = Files.readString(stateFile);
        String pinnedHash = serviceProtocol.metadata().hash();
        Files.writeString(stateFile, encoded.replace("\"serviceProtocolHash\":\"" + pinnedHash + "\"",
                "\"serviceProtocolHash\":\"" + "0".repeat(64) + "\""));

        assertThrows(java.io.IOException.class, () -> store(stateFile).load());
    }

    private RadioPersistence store(Path file) throws java.io.IOException {
        return new RadioPersistence(file, radioProtocol, serviceProtocol, contract);
    }

    private VirtualMessageBus restoredBus(RadioPersistence.Snapshot snapshot) {
        VirtualMessageBus restored = new VirtualMessageBus(snapshot.radio().tick(), 8);
        restored.restore(snapshot.radio(), protocols);
        return restored;
    }

    private ContractRuntime restoredContracts(RadioPersistence.Snapshot snapshot) {
        return ContractRuntime.restore(contract, serviceProtocol, ContractRuntime.MAX_RETAINED_CONTRACTS,
                snapshot.contracts());
    }

    private ContractRuntime runtime(long tick) {
        return new ContractRuntime(contract, serviceProtocol, tick, ContractRuntime.MAX_RETAINED_CONTRACTS);
    }

    private VirtualMessageBus.Endpoint endpoint(int x) {
        return new VirtualMessageBus.Endpoint("p4_network", x, 0, 0, 16);
    }

    private Map<String, String> payload(String action, String id, long units) {
        return Map.of("contract", id, "action", action, "units", Long.toString(units));
    }
}
