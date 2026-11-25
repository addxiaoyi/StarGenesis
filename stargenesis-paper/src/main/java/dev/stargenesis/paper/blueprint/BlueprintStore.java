package dev.stargenesis.paper.blueprint;

import dev.stargenesis.api.design.DesignId;
import dev.stargenesis.api.design.DesignMetadata;
import dev.stargenesis.api.design.DesignPermissions;
import dev.stargenesis.design.Blueprint;
import dev.stargenesis.design.DesignCodec;
import dev.stargenesis.design.DesignValidationException;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Owner-scoped, bounded persistence for player-authored blueprints. */
public final class BlueprintStore {
    public static final int MAX_BLUEPRINTS_PER_PLAYER = 64;
    private final Path root;
    private final DesignCodec codec;

    public BlueprintStore(Path root, DesignCodec codec) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        this.codec = Objects.requireNonNull(codec, "codec");
        Files.createDirectories(this.root);
        rejectSymlink(this.root);
    }

    public Blueprint newBlueprint(UUID owner, DesignId id) {
        DesignMetadata metadata = new DesignMetadata(id, DesignCodec.SCHEMA_VERSION, "1.0.0", owner,
                "player:" + owner, List.of(), "", new DesignPermissions(false, false, Set.of()));
        return new Blueprint(metadata, List.of(new Blueprint.Component("core", "genesis:core", 0, 0, 0, 0)), List.of());
    }

    public synchronized boolean exists(UUID owner, DesignId id) throws IOException {
        Path directory = ownerDirectory(owner);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return false;
        rejectSymlink(directory);
        return Files.exists(path(owner, id), LinkOption.NOFOLLOW_LINKS);
    }

    public synchronized Blueprint save(UUID owner, Blueprint blueprint, String expectedHash) throws IOException {
        if (!blueprint.metadata().author().equals(owner)) throw new SecurityException("Only the blueprint author may save it");
        Path ownerDirectory = ownerDirectory(owner);
        Files.createDirectories(ownerDirectory);
        rejectSymlink(ownerDirectory);
        Path target = path(owner, blueprint.metadata().id());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        if (Files.isSymbolicLink(target) || Files.isSymbolicLink(temporary))
            throw new IOException("Symlink blueprint path rejected");
        verifyExpectedVersion(owner, blueprint.metadata().id(), target, expectedHash);
        if (!Files.exists(target) && countBlueprints(ownerDirectory) >= MAX_BLUEPRINTS_PER_PLAYER)
            throw new IOException("Blueprint limit reached");

        DesignMetadata metadata = blueprint.metadata();
        DesignMetadata unsignedMetadata = new DesignMetadata(metadata.id(), metadata.schemaVersion(), metadata.version(),
                metadata.author(), metadata.source(), metadata.dependencies(), "", metadata.permissions());
        Blueprint unsigned = new Blueprint(unsignedMetadata, blueprint.components(), blueprint.connections());
        String encoded;
        try { encoded = codec.seal(unsigned); }
        catch (DesignValidationException invalid) { throw new IOException("Blueprint validation failed", invalid); }
        byte[] data = encoded.getBytes(StandardCharsets.UTF_8);
        if (data.length > DesignCodec.MAX_DOCUMENT_BYTES) throw new IOException("Blueprint exceeds the document size limit");
        Files.write(temporary, data);
        try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException failure) {
            Files.deleteIfExists(temporary);
            throw new IOException("Atomic blueprint save is unavailable", failure);
        }
        return (Blueprint) codec.load(encoded);
    }

    public synchronized Optional<Blueprint> load(UUID owner, DesignId id) throws IOException {
        Path directory = ownerDirectory(owner);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        rejectSymlink(directory);
        Path target = path(owner, id);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        Blueprint blueprint = read(target);
        verifyOwner(owner, id, blueprint);
        return Optional.of(blueprint);
    }

    public synchronized List<Blueprint> list(UUID owner) throws IOException {
        Path directory = ownerDirectory(owner);
        if (!Files.exists(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return List.of();
        rejectSymlink(directory);
        List<Blueprint> blueprints = new ArrayList<>();
        try (var files = Files.newDirectoryStream(directory, "*.json")) {
            for (Path file : files) {
                if (blueprints.size() == MAX_BLUEPRINTS_PER_PLAYER) throw new IOException("Blueprint directory exceeds its file limit");
                Blueprint blueprint = read(file);
                verifyOwner(owner, blueprint.metadata().id(), blueprint);
                blueprints.add(blueprint);
            }
        }
        return blueprints.stream().sorted(Comparator.comparing(blueprint -> blueprint.metadata().id())).toList();
    }

    private void verifyExpectedVersion(UUID owner, DesignId id, Path target, String expectedHash) throws IOException {
        if (expectedHash == null || !(expectedHash.isEmpty() || expectedHash.matches("[0-9a-f]{64}")))
            throw new IllegalArgumentException("Invalid expected blueprint hash");
        boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        if (expectedHash.isEmpty() && exists) throw new IOException("Blueprint already exists; open it before editing");
        if (!expectedHash.isEmpty() && !exists) throw new IOException("Saved blueprint was removed; reopen it before saving");
        if (exists) {
            Blueprint current = read(target);
            verifyOwner(owner, id, current);
            if (!current.metadata().hash().equals(expectedHash))
                throw new IOException("Saved blueprint changed since it was opened; reopen it before saving");
        }
    }

    private Blueprint read(Path file) throws IOException {
        rejectSymlink(file);
        if (!Files.isRegularFile(file) || Files.size(file) > DesignCodec.MAX_DOCUMENT_BYTES)
            throw new IOException("Blueprint file is not regular or exceeds the size limit");
        byte[] data;
        try (var input = Files.newInputStream(file)) { data = input.readNBytes(DesignCodec.MAX_DOCUMENT_BYTES + 1); }
        if (data.length > DesignCodec.MAX_DOCUMENT_BYTES) throw new IOException("Blueprint exceeds the size limit");
        try {
            if (codec.load(new String(data, StandardCharsets.UTF_8)) instanceof Blueprint blueprint) return blueprint;
            throw new IOException("Stored document is not a blueprint");
        } catch (DesignValidationException invalid) {
            throw new IOException("Stored blueprint failed integrity validation", invalid);
        }
    }

    private void verifyOwner(UUID owner, DesignId id, Blueprint blueprint) throws IOException {
        if (!blueprint.metadata().id().equals(id) || !blueprint.metadata().author().equals(owner))
            throw new IOException("Blueprint owner or ID mismatch");
    }

    private int countBlueprints(Path directory) throws IOException {
        int count = 0;
        try (var files = Files.newDirectoryStream(directory, "*.json")) {
            for (Path ignored : files) {
                if (++count > MAX_BLUEPRINTS_PER_PLAYER) break;
            }
        }
        return count;
    }

    private Path ownerDirectory(UUID owner) {
        if (owner == null) throw new IllegalArgumentException("Missing blueprint owner");
        return root.resolve(owner.toString());
    }

    private Path path(UUID owner, DesignId id) {
        if (id == null) throw new IllegalArgumentException("Missing blueprint ID");
        String key;
        try {
            key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(id.value().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
        return ownerDirectory(owner).resolve(key + ".json");
    }

    private void rejectSymlink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("Symlink blueprint path rejected");
    }
}
