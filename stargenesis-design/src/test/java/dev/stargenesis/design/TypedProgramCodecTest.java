package dev.stargenesis.design;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TypedProgramCodecTest {
    private final TypedProgramCodec codec = new TypedProgramCodec();
    private final ObjectMapper json = new ObjectMapper();
    private final UUID author = UUID.fromString("00000000-0000-0000-0000-000000000021");

    private ProgramIR program() {
        return new ProgramIR(List.of(
                new ProgramIR.Instruction("value", PrimitiveCatalog.Operation.CONSTANT_INTEGER, List.of(), Map.of("value", "3")),
                new ProgramIR.Instruction("wait", PrimitiveCatalog.Operation.WAIT, List.of(), Map.of())), 64);
    }

    @Test void typedProgramSealsLoadsAndRoundTripsWithStableHash() {
        String encoded = codec.seal(new dev.stargenesis.api.design.DesignId("player:typed"), author, program());
        TypedProgramDocument loaded = codec.load(encoded);

        assertEquals("player:typed", loaded.id().value());
        assertEquals(author, loaded.author());
        assertEquals(program(), loaded.program());
        assertEquals(encoded, codec.write(loaded));
    }

    @Test void typedProgramSchemaValidatesDocumentAndRejectsUnknownFields() throws Exception {
        String encoded = codec.seal(new dev.stargenesis.api.design.DesignId("player:typed"), author, program());
        var schemaJson = resource("/schemas/typed_program.schema.json");
        var schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(json.readTree(schemaJson));
        assertTrue(schema.validate(json.readTree(encoded)).isEmpty());

        ObjectNode malformed = (ObjectNode) json.readTree(encoded);
        malformed.put("executeCommand", "op someone");
        assertFalse(schema.validate(malformed).isEmpty());
        assertThrows(DesignValidationException.class, () -> codec.load(malformed.toString()));
    }

    @Test void hashTamperingDuplicateKeysAndUnsignedDocumentsAreRejected() throws Exception {
        String encoded = codec.seal(new dev.stargenesis.api.design.DesignId("player:typed"), author, program());
        ObjectNode tampered = (ObjectNode) json.readTree(encoded);
        ((ObjectNode) tampered.path("instructions").get(0).path("parameters")).put("value", "4");
        assertThrows(DesignValidationException.class, () -> codec.load(tampered.toString()));

        String duplicate = encoded.replace("\"hash\":", "\"hash\":\"" + "0".repeat(64) + "\",\"hash\":");
        assertThrows(DesignValidationException.class, () -> codec.load(duplicate));

        var unsigned = new TypedProgramDocument(new dev.stargenesis.api.design.DesignId("player:typed"), author,
                TypedProgramCodec.SCHEMA_VERSION, "", program());
        assertThrows(DesignValidationException.class, () -> codec.write(unsigned));
    }

    @Test void unknownSchemaVersionsAndInvalidDocumentIdentityFailClosed() {
        String encoded = codec.seal(new dev.stargenesis.api.design.DesignId("player:typed"), author, program());
        assertThrows(DesignValidationException.class, () -> codec.load(encoded.replace("\"schemaVersion\":1", "\"schemaVersion\":2")));
        assertThrows(IllegalArgumentException.class, () -> new TypedProgramDocument(
                new dev.stargenesis.api.design.DesignId("player:typed"), author, 2, "", program()));
    }

    private String resource(String name) throws IOException {
        try (var input = getClass().getResourceAsStream(name)) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
