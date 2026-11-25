package dev.stargenesis.paper;

import dev.stargenesis.api.design.DesignDependency;
import dev.stargenesis.api.design.DesignId;
import dev.stargenesis.api.design.DesignMetadata;
import dev.stargenesis.api.design.DesignPermissions;
import dev.stargenesis.design.ArenaRuleset;
import dev.stargenesis.design.ArenaRulesetEditorSession;
import dev.stargenesis.design.DesignCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class PublishedDesignStoreTest {
    private static final UUID AUTHOR = UUID.fromString("00000000-0000-0000-0000-000000000021");
    private static final UUID PRIVATE_AUTHOR = UUID.fromString("00000000-0000-0000-0000-000000000022");
    private final DesignCodec codec = new DesignCodec();

    @Test
    void publicationIsExactImmutableAndListsStoredRulesets(@TempDir Path directory) throws Exception {
        PublishedDesignStore store = new PublishedDesignStore(directory, codec);
        ArenaRuleset starter = starter();
        store.seedBundled(starter);
        ArenaRuleset firstDraft = ArenaRulesetEditorSession.create(AUTHOR, starter).draft();
        ArenaRuleset first = (ArenaRuleset) store.publish(firstDraft, AUTHOR);
        DesignDependency ownRelease = new DesignDependency(first.metadata().id(), first.metadata().version(), first.metadata().hash());
        assertEquals(first, store.findExact(ownRelease).orElseThrow());
        assertEquals(2, store.list(first.kind()).size());
        assertEquals(first, store.publish(first, AUTHOR));

        ArenaRulesetEditorSession changedEditor = ArenaRulesetEditorSession.create(AUTHOR, starter);
        changedEditor.adjustWidth(1);
        changedEditor.preview();
        ArenaRuleset changed = (ArenaRuleset) codec.load(changedEditor.save(codec));
        assertThrows(java.io.IOException.class, () -> store.publish(changed, AUTHOR));

        ArenaRulesetEditorSession nextVersion = ArenaRulesetEditorSession.create(AUTHOR, starter, changed);
        nextVersion.adjustWidth(1);
        nextVersion.preview();
        ArenaRuleset versioned = (ArenaRuleset) codec.load(nextVersion.save(codec));
        assertEquals("1.0.1", versioned.metadata().version());
        assertEquals(versioned, store.publish(versioned, AUTHOR));
    }

    @Test
    void privateAndMissingDependenciesAreRejected(@TempDir Path directory) throws Exception {
        PublishedDesignStore store = new PublishedDesignStore(directory, codec);
        ArenaRuleset starter = starter();
        store.seedBundled(starter);
        DesignMetadata privateMetadata = new DesignMetadata(new DesignId("test:private-dependency"), 1, "1.0.0",
                PRIVATE_AUTHOR, "test:private", List.of(), "", new DesignPermissions(false, false, Set.of()));
        ArenaRuleset privateRelease = new ArenaRuleset(privateMetadata, 16, 16, 1200, 4);
        ArenaRuleset storedPrivate = (ArenaRuleset) store.publish(privateRelease, PRIVATE_AUTHOR);

        DesignMetadata dependentMetadata = new DesignMetadata(new DesignId("test:dependent-arena"), 1, "1.0.0", AUTHOR,
                "test:arena", List.of(new DesignDependency(storedPrivate.metadata().id(), "1.0.0",
                storedPrivate.metadata().hash())), "", new DesignPermissions(false, false, Set.of()));
        ArenaRuleset dependent = new ArenaRuleset(dependentMetadata, 16, 16, 1200, 4);
        assertThrows(SecurityException.class, () -> store.publish(dependent, AUTHOR));

        DesignMetadata missingMetadata = new DesignMetadata(new DesignId("test:missing-dependent"), 1, "1.0.0", AUTHOR,
                "test:arena", List.of(new DesignDependency(new DesignId("test:missing"), "1.0.0", "0".repeat(64))),
                "", new DesignPermissions(false, false, Set.of()));
        ArenaRuleset missing = new ArenaRuleset(missingMetadata, 16, 16, 1200, 4);
        assertThrows(IllegalArgumentException.class, () -> store.publish(missing, AUTHOR));
    }

    @Test
    void latestPublishedPermissionsControlEditingAndOnlyOwnerCanRevoke(@TempDir Path directory) throws Exception {
        PublishedDesignStore store = new PublishedDesignStore(directory, codec);
        ArenaRuleset starter = starter();
        store.seedBundled(starter);

        ArenaRuleset first = publishDraft(store, ArenaRulesetEditorSession.create(AUTHOR, starter), AUTHOR);
        ArenaRulesetEditorSession grant = ArenaRulesetEditorSession.edit(AUTHOR, first);
        grant.grantEditor(PRIVATE_AUTHOR);
        ArenaRuleset shared = publishDraft(store, grant, AUTHOR);
        assertEquals("1.0.1", shared.metadata().version());
        assertTrue(shared.metadata().permissions().editors().contains(PRIVATE_AUTHOR));
        assertDoesNotThrow(() -> store.requireCanEdit(shared, PRIVATE_AUTHOR));

        ArenaRulesetEditorSession editor = ArenaRulesetEditorSession.edit(PRIVATE_AUTHOR, shared);
        assertThrows(SecurityException.class, () -> editor.grantEditor(UUID.randomUUID()));
        editor.adjustWidth(1);
        editor.bumpPatchVersion();
        editor.preview();
        ArenaRuleset editorRelease = publishDraft(store, editor, PRIVATE_AUTHOR);
        assertEquals(17, editorRelease.width());

        ArenaRulesetEditorSession revoke = ArenaRulesetEditorSession.edit(AUTHOR, editorRelease);
        revoke.revokeEditor(PRIVATE_AUTHOR);
        ArenaRuleset revoked = publishDraft(store, revoke, AUTHOR);
        assertEquals("1.0.3", revoked.metadata().version());
        assertFalse(revoked.metadata().permissions().editors().contains(PRIVATE_AUTHOR));
        assertThrows(SecurityException.class, () -> store.requireCanEdit(editorRelease, PRIVATE_AUTHOR));

        ArenaRulesetEditorSession staleEditor = ArenaRulesetEditorSession.edit(PRIVATE_AUTHOR, editorRelease);
        staleEditor.adjustHeight(1);
        staleEditor.bumpPatchVersion();
        staleEditor.preview();
        ArenaRuleset staleCandidate = (ArenaRuleset) codec.load(staleEditor.save(codec));
        assertThrows(SecurityException.class, () -> store.publish(staleCandidate, PRIVATE_AUTHOR));
    }

    @Test
    void corruptReleaseFailsClosed(@TempDir Path directory) throws Exception {
        PublishedDesignStore store = new PublishedDesignStore(directory, codec);
        ArenaRuleset starter = starter();
        store.seedBundled(starter);
        Path release;
        try (var files = Files.walk(directory)) {
            release = files.filter(path -> path.getFileName().toString().endsWith(".json")).findFirst().orElseThrow();
        }
        Files.writeString(release, "{}", StandardCharsets.UTF_8);
        assertThrows(java.io.IOException.class, () -> store.list(starter.kind()));
    }

    @Test
    void publicationRevalidatesWorkBudgetAtTheStorageBoundary(@TempDir Path directory) throws Exception {
        PublishedDesignStore store = new PublishedDesignStore(directory, codec);
        ArenaRuleset source = ArenaRulesetEditorSession.create(AUTHOR, starter()).draft();
        ArenaRuleset expensive = new ArenaRuleset(source.metadata(), 128, 128, 72_000, 64);
        assertThrows(IllegalArgumentException.class, () -> store.publish(expensive, AUTHOR));
    }

    private ArenaRuleset starter() throws Exception {
        try (InputStream input = getClass().getResourceAsStream("/examples/arena_ruleset.json")) {
            assertNotNull(input);
            return (ArenaRuleset) codec.load(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private ArenaRuleset publishDraft(PublishedDesignStore store, ArenaRulesetEditorSession editor, UUID actor) throws Exception {
        editor.preview();
        ArenaRuleset saved = (ArenaRuleset) codec.load(editor.save(codec));
        return (ArenaRuleset) store.publish(saved, actor);
    }
}
