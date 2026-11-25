package dev.stargenesis.paper.blueprint;

import dev.stargenesis.api.design.DesignId;
import dev.stargenesis.api.design.DesignMetadata;
import dev.stargenesis.api.design.DesignPermissions;
import dev.stargenesis.design.Blueprint;
import dev.stargenesis.design.DesignCodec;
import dev.stargenesis.design.DesignValidationException;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
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

/** Author-scoped, bounded persistence with persisted collaboration and cloning permissions. */
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

    public synchronized Blueprint save(UUID editor, Blueprint blueprint, String expectedHash) throws IOException {
        Objects.requireNonNull(editor, "editor");
        UUID owner = blueprint.metadata().author();
        Path ownerDirectory = ownerDirectory(owner);
        rejectSymlink(ownerDirectory);
        Path target = path(owner, blueprint.metadata().id());
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        if (Files.isSymbolicLink(target) || Files.isSymbolicLink(temporary))
            throw new IOException("Symlink blueprint path rejected");
        Blueprint current = verifyExpectedVersion(owner, blueprint.metadata().id(), target, expectedHash);
        if (!owner.equals(editor)) {
            if (current == null || !current.metadata().permissions().mayEdit(editor, owner))
                throw new SecurityException("Blueprint editing requires the author's current permission");
            DesignMetadata previous = current.metadata();
            DesignMetadata candidate = blueprint.metadata();
            if (!previous.permissions().equals(candidate.permissions())
                    || !previous.source().equals(candidate.source())
                    || !previous.dependencies().equals(candidate.dependencies())
                    || !previous.version().equals(candidate.version())
                    || previous.schemaVersion() != candidate.schemaVersion())
                throw new SecurityException("Only the blueprint author may change permissions, version or provenance");
        }
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
        Files.createDirectories(ownerDirectory);
        rejectSymlink(ownerDirectory);
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(data);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
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

    public synchronized Optional<Blueprint> loadFor(UUID reader, UUID author, DesignId id) throws IOException {
        Objects.requireNonNull(reader, "reader");
        Optional<Blueprint> saved = load(author, id);
        if (saved.isPresent() && !mayRead(reader, saved.get()))
            throw new SecurityException("Blueprint is private and has not been shared with this player");
        return saved;
    }

    public synchronized List<Blueprint> listFor(UUID reader, UUID author) throws IOException {
        Objects.requireNonNull(reader, "reader");
        return list(author).stream().filter(blueprint -> mayRead(reader, blueprint)).toList();
    }

    public synchronized Blueprint cloneBlueprint(UUID actor, UUID sourceAuthor, DesignId sourceId,
                                                  DesignId targetId) throws IOException {
        Blueprint source = loadFor(actor, sourceAuthor, sourceId)
                .orElseThrow(() -> new IOException("Source blueprint does not exist"));
        if (!sourceAuthor.equals(actor) && !source.metadata().permissions().allowClone())
            throw new SecurityException("Blueprint author has not allowed cloning");
        if (sourceId.equals(targetId)) throw new IllegalArgumentException("Clone must use a new design ID");
        DesignMetadata old = source.metadata();
        String provenance = "clone:" + sourceAuthor + ":" + sourceId.value() + "@" + old.version() + "#" + old.hash();
        DesignMetadata metadata = new DesignMetadata(targetId, DesignCodec.SCHEMA_VERSION, "1.0.0", actor,
                provenance, old.dependencies(), "", new DesignPermissions(false, false, Set.of()));
        return save(actor, new Blueprint(metadata, source.components(), source.connections()), "");
    }

    private boolean mayRead(UUID reader, Blueprint blueprint) {
        return blueprint.metadata().permissions().publicRead()
                || blueprint.metadata().permissions().mayEdit(reader, blueprint.metadata().author());
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
                if (!file.equals(path(owner, blueprint.metadata().id())))
                    throw new IOException("Blueprint filename does not match its design ID");
                blueprints.add(blueprint);
            }
        }
        return blueprints.stream().sorted(Comparator.comparing(blueprint -> blueprint.metadata().id())).toList();
    }

    private Blueprint verifyExpectedVersion(UUID owner, DesignId id, Path target, String expectedHash) throws IOException {
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
            return current;
        }
        return null;
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
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return 0;
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
