package dev.stargenesis.paper;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

final class FactoryRelayCommandRules {
    private FactoryRelayCommandRules() { }

    static List<String> validate(String[] arguments) {
        if (arguments == null || arguments.length == 0 || arguments.length > FactoryRelayPacket.MAX_ARGUMENTS)
            throw new IllegalArgumentException("Remote factory command is outside the allowed bounds");
        List<String> copied = List.of(arguments.clone());
        int totalBytes = 0;
        for (String argument : copied) {
            if (argument == null || argument.isBlank() || argument.getBytes(StandardCharsets.UTF_8).length
                    > FactoryRelayPacket.MAX_ARGUMENT_BYTES || argument.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("Remote factory argument is outside the allowed bounds");
            totalBytes = Math.addExact(totalBytes, argument.getBytes(StandardCharsets.UTF_8).length);
        }
        if (totalBytes > 2048) throw new IllegalArgumentException("Remote factory command exceeds its size limit");

        String operation = copied.getFirst().toLowerCase(Locale.ROOT);
        switch (operation) {
            case "status" -> requireCount(copied, 1);
            case "orders" -> {
                if (copied.size() > 2)
                    throw new IllegalArgumentException("Remote order query has an invalid argument count");
            }
            case "bid", "contribute" -> requireCount(copied, 4);
            case "fulfill" -> requireCount(copied, 2);
            case "advance" -> requireCount(copied, 3);
            case "station" -> validateStation(copied);
            default -> throw new IllegalArgumentException("This factory operation cannot be relayed");
        }
        return copied;
    }

    private static void validateStation(List<String> arguments) {
        if (arguments.size() < 2) throw new IllegalArgumentException("Invalid remote factory station command");
        switch (arguments.get(1).toLowerCase(Locale.ROOT)) {
            case "status" -> requireCount(arguments, 2);
            case "queue" -> requireCount(arguments, 5);
            case "cancel" -> requireCount(arguments, 3);
            case "collect" -> requireCount(arguments, 4);
            default -> throw new IllegalArgumentException("This factory station operation cannot be relayed");
        }
    }

    private static void requireCount(List<String> arguments, int expected) {
        if (arguments.size() != expected)
            throw new IllegalArgumentException("Remote factory command has an invalid argument count");
    }
}
