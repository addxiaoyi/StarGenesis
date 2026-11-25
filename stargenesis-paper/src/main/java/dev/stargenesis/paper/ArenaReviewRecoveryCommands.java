package dev.stargenesis.paper;

import dev.stargenesis.design.ArenaRuleset;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;

import java.io.IOException;
import java.util.Objects;
import java.util.UUID;

/** Explicit operator replay of an immutable signed approval, without creating or changing a decision. */
final class ArenaReviewRecoveryCommands {
    private static final String PERMISSION = "stargenesis.arena.review";
    private final ArenaReviewQueue reviews;

    ArenaReviewRecoveryCommands(ArenaReviewQueue reviews) {
        this.reviews = Objects.requireNonNull(reviews, "arena review queue");
    }

    void execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            message(sender, "StarGenesis: permission denied (" + PERMISSION + ")");
            return;
        }
        if (args.length != 5 || !args[1].equalsIgnoreCase("recover")) {
            message(sender, "Usage: /genesis arena recover <sha256> <reviewer-uuid> <original-signature>");
            return;
        }
        try {
            UUID reviewer = UUID.fromString(args[3]);
            if (!reviewer.toString().equalsIgnoreCase(args[3]))
                throw new IllegalArgumentException("Recovery reviewer must be a complete canonical UUID");
            ArenaRuleset published = reviews.recoverSignedApproval(args[2], reviewer, args[4]);
            message(sender, "Recovered approved arena release " + published.metadata().id().value()
                    + "@" + published.metadata().version() + " #" + published.metadata().hash()
                    + " | reviewer=" + reviewer);
        } catch (IOException | IllegalArgumentException | SecurityException failure) {
            message(sender, "Arena approval recovery failed: " + failure.getMessage());
        }
    }

    private static void message(CommandSender sender, String text) { sender.sendMessage(Component.text(text)); }
}
