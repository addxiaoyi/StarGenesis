package dev.stargenesis.design;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.regex.Pattern;

/** Canonical bounded text payload validation against a pinned protocol definition. */
public final class ProtocolPayloadCodec {
    private static final Pattern INTEGER = Pattern.compile("0|-?[1-9][0-9]{0,18}");
    private static final Pattern POSITION = Pattern.compile("-?(0|[1-9][0-9]{0,5}):-?(0|[1-9][0-9]{0,5})");
    private static final Pattern RESOURCE_ID = Pattern.compile("[a-z][a-z0-9_-]{0,31}:[a-z0-9][a-z0-9_./-]{0,94}");
    private static final Pattern TOKEN = Pattern.compile("[a-zA-Z0-9_-]{1,128}");

    public String encode(Protocol protocol, Map<String, String> values) {
        Objects.requireNonNull(protocol, "protocol");
        Objects.requireNonNull(values, "values");
        if (values.size() > protocol.fields().size()) throw new IllegalArgumentException("Payload has unknown fields");
        Map<String, Protocol.Field> definitions = new TreeMap<>();
        protocol.fields().forEach(field -> definitions.put(field.name(), field));
        TreeMap<String, String> canonical = new TreeMap<>();
        values.forEach((name, value) -> {
            Protocol.Field field = definitions.get(name);
            if (field == null) throw new IllegalArgumentException("Unknown payload field " + name);
            validateValue(field, value);
            canonical.put(name, value);
        });
        protocol.fields().stream().filter(Protocol.Field::required).forEach(field -> {
            if (!canonical.containsKey(field.name())) throw new IllegalArgumentException("Missing required field " + field.name());
        });
        StringBuilder encoded = new StringBuilder();
        canonical.forEach((name, value) -> {
            if (!encoded.isEmpty()) encoded.append('&');
            encoded.append(name.length()).append(':').append(name).append('=')
                    .append(value.length()).append(':').append(value);
        });
        String payload = encoded.toString();
        if (payload.getBytes(StandardCharsets.UTF_8).length > protocol.maxPayloadBytes())
            throw new IllegalArgumentException("Protocol payload exceeds its byte budget");
        return payload;
    }

    public Map<String, String> decode(Protocol protocol, String payload) {
        Objects.requireNonNull(protocol, "protocol");
        if (payload == null || payload.isEmpty()
                || payload.getBytes(StandardCharsets.UTF_8).length > protocol.maxPayloadBytes()) {
            throw new IllegalArgumentException("Protocol payload is empty or exceeds its byte budget");
        }
        Map<String, String> values = new java.util.LinkedHashMap<>();
        int offset = 0;
        while (offset < payload.length()) {
            int nameLengthEnd = payload.indexOf(':', offset);
            int nameLength = readLength(payload, offset, nameLengthEnd, 128);
            int nameStart = nameLengthEnd + 1;
            int nameEnd = Math.addExact(nameStart, nameLength);
            if (nameEnd >= payload.length() || payload.charAt(nameEnd) != '=') {
                throw new IllegalArgumentException("Malformed protocol field name");
            }
            int valueLengthStart = nameEnd + 1;
            int valueLengthEnd = payload.indexOf(':', valueLengthStart);
            int valueLength = readLength(payload, valueLengthStart, valueLengthEnd, 128);
            int valueStart = valueLengthEnd + 1;
            int valueEnd = Math.addExact(valueStart, valueLength);
            if (valueEnd > payload.length()) throw new IllegalArgumentException("Truncated protocol field value");
            String name = payload.substring(nameStart, nameEnd);
            String value = payload.substring(valueStart, valueEnd);
            if (values.putIfAbsent(name, value) != null)
                throw new IllegalArgumentException("Duplicate protocol field " + name);
            offset = valueEnd;
            if (offset < payload.length()) {
                if (payload.charAt(offset) != '&') throw new IllegalArgumentException("Malformed protocol field separator");
                offset++;
                if (offset == payload.length()) throw new IllegalArgumentException("Trailing protocol field separator");
            }
        }
        if (!encode(protocol, values).equals(payload))
            throw new IllegalArgumentException("Protocol payload is invalid or not canonical");
        return Map.copyOf(values);
    }

    private static int readLength(String value, int start, int end, int maximum) {
        if (end < start || end == start || end - start > 3
                || (end - start > 1 && value.charAt(start) == '0'))
            throw new IllegalArgumentException("Malformed protocol field length");
        int length = 0;
        for (int index = start; index < end; index++) {
            char digit = value.charAt(index);
            if (digit < '0' || digit > '9') throw new IllegalArgumentException("Malformed protocol field length");
            length = length * 10 + digit - '0';
        }
        if (length < 1 || length > maximum)
            throw new IllegalArgumentException("Protocol field length is outside its limit");
        return length;
    }

    private static void validateValue(Protocol.Field field, String value) {
        if (value == null || value.length() > 128) throw new IllegalArgumentException("Invalid field value");
        boolean valid = switch (field.type()) {
            case BOOLEAN -> "true".equals(value) || "false".equals(value);
            case INTEGER -> INTEGER.matcher(value).matches() && parseLong(value);
            case POSITION -> POSITION.matcher(value).matches() && positionInBounds(value);
            case RESOURCE_ID -> RESOURCE_ID.matcher(value).matches() && validResourceId(value);
            case TOKEN -> TOKEN.matcher(value).matches();
        };
        if (!valid) throw new IllegalArgumentException("Invalid " + field.type() + " value for " + field.name());
    }

    private static boolean parseLong(String value) {
        try { Long.parseLong(value); return true; }
        catch (NumberFormatException failure) { return false; }
    }

    private static boolean validResourceId(String value) {
        try { new dev.stargenesis.api.design.DesignId(value); return true; }
        catch (IllegalArgumentException failure) { return false; }
    }

    private static boolean positionInBounds(String value) {
        String[] coordinates = value.split(":", -1);
        return Math.abs(Integer.parseInt(coordinates[0])) <= 30_000
                && Math.abs(Integer.parseInt(coordinates[1])) <= 30_000;
    }
}
