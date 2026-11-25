package dev.stargenesis.paper;

import dev.stargenesis.integrations.SettlementCheckpoint;
import dev.stargenesis.integrations.SettlementPlan;
import dev.stargenesis.integrations.SettlementSagaStore;
import dev.stargenesis.integrations.SettlementTransfer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static dev.stargenesis.integrations.SettlementCheckpoint.State.*;
import static org.junit.jupiter.api.Assertions.*;

class SettlementJournalCommandsTest {
    private static final UUID SAGA = UUID.fromString("00000000-0000-0000-0000-000000000041");
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000042");
    private static final String READ_FAILURE = "Settlement journal could not be read; no record state is available.";

    @Test
    void fileBackedQueriesObserveRecordsAndRevisionsCreatedAfterStartup(@TempDir Path directory) throws Exception {
        SettlementJournalCommands commands = new SettlementJournalCommands(directory);
        SettlementSagaFileStore writer = new SettlementSagaFileStore(directory);
        CapturedSender output = new CapturedSender();
        SettlementPlan plan = plan(SAGA);
        writer.save(new SettlementCheckpoint(plan, PREPARED, 0, 0, ""));

        commands.execute(output.sender, new String[]{"settlement", "list"});
        assertEquals(2, output.messages.size());
        assertTrue(output.text().contains("records=1"));
        assertTrue(output.text().contains("PREPARED"));
        assertTrue(commands.startupReport().contains("records=0"), "The startup report describes the validated startup state");

        writer.save(new SettlementCheckpoint(plan, APPLYING, 0, 1, ""));
        writer.save(new SettlementCheckpoint(plan, APPLYING, 1, 2, ""));
        writer.save(new SettlementCheckpoint(plan, APPLY_REVIEW, 1, 3, "Receipt requires review"));
        output.clear();
        commands.execute(output.sender, inspect(SAGA));
        assertTrue(output.text().contains("APPLY_REVIEW"));
        assertTrue(output.text().contains("revision=3"));
        assertTrue(output.text().contains("step=2/2 | operation=APPLY"));
        assertTrue(output.text().contains("source=account:escrow | target=account:seller | asset=starcore:plate | amount=20"));
        assertTrue(output.text().contains("Provider review key=" + plan.applyKey(1)));
        assertFalse(output.text().contains("revision=0"));

        SettlementPlan second = plan(UUID.fromString("00000000-0000-0000-0000-000000000043"));
        writer.save(new SettlementCheckpoint(second, PREPARED, 0, 0, ""));
        output.clear();
        commands.execute(output.sender, new String[]{"settlement", "list"});
        assertTrue(output.text().contains("records=2"));
        assertTrue(output.text().contains(second.id().toString()));
    }

    @Test
    void corruptionAfterASuccessfulQueryNeverReturnsTheOldState(@TempDir Path directory) throws Exception {
        SettlementSagaFileStore writer = new SettlementSagaFileStore(directory);
        writer.save(new SettlementCheckpoint(plan(SAGA), PREPARED, 0, 0, ""));
        SettlementJournalCommands commands = new SettlementJournalCommands(directory);
        CapturedSender output = new CapturedSender();
        commands.execute(output.sender, inspect(SAGA));
        assertTrue(output.text().contains("account:buyer"));

        Path file = directory.resolve("settlement-sagas").resolve(SAGA + ".saga");
        byte[] encoded = Files.readAllBytes(file);
        encoded[encoded.length - 1] ^= 1;
        Files.write(file, encoded);
        for (String[] args : List.of(new String[]{"settlement", "list"}, inspect(SAGA))) {
            output.clear();
            commands.execute(output.sender, args);
            assertEquals(List.of(Component.text(READ_FAILURE)), output.messages);
            assertFalse(output.text().contains("account:buyer"));
            assertFalse(output.text().contains(SAGA.toString()));
        }
        assertThrows(IllegalStateException.class, () -> new SettlementJournalCommands(directory));
    }

