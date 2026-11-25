package dev.stargenesis.design;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.stargenesis.api.design.DesignId;
import dev.stargenesis.api.design.DesignKind;
import dev.stargenesis.api.design.DesignMetadata;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolMessageCodecTest {
    private final ProtocolMessageCodec codec = new ProtocolMessageCodec();
    private final DesignCodec designCodec = new DesignCodec();
    private final ObjectMapper json = new ObjectMapper();
    private final UUID sender = UUID.fromString("00000000-0000-0000-0000-000000000041");
    private final UUID recipient = UUID.fromString("00000000-0000-0000-0000-000000000042");

    @Test void typedMessageRoundTripsAgainstThePinnedProtocolIdentity() throws IOException {
        Protocol protocol = protocol(512, List.of(
                new Protocol.Field("accepted", Protocol.FieldType.BOOLEAN, true),
                new Protocol.Field("units", Protocol.FieldType.INTEGER, true),
                new Protocol.Field("position", Protocol.FieldType.POSITION, false),
                new Protocol.Field("resource", Protocol.FieldType.RESOURCE_ID, false),
                new Protocol.Field("tag", Protocol.FieldType.TOKEN, false)));
        Map<String, String> payload = Map.of("accepted", "true", "units", "12", "position", "4,2,1",
                "resource", "ore", "tag", "supply_offer");

        var decoded = codec.decode(protocol, codec.encode(protocol, sender, recipient, 71, 3, payload));

        assertEquals(protocol.metadata().id(), decoded.protocol());
        assertEquals(protocol.metadata().hash(), decoded.protocolHash());
        assertEquals(sender, decoded.sender());
        assertEquals(recipient, decoded.recipient());
        assertEquals(71, decoded.tick());
        assertEquals(3, decoded.sequence());
        assertEquals(payload, decoded.payload());
    }

    @Test void requiredTypesAndProtocolFieldsAreValidatedBeforeEncoding() throws IOException {
        Protocol protocol = protocol(256, List.of(
                new Protocol.Field("sequence", Protocol.FieldType.INTEGER, true),
                new Protocol.Field("ready", Protocol.FieldType.BOOLEAN, false)));

        assertThrows(IllegalArgumentException.class, () -> codec.encode(protocol, sender, recipient, 0, 0, Map.of()));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(protocol, sender, recipient, 0, 0,
                Map.of("sequence", "1", "ready", "yes")));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(protocol, sender, recipient, 0, 0,
                Map.of("sequence", "1", "unknown", "2")));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(protocol, sender, recipient, -1, 0,
                Map.of("sequence", "1")));
    }

    @Test void messageDecoderRejectsUnknownOrDuplicateEnvelopeFieldsAndWrongProtocolHash() throws IOException {
        Protocol protocol = protocol(256, List.of(new Protocol.Field("sequence", Protocol.FieldType.INTEGER, true)));
        String encoded = codec.encode(protocol, sender, recipient, 1, 0, Map.of("sequence", "9"));
        ObjectNode unknown = (ObjectNode) json.readTree(encoded);
        unknown.put("executeCommand", "op someone");
        assertThrows(IllegalArgumentException.class, () -> codec.decode(protocol, unknown.toString()));
        assertThrows(IllegalArgumentException.class, () -> codec.decode(protocol,
                encoded.replace("\"protocol\":", "\"protocol\":\"other:protocol\",\"protocol\":")));

        Protocol other = protocol(256, List.of(
                new Protocol.Field("sequence", Protocol.FieldType.INTEGER, true),
                new Protocol.Field("ready", Protocol.FieldType.BOOLEAN, false)));
        assertEquals(protocol.metadata().id(), other.metadata().id());
        assertNotEquals(protocol.metadata().hash(), other.metadata().hash());
        assertThrows(IllegalArgumentException.class, () -> codec.decode(other, encoded));
    }

    @Test void maximumPayloadBytesApplyToCanonicalPayloadOnly() throws IOException {
        Protocol protocol = protocol(16, List.of(new Protocol.Field("tag", Protocol.FieldType.TOKEN, true)));
        assertThrows(IllegalArgumentException.class, () -> codec.encode(protocol, sender, recipient, 0, 0,
                Map.of("tag", "x".repeat(63))));

        Protocol enough = protocol(128, List.of(new Protocol.Field("tag", Protocol.FieldType.TOKEN, true)));
        assertDoesNotThrow(() -> codec.encode(enough, sender, recipient, 0, 0, Map.of("tag", "chip")));
    }

    private Protocol protocol(int maxPayloadBytes, List<Protocol.Field> fields) throws IOException {
        Protocol template;
        try (var input = getClass().getResourceAsStream("/examples/protocol.json")) {
            assertNotNull(input);
            template = (Protocol) designCodec.load(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
        DesignMetadata metadata = template.metadata();
        DesignMetadata unsignedMetadata = new DesignMetadata(new DesignId("genesis:protocol"), metadata.schemaVersion(),
                metadata.version(), metadata.author(), metadata.source(), metadata.dependencies(), "", metadata.permissions());
        Protocol unsigned = new Protocol(unsignedMetadata, fields, maxPayloadBytes);
        return (Protocol) designCodec.load(designCodec.seal(unsigned));
    }
}
