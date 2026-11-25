package dev.stargenesis.design;

import dev.stargenesis.api.design.DesignDependency;
import dev.stargenesis.api.design.DesignId;
import dev.stargenesis.api.design.DesignMetadata;
import dev.stargenesis.api.design.DesignPermissions;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class ProtocolPayloadCodecTest {
    private static final UUID AUTHOR = new UUID(0, 1);
    private final ProtocolPayloadCodec codec = new ProtocolPayloadCodec();

    @Test void canonicalPayloadUsesSortedFieldsAndValidatesTypedValues() {
        Protocol protocol = protocol(256);
        String payload = codec.encode(protocol, Map.of(
                "units", "7", "route", "-12:18", "asset", "genesis:crystal"));
        assertEquals("5:asset=15:genesis:crystal&5:route=6:-12:18&5:units=1:7", payload);
        assertEquals(Map.of("units", "7", "route", "-12:18", "asset", "genesis:crystal"),
                codec.decode(protocol, payload));
    }

    @Test void requiredUnknownAndIllTypedFieldsAreRejected() {
        Protocol protocol = protocol(256);
        assertThrows(IllegalArgumentException.class, () -> codec.encode(protocol, Map.of("asset", "genesis:crystal")));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(protocol, Map.of(
                "units", "7", "extra", "value")));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(protocol, Map.of(
                "units", "07", "asset", "genesis:crystal")));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(protocol, Map.of(
                "units", "-0", "asset", "genesis:crystal")));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(protocol, Map.of(
                "units", "7", "route", "30001:0", "asset", "genesis:crystal")));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(protocol, Map.of(
                "units", "7", "asset", "../../secret")));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(protocol, Map.of(
                "units", "7", "asset", "genesis:../secret")));
    }

    @Test void payloadByteBudgetIsMeasuredAfterCanonicalEncoding() {
        Protocol protocol = protocol(32);
        assertThrows(IllegalArgumentException.class, () -> codec.encode(protocol, Map.of(
                "units", "7", "asset", "genesis:crystal")));
    }

    @Test void payloadDecoderRejectsMalformedNoncanonicalAndIllTypedValues() {
        Protocol protocol = protocol(256);
        assertThrows(IllegalArgumentException.class, () -> codec.decode(protocol, ""));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(protocol, "05:units=1:7"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(protocol, "5:units=1:7&5:units=1:8"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(protocol, "5:units=2:07"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(protocol, "5:units=1:7&"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(protocol, "5:asset=15:genesis:crystal"));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(protocol,
                "5:units=1:7&5:asset=15:genesis:crystal"));
    }

    private static Protocol protocol(int maximumBytes) {
        DesignMetadata metadata = new DesignMetadata(new DesignId("genesis:test-trade"), 1, "1.0.0", AUTHOR,
                "test:protocol", List.<DesignDependency>of(), "0".repeat(64),
                new DesignPermissions(true, true, Set.of()));
        return new Protocol(metadata, List.of(
                new Protocol.Field("units", Protocol.FieldType.INTEGER, true),
                new Protocol.Field("route", Protocol.FieldType.POSITION, false),
                new Protocol.Field("asset", Protocol.FieldType.RESOURCE_ID, false)), maximumBytes);
    }
}
