package dev.stargenesis.design;

import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.networknt.schema.*;
import dev.stargenesis.api.design.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import static org.junit.jupiter.api.Assertions.*;

class DesignCodecTest {
    private final DesignCodec codec = new DesignCodec();
    private final ObjectMapper json = new ObjectMapper();

    private String resource(String name) throws IOException {
        try (InputStream input = getClass().getResourceAsStream(name)) {
            assertNotNull(input, name); return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
    private String fixture(DesignKind kind) throws IOException { return resource("/examples/"+kind.name().toLowerCase(Locale.ROOT)+".json"); }
    private ObjectNode unsigned(DesignKind kind) throws IOException {
        ObjectNode node = (ObjectNode) json.readTree(fixture(kind));
        ((ObjectNode) node.path("metadata")).put("hash", ""); return node;
    }
    private void rejects(DesignKind kind, Consumer<ObjectNode> change) throws IOException {
        ObjectNode root = unsigned(kind); change.accept(root);
        assertThrows(DesignValidationException.class, () -> codec.seal(root.toString()));
    }

    @ParameterizedTest @EnumSource(DesignKind.class)
    void signedExamplesLoadAndRoundTrip(DesignKind kind) throws IOException {
        DesignDocument design = codec.load(fixture(kind));
        assertEquals(kind, design.kind()); assertEquals(1, design.metadata().schemaVersion());
        assertEquals(design, codec.load(codec.write(design)));
    }

    @ParameterizedTest @EnumSource(DesignKind.class)
    void schemasLoadValidateExamplesAndRejectWrongKind(DesignKind kind) throws IOException {
        JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(json.readTree(resource("/schemas/"+kind.name().toLowerCase(Locale.ROOT)+".schema.json")));
        ObjectNode example = (ObjectNode) json.readTree(fixture(kind));
        assertTrue(schema.validate(example).isEmpty(), () -> schema.validate(example).toString());
        example.put("kind", "COMMAND"); assertFalse(schema.validate(example).isEmpty());
    }

    @ParameterizedTest @EnumSource(DesignKind.class)
    void newDocumentsCanBeSealedAndHashesAreStable(DesignKind kind) throws IOException {
        String sealed = codec.seal(unsigned(kind).toString());
        assertEquals(codec.load(fixture(kind)), codec.load(sealed));
        assertEquals(sealed, codec.write(codec.load(sealed)));
    }

    @ParameterizedTest @EnumSource(DesignKind.class)
    void unknownFieldsAndMissingMetadataAreRejected(DesignKind kind) throws IOException {
        rejects(kind, root -> root.put("executeCommand", "op someone"));
        rejects(kind, root -> root.remove("metadata"));
        rejects(kind, root -> ((ObjectNode) root.path("metadata")).put("schemaVersion", 2));
    }

    @Test void hashTamperingAndSilentResigningAreRejected() throws IOException {
        String signed = fixture(DesignKind.ARENA_RULESET);
        assertThrows(DesignValidationException.class, () -> codec.load(signed.replace("\"width\": 16", "\"width\": 17")));
        assertThrows(DesignValidationException.class, () -> codec.seal(signed));
        assertThrows(DesignValidationException.class, () -> codec.load(unsigned(DesignKind.BLUEPRINT).toString()));
    }

    @Test void immutableNewDocumentCanBeSealedWithoutHandBuildingJson() throws IOException {
        Blueprint source = (Blueprint) codec.load(fixture(DesignKind.BLUEPRINT));
        DesignMetadata metadata = new DesignMetadata(source.metadata().id(), source.metadata().schemaVersion(),
                source.metadata().version(), source.metadata().author(), source.metadata().source(),
                source.metadata().dependencies(), "", source.metadata().permissions());
        Blueprint unsigned = new Blueprint(metadata, source.components(), source.connections());
        assertEquals(source, codec.load(codec.seal(unsigned)));
        assertThrows(DesignValidationException.class, () -> codec.seal(source));
    }

    @Test void jsonOrderWhitespaceAndEditorSetOrderDoNotChangeHash() throws IOException {
        ObjectNode source = unsigned(DesignKind.BLUEPRINT);
        ArrayNode editors = (ArrayNode) source.path("metadata").path("permissions").path("editors");
        editors.add("00000000-0000-0000-0000-000000000003"); editors.add("00000000-0000-0000-0000-000000000002");
        String first = codec.seal(source.toPrettyString());
        editors.removeAll(); editors.add("00000000-0000-0000-0000-000000000002"); editors.add("00000000-0000-0000-0000-000000000003");
        assertEquals(first, codec.seal(source.toString()));
    }

    @Test void malformedOverdeepOversizedAndDuplicateJsonAreRejected() {
        for (String bad : List.of("", "[]", "null", "{}{}", "{\"kind\":\"BLUEPRINT\",\"kind\":\"PROGRAM\"}",
                "[".repeat(40)+"0"+"]".repeat(40), " ".repeat(DesignCodec.MAX_DOCUMENT_BYTES+1))) {
            assertThrows(DesignValidationException.class, () -> codec.load(bad));
        }
    }

    @Test void utf8BudgetIsNotOnlyACharacterBudget() {
        String oversized = "\""+"界".repeat(100_000)+"\"";
        assertTrue(oversized.length() < DesignCodec.MAX_DOCUMENT_BYTES);
        assertThrows(DesignValidationException.class, () -> codec.load(oversized));
    }

    @ParameterizedTest @ValueSource(strings={"../escape", "genesis:../escape", "genesis:trailing/", "A:bad"})
    void pathLikeOrUnboundedIdsAreRejected(String id) throws IOException {
        rejects(DesignKind.BLUEPRINT, root -> ((ObjectNode) root.path("metadata").path("id")).put("value", id));
    }

    @Test void metadataAndPermissionCoercionIsRejected() throws IOException {
        rejects(DesignKind.BLUEPRINT, root -> ((ObjectNode) root.path("metadata")).put("author", "1-1-1-1-1"));
        rejects(DesignKind.BLUEPRINT, root -> ((ObjectNode) root.path("metadata")).put("source", "   "));
        rejects(DesignKind.BLUEPRINT, root -> ((ObjectNode) root.path("metadata")).put("version", "1.0"));
        rejects(DesignKind.BLUEPRINT, root -> ((ObjectNode) root.path("metadata").path("permissions")).put("publicRead", "true"));
        rejects(DesignKind.BLUEPRINT, root -> {
            ArrayNode editors = (ArrayNode) root.path("metadata").path("permissions").path("editors");
            editors.add("00000000-0000-0000-0000-000000000001"); editors.add("00000000-0000-0000-0000-000000000001");
        });
    }

    @Test void blueprintRejectsOverlapDisconnectedAndUnknownConnections() throws IOException {
        rejects(DesignKind.BLUEPRINT, root -> {
            ArrayNode components = (ArrayNode) root.path("components");
            ObjectNode duplicate = components.get(0).deepCopy(); duplicate.put("id", "second"); components.add(duplicate);
        });
        rejects(DesignKind.BLUEPRINT, root -> {
            ArrayNode components = (ArrayNode) root.path("components");
            ObjectNode second = components.get(0).deepCopy(); second.put("id", "second"); second.put("x", 1); components.add(second);
        });
        rejects(DesignKind.BLUEPRINT, root -> ((ArrayNode)root.path("connections")).addObject().put("from", "core").put("to", "missing"));
        rejects(DesignKind.BLUEPRINT, root -> ((ObjectNode)root.path("components").get(0)).put("rotation", 4));
    }

    @Test void programRejectsArbitraryCodeCyclesAndTypeMismatch() throws IOException {
        rejects(DesignKind.PROGRAM, root -> ((ObjectNode)root.path("nodes").get(0)).put("operation", "RUN_JAVA"));
        rejects(DesignKind.PROGRAM, root -> ((ArrayNode)root.path("edges")).addObject().put("from", "wait").put("to", "wait"));
        rejects(DesignKind.PROGRAM, root -> ((ObjectNode)root.path("nodes").get(0)).put("outputType", "BOOLEAN"));
        rejects(DesignKind.PROGRAM, root -> root.put("maxInstructionsPerStep", Integer.MAX_VALUE));
        rejects(DesignKind.PROGRAM, root -> {
            ArrayNode nodes = (ArrayNode)root.path("nodes");
            ObjectNode second = nodes.get(0).deepCopy(); second.put("id", "compare"); second.put("operation", "COMPARE");
            second.put("inputType", "INTEGER"); second.put("outputType", "BOOLEAN"); nodes.add(second);
            ((ArrayNode)root.path("edges")).addObject().put("from", "wait").put("to", "compare");
        });
    }

    @Test void protocolAndContractBoundsAreEnforced() throws IOException {
        rejects(DesignKind.PROTOCOL, root -> root.put("maxPayloadBytes", 0));
        rejects(DesignKind.PROTOCOL, root -> ((ArrayNode)root.path("fields")).add(root.path("fields").get(0).deepCopy()));
        rejects(DesignKind.CONTRACT, root -> root.put("timeoutSteps", 0));
        rejects(DesignKind.CONTRACT, root -> ((ArrayNode)root.path("metadata").path("dependencies")).removeAll());
        rejects(DesignKind.CONTRACT, root -> root.put("maxUnits", 0));
    }

    @Test void numericNullFloatAndStringCoercionAreRejected() throws IOException {
        rejects(DesignKind.ARENA_RULESET, root -> root.put("width", "16"));
        rejects(DesignKind.ARENA_RULESET, root -> root.put("width", 16.1));
        rejects(DesignKind.ARENA_RULESET, root -> root.putNull("width"));
        rejects(DesignKind.ARENA_RULESET, root -> root.remove("width"));
        rejects(DesignKind.ARENA_RULESET, root -> root.put("durationSteps", 72_001));
    }

    @Test void enumOrdinalsAndStringParameterCoercionAreRejected() throws IOException {
        rejects(DesignKind.PROGRAM, root -> ((ObjectNode) root.path("nodes").get(0)).put("operation", 8));
        rejects(DesignKind.PROGRAM, root -> ((ObjectNode) root.path("nodes").get(0)).put("inputType", 0));
        rejects(DesignKind.PROTOCOL, root -> ((ObjectNode) root.path("fields").get(0)).put("type", 0));
        rejects(DesignKind.PROGRAM, root -> ((ObjectNode) root.path("nodes").get(0).path("parameters")).put("ticks", 1));
        rejects(DesignKind.PROGRAM, root -> ((ObjectNode) root.path("nodes").get(0).path("parameters")).put("ticks", true));
    }

    @Test void unpairedSurrogatesAreRejectedBeforeHashing() throws IOException {
        rejects(DesignKind.BLUEPRINT, root -> ((ObjectNode) root.path("metadata")).put("source", "bad-" + (char) 0xd800));
        rejects(DesignKind.BLUEPRINT, root -> ((ObjectNode) root.path("metadata")).put("source", "bad-" + (char) 0xdc00));
        ObjectNode valid = unsigned(DesignKind.BLUEPRINT);
        ((ObjectNode) valid.path("metadata")).put("source", "public-" + new String(Character.toChars(0x1f680)));
        assertEquals(codec.load(codec.seal(valid.toString())), codec.load(codec.seal(valid.toPrettyString())));
    }

    @ParameterizedTest @EnumSource(DesignKind.class)
    void schemaRejectsUnknownFieldsMissingFieldsAndWrongScalars(DesignKind kind) throws IOException {
        JsonSchema schema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
                .getSchema(json.readTree(resource("/schemas/" + kind.name().toLowerCase(Locale.ROOT) + ".schema.json")));
        ObjectNode example = (ObjectNode) json.readTree(fixture(kind));
        example.put("executeCommand", "op someone"); assertFalse(schema.validate(example).isEmpty());
        example.remove("executeCommand"); example.remove("metadata"); assertFalse(schema.validate(example).isEmpty());
        example = (ObjectNode) json.readTree(fixture(kind));
        ((ObjectNode) example.path("metadata").path("permissions")).put("publicRead", "true");
        assertFalse(schema.validate(example).isEmpty());
    }

    @Test void legacyMigrationIsExplicitHashVerifiedAndRepeatable() throws IOException {
        String legacy = resource("/examples/legacy-blueprint-v0.json");
        assertThrows(DesignValidationException.class, () -> codec.load(legacy));
        DesignCodec.MigrationResult result = codec.migrateV0(legacy);
        assertNotEquals(result.previousHash(), result.currentHash());
        assertEquals(codec.load(fixture(DesignKind.BLUEPRINT)), result.document());
        assertEquals(result, codec.migrateV0(legacy));
        assertThrows(DesignValidationException.class, () -> codec.migrateV0(result.json()));
        assertThrows(DesignValidationException.class, () -> codec.migrateV0(legacy.replace("genesis:core", "genesis:fake")));
    }

    @Test void overflowingLegacyVersionCannotWrapToZero() throws Exception {
        ObjectNode root = (ObjectNode) json.readTree(resource("/examples/legacy-blueprint-v0.json"));
        ObjectNode meta = (ObjectNode) root.path("metadata");
        meta.put("schemaVersion", 4_294_967_296L);
        meta.remove("hash");
        String hash = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(sorted(root).toString().getBytes(StandardCharsets.UTF_8)));
        meta.put("hash", hash);
        assertThrows(DesignValidationException.class, () -> codec.migrateV0(root.toString()));
    }

    private JsonNode sorted(JsonNode input) {
        if (input.isObject()) {
            ObjectNode result = json.createObjectNode();
            List<String> names = new ArrayList<>(); input.fieldNames().forEachRemaining(names::add);
            Collections.sort(names); for (String name : names) result.set(name, sorted(input.get(name)));
            return result;
        }
        if (input.isArray()) {
            ArrayNode result = json.createArrayNode(); input.forEach(n -> result.add(sorted(n))); return result;
        }
        return input;
    }

    @Test void modelsAreDeeplyImmutableAndProgramsAreNeverExecuted() throws IOException {
        Blueprint blueprint = (Blueprint)codec.load(fixture(DesignKind.BLUEPRINT));
        assertThrows(UnsupportedOperationException.class, () -> blueprint.components().clear());
        assertThrows(UnsupportedOperationException.class, () -> blueprint.metadata().dependencies().clear());
        assertThrows(UnsupportedOperationException.class, () -> blueprint.metadata().permissions().editors().clear());
        Program program = (Program)codec.load(fixture(DesignKind.PROGRAM));
        assertThrows(UnsupportedOperationException.class, () -> program.nodes().getFirst().parameters().put("java", "code"));
    }
}
