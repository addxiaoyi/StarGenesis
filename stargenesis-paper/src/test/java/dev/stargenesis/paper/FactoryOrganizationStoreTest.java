package dev.stargenesis.paper;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class FactoryOrganizationStoreTest {
    @TempDir Path temporaryDirectory;

    @Test
    void atomicallyReplacesAndRestoresTheLatestRoster() throws IOException {
        FactoryOrganizationStore store = new FactoryOrganizationStore(temporaryDirectory);
        assertTrue(store.load().isEmpty());
        UUID owner = new UUID(0, 1);
        FactoryOrganization first = new FactoryOrganization("research_team", owner, List.of(owner));
        store.save(first);
        FactoryOrganization second = first.addMember(new UUID(0, 2));
        store.save(second);

        assertEquals(second, store.load().orElseThrow());
        try (var files = Files.list(temporaryDirectory)) {
            assertEquals(List.of("factory-organization-v1.checkpoint"),
                    files.map(path -> path.getFileName().toString()).toList());
        }
    }

    @Test
    void rejectsCorruptAndOversizedRosterFiles() throws IOException {
        FactoryOrganizationStore store = new FactoryOrganizationStore(temporaryDirectory);
        Files.createDirectories(temporaryDirectory);
        Path checkpoint = temporaryDirectory.resolve("factory-organization-v1.checkpoint");
        Files.write(checkpoint, new byte[FactoryOrganizationCodec.MAX_ENCODED_BYTES + 1]);
        assertThrows(IOException.class, store::load);

        Files.write(checkpoint, FactoryOrganizationCodec.encode(
                new FactoryOrganization("research_team", new UUID(0, 1), List.of(new UUID(0, 1)))));
        byte[] corrupted = Files.readAllBytes(checkpoint);
        corrupted[8] ^= 1;
        Files.write(checkpoint, corrupted);
        assertThrows(IOException.class, store::load);
    }

    @Test
    void codecRejectsTrailingDataAndChecksumChanges() {
        FactoryOrganization organization = new FactoryOrganization("research_team", new UUID(0, 1),
                List.of(new UUID(0, 1), new UUID(0, 2)));
        byte[] encoded = FactoryOrganizationCodec.encode(organization);
        assertEquals(organization, FactoryOrganizationCodec.decode(encoded));

        byte[] modified = encoded.clone();
        modified[8] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> FactoryOrganizationCodec.decode(modified));
        byte[] trailing = java.util.Arrays.copyOf(encoded, encoded.length + 1);
        assertThrows(IllegalArgumentException.class, () -> FactoryOrganizationCodec.decode(trailing));
    }
}
