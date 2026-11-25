package dev.stargenesis.paper;

import dev.stargenesis.integrations.SettlementCheckpoint;
import dev.stargenesis.integrations.SettlementSagaStore;
import dev.stargenesis.integrations.SettlementTransfer;
import net.kyori.adventure.text.Component;
import org.bukkit.command.CommandSender;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

final class SettlementJournalCommands {
    private static final int PAGE_SIZE = 20;
    private final SettlementSagaStore store;
    private final int startupRecords;
    private final long startupUnresolved;

    SettlementJournalCommands(Path dataDirectory) {
        this(new SettlementSagaFileStore(dataDirectory));
    }

    SettlementJournalCommands(SettlementSagaStore store) {
        this.store = Objects.requireNonNull(store, "settlement saga store");
        try {
            List<SettlementCheckpoint> checkpoints = store.loadAll();
            startupRecords = checkpoints.size();
            startupUnresolved = checkpoints.stream().filter(checkpoint -> switch (checkpoint.state()) {
                case COMMITTED, ABORTED -> false;
                default -> true;
            }).count();
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot load the settlement saga journal", failure);
        }
    }

    String startupReport() {
        return "Settlement saga journal verified | records=" + startupRecords
                + " | unresolved=" + startupUnresolved + " | real-settlement=disabled";
    }

    void execute(CommandSender sender, String[] args) {
        try {
            if (args.length == 1 || ((args.length == 2 || args.length == 3) && args[1].equalsIgnoreCase("list"))) {
                list(sender, args.length == 3 ? args[2] : "1");
            } else if (args.length == 3 && args[1].equalsIgnoreCase("inspect")) {
                inspect(sender, args[2]);
            } else {
                message(sender, "Usage: /genesis settlement list [page] | inspect <saga-uuid>");
            }
        } catch (IOException failure) {
            message(sender, "Settlement journal could not be read; no record state is available.");
        }
    }

    private void list(CommandSender sender, String requestedPage) throws IOException {
        List<SettlementCheckpoint> checkpoints = store.loadAll();
        int pages = Math.max(1, Math.ceilDiv(checkpoints.size(), PAGE_SIZE));
        int page = requestedPage.matches("[1-9][0-9]{0,2}") ? Integer.parseInt(requestedPage) : 0;
        if (page < 1 || page > pages) {
            message(sender, "Settlement journal page must be within 1.." + pages);
            return;
        }
        message(sender, "Settlement journal | records=" + checkpoints.size() + " | page=" + page + "/" + pages);
        int first = (page - 1) * PAGE_SIZE;
        for (SettlementCheckpoint checkpoint : checkpoints.subList(first, Math.min(first + PAGE_SIZE, checkpoints.size())))
            message(sender, summary(checkpoint));
    }

    private void inspect(CommandSender sender, String requestedId) throws IOException {
        UUID id;
        try {
            id = UUID.fromString(requestedId);
            if (!id.toString().equalsIgnoreCase(requestedId)) throw new IllegalArgumentException("Non-canonical UUID");
        } catch (IllegalArgumentException failure) {
            message(sender, "Supply the complete settlement saga UUID.");
            return;
        }
        var match = store.loadAll().stream().filter(checkpoint -> checkpoint.plan().id().equals(id)).findFirst();
        if (match.isEmpty()) {
            message(sender, "Unknown settlement saga.");
            return;
        }
        SettlementCheckpoint checkpoint = match.get();
        message(sender, summary(checkpoint) + " | initiator=" + checkpoint.plan().initiator());
        if (!checkpoint.diagnostic().isEmpty()) message(sender, checkpoint.diagnostic());
        int cursor = checkpoint.cursor();
        boolean compensation = switch (checkpoint.state()) {
            case COMPENSATING, COMPENSATION_REVIEW -> true;
            default -> false;
        };
        int index = switch (checkpoint.state()) {
            case PREPARED, APPLYING, APPLY_REVIEW -> cursor < checkpoint.plan().transfers().size() ? cursor : -1;
            case COMPENSATING, COMPENSATION_REVIEW -> cursor > 0 ? cursor - 1 : -1;
            case COMMITTED, ABORTED -> -1;
        };
        if (index < 0) {
            message(sender, "No external effect pending.");
            return;
        }
        SettlementTransfer transfer = checkpoint.plan().transfers().get(index);
        String source = compensation ? transfer.targetAccount() : transfer.sourceAccount();
        String target = compensation ? transfer.sourceAccount() : transfer.targetAccount();
        message(sender, "Expected effect | step=" + (index + 1) + "/" + checkpoint.plan().transfers().size()
                + " | operation=" + (compensation ? "COMPENSATE" : "APPLY")
                + " | source=" + source + " | target=" + target
                + " | asset=" + transfer.assetId() + " | amount=" + transfer.amount());
        String key = compensation ? checkpoint.plan().compensationKey(index) : checkpoint.plan().applyKey(index);
        boolean review = checkpoint.state() == SettlementCheckpoint.State.APPLY_REVIEW
                || checkpoint.state() == SettlementCheckpoint.State.COMPENSATION_REVIEW;
        message(sender, (review ? "Provider review key=" : "Expected idempotency key=") + key);
    }

    private static void message(CommandSender sender, String text) { sender.sendMessage(Component.text(text)); }

    private static String summary(SettlementCheckpoint checkpoint) {
        return checkpoint.plan().id() + " | " + checkpoint.state() + " | provider=" + checkpoint.plan().providerId()
                + " | cursor=" + checkpoint.cursor() + "/" + checkpoint.plan().transfers().size()
                + " | revision=" + checkpoint.revision();
    }
}
