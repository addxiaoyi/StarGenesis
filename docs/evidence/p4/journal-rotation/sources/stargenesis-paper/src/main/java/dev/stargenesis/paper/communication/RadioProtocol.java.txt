package dev.stargenesis.paper.communication;

import dev.stargenesis.api.design.DesignId;
import dev.stargenesis.api.design.DesignMetadata;
import dev.stargenesis.api.design.DesignPermissions;
import dev.stargenesis.design.DesignCodec;
import dev.stargenesis.design.Protocol;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Shared token radio schema. Each message envelope remains bound to this exact document hash. */
public final class RadioProtocol {
    public static final DesignId ID = new DesignId("genesis:radio");
    private static final UUID AUTHOR = UUID.fromString("00000000-0000-0000-0000-000000000043");
    private static final String SOURCE = "genesis:radio-v1";
    private static final Protocol PROTOCOL = create();

    private RadioProtocol() { }

    public static Protocol protocol() { return PROTOCOL; }

    public static VirtualMessageBus.ProtocolKey key() {
        return new VirtualMessageBus.ProtocolKey(PROTOCOL.metadata().id(), PROTOCOL.metadata().hash());
    }

    private static Protocol create() {
        DesignMetadata metadata = new DesignMetadata(ID, DesignCodec.SCHEMA_VERSION, "1.0.0", AUTHOR, SOURCE,
                List.of(), "", new DesignPermissions(true, true, Set.of()));
        Protocol unsigned = new Protocol(metadata, List.of(
                new Protocol.Field("sequence", Protocol.FieldType.INTEGER, true),
                new Protocol.Field("channel", Protocol.FieldType.TOKEN, true),
                new Protocol.Field("value", Protocol.FieldType.TOKEN, true)), 256);
        DesignCodec designs = new DesignCodec();
        return (Protocol) designs.load(designs.seal(unsigned));
    }
}
