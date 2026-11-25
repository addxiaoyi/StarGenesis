package dev.stargenesis.paper.program;

import dev.stargenesis.api.design.DesignId;
import dev.stargenesis.design.ProgramIR;
import dev.stargenesis.design.TypedProgramCodec;
import dev.stargenesis.design.TypedProgramDocument;
import dev.stargenesis.design.DesignValidationException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Owner-scoped bounded persistence for typed programs. */
public final class ProgramStore {
    public static final int MAX_PROGRAMS_PER_PLAYER = 64;
    private final Path root;
    private final TypedProgramCodec codec;

    public ProgramStore(Path root, TypedProgramCodec codec) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        this.codec = Objects.requireNonNull(codec, "codec");
        Files.createDirectories(this.root);
        rejectSymlink(this.root);
    }

    public synchronized boolean exists(UUID owner, DesignId id) throws IOException {
        Path directory = ownerDirectory(owner);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return false;
        rejectSymlink(directory);
        return Files.exists(path(owner, id), LinkOption.NOFOLLOW_LINKS);
    }

    public synchronized TypedProgramDocument save(UUID owner, DesignId id, ProgramIR program,
                                                   String expectedHash) throws IOException {
        Path directory = ownerDirectory(owner);
        Files.createDirectories(directory);
        rejectSymlink(directory);
        Path target = path(owner, id);
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        if (Files.isSymbolicLink(target) || Files.isSymbolicLink(temporary))
            throw new IOException("Symlink program path rejected");
        verifyExpectedVersion(owner, id, target, expectedHash);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS) && countPrograms(directory) >= MAX_PROGRAMS_PER_PLAYER)
            throw new IOException("Program limit reached");

        String encoded;
        try { encoded = codec.seal(id, owner, program); }
        catch (DesignValidationException invalid) { throw new IOException("Program validation failed", invalid); }
        byte[] data = encoded.getBytes(StandardCharsets.UTF_8);
        if (data.length > TypedProgramCodec.MAX_DOCUMENT_BYTES) throw new IOException("Program exceeds the document size limit");
        try (var channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            var buffer = java.nio.ByteBuffer.wrap(data);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException failure) {
            Files.deleteIfExists(temporary);
            throw new IOException("Atomic program save is unavailable", failure);
        }
        return codec.load(encoded);
    }

    public synchronized Optional<TypedProgramDocument> load(UUID owner, DesignId id) throws IOException {
        Path directory = ownerDirectory(owner);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        rejectSymlink(directory);
        Path target = path(owner, id);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        TypedProgramDocument document = read(target);
        verifyOwner(owner, id, document);
        return Optional.of(document);
    }

    public synchronized List<TypedProgramDocument> list(UUID owner) throws IOException {
        Path directory = ownerDirectory(owner);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
        rejectSymlink(directory);
        List<TypedProgramDocument> programs = new ArrayList<>();
        try (var files = Files.newDirectoryStream(directory, "*.json")) {
            for (Path file : files) {
                if (programs.size() == MAX_PROGRAMS_PER_PLAYER)
                    throw new IOException("Program directory exceeds its file limit");
                TypedProgramDocument document = read(file);
                verifyOwner(owner, document.id(), document);
                if (!file.equals(path(owner, document.id()))) throw new IOException("Program ID does not match its file path");
                programs.add(document);
            }
        }
        return programs.stream().sorted(Comparator.comparing(document -> document.id())).toList();
    }

    private void verifyExpectedVersion(UUID owner, DesignId id, Path target, String expectedHash) throws IOException {
        if (expectedHash == null || !(expectedHash.isEmpty() || expectedHash.matches("[0-9a-f]{64}")))
            throw new IllegalArgumentException("Invalid expected program hash");
        boolean exists = Files.exists(target, LinkOption.NOFOLLOW_LINKS);
        if (expectedHash.isEmpty() && exists) throw new IOException("Program already exists; open it before editing");
        if (!expectedHash.isEmpty() && !exists) throw new IOException("Saved program was removed; reopen it before saving");
        if (exists) {
            TypedProgramDocument current = read(target);
            verifyOwner(owner, id, current);
            if (!current.hash().equals(expectedHash))
                throw new IOException("Saved program changed since it was opened; reopen it before saving");
        }
    }

    private TypedProgramDocument read(Path file) throws IOException {
        rejectSymlink(file);
        if (!Files.isRegularFile(file) || Files.size(file) > TypedProgramCodec.MAX_DOCUMENT_BYTES)
            throw new IOException("Program file is not regular or exceeds the size limit");
        byte[] data;
        try (var input = Files.newInputStream(file)) { data = input.readNBytes(TypedProgramCodec.MAX_DOCUMENT_BYTES + 1); }
        if (data.length > TypedProgramCodec.MAX_DOCUMENT_BYTES) throw new IOException("Program exceeds the size limit");
        try { return codec.load(new String(data, StandardCharsets.UTF_8)); }
        catch (DesignValidationException invalid) { throw new IOException("Stored program failed integrity validation", invalid); }
    }

    private void verifyOwner(UUID owner, DesignId id, TypedProgramDocument document) throws IOException {
        if (!document.id().equals(id) || !document.author().equals(owner))
            throw new IOException("Program owner or ID mismatch");
    }

    private int countPrograms(Path directory) throws IOException {
        int count = 0;
        try (var files = Files.newDirectoryStream(directory, "*.json")) {
            for (Path ignored : files) if (++count > MAX_PROGRAMS_PER_PLAYER) break;
        }
        return count;
    }

    private Path ownerDirectory(UUID owner) {
        if (owner == null) throw new IllegalArgumentException("Missing program owner");
        return root.resolve(owner.toString());
    }

    private Path path(UUID owner, DesignId id) {
        if (id == null) throw new IllegalArgumentException("Missing program ID");
        String key;
        try {
            key = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(id.value().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
        return ownerDirectory(owner).resolve(key + ".json");
    }

    private void rejectSymlink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("Symlink program path rejected");
    }
}
