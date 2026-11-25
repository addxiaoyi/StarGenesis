package dev.stargenesis.design;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.stargenesis.api.design.DesignId;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;

/** Strict codec for transient messages bound to one validated protocol identity and hash. */
public final class ProtocolMessageCodec {
    private static final Set<String> ENVELOPE_FIELDS = Set.of(
            "protocol", "protocolHash", "sender", "recipient", "tick", "sequence", "payload");
    private final ObjectMapper mapper;
    private final DesignCodec designCodec = new DesignCodec();

    public ProtocolMessageCodec() {
        JsonFactory factory = JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(8)
                        .maxStringLength(4096).maxNumberLength(20).build()).build();
        mapper = new ObjectMapper(factory);
        mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
        mapper.disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    }

    public String encode(Protocol protocol, UUID sender, UUID recipient, long tick, int sequence,
                         Map<String, String> payload) {
        validateEnvelope(protocol, sender, recipient, tick, sequence);
        TreeMap<String, String> sortedPayload = validatePayload(protocol, payload);
        ObjectNode payloadNode = mapper.createObjectNode();
        sortedPayload.forEach(payloadNode::put);
        if (bytes(payloadNode).length > protocol.maxPayloadBytes())
            throw new IllegalArgumentException("Protocol payload exceeds its byte budget");

        ObjectNode message = mapper.createObjectNode();
        message.put("protocol", protocol.metadata().id().value());
        message.put("protocolHash", protocol.metadata().hash());
        message.put("sender", sender.toString());
        message.put("recipient", recipient.toString());
        message.put("tick", tick);
        message.put("sequence", sequence);
        message.set("payload", payloadNode);
        return json(message);
    }

    public Message decode(Protocol protocol, String encoded) {
        Objects.requireNonNull(protocol, "protocol");
        if (encoded == null || encoded.getBytes(StandardCharsets.UTF_8).length > 8192)
            throw new IllegalArgumentException("Protocol message exceeds the envelope size limit");
        ObjectNode root;
        try {
            var parsed = mapper.readTree(encoded);
            if (!(parsed instanceof ObjectNode object)) throw new IllegalArgumentException("Expected message object");
            root = object;
        } catch (java.io.IOException malformed) {
            throw new IllegalArgumentException("Malformed or over-budget protocol message", malformed);
        }
        Set<String> names = new HashSet<>();
        root.fieldNames().forEachRemaining(names::add);
        if (!names.equals(ENVELOPE_FIELDS)) throw new IllegalArgumentException("Unknown or missing message envelope fields");
        if (!root.path("protocol").isTextual() || !root.path("protocol").asText().equals(protocol.metadata().id().value())
                || !root.path("protocolHash").isTextual() || !root.path("protocolHash").asText().equals(protocol.metadata().hash()))
            throw new IllegalArgumentException("Protocol identity or pinned hash mismatch");
        UUID sender = canonicalUuid(root.path("sender"));
        UUID recipient = canonicalUuid(root.path("recipient"));
        if (!root.path("tick").isIntegralNumber() || !root.path("tick").canConvertToLong()
                || !root.path("sequence").isIntegralNumber() || !root.path("sequence").canConvertToInt())
            throw new IllegalArgumentException("Invalid message tick or sequence");
        long tick = root.path("tick").longValue();
        int sequence = root.path("sequence").intValue();
        validateEnvelope(protocol, sender, recipient, tick, sequence);
        if (!(root.get("payload") instanceof ObjectNode payloadNode))
            throw new IllegalArgumentException("Protocol payload must be an object");
        Map<String, String> payload = new TreeMap<>();
        payloadNode.fields().forEachRemaining(entry -> {
            if (!entry.getValue().isTextual()) throw new IllegalArgumentException("Protocol payload fields must be strings");
            payload.put(entry.getKey(), entry.getValue().textValue());
        });
        TreeMap<String, String> validated = validatePayload(protocol, payload);
        if (bytes(payloadNode).length > protocol.maxPayloadBytes())
            throw new IllegalArgumentException("Protocol payload exceeds its byte budget");
        return new Message(new DesignId(protocol.metadata().id().value()), protocol.metadata().hash(),
                sender, recipient, tick, sequence, validated);
    }

    private TreeMap<String, String> validatePayload(Protocol protocol, Map<String, String> payload) {
        Objects.requireNonNull(payload, "payload");
        if (payload.size() > protocol.fields().size()) throw new IllegalArgumentException("Unknown protocol payload field");
        Map<String, Protocol.Field> fields = new TreeMap<>();
        protocol.fields().forEach(field -> fields.put(field.name(), field));
        TreeMap<String, String> validated = new TreeMap<>();
        payload.forEach((name, value) -> {
            Protocol.Field field = fields.get(name);
            if (field == null) throw new IllegalArgumentException("Unknown protocol field " + name);
            Objects.requireNonNull(value, "payload value");
            validateValue(field, value);
            validated.put(name, value);
        });
        for (Protocol.Field field : protocol.fields())
            if (field.required() && !validated.containsKey(field.name()))
                throw new IllegalArgumentException("Missing required protocol field " + field.name());
        return validated;
    }

    private void validateValue(Protocol.Field field, String value) {
        switch (field.type()) {
            case BOOLEAN -> {
                if (!value.equals("true") && !value.equals("false"))
                    throw new IllegalArgumentException("Invalid boolean field " + field.name());
            }
            case INTEGER -> {
                try { Long.parseLong(value); }
                catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid integer field " + field.name(), invalid); }
            }
            case POSITION -> {
                String[] coordinates = value.split(",", -1);
                if (coordinates.length != 3) throw new IllegalArgumentException("Position must contain x,y,z");
                try {
                    for (String coordinate : coordinates) {
                        int position = Integer.parseInt(coordinate);
                        if (position < -1_000_000 || position > 1_000_000)
                            throw new IllegalArgumentException("Position coordinate exceeds its bound");
                    }
                } catch (NumberFormatException invalid) { throw new IllegalArgumentException("Invalid position field", invalid); }
            }
            case RESOURCE_ID, TOKEN -> {
                if (!value.matches("[a-z][a-z0-9_:-]{0,63}"))
                    throw new IllegalArgumentException("Invalid token field " + field.name());
            }
        }
    }

    private void validateEnvelope(Protocol protocol, UUID sender, UUID recipient, long tick, int sequence) {
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(recipient, "recipient");
        if (tick < 0 || sequence < 0) throw new IllegalArgumentException("Negative protocol message time or sequence");
        designCodec.write(protocol);
    }

    private UUID canonicalUuid(com.fasterxml.jackson.databind.JsonNode value) {
        if (!value.isTextual()) throw new IllegalArgumentException("Message player ID must be a UUID string");
        UUID uuid;
        try { uuid = UUID.fromString(value.asText()); }
        catch (IllegalArgumentException invalid) { throw new IllegalArgumentException("Invalid message player UUID", invalid); }
        if (!uuid.toString().equals(value.asText())) throw new IllegalArgumentException("Message UUID must be canonical lowercase");
        return uuid;
    }

    private byte[] bytes(com.fasterxml.jackson.databind.JsonNode value) {
        try { return mapper.writeValueAsBytes(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalArgumentException("Cannot encode protocol payload", invalid);
        }
    }

    private String json(com.fasterxml.jackson.databind.JsonNode value) {
        try { return mapper.writeValueAsString(value); }
        catch (com.fasterxml.jackson.core.JsonProcessingException invalid) {
            throw new IllegalArgumentException("Cannot encode protocol message", invalid);
        }
    }

    public record Message(DesignId protocol, String protocolHash, UUID sender, UUID recipient,
                          long tick, int sequence, Map<String, String> payload) {
        public Message { payload = Map.copyOf(new TreeMap<>(payload)); }
    }
}