    @Test
    void compensationReviewDisplaysTheExactInverseAndItsOwnStableKey() {
        MutableReadStore store = new MutableReadStore();
        SettlementPlan plan = plan(SAGA);
        store.records = List.of(new SettlementCheckpoint(plan, COMPENSATION_REVIEW, 2, 5, "Inverse not confirmed"));
        SettlementJournalCommands commands = new SettlementJournalCommands(store);
        CapturedSender output = new CapturedSender();

        commands.execute(output.sender, inspect(SAGA));
        assertTrue(output.text().contains("step=2/2 | operation=COMPENSATE"));
        assertTrue(output.text().contains("source=account:seller | target=account:escrow | asset=starcore:plate | amount=20"));
        assertTrue(output.text().contains("Provider review key=" + plan.compensationKey(1)));
        assertFalse(output.text().contains(plan.applyKey(1)));
        assertEquals(2, store.loads, "The command must read again after startup validation");
    }

    @Test
    void everyLegalCursorBoundaryHasAnAccurateReadOnlyView() {
        MutableReadStore store = new MutableReadStore();
        SettlementPlan plan = plan(SAGA);
        SettlementJournalCommands commands = new SettlementJournalCommands(store);
        CapturedSender output = new CapturedSender();

        for (SettlementCheckpoint checkpoint : List.of(
                new SettlementCheckpoint(plan, PREPARED, 0, 0, ""),
                new SettlementCheckpoint(plan, APPLYING, 0, 1, ""),
                new SettlementCheckpoint(plan, APPLY_REVIEW, 0, 2, ""))) {
            store.records = List.of(checkpoint);
            output.clear();
            commands.execute(output.sender, inspect(SAGA));
            assertTrue(output.text().contains("step=1/2 | operation=APPLY"), checkpoint.state().toString());
            assertTrue(output.text().contains("source=account:buyer | target=account:escrow"));
            assertTrue(output.text().contains(plan.applyKey(0)));
        }
        for (SettlementCheckpoint checkpoint : List.of(
                new SettlementCheckpoint(plan, COMPENSATING, 1, 5, ""),
                new SettlementCheckpoint(plan, COMPENSATION_REVIEW, 1, 6, ""))) {
            store.records = List.of(checkpoint);
            output.clear();
            commands.execute(output.sender, inspect(SAGA));
            assertTrue(output.text().contains("step=1/2 | operation=COMPENSATE"), checkpoint.state().toString());
            assertTrue(output.text().contains("source=account:escrow | target=account:buyer"));
            assertTrue(output.text().contains(plan.compensationKey(0)));
        }
        for (SettlementCheckpoint checkpoint : List.of(
                new SettlementCheckpoint(plan, APPLYING, 2, 3, ""),
                new SettlementCheckpoint(plan, COMPENSATING, 0, 5, ""),
                new SettlementCheckpoint(plan, COMMITTED, 2, 4, ""),
                new SettlementCheckpoint(plan, ABORTED, 0, 6, ""))) {
            store.records = List.of(checkpoint);
            output.clear();
            commands.execute(output.sender, inspect(SAGA));
            assertTrue(output.text().contains("No external effect pending."), checkpoint.state().toString());
            assertFalse(output.text().contains("Expected effect"));
            assertFalse(output.text().contains("key="));
        }
    }

    @Test
    void paginationStaysBoundedAndInvalidQueriesDoNotRevealRecords() {
        MutableReadStore store = new MutableReadStore();
        List<SettlementCheckpoint> records = new ArrayList<>();
        for (int index = 1; index <= 21; index++)
            records.add(new SettlementCheckpoint(plan(new UUID(0, index)), PREPARED, 0, 0, ""));
        store.records = List.copyOf(records);
        SettlementJournalCommands commands = new SettlementJournalCommands(store);
        CapturedSender output = new CapturedSender();

        commands.execute(output.sender, new String[]{"settlement", "list", "1"});
        assertEquals(21, output.messages.size());
        assertTrue(output.text().contains("page=1/2"));
        assertFalse(output.text().contains(records.getLast().plan().id().toString()));
        output.clear();
        commands.execute(output.sender, new String[]{"settlement", "list", "2"});
        assertEquals(2, output.messages.size());
        assertTrue(output.text().contains(records.getLast().plan().id().toString()));
        for (String page : List.of("0", "3", "-1", "1.0", "1000")) {
            output.clear();
            commands.execute(output.sender, new String[]{"settlement", "list", page});
            assertEquals(List.of(Component.text("Settlement journal page must be within 1..2")), output.messages);
        }
        output.clear();
        commands.execute(output.sender, new String[]{"settlement", "inspect", "1-1-1-1-1"});
        assertEquals(List.of(Component.text("Supply the complete settlement saga UUID.")), output.messages);
        output.clear();
        commands.execute(output.sender, inspect(SAGA));
        assertEquals(List.of(Component.text("Unknown settlement saga.")), output.messages);
    }

