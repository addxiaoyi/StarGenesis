package dev.stargenesis.paper.communication;

import dev.stargenesis.api.design.DesignDependency;
import dev.stargenesis.api.design.DesignId;
import dev.stargenesis.api.design.DesignMetadata;
import dev.stargenesis.api.design.DesignPermissions;
import dev.stargenesis.design.Contract;
import dev.stargenesis.design.DesignCodec;
import dev.stargenesis.design.Protocol;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Built-in pinned service protocol and finite simulation contract used by the P4 player workflow. */
public final class ServiceContractDefinition {
    public static final DesignId PROTOCOL_ID = new DesignId("genesis:service");
    public static final DesignId CONTRACT_ID = new DesignId("genesis:service_contract");
    private static final UUID AUTHOR = UUID.fromString("00000000-0000-0000-0000-000000000044");
    private static final Protocol PROTOCOL;
    private static final Contract CONTRACT;

    static {
        DesignCodec codec = new DesignCodec();
        DesignMetadata protocolMetadata = new DesignMetadata(PROTOCOL_ID, DesignCodec.SCHEMA_VERSION, "1.0.0",
                AUTHOR, "genesis:service-contract", List.of(), "", new DesignPermissions(true, true, Set.of()));
        Protocol unsignedProtocol = new Protocol(protocolMetadata, List.of(
                new Protocol.Field("contract", Protocol.FieldType.TOKEN, true),
                new Protocol.Field("action", Protocol.FieldType.TOKEN, true),
                new Protocol.Field("units", Protocol.FieldType.INTEGER, true)), 256);
        PROTOCOL = (Protocol) codec.load(codec.seal(unsignedProtocol));

        DesignMetadata contractMetadata = new DesignMetadata(CONTRACT_ID, DesignCodec.SCHEMA_VERSION, "1.0.0",
                AUTHOR, "genesis:service-contract", List.of(new DesignDependency(PROTOCOL_ID,
                PROTOCOL.metadata().version(), PROTOCOL.metadata().hash())), "",
                new DesignPermissions(true, true, Set.of()));
        Contract unsignedContract = new Contract(contractMetadata, PROTOCOL_ID, 200, 64);
        CONTRACT = (Contract) codec.load(codec.seal(unsignedContract));
    }

    private ServiceContractDefinition() { }

    public static Protocol protocol() { return PROTOCOL; }
    public static Contract contract() { return CONTRACT; }
    public static VirtualMessageBus.ProtocolKey key() {
        return new VirtualMessageBus.ProtocolKey(PROTOCOL.metadata().id(), PROTOCOL.metadata().hash());
    }
}
