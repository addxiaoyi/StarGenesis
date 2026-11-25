package dev.stargenesis.paper.design;

import dev.stargenesis.api.design.DesignDocument;
import dev.stargenesis.api.design.DesignId;
import dev.stargenesis.design.DesignCodec;
import dev.stargenesis.design.DesignValidationException;
import dev.stargenesis.design.SignedDesignCodec;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
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
import java.util.List;
import java.util.UUID;

/** Owner-private imported signed designs. Inbox names and destination paths are host-derived. */
public final class SignedDesignPackageStore {
    public static final int MAX_IMPORTS_PER_PLAYER = 64;
    private final Path inbox;
    private final Path imported;
    private final PublisherKeyStore publishers;
    private final SignedDesignCodec codec = new SignedDesignCodec(new DesignCodec());
    private final ObjectMapper mapper;

    public SignedDesignPackageStore(Path dataDirectory) throws IOException {
        Path root = dataDirectory.toAbsolutePath().normalize();
        inbox = root.resolve("design-inbox");
        imported = root.resolve("imports");
        Files.createDirectories(inbox);
        Files.createDirectories(imported);
        rejectSymlink(root); rejectSymlink(inbox); rejectSymlink(imported);
        publishers = new PublisherKeyStore(root.resolve("trusted-publishers"));
        mapper = new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(36)
                        .maxStringLength(SignedDesignCodec.MAX_ENVELOPE_BYTES).maxNumberLength(20).build()).build());
        mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS);
        mapper.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        mapper.disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    }

    public synchronized Imported install(UUID owner, String packageName) throws IOException {
        if (owner == null) throw new IllegalArgumentException("Missing import owner");
        if (packageName == null || !packageName.matches("[a-z0-9][a-z0-9_-]{0,63}"))
            throw new IllegalArgumentException("Package name must be a bounded lowercase ID");
        Path source = inbox.resolve(packageName + ".json");
        String envelope = readBounded(source);
        SignedDesignCodec.ImportedDesign verified;
        try { verified = codec.verifyAndLoad(envelope, publishers.loadAll()); }
        catch (DesignValidationException invalid) { throw new IOException("Signed design import rejected", invalid); }
        Path directory = ownerDirectory(owner);
        Files.createDirectories(directory);
        rejectSymlink(directory);
        Path destination = path(owner, verified.document().metadata().id());
        boolean exists = Files.exists(destination, LinkOption.NOFOLLOW_LINKS);
        if (!exists && count(directory) >= MAX_IMPORTS_PER_PLAYER) throw new IOException("Private design import limit reached");
        if (exists) {
            Imported previous = read(destination);
            verifyAgain(owner, destination, previous);
            if (!previous.documentHash().equals(verified.documentHash())
                    || !previous.publisherKeyId().equals(verified.publisherKeyId()))
                throw new IOException("Imported design ID already exists with another publisher or hash");
            return previous;
        }
        Imported importedDesign = new Imported(1, verified.publisherKeyId(), verified.document().kind().name(),
                verified.document().metadata().id(), verified.document().metadata().version(),
                verified.documentHash(), envelope);
        write(destination, importedDesign);
        return importedDesign;
    }

    public synchronized List<Imported> list(UUID owner) throws IOException {
        Path directory = ownerDirectory(owner);
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return List.of();
        rejectSymlink(directory);
        List<Imported> entries = new ArrayList<>();
        try (var files = Files.newDirectoryStream(directory, "*.json")) {
            for (Path file : files) {
                if (entries.size() >= MAX_IMPORTS_PER_PLAYER) throw new IOException("Import directory exceeds its count budget");
                Imported saved = read(file);
                verifyAgain(owner, file, saved);
                entries.add(saved);
            }
        }
        return entries.stream().sorted(Comparator.comparing(Imported::id)).toList();
    }

    public synchronized DesignDocument load(UUID owner, DesignId id) throws IOException {
        Path file = path(owner, id);
        Imported saved = read(file);
        return verifyAgain(owner, file, saved);
    }

    private DesignDocument verifyAgain(UUID owner, Path file, Imported saved) throws IOException {
        if (saved == null || saved.schemaVersion() != 1)
            throw new IOException("Unsupported imported design schema");
        try {
            SignedDesignCodec.ImportedDesign verified = codec.verifyAndLoad(saved.signedEnvelope(), publishers.loadAll());
            if (!verified.documentHash().equals(saved.documentHash())
                    || !verified.publisherKeyId().equals(saved.publisherKeyId())
                    || !verified.document().metadata().id().equals(saved.id())
                    || !verified.document().metadata().version().equals(saved.version())
                    || !verified.document().kind().name().equals(saved.kind()))
                throw new IOException("Imported design manifest does not match its signed document");
            if (!path(owner, saved.id()).equals(file))
                throw new IOException("Imported design path does not match its signed identity");
            return verified.document();
        } catch (DesignValidationException invalid) { throw new IOException("Imported signature or trust root is invalid", invalid); }
    }

    private Imported read(Path file) throws IOException {
        rejectSymlink(file.getParent());
        rejectSymlink(file);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > SignedDesignCodec.MAX_ENVELOPE_BYTES + 4096)
            throw new IOException("Imported design is not regular or exceeds its size limit");
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(SignedDesignCodec.MAX_ENVELOPE_BYTES + 4097);
            if (bytes.length > SignedDesignCodec.MAX_ENVELOPE_BYTES + 4096) throw new IOException("Imported design exceeds its size limit");
            return mapper.readValue(bytes, Imported.class);
        }
    }

    private String readBounded(Path file) throws IOException {
        rejectSymlink(file);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                || Files.size(file) > SignedDesignCodec.MAX_ENVELOPE_BYTES)
            throw new IOException("Signed design inbox package is missing or exceeds 300 KiB");
        try (var input = Files.newInputStream(file)) {
            byte[] bytes = input.readNBytes(SignedDesignCodec.MAX_ENVELOPE_BYTES + 1);
            if (bytes.length > SignedDesignCodec.MAX_ENVELOPE_BYTES)
                throw new IOException("Signed design inbox package exceeds 300 KiB");
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private void write(Path target, Imported imported) throws IOException {
        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        rejectSymlink(target); rejectSymlink(temporary);
        byte[] bytes = mapper.writeValueAsBytes(imported);
        if (bytes.length > SignedDesignCodec.MAX_ENVELOPE_BYTES + 4096)
            throw new IOException("Imported design package exceeds its size limit");
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            var buffer = java.nio.ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException failure) {
            Files.deleteIfExists(temporary);
            throw new IOException("Atomic signed design import unavailable", failure);
        }
    }

    private int count(Path directory) throws IOException {
        int result = 0;
        try (var files = Files.newDirectoryStream(directory, "*.json")) {
            for (Path ignored : files) if (++result > MAX_IMPORTS_PER_PLAYER) break;
        }
        return result;
    }

    private Path ownerDirectory(UUID owner) {
        if (owner == null) throw new IllegalArgumentException("Missing import owner");
        return imported.resolve(owner.toString());
    }

    private Path path(UUID owner, DesignId id) {
        try {
            String hash = java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(id.value().getBytes(StandardCharsets.UTF_8)));
            return ownerDirectory(owner).resolve(hash + ".json");
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable", impossible); }
    }

    private void rejectSymlink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("Signed design import symlink rejected");
    }

    public record Imported(int schemaVersion, String publisherKeyId, String kind, DesignId id,
                           String version, String documentHash, String signedEnvelope) { }
}