    @Test
    void readFailuresRemainExplicitAndNeverUseASuccessfulStartupSnapshot() {
        MutableReadStore unavailable = new MutableReadStore();
        unavailable.failure = new IOException("Provider journal is unavailable");
        assertThrows(IllegalStateException.class, () -> new SettlementJournalCommands(unavailable));

        MutableReadStore store = new MutableReadStore();
        store.records = List.of(new SettlementCheckpoint(plan(SAGA), APPLY_REVIEW, 0, 2, "review"));
        SettlementJournalCommands commands = new SettlementJournalCommands(store);
        store.failure = new IOException("Read failed after startup");
        CapturedSender output = new CapturedSender();
        for (String[] args : List.of(new String[]{"settlement", "list"}, inspect(SAGA))) {
            output.clear();
            commands.execute(output.sender, args);
            assertEquals(List.of(Component.text(READ_FAILURE)), output.messages);
        }
    }

    @Test
    void storedDiagnosticAndExpectedIdentifiersAreSentAsLiteralComponents() {
        String diagnostic = "<click:run_command:'/op nobody'>receipt missing</click>";
        String asset = "<red>resource</red>";
        SettlementPlan plan = new SettlementPlan(SAGA, ACTOR, "starcore", List.of(
                new SettlementTransfer("source:<alice>", "target:<bob>", asset, 10)));
        MutableReadStore store = new MutableReadStore();
        store.records = List.of(new SettlementCheckpoint(plan, APPLY_REVIEW, 0, 2, diagnostic));
        CapturedSender output = new CapturedSender();
        new SettlementJournalCommands(store).execute(output.sender, inspect(SAGA));

        assertEquals(Component.text(diagnostic), output.messages.get(1));
        assertEquals(Component.text("Expected effect | step=1/1 | operation=APPLY"
                + " | source=source:<alice> | target=target:<bob> | asset=" + asset + " | amount=10"), output.messages.get(2));
        output.messages.forEach(component -> assertInstanceOf(TextComponent.class, component));
    }

    private static SettlementPlan plan(UUID id) {
        return new SettlementPlan(id, ACTOR, "starcore", List.of(
                new SettlementTransfer("account:buyer", "account:escrow", "starcore:ore", 10),
                new SettlementTransfer("account:escrow", "account:seller", "starcore:plate", 20)));
    }

    private static String[] inspect(UUID id) { return new String[]{"settlement", "inspect", id.toString()}; }

    private static final class MutableReadStore implements SettlementSagaStore {
        private List<SettlementCheckpoint> records = List.of();
        private IOException failure;
        private int loads;

        @Override public List<SettlementCheckpoint> loadAll() throws IOException {
            loads++;
            if (failure != null) throw failure;
            return List.copyOf(records);
        }

        @Override public Optional<SettlementCheckpoint> find(UUID id) { throw new AssertionError("Review commands read a validated full journal"); }
        @Override public void save(SettlementCheckpoint checkpoint) { throw new AssertionError("Review commands must stay read-only"); }
    }

    private static final class CapturedSender {
        private final List<Component> messages = new ArrayList<>();
        private final CommandSender sender = (CommandSender) Proxy.newProxyInstance(CommandSender.class.getClassLoader(),
                new Class<?>[]{CommandSender.class}, (proxy, method, args) -> {
                    if (method.getName().equals("sendMessage") && args != null && args.length == 1
                            && args[0] instanceof Component component) {
                        messages.add(component);
                        return null;
                    }
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "CapturedSettlementReviewer";
                            case "hashCode" -> System.identityHashCode(proxy);
                            case "equals" -> proxy == args[0];
                            default -> throw new UnsupportedOperationException(method.toString());
                        };
                    }
                    throw new AssertionError("Unexpected command sender operation: " + method);
                });

        private String text() {
            return String.join("\n", messages.stream().map(component -> ((TextComponent) component).content()).toList());
        }

        private void clear() { messages.clear(); }
    }
}
