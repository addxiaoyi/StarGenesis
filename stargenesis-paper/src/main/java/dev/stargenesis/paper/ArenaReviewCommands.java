package dev.stargenesis.paper;

import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** Read-only queries over the durable review archive, available to players and console. */
final class ArenaReviewCommands {
    private static final String PERMISSION = "stargenesis.arena.review";
    private final ArenaReviewQueue reviews;

    ArenaReviewCommands(ArenaReviewQueue reviews) {
        this.reviews = Objects.requireNonNull(reviews, "arena review queue");
    }

    static boolean requiresReviewPermission(String operation) {
        return List.of("review", "approve", "reject", "decisions", "decision", "recover")
                .contains(operation.toLowerCase(Locale.ROOT));
    }

    static boolean isQuery(String[] args) {
        return args.length >= 2 && (args[1].equalsIgnoreCase("decisions") || args[1].equalsIgnoreCase("decision"));
    }

    void execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            message(sender, "StarGenesis: permission denied (" + PERMISSION + ")");
            return;
        }
        try {
            if ((args.length == 2 || args.length == 3) && args[1].equalsIgnoreCase("decisions")) {
                String page = args.length == 3 ? args[2] : "1";
                if (!page.matches("[1-9][0-9]{0,2}"))
                    throw new IllegalArgumentException("Arena decision page must be a positive integer");
                list(sender, Integer.parseInt(page));
            } else if (args.length == 3 && args[1].equalsIgnoreCase("decision")) {
                inspect(sender, args[2]);
            } else {
                message(sender, "Usage: /genesis arena decisions [page] | decision <sha256>");
            }
        } catch (IOException failure) {
            message(sender, "Arena review archive could not be read; no decision state is available.");
        } catch (IllegalArgumentException failure) {
            message(sender, failure.getMessage());
        }
    }

    private void list(CommandSender sender, int page) throws IOException {
        ArenaReviewQueue.DecisionPage result = reviews.listDecisions(page);
        message(sender, "Arena review decisions | records=" + result.totalRecords()
                + " | page=" + result.page() + "/" + result.totalPages());
        for (ArenaReviewQueue.DecisionView decision : result.decisions()) message(sender, summary(decision));
    }

    private void inspect(CommandSender sender, String hash) throws IOException {
        var match = reviews.inspectDecision(hash);
        if (match.isEmpty()) {
            message(sender, "Unknown arena review decision.");
            return;
        }
        ArenaReviewQueue.DecisionInspection inspection = match.get();
        ArenaReviewQueue.DecisionView decision = inspection.decision();
        message(sender, summary(decision));
        message(sender, "submitter=" + decision.submitter() + " | publication=" + inspection.publication());
        message(sender, "author-key=" + (decision.keyFingerprint().isEmpty() ? "unsigned-submission" : decision.keyFingerprint()));
        if (!decision.reason().isEmpty()) message(sender, "reason=" + decision.reason());
        if (decision.signed()) {
            message(sender, "reviewer-key=" + decision.reviewerFingerprint());
            message(sender, "publicKeyBase64=" + decision.reviewerPublicKeyBase64());
            message(sender, "signatureBase64=" + decision.reviewerSignatureBase64());
        }
    }

    private static String summary(ArenaReviewQueue.DecisionView decision) {
        return decision.hash() + " | " + (decision.approved() ? "APPROVE" : "REJECT")
                + " | reviewer=" + decision.reviewer() + " | at=" + Instant.ofEpochMilli(decision.decidedAtMillis())
                + " | signature=" + (decision.signed() ? "Ed25519-valid" : "legacy-unsigned");
    }

    private static void message(CommandSender sender, String text) { sender.sendMessage(Component.text(text)); }
}
