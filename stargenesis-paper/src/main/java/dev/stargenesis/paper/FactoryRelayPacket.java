package dev.stargenesis.paper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

record FactoryRelayPacket(Kind kind, String sourceServer, String targetServer, UUID requestId,
                          UUID actorId, List<String> arguments, String response) {
    static final int MAX_ARGUMENTS = 8;
    static final int MAX_ARGUMENT_BYTES = 512;
    static final int MAX_RESPONSE_BYTES = 4096;

    FactoryRelayPacket {
        Objects.requireNonNull(kind, "kind");
        if (!ProtocolRelayPacket.validServerId(sourceServer) || !ProtocolRelayPacket.validServerId(targetServer)
                || sourceServer.equals(targetServer))
            throw new IllegalArgumentException("Invalid factory relay server identity");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(actorId, "actorId");
        arguments = List.copyOf(arguments);
        Objects.requireNonNull(response, "response");
        if (kind == Kind.REQUEST) {
            if (arguments.isEmpty() || arguments.size() > MAX_ARGUMENTS || !response.isEmpty())
                throw new IllegalArgumentException("Invalid factory relay request fields");
            int totalBytes = 0;
            for (String argument : arguments) {
                int bytes = textBytes(argument, MAX_ARGUMENT_BYTES);
                totalBytes = Math.addExact(totalBytes, bytes);
            }
            if (totalBytes > 2048) throw new IllegalArgumentException("Factory relay command exceeds its size limit");
        } else if (!arguments.isEmpty() || response.isBlank()) {
            throw new IllegalArgumentException("Invalid factory relay response fields");
        } else {
            textBytes(response, MAX_RESPONSE_BYTES);
        }
    }

    static FactoryRelayPacket request(String sourceServer, String targetServer, UUID requestId,
                                      UUID actorId, List<String> arguments) {
        return new FactoryRelayPacket(Kind.REQUEST, sourceServer, targetServer, requestId,
                actorId, arguments, "");
    }

    static FactoryRelayPacket response(String sourceServer, String targetServer, UUID requestId,
                                       UUID actorId, String response) {
        return new FactoryRelayPacket(Kind.RESPONSE, sourceServer, targetServer, requestId,
                actorId, List.of(), response);
    }

    private static int textBytes(String value, int maximumBytes) {
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("Factory relay text is empty");
        int bytes = value.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > maximumBytes || value.chars().anyMatch(character ->
                character < 32 && character != '\n' && character != '\t'))
            throw new IllegalArgumentException("Factory relay text is outside the allowed bounds");
        return bytes;
    }

    enum Kind { REQUEST, RESPONSE }
}
