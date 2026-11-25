package dev.stargenesis.paper;

import dev.stargenesis.sim.FactoryCheckpointCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FactoryCheckpointStoreTest {
    @TempDir Path temporaryDirectory;

    @Test
    void atomicallyReplacesAndReloadsTheLatestCheckpoint() throws IOException {
        FactoryCheckpointStore store = new FactoryCheckpointStore(temporaryDirectory);
        assertTrue(store.load().isEmpty());

        FactorySession first = FactorySession.start(members());
        store.save(first.checkpoint());
        FactorySession second = first.command(members().getFirst(), new String[] {
                "order", "factory:frame", "2", "10", "4", "4"}).session();
        store.save(second.checkpoint());

        var recovered = store.load().orElseThrow();
        assertArrayEquals(second.encode(), FactoryCheckpointCodec.encode(recovered));
        try (var files = Files.list(temporaryDirectory)) {
            assertEquals(List.of("factory-v1.checkpoint"), files.map(path -> path.getFileName().toString()).toList());
        }
    }

    @Test
    void rejectsAnOversizedCheckpointBeforeDecoding() throws IOException {
        Files.createDirectories(temporaryDirectory);
        Files.write(temporaryDirectory.resolve("factory-v1.checkpoint"), new byte[8]);
        FactoryCheckpointStore store = new FactoryCheckpointStore(temporaryDirectory,
                path -> (long) FactoryCheckpointCodec.MAX_ENCODED_BYTES + 1);

        assertThrows(IOException.class, store::load);
    }

    @Test
    void failedAtomicReplacementPreservesTheCommittedCheckpoint() throws IOException {
        FactoryCheckpointStore store = new FactoryCheckpointStore(temporaryDirectory);
        FactorySession committed = FactorySession.start(members());
        store.save(committed.checkpoint());
        byte[] committedBytes = store.load().map(FactoryCheckpointCodec::encode).orElseThrow();

        FactorySession staged = committed.command(members().getFirst(), new String[] {
                "order", "factory:frame", "2", "10", "4", "4"}).session();
        FactoryCheckpointStore failingStore = new FactoryCheckpointStore(temporaryDirectory,
                (temporary, target) -> {
                    assertTrue(Files.isRegularFile(temporary));
                    assertEquals(temporaryDirectory.resolve("factory-v1.checkpoint"), target);
                    throw new IOException("simulated failure before atomic replacement");
                });

        assertThrows(IOException.class, () -> failingStore.save(staged.checkpoint()));
        assertArrayEquals(committedBytes,
                failingStore.load().map(FactoryCheckpointCodec::encode).orElseThrow());
        try (var files = Files.list(temporaryDirectory)) {
            assertEquals(List.of("factory-v1.checkpoint"),
                    files.map(path -> path.getFileName().toString()).sorted().toList());
        }
    }

    private static List<UUID> members() {
        return List.of(1, 2, 3, 4).stream().map(index -> new UUID(0, index)).toList();
    }
}
