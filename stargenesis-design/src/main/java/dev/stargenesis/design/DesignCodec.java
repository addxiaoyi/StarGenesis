package dev.stargenesis.design;

import com.fasterxml.jackson.core.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.*;
import com.fasterxml.jackson.databind.cfg.CoercionAction;
import com.fasterxml.jackson.databind.cfg.CoercionInputShape;
import com.fasterxml.jackson.databind.type.LogicalType;
import dev.stargenesis.api.design.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;
import java.util.UUID;

/** Strict, bounded JSON interchange. Never loads user-supplied classes or runs code. */
public final class DesignCodec {
    public static final int SCHEMA_VERSION = 1;
    public static final int MAX_DOCUMENT_BYTES = 262_144;
    private final ObjectMapper mapper;

    public DesignCodec() {
        JsonFactory factory = JsonFactory.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32)
                        .maxStringLength(4096).maxNumberLength(10).build()).build();
        mapper = new ObjectMapper(factory);
        mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        mapper.enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
        mapper.enable(DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES);
        mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        mapper.enable(DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS);
        mapper.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        mapper.disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
        // String-valued parameters are an exact wire type, not an invitation to stringify scalars.
        mapper.coercionConfigFor(LogicalType.Textual)
                .setCoercion(CoercionInputShape.Integer, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Float, CoercionAction.Fail)
                .setCoercion(CoercionInputShape.Boolean, CoercionAction.Fail);
    }

    public DesignDocument load(String json) {
        ObjectNode root = parse(json); checkVersion(root); checkHash(root);
        return decode(root);
    }

    /** Creation/import operation: only unsigned new documents may be sealed. */
    public String seal(String unsignedJson) {
        ObjectNode root = parse(unsignedJson); checkVersion(root);
        if (!metadata(root).path("hash").isTextual() || !metadata(root).path("hash").asText().isEmpty())
            throw new DesignValidationException("New document hash must be empty; existing documents must be verified");
        decode(root);
        metadata(root).put("hash", hash(root));
        return json(canonical(root));
    }

    public String write(DesignDocument document) {
        Objects.requireNonNull(document, "document");
        if (!(document instanceof Blueprint || document instanceof Program || document instanceof Protocol
                || document instanceof Contract || document instanceof ArenaRuleset))
            throw new DesignValidationException("Unregistered design class");
        ObjectNode root = encode(document);
        String encoded = json(canonical(root));
        load(encoded); // Never silently re-sign an object with a stale hash.
        return encoded;
    }

    /** Saves an edited design only after checking the actor against its author/editor metadata. */
    public String writeEdited(DesignDocument document, UUID actor) {
        Objects.requireNonNull(document, "document");
        Objects.requireNonNull(actor, "actor");
        DesignMetadata metadata = document.metadata();
        if (!metadata.permissions().mayEdit(actor, metadata.author())) {
            throw new SecurityException("Actor is not authorized to edit this design");
        }
        DesignMetadata unsignedMetadata = new DesignMetadata(metadata.id(), metadata.schemaVersion(), metadata.version(),
                metadata.author(), metadata.source(), metadata.dependencies(), "", metadata.permissions());
        DesignDocument unsigned = withMetadata(document, unsignedMetadata);
        return seal(json(encode(unsigned)));
    }

    private DesignDocument withMetadata(DesignDocument document, DesignMetadata metadata) {
        if (document instanceof Blueprint blueprint)
            return new Blueprint(metadata, blueprint.components(), blueprint.connections());
        if (document instanceof Program program)
            return new Program(metadata, program.nodes(), program.edges(), program.maxInstructionsPerStep());
        if (document instanceof Protocol protocol)
            return new Protocol(metadata, protocol.fields(), protocol.maxPayloadBytes());
        if (document instanceof Contract contract)
            return new Contract(metadata, contract.protocol(), contract.timeoutSteps(), contract.maxUnits());
        if (document instanceof ArenaRuleset ruleset)
            return ruleset.withMetadata(metadata);
        throw new DesignValidationException("Unregistered design class");
    }

    /** Explicit legacy migration: v0 metadata.revision is renamed to metadata.version. */
    public MigrationResult migrateV0(String legacyJson) {
        ObjectNode root = parse(legacyJson); ObjectNode meta = metadata(root);
        if (!meta.path("schemaVersion").isIntegralNumber() || !meta.path("schemaVersion").canConvertToInt()
                || meta.path("schemaVersion").intValue() != 0)
            throw new DesignValidationException("Only explicit schema 0 -> 1 migration is supported");
        checkHash(root); String originalHash = meta.path("hash").asText();
        if (!meta.path("revision").isTextual() || meta.has("version"))
            throw new DesignValidationException("Legacy schema requires revision and forbids version");
        meta.set("version", meta.remove("revision")); meta.put("schemaVersion", SCHEMA_VERSION); meta.put("hash", "");
        String migratedJson = seal(json(root)); DesignDocument document = load(migratedJson);
        return new MigrationResult(originalHash, document.metadata().hash(), migratedJson, document);
    }

    /** Upgrades a verified schema-1 arena without changing its identity, ACL or pinned dependencies. */
    public MigrationResult migrateArenaV1(String legacyJson) {
        DesignDocument document = load(legacyJson);
        if (!(document instanceof ArenaRuleset legacy) || legacy.metadata().schemaVersion() != SCHEMA_VERSION)
            throw new DesignValidationException("Expected a verified schema-1 arena ruleset");
        DesignMetadata old = legacy.metadata();
        DesignMetadata metadata = new DesignMetadata(old.id(), ArenaRuleset.SCHEMA_VERSION, nextPatch(old.version()),
                old.author(), old.source(), old.dependencies(), "", old.permissions());
        int objectiveX = legacy.width() / 2;
        int objectiveY = (legacy.height() - 1) / 2;
        int blueX = legacy.width() - 1;
        int blueY = legacy.height() - 1;
        long minimumRound = Math.max(objectiveX + objectiveY, blueX - objectiveX + blueY - objectiveY);
        long cellCount = (long) legacy.width() * legacy.height();
        long maximumRound = ArenaRulesetValidator.MAX_WORK_UNITS / (cellCount * 2);
        long duration = Math.max(minimumRound, Math.min(legacy.durationSteps(), maximumRound));
        int unitBudget = (int) Math.max(2,
                Math.min(legacy.unitBudget(), ArenaRulesetValidator.MAX_WORK_UNITS / (cellCount * duration)));
        var layout = new ArenaRuleset.Layout(List.of(), List.of(
                new ArenaRuleset.Team("red", List.of(new ArenaRuleset.Cell(0, 0))),
                new ArenaRuleset.Team("blue", List.of(new ArenaRuleset.Cell(blueX, blueY)))),
                List.of(new ArenaRuleset.Cell(objectiveX, objectiveY)));
        var victory = new ArenaRuleset.Victory(ArenaRuleset.Combination.ANY,
                List.of(new ArenaRuleset.Condition(ArenaRuleset.Metric.CONTROLLED_OBJECTIVES, 1)));
        ArenaRuleset upgraded = new ArenaRuleset(metadata, legacy.width(), legacy.height(), duration, unitBudget,
                layout, victory);
        String encoded = seal(json(encode(upgraded)));
        DesignDocument migrated = load(encoded);
        return new MigrationResult(old.hash(), migrated.metadata().hash(), encoded, migrated);
    }

    public record MigrationResult(String previousHash, String currentHash, String json, DesignDocument document) { }

    private static String nextPatch(String version) {
        String[] parts = version.split("\\.");
        int patch = Integer.parseInt(parts[2]);
        if (patch >= 999_999) throw new DesignValidationException("Arena patch version is exhausted");
        return parts[0] + "." + parts[1] + "." + (patch + 1);
    }

    private ObjectNode parse(String value) {
        if (value == null || value.length() > MAX_DOCUMENT_BYTES
                || value.getBytes(StandardCharsets.UTF_8).length > MAX_DOCUMENT_BYTES)
            throw new DesignValidationException("Document exceeds UTF-8 byte budget");
        try {
            JsonNode node = mapper.readTree(value);
            if (!(node instanceof ObjectNode root)) throw new DesignValidationException("Expected JSON object");
            validateUnicode(root);
            return root;
        } catch (JsonProcessingException ex) { throw new DesignValidationException("Malformed or over-budget JSON", ex); }
    }

    private void validateUnicode(JsonNode node) {
        if (node.isTextual()) {
            String value = node.textValue();
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (Character.isHighSurrogate(c)) {
                    if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i)))
                        throw new DesignValidationException("Unpaired Unicode surrogate");
                } else if (Character.isLowSurrogate(c)) {
                    throw new DesignValidationException("Unpaired Unicode surrogate");
                }
            }
        } else if (node.isContainerNode()) {
            node.elements().forEachRemaining(this::validateUnicode);
        }
    }

    private DesignDocument decode(ObjectNode root) {
        JsonNode kindNode = root.get("kind");
        if (kindNode == null || !kindNode.isTextual()) throw new DesignValidationException("Missing design kind");
        try {
            DesignKind kind = DesignKind.valueOf(kindNode.asText());
            Class<? extends DesignDocument> type = switch (kind) {
                case BLUEPRINT -> Blueprint.class; case PROGRAM -> Program.class; case PROTOCOL -> Protocol.class;
                case CONTRACT -> Contract.class; case ARENA_RULESET -> ArenaRuleset.class;
            };
            rejectDuplicateEditors(root);
            ObjectNode body = root.deepCopy(); body.remove("kind");
            if (kind == DesignKind.ARENA_RULESET && metadata(root).path("schemaVersion").intValue() == SCHEMA_VERSION) {
                LegacyArena arena = mapper.treeToValue(body, LegacyArena.class);
                return new ArenaRuleset(arena.metadata(), arena.width(), arena.height(), arena.durationSteps(),
                        arena.unitBudget());
            }
            return mapper.treeToValue(body, type);
        } catch (JsonProcessingException | IllegalArgumentException ex) {
            throw new DesignValidationException("Invalid " + kindNode.asText() + " document", ex);
        }
    }

    private ObjectNode encode(DesignDocument document) {
        ObjectNode root = mapper.valueToTree(document);
        if (document instanceof ArenaRuleset arena && arena.metadata().schemaVersion() == SCHEMA_VERSION)
            root.remove(List.of("layout", "victory"));
        root.put("kind", document.kind().name());
        return root;
    }

    private record LegacyArena(DesignMetadata metadata, int width, int height, long durationSteps, int unitBudget) { }

    private void rejectDuplicateEditors(ObjectNode root) {
        String uuid = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
        if (!metadata(root).path("author").isTextual() || !metadata(root).path("author").asText().matches(uuid))
            throw new DesignValidationException("author must be a canonical UUID");
        JsonNode editors = metadata(root).path("permissions").path("editors");
        if (!editors.isArray()) throw new DesignValidationException("editors must be an array");
        Set<String> seen = new HashSet<>();
        for (JsonNode editor : editors) {
            if (!editor.isTextual() || !editor.textValue().matches(uuid) || !seen.add(editor.textValue()))
                throw new DesignValidationException("Invalid or duplicate editor");
        }
    }

    private ObjectNode metadata(ObjectNode root) {
        if (!(root.get("metadata") instanceof ObjectNode meta)) throw new DesignValidationException("Missing metadata");
        return meta;
    }

    private void checkVersion(ObjectNode root) {
        JsonNode version = metadata(root).get("schemaVersion");
        boolean arenaV2 = DesignKind.ARENA_RULESET.name().equals(root.path("kind").textValue())
                && version != null && version.intValue() == ArenaRuleset.SCHEMA_VERSION;
        if (version == null || !version.isIntegralNumber() || !version.canConvertToInt()
                || (version.intValue() != SCHEMA_VERSION && !arenaV2))
            throw new DesignValidationException("Unsupported schemaVersion; migration must be explicit");
    }

    private void checkHash(ObjectNode root) {
        JsonNode hashNode = metadata(root).get("hash");
        if (hashNode == null || !hashNode.isTextual() || !hashNode.asText().matches("[0-9a-f]{64}"))
            throw new DesignValidationException("Missing canonical SHA-256");
        if (!MessageDigest.isEqual(hash(root).getBytes(StandardCharsets.US_ASCII),
                hashNode.asText().getBytes(StandardCharsets.US_ASCII)))
            throw new DesignValidationException("Design hash mismatch");
    }

    private String hash(ObjectNode root) {
        ObjectNode copy = root.deepCopy(); metadata(copy).remove("hash");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(json(canonical(copy)).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) { throw new IllegalStateException("SHA-256 unavailable", ex); }
    }

    private JsonNode canonical(JsonNode node) {
        if (node instanceof ObjectNode object) {
            ObjectNode result = mapper.createObjectNode(); List<String> names = new ArrayList<>();
            object.fieldNames().forEachRemaining(names::add); Collections.sort(names);
            for (String name : names) result.set(name, canonical(object.get(name)));
            // Editors are a set. This field is always an array of canonical UUID strings.
            if (result.path("editors").isArray()) {
                List<String> editors = new ArrayList<>(); result.path("editors").forEach(e -> editors.add(e.asText()));
                Collections.sort(editors); ArrayNode sorted = mapper.createArrayNode(); editors.forEach(sorted::add);
                result.set("editors", sorted);
            }
            return result;
        }
        if (node instanceof ArrayNode array) {
            ArrayNode result = mapper.createArrayNode(); array.forEach(item -> result.add(canonical(item))); return result;
        }
        return node;
    }

    private String json(JsonNode value) {
        try { return mapper.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new DesignValidationException("Cannot encode document", ex); }
    }
}
