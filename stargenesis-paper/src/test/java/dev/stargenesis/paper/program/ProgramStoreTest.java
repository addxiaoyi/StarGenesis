package dev.stargenesis.paper.program;

import dev.stargenesis.api.design.DesignId;
import dev.stargenesis.design.PrimitiveCatalog;
import dev.stargenesis.design.ProgramIR;
import dev.stargenesis.design.TypedProgramCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProgramStoreTest {
    @TempDir Path directory;

    private ProgramIR program(long value) {
        return new ProgramIR(List.of(new ProgramIR.Instruction("value", PrimitiveCatalog.Operation.CONSTANT_INTEGER,
                List.of(), Map.of("value", Long.toString(value)))), 32);
    }

    @Test void savesAndReloadsOwnerProgramWithOptimisticConflictCheck() throws Exception {
        UUID owner = new UUID(0, 31);
        DesignId id = new DesignId("player:controller");
        ProgramStore store = new ProgramStore(directory, new TypedProgramCodec());
        var initial = store.save(owner, id, program(1), "");
        var updated = store.save(owner, id, program(2), initial.hash());

        assertNotEquals(initial.hash(), updated.hash());
        assertEquals(updated, store.load(owner, id).orElseThrow());
        assertEquals(1, store.list(owner).size());
        assertThrows(IOException.class, () -> store.save(owner, id, program(3), initial.hash()));
    }

    @Test void keepsProgramsPrivateAndRejectsTamperedFiles() throws Exception {
        UUID owner = new UUID(0, 32);
        UUID other = new UUID(0, 33);
        DesignId id = new DesignId("player:private");
        ProgramStore store = new ProgramStore(directory, new TypedProgramCodec());
        store.save(owner, id, program(1), "");
        assertTrue(store.load(other, id).isEmpty());

        Path file;
        try (var files = Files.list(directory.resolve(owner.toString()))) {
            file = files.findFirst().orElseThrow();
        }
        Files.writeString(file, "{}");
        assertThrows(IOException.class, () -> store.load(owner, id));
    }

    @Test void appliesPerPlayerProgramLimit() throws Exception {
        UUID owner = new UUID(0, 34);
        ProgramStore store = new ProgramStore(directory, new TypedProgramCodec());
        for (int index = 0; index < ProgramStore.MAX_PROGRAMS_PER_PLAYER; index++)
            store.save(owner, new DesignId("player:program-" + index), program(index), "");
        assertThrows(IOException.class, () -> store.save(owner, new DesignId("player:overflow"), program(0), ""));
        assertEquals(ProgramStore.MAX_PROGRAMS_PER_PLAYER, store.list(owner).size());
    }

    @Test void listingRejectsAValidDocumentStoredUnderTheWrongIdFileName() throws Exception {
        UUID owner = new UUID(0, 35);
        DesignId id = new DesignId("player:canonical-path");
        ProgramStore store = new ProgramStore(directory, new TypedProgramCodec());
        var saved = store.save(owner, id, program(1), "");
        Path canonical;
        try (var files = Files.list(directory.resolve(owner.toString()))) { canonical = files.findFirst().orElseThrow(); }
        Path wrong = canonical.resolveSibling("wrong-id.json");
        Files.move(canonical, wrong);
        assertTrue(store.load(owner, id).isEmpty());
        assertThrows(IOException.class, () -> store.list(owner));
        Files.move(wrong, canonical);
        assertEquals(List.of(saved), store.list(owner));
    }

    @Test void failedInitialSaveDoesNotPublishAProgramAndCanRetryTheSameId() throws Exception {
        UUID owner = new UUID(0, 36); DesignId id = new DesignId("player:retry-create");
        var store = new ProgramStore(directory, new TypedProgramCodec());
        String key = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(id.value().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        Files.createDirectories(directory.resolve(owner.toString()));
        Path blocker = Files.createDirectory(directory.resolve(owner.toString()).resolve(key + ".json.tmp"));
        assertThrows(IOException.class, () -> store.save(owner, id, program(1), ""));
        assertFalse(store.exists(owner, id));
        assertTrue(store.list(owner).isEmpty());
        Files.delete(blocker);
        var saved = store.save(owner, id, program(1), "");
        assertEquals(saved, new ProgramStore(directory, new TypedProgramCodec()).load(owner, id).orElseThrow());
    }

    @Test void loadAndListRejectASealedProgramCopiedFromAnotherOwner() throws Exception {
        UUID owner = new UUID(0, 37), other = new UUID(0, 38);
        DesignId id = new DesignId("player:ownership");
        var store = new ProgramStore(directory, new TypedProgramCodec());
        store.save(owner, id, program(1), "");
        Path file;
        try (var files = Files.list(directory.resolve(owner.toString()))) { file = files.findFirst().orElseThrow(); }
        Files.createDirectories(directory.resolve(other.toString()));
        Path copy = directory.resolve(other.toString()).resolve(file.getFileName());
        Files.copy(file, copy); byte[] before = Files.readAllBytes(copy);
        assertThrows(IOException.class, () -> store.load(other, id));
        assertThrows(IOException.class, () -> store.list(other));
        assertThrows(IOException.class, () -> store.save(other, id, program(2), ""));
        assertArrayEquals(before, Files.readAllBytes(copy));
    }
}
