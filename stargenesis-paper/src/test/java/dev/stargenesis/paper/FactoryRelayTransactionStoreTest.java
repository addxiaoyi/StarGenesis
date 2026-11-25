package dev.stargenesis.paper;

import dev.stargenesis.api.sim.AgentId;
import dev.stargenesis.sim.FactoryCheckpointCodec;
import dev.stargenesis.sim.FactoryWorld;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FactoryRelayTransactionStoreTest {
    @TempDir Path directory;
    private static final List<UUID> MEMBERS = List.of(1, 2, 3, 4).stream()
            .map(value -> new UUID(0, value)).toList();
    private static final UUID REQUEST = new UUID(0, 40);
    private static final String REQUEST_DIGEST = "a".repeat(64);

    @Test
    void recoversAPreparedTransactionBeforeTheFactoryCheckpointWasWritten() throws IOException {
        FactoryCheckpointStore factory = new FactoryCheckpointStore(directory);
        FactorySession before = readyForCollection();
        factory.save(before.checkpoint());
        var collected = collect(before);
        new FactoryRelayTransactionStore(directory).prepare(before.checkpoint(), collected.session().checkpoint(),
                REQUEST, REQUEST_DIGEST, collected.message());

        FactoryCheckpointStore failingFactory = new FactoryCheckpointStore(directory,
                (temporary, target) -> { throw new IOException("simulated checkpoint failure"); });
        FactoryRelayReceiptStore receipts = new FactoryRelayReceiptStore(directory);
        assertThrows(IOException.class, () -> new FactoryRelayTransactionStore(directory)
                .recover(failingFactory, receipts));
        assertEquals(before.checkpoint(), factory.load().orElseThrow());

        var recovered = new FactoryRelayTransactionStore(directory).recover(factory,
                new FactoryRelayReceiptStore(directory)).orElseThrow();
        assertEquals(collected.session().checkpoint(), recovered);
        assertEquals(collected.message(), new FactoryRelayReceiptStore(directory).find(REQUEST).orElseThrow().response());
        assertTrue(new FactoryRelayTransactionStore(directory).recover(factory, receipts).isEmpty());
    }

    @Test
    void checkpointCommitBeforeReceiptFailureRecoversOnceAndSurvivesLaterTicks() throws IOException {
        FactoryCheckpointStore factory = new FactoryCheckpointStore(directory);
        FactorySession before = readyForCollection();
        factory.save(before.checkpoint());
        var collected = collect(before);
        FactoryRelayTransactionStore transactions = new FactoryRelayTransactionStore(directory);
        transactions.prepare(before.checkpoint(), collected.session().checkpoint(), REQUEST,
                REQUEST_DIGEST, collected.message());
        AtomicInteger writes = new AtomicInteger();
        FactoryRelayReceiptStore failingReceipts = new FactoryRelayReceiptStore(directory, (temporary, target) -> {
            if (writes.incrementAndGet() == 2) throw new IOException("simulated receipt commit failure");
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        });
        assertThrows(IOException.class, () -> transactions.recover(factory, failingReceipts));
        assertEquals(collected.session().checkpoint(), factory.load().orElseThrow());
        assertEquals(FactoryRelayReceiptStore.State.PENDING,
                new FactoryRelayReceiptStore(directory).find(REQUEST).orElseThrow().state());

        FactoryRelayReceiptStore receipts = new FactoryRelayReceiptStore(directory);
        FactorySession restored = FactorySession.restore(new FactoryRelayTransactionStore(directory)
                .recover(factory, receipts).orElseThrow());
        FactorySession later = restored.command(MEMBERS.getFirst(), new String[]{"advance", "2", "1"}).session();
        factory.save(later.checkpoint());
        assertEquals(collected.message(), receipts.find(REQUEST).orElseThrow().response());
        assertEquals(FactoryRelayReceiptStore.State.DONE, receipts.find(REQUEST).orElseThrow().state());
        assertTrue(transactions.recover(factory, receipts).isEmpty());
        try (FactoryWorld world = FactoryWorld.restore(factory.load().orElseThrow())) {
            assertEquals(26, world.balance(FactoryWorld.playerAccount(new AgentId(MEMBERS.getFirst())),
                    FactoryWorld.COMPONENT_RESOURCE));
        }
    }

    @Test
    void refusesDivergentFactoryStateOrReusedReceiptIdentity() throws IOException {
        FactoryCheckpointStore factory = new FactoryCheckpointStore(directory);
        FactorySession before = readyForCollection();
        factory.save(before.checkpoint());
        var collected = collect(before);
        FactoryRelayTransactionStore transactions = new FactoryRelayTransactionStore(directory);
        transactions.prepare(before.checkpoint(), collected.session().checkpoint(), REQUEST,
                REQUEST_DIGEST, collected.message());
        FactorySession divergent = before.command(MEMBERS.getFirst(), new String[]{"advance", "2", "1"}).session();
        factory.save(divergent.checkpoint());
        FactoryRelayReceiptStore receipts = new FactoryRelayReceiptStore(directory);
        assertThrows(IOException.class, () -> transactions.recover(factory, receipts));
        assertEquals(divergent.checkpoint(), factory.load().orElseThrow());
        factory.save(before.checkpoint());
        receipts.begin(REQUEST, "b".repeat(64));
        assertThrows(IOException.class, () -> transactions.recover(factory, receipts));
        assertEquals(before.checkpoint(), factory.load().orElseThrow());
    }

    @Test
    void rejectsCorruptJournalWithoutMutatingTheFactory() throws IOException {
        FactoryCheckpointStore factory = new FactoryCheckpointStore(directory);
        FactorySession before = readyForCollection();
        factory.save(before.checkpoint());
        var collected = collect(before);
        FactoryRelayTransactionStore transactions = new FactoryRelayTransactionStore(directory);
        transactions.prepare(before.checkpoint(), collected.session().checkpoint(), REQUEST,
                REQUEST_DIGEST, collected.message());
        Path journal = directory.resolve("factory-relay/transaction-v1.checkpoint");
        byte[] encoded = Files.readAllBytes(journal);
        encoded[30] ^= 1;
        Files.write(journal, encoded);
        assertThrows(IOException.class, () -> transactions.recover(factory, new FactoryRelayReceiptStore(directory)));
        assertArrayEquals(before.encode(), FactoryCheckpointCodec.encode(factory.load().orElseThrow()));
    }

    private static FactorySession readyForCollection() {
        FactorySession session = FactorySession.start(MEMBERS);
        session = session.command(MEMBERS.getFirst(),
                new String[]{"station", "queue", "factory:frame", "1", "1"}).session();
        return session.command(MEMBERS.getFirst(), new String[]{"advance", "0", "2"}).session();
    }

    private static FactorySession.CommandResult collect(FactorySession session) {
        return session.command(MEMBERS.getFirst(), new String[]{"station", "collect", "2", "2"});
    }
}
