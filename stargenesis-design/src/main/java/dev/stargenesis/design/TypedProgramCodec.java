package dev.stargenesis.design;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.stargenesis.api.design.DesignId;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Strict, bounded persistence codec for the versioned typed-program format. */
public final class TypedProgramCodec {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_DOCUMENT_BYTES = 262_144;
    private final ObjectMapper mapper;

    public TypedProgramCodec() {
        JsonFactory factory = JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(40)
                        .maxStringLength(4096).maxNumberLength(20).build()).build();
        mapper = new ObjectMapper(factory);
        mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS);
        mapper.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        mapper.disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    }

    public String seal(DesignId id, UUID author, ProgramIR program) {
        TypedProgramDocument unsigned = new TypedProgramDocument(id, author, SCHEMA_VERSION, "", program);
        ObjectNode root = node(unsigned);
        root.put("hash", hash(root));
        String encoded = json(canonical(root));
        if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_DOCUMENT_BYTES)
            throw new DesignValidationException("Typed program exceeds the document size limit");
        load(encoded);
        return encoded;
    }

    public TypedProgramDocument load(String encoded) {
        ObjectNode root = parse(encoded);
        TypedProgramWire wire = convert(root, TypedProgramWire.class);
        if (!"TYPED_PROGRAM".equals(wire.kind())) throw new DesignValidationException("Expected TYPED_PROGRAM document");
        if (wire.schemaVersion() != SCHEMA_VERSION) throw new DesignValidationException("Unsupported typed program schemaVersion");
        if (wire.id() == null || wire.author() == null || wire.hash() == null || !wire.hash().matches("[0-9a-f]{64}"))
            throw new DesignValidationException("Missing typed program identity or SHA-256");
        DesignId id;
        UUID author;
        try {
            id = new DesignId(wire.id());
            author = UUID.fromString(wire.author());
        } catch (IllegalArgumentException invalid) {
            throw new DesignValidationException("Invalid typed program identity", invalid);
        }
        if (!author.toString().equals(wire.author())) throw new DesignValidationException("Author UUID must be canonical lowercase");
        if (wire.instructions() == null) throw new DesignValidationException("Missing typed program instructions");
        List<ProgramIR.Instruction> instructions = new ArrayList<>();
        try {
            for (InstructionWire instruction : wire.instructions()) {
                if (instruction == null || instruction.operation() == null)
                    throw new IllegalArgumentException("Missing typed instruction operation");
                instructions.add(new ProgramIR.Instruction(instruction.id(),
                        PrimitiveCatalog.Operation.valueOf(instruction.operation()), instruction.inputs(), instruction.parameters()));
            }
            ProgramIR program = new ProgramIR(instructions, wire.maxInstructionsPerStep());
            String expected = hash(root);
            if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                    wire.hash().getBytes(StandardCharsets.US_ASCII)))
                throw new DesignValidationException("Typed program hash mismatch");
            return new TypedProgramDocument(id, author, wire.schemaVersion(), wire.hash(), program);
        } catch (DesignValidationException invalid) {
            throw invalid;
        } catch (RuntimeException invalid) {
            throw new DesignValidationException("Invalid typed program content", invalid);
        }
    }

    public String write(TypedProgramDocument document) {
        if (document == null || document.hash().isEmpty())
            throw new DesignValidationException("Only sealed typed programs can be written");
        ObjectNode root = node(document);
        String expected = hash(root);
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.US_ASCII),
                document.hash().getBytes(StandardCharsets.US_ASCII)))
            throw new DesignValidationException("Typed program hash mismatch");
        String encoded = json(canonical(root));
        if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_DOCUMENT_BYTES)
            throw new DesignValidationException("Typed program exceeds the document size limit");
        load(encoded);
        return encoded;
    }

    private ObjectNode parse(String encoded) {
        if (encoded == null || encoded.length() > MAX_DOCUMENT_BYTES
                || encoded.getBytes(StandardCharsets.UTF_8).length > MAX_DOCUMENT_BYTES)
            throw new DesignValidationException("Typed program exceeds the UTF-8 byte budget");
        try {
            var parsed = mapper.readTree(encoded);
            if (!(parsed instanceof ObjectNode root)) throw new DesignValidationException("Expected typed program object");
            return root;
        } catch (java.io.IOException invalid) {
            throw new DesignValidationException("Malformed or over-budget typed program JSON", invalid);
        }
    }

    private <T> T convert(ObjectNode root, Class<T> type) {
        try { return mapper.treeToValue(root, type); }
        catch (java.io.IOException invalid) { throw new DesignValidationException("Invalid typed program fields", invalid); }
    }

    private ObjectNode node(TypedProgramDocument document) {
        ObjectNode root = mapper.createObjectNode();
        root.put("kind", "TYPED_PROGRAM");
        root.put("schemaVersion", document.schemaVersion());
        root.put("id", document.id().value());
        root.put("author", document.author().toString());
        root.put("hash", document.hash());
        root.put("maxInstructionsPerStep", document.program().maxInstructionsPerStep());
        ArrayNode instructions = root.putArray("instructions");
        for (ProgramIR.Instruction instruction : document.program().instructions()) {
            ObjectNode item = instructions.addObject();
            item.put("id", instruction.id());
            item.put("operation", instruction.operation().name());
            ArrayNode inputs = item.putArray("inputs");
            instruction.inputs().forEach(inputs::add);
            item.set("parameters", mapper.valueToTree(instruction.parameters()));
        }
        return root;
    }

    private String hash(ObjectNode root) {
        ObjectNode unsigned = root.deepCopy();
        unsigned.remove("hash");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json(canonical(unsigned)).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private com.fasterxml.jackson.databind.JsonNode canonical(com.fasterxml.jackson.databind.JsonNode value) {
        if (value instanceof ObjectNode object) {
            ObjectNode sorted = mapper.createObjectNode();
            List<String> names = new ArrayList<>();
            object.fieldNames().forEachRemaining(names::add);
            Collections.sort(names);
            for (String name : names) sorted.set(name, canonical(object.get(name)));
            return sorted;
        }
        if (value instanceof ArrayNode array) {
            ArrayNode copy = mapper.createArrayNode();
            array.forEach(item -> copy.add(canonical(item)));
            return copy;
        }
        return value;
    }

    private String json(com.fasterxml.jackson.databind.JsonNode value) {
        try { return mapper.writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new DesignValidationException("Cannot encode typed program", invalid);
        }
    }

    private record TypedProgramWire(String kind, int schemaVersion, String id, String author, String hash,
                                    int maxInstructionsPerStep, List<InstructionWire> instructions) { }
    private record InstructionWire(String id, String operation, List<String> inputs,
                                  java.util.Map<String, String> parameters) { }
}
