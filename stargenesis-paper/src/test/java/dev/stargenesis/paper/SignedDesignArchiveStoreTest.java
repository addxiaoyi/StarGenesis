package dev.stargenesis.paper;

import dev.stargenesis.api.design.DesignKind;
import dev.stargenesis.design.DesignCodec;
import dev.stargenesis.design.DesignExchange;
import dev.stargenesis.design.DesignSigningTrust;
import dev.stargenesis.design.SignedDesignArtifact;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class SignedDesignArchiveStoreTest {
    private final DesignCodec codec = new DesignCodec();
    private final DesignExchange exchange = new DesignExchange(codec);

    @Test
    void importsEveryNonArenaKindAndRevalidatesTheArchiveAfterTrustChanges(@TempDir Path directory) throws Exception {
        SignedDesignArchiveStore store = new SignedDesignArchiveStore(directory, exchange);
        List<String> examples = List.of("blueprint", "program", "protocol", "contract");

        for (String example : examples) {
            String json = example(example);
            KeyPair keyPair = DesignExchange.newSigningKey();
            byte[] encoded = exchange.sign(json, keyPair).encode();
            UUID author = codec.load(json).metadata().author();
            DesignSigningTrust trusted = id -> id.equals(author)
                    ? Optional.of(keyPair.getPublic().getEncoded()) : Optional.empty();

            var imported = store.importSigned(encoded, trusted);
            assertEquals(codec.load(json), imported.document());
            assertEquals(imported, store.importSigned(encoded, trusted));
            assertEquals(imported, new SignedDesignArchiveStore(directory, exchange)
                    .find(imported.artifactHash()).orElseThrow());
        }

        var reopened = new SignedDesignArchiveStore(directory, exchange).list();
        assertEquals(4, reopened.size());
        assertEquals(Set.of(DesignKind.BLUEPRINT, DesignKind.PROGRAM, DesignKind.PROTOCOL, DesignKind.CONTRACT),
                reopened.stream().map(entry -> entry.document().kind()).collect(java.util.stream.Collectors.toSet()));
        try (var files = Files.list(directory.resolve("design-library"))) {
            assertEquals(4, files.filter(path -> path.toString().endsWith(".sgdesign")).count());
        }
        try (var files = Files.list(directory.resolve("design-library"))) {
            assertEquals(0, files.filter(path -> path.toString().endsWith(".tmp")).count());
        }
    }

    @Test
    void rejectsUntrustedPublishersAndKeepsArenaRulesInModeration(@TempDir Path directory) throws Exception {
        SignedDesignArchiveStore store = new SignedDesignArchiveStore(directory, exchange);
        KeyPair blueprintKey = DesignExchange.newSigningKey();
        byte[] blueprint = exchange.sign(example("blueprint"), blueprintKey).encode();
        assertThrows(SecurityException.class, () -> store.importSigned(blueprint, id -> Optional.empty()));

        String arenaJson = example("arena_ruleset");
        KeyPair arenaKey = DesignExchange.newSigningKey();
        UUID arenaAuthor = codec.load(arenaJson).metadata().author();
        byte[] arena = exchange.sign(arenaJson, arenaKey).encode();
        DesignSigningTrust trustedArena = id -> id.equals(arenaAuthor)
                ? Optional.of(arenaKey.getPublic().getEncoded()) : Optional.empty();

        assertThrows(IllegalArgumentException.class, () -> store.importSigned(arena, trustedArena));
        assertFalse(Files.exists(directory.resolve("design-library")));
    }

    @Test
    void archiveDetectsCorruptionAndRequiresACompleteArtifactHash(@TempDir Path directory) throws Exception {
        SignedDesignArchiveStore store = new SignedDesignArchiveStore(directory, exchange);
        KeyPair keyPair = DesignExchange.newSigningKey();
        byte[] encoded = exchange.sign(example("program"), keyPair).encode();
        UUID author = codec.load(example("program")).metadata().author();
        DesignSigningTrust trusted = id -> id.equals(author)
                ? Optional.of(keyPair.getPublic().getEncoded()) : Optional.empty();
        var imported = store.importSigned(encoded, trusted);

        assertThrows(IllegalArgumentException.class, () -> store.find("../program"));
        assertTrue(store.find("0".repeat(64)).isEmpty());

        Path stored = directory.resolve("design-library").resolve(imported.artifactHash() + ".sgdesign");
        byte[] damaged = Files.readAllBytes(stored);
        damaged[damaged.length - 1] ^= 1;
        Files.write(stored, damaged);

        assertThrows(IOException.class, store::list);
    }

    private String example(String name) throws IOException {
        try (var input = getClass().getResourceAsStream("/examples/" + name + ".json")) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
