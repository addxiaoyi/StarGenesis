package dev.stargenesis.paper.communication;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.stargenesis.design.Contract;
import dev.stargenesis.design.DesignCodec;
import dev.stargenesis.design.Protocol;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Versioned durable state for the simulation-only P4 radio and contract workflow. */
public final class RadioPersistence {
    public static final int SNAPSHOT_VERSION = 1;
    public static final int JOURNAL_VERSION = 2;
    public static final long MAX_FILE_BYTES = 16L * 1024 * 1024;
    public static final int MAX_ARCHIVES = 16;
    /** Payload budget: archive slots plus 16 MiB active state and one 16 MiB atomic-write staging slot. */
    public static final long MAX_TOTAL_BYTES = 128L * 1024 * 1024;
    private final Path file;
    private final Path temporary;
    private final Path archiveDirectory;
    private final Path lockFile;
    private final String radioProtocolId;
    private final String radioProtocolHash;
    private final String serviceProtocolId;
    private final String serviceProtocolHash;
    private final String contractId;
    private final String contractHash;
    private final ObjectMapper mapper;
    private boolean observed;
    private String expectedHash;

    public RadioPersistence(Path file, Protocol radioProtocol, Protocol serviceProtocol, Contract contract) throws IOException {
        this.file = file.toAbsolutePath().normalize();
        temporary = this.file.resolveSibling(this.file.getFileName() + ".tmp");
        archiveDirectory = this.file.resolveSibling(this.file.getFileName() + ".archives");
        lockFile = this.file.resolveSibling(this.file.getFileName() + ".lock");
        if (this.file.getParent() == null) throw new IOException("Radio persistence path has no parent");
        rejectSymlinks(this.file.getParent());
        Files.createDirectories(this.file.getParent());
        DesignCodec codec = new DesignCodec();
        codec.write(radioProtocol);
        codec.write(serviceProtocol);
        codec.write(contract);
        radioProtocolId = radioProtocol.metadata().id().value();
        radioProtocolHash = radioProtocol.metadata().hash();
        serviceProtocolId = serviceProtocol.metadata().id().value();
        serviceProtocolHash = serviceProtocol.metadata().hash();
        contractId = contract.metadata().id().value();
        contractHash = contract.metadata().hash();
        mapper = new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32)
                        .maxStringLength(8192).maxNumberLength(20).build()).build());
        mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS);
        mapper.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        mapper.disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    }

    public synchronized Optional<Snapshot> load() throws IOException { return loadJournal().active(); }

    synchronized LoadedJournal loadJournal() throws IOException {
        return locked(() -> {
            byte[] data = activeBytes();
            LoadedJournal loaded = readJournal(data);
            expectedHash = hashOrAbsent(data);
            observed = true;
            return loaded;
        });
    }

    synchronized Snapshot loadArchive(ArchiveReference reference) throws IOException {
        return locked(() -> readArchive(reference));
    }

    public synchronized void save(VirtualMessageBus bus, ContractRuntime contracts) throws IOException {
        locked(() -> {
            LoadedJournal current = checkedCurrent();
            saveLocked(bus, contracts, current.references(), current);
            return null;
        });
    }

    synchronized void saveJournal(VirtualMessageBus bus, ContractRuntime contracts,
                                  List<ArchiveReference> references) throws IOException {
        locked(() -> {
            saveLocked(bus, contracts, List.copyOf(references), checkedCurrent());
            return null;
        });
    }

    /** Prepares only the next uncommitted slot. Its publication does not retire the active state. */
    synchronized ArchiveReference archive(Snapshot snapshot) throws IOException {
        return locked(() -> {
            LoadedJournal current = checkedCurrent();
            validate(snapshot);
            if (current.active().isEmpty() || !sameHistoryAtOrAfter(current.active().get(), snapshot))
                throw new IOException("Radio archive must preserve the current committed history");
            if (current.references().size() >= MAX_ARCHIVES)
                throw new IOException("Radio archive capacity exhausted; history is retained");
            byte[] data = mapper.writeValueAsBytes(snapshot);
            if (data.length > MAX_FILE_BYTES) throw new IOException("Radio archive exceeds 16 MiB");
            long total = 2 * MAX_FILE_BYTES + data.length;
            for (ArchiveReference reference : current.references()) total += reference.bytes();
            if (total > MAX_TOTAL_BYTES) throw new IOException("Radio archive bytes exceed budget");
            var reference = new ArchiveReference(current.references().size() + 1, sha256(data), data.length);
            rejectSymlinks(archiveDirectory);
            Files.createDirectories(archiveDirectory);
            validateDirectory(current.references().size());
            Path destination = archivePath(reference);
            if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
                byte[] existing = readBounded(destination);
                if (existing.length == data.length && sha256(existing).equals(reference.sha256())) return reference;
            }
            // The lock and CAS prove that this fixed slot is not referenced by any committed manifest.
            writeAtomic(archiveDirectory.resolve("archive.tmp"), destination, data);
            verifyArchive(reference);
            return reference;
        });
    }

    private LoadedJournal checkedCurrent() throws IOException {
        byte[] data = activeBytes();
        String actualHash = hashOrAbsent(data);
        if (observed && !actualHash.equals(expectedHash))
            throw new IOException("Radio state changed since this instance loaded it; stale write rejected");
        LoadedJournal current = readJournal(data);
        if (!observed) {
            expectedHash = actualHash;
            observed = true;
        }
        return current;
    }

    private void saveLocked(VirtualMessageBus bus, ContractRuntime contracts, List<ArchiveReference> references,
                            LoadedJournal current) throws IOException {
        if (references.size() < current.references().size()
                || references.size() > current.references().size() + 1
                || !references.subList(0, current.references().size()).equals(current.references()))
            throw new IOException("Committed radio archive history cannot be removed or replaced");
        // checkedCurrent already verified the unchanged committed prefix under this same OS lock.
        if (references.size() != current.references().size()) validateReferences(references);
        Snapshot appended = references.size() > current.references().size() ? readArchive(references.getLast()) : null;
        if (appended != null && (current.active().isEmpty() || !sameHistoryAtOrAfter(current.active().get(), appended)))
            throw new IOException("Rotation archive must preserve the current committed radio history");
        var radio = bus.snapshot();
        var contractState = contracts.snapshot();
        Snapshot snapshot = new Snapshot(SNAPSHOT_VERSION, radioProtocolId, radioProtocolHash,
                serviceProtocolId, serviceProtocolHash, contractId, contractHash, radio, contractState);
        validate(snapshot);
        if (appended != null && appended.radio().tick() != radio.tick())
            throw new IOException("Rotated active state must start at the archived logical tick");
        if (!references.isEmpty() && readArchive(references.getLast()).radio().tick() > radio.tick())
            throw new IOException("Active radio tick precedes its archive history");
        byte[] data = mapper.writeValueAsBytes(references.isEmpty() ? snapshot
                : new Journal(JOURNAL_VERSION, snapshot, references));
        if (data.length > MAX_FILE_BYTES) throw new IOException("Radio state exceeds 16 MiB");
        writeAtomic(temporary, file, data);
        expectedHash = sha256(data);
        observed = true;
    }

    private LoadedJournal readJournal(byte[] data) throws IOException {
        if (data == null) {
            validateDirectory(0);
            return new LoadedJournal(Optional.empty(), List.of());
        }
        int version = readVersion(data);
        if (version == SNAPSHOT_VERSION) {
            Snapshot snapshot = mapper.readValue(data, Snapshot.class);
            validate(snapshot);
            validateDirectory(0);
            return new LoadedJournal(Optional.of(snapshot), List.of());
        }
        if (version != JOURNAL_VERSION) throw new IOException("Unsupported radio state schema version");
        Journal journal = mapper.readValue(data, Journal.class);
        validate(journal.active());
        if (journal.archives() == null || journal.archives().isEmpty())
            throw new IOException("Radio journal requires its committed archive references");
        validateReferences(journal.archives());
        validateDirectory(journal.archives().size());
        return new LoadedJournal(Optional.of(journal.active()), journal.archives());
    }

    /** Select the envelope without retaining a second full JSON object tree beside the restored snapshot. */
    private int readVersion(byte[] data) throws IOException {
        try (var parser = mapper.getFactory().createParser(data)) {
            if (parser.nextToken() != JsonToken.START_OBJECT) throw new IOException("Radio state is not an object");
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if (field.equals("schemaVersion")) {
                    if (value != JsonToken.VALUE_NUMBER_INT) throw new IOException("Radio state has an invalid schema version");
                    return parser.getIntValue();
                }
                parser.skipChildren();
            }
            throw new IOException("Radio state has no schema version");
        }
    }

    private void validateReferences(List<ArchiveReference> references) throws IOException {
        if (references.size() > MAX_ARCHIVES) throw new IOException("Radio archive count exceeds budget");
        long bytes = 2 * MAX_FILE_BYTES;
        for (int index = 0; index < references.size(); index++) {
            ArchiveReference reference = references.get(index);
            validate(reference);
            if (reference.generation() != index + 1)
                throw new IOException("Radio archive generations must be consecutive and unique");
            bytes += reference.bytes();
            if (bytes > MAX_TOTAL_BYTES) throw new IOException("Radio archive bytes exceed budget");
            verifyArchive(reference);
        }
    }

    private Snapshot readArchive(ArchiveReference reference) throws IOException {
        byte[] data = readBounded(archivePath(reference));
        if (data.length != reference.bytes() || !sha256(data).equals(reference.sha256()))
            throw new IOException("Radio archive hash or byte length mismatch");
        Snapshot snapshot = mapper.readValue(data, Snapshot.class);
        validate(snapshot);
        return snapshot;
    }

    /** Reads one file at a time without retaining all historical object graphs in memory. */
    private void verifyArchive(ArchiveReference reference) throws IOException {
        validate(reference);
        Path path = archivePath(reference);
        rejectSymlinks(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) != reference.bytes())
            throw new IOException("Radio archive is missing or its byte length changed");
        MessageDigest digest = digest();
        long count = 0;
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                count += read;
                if (count > reference.bytes()) throw new IOException("Radio archive exceeds its pinned byte length");
                digest.update(buffer, 0, read);
            }
        }
        if (count != reference.bytes() || !HexFormat.of().formatHex(digest.digest()).equals(reference.sha256()))
            throw new IOException("Radio archive hash or byte length mismatch");
    }

    private void validateDirectory(int committed) throws IOException {
        rejectSymlinks(archiveDirectory);
        if (!Files.exists(archiveDirectory, LinkOption.NOFOLLOW_LINKS)) return;
        if (!Files.isDirectory(archiveDirectory, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Radio archive directory is not a directory");
        long bytes = 2 * MAX_FILE_BYTES;
        try (var entries = Files.newDirectoryStream(archiveDirectory)) {
            for (Path entry : entries) {
                rejectSymlinks(entry);
                String name = entry.getFileName().toString();
                boolean allowed = name.equals("archive.tmp");
                if (name.matches("segment-[0-9]{4}\\.json")) {
                    int generation = Integer.parseInt(name.substring(8, 12));
                    allowed = generation >= 1 && generation <= Math.min(MAX_ARCHIVES, committed + 1);
                }
                if (!allowed || !Files.isRegularFile(entry, LinkOption.NOFOLLOW_LINKS)
                        || Files.size(entry) > MAX_FILE_BYTES)
                    throw new IOException("Unexpected or oversized file in radio archive directory");
                if (!name.equals("archive.tmp")) {
                    bytes += Files.size(entry);
                    if (bytes > MAX_TOTAL_BYTES) throw new IOException("Radio archive directory bytes exceed budget");
                }
            }
        }
    }

    private Path archivePath(ArchiveReference reference) throws IOException {
        validate(reference);
        rejectSymlinks(archiveDirectory);
        return archiveDirectory.resolve(String.format(Locale.ROOT, "segment-%04d.json", reference.generation()));
    }

    private void validate(ArchiveReference reference) throws IOException {
        if (reference == null || reference.generation() < 1 || reference.generation() > MAX_ARCHIVES
                || reference.sha256() == null || !reference.sha256().matches("[0-9a-f]{64}")
                || reference.bytes() < 1 || reference.bytes() > MAX_FILE_BYTES)
            throw new IOException("Invalid radio archive reference");
    }

    private byte[] activeBytes() throws IOException {
        rejectSymlinks(file);
        return Files.exists(file, LinkOption.NOFOLLOW_LINKS) ? readBounded(file) : null;
    }

    private byte[] readBounded(Path path) throws IOException {
        rejectSymlinks(path);
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Radio state is not a regular file or exceeds 16 MiB");
        long size = Files.size(path);
        if (size > MAX_FILE_BYTES) throw new IOException("Radio state exceeds 16 MiB");
        byte[] data = new byte[(int) size];
        try (var input = Files.newInputStream(path)) {
            int offset = 0;
            while (offset < data.length) {
                int count = input.read(data, offset, Math.min(8192, data.length - offset));
                if (count < 0) throw new IOException("Radio state changed length during read");
                offset += count;
            }
            if (input.read() != -1) throw new IOException("Radio state changed length during read");
        }
        return data;
    }

    private void writeAtomic(Path source, Path destination, byte[] data) throws IOException {
        rejectSymlinks(source);
        rejectSymlinks(destination);
        Path otherTemporary = source.equals(temporary) ? archiveDirectory.resolve("archive.tmp") : temporary;
        rejectSymlinks(otherTemporary);
        // One shared staging reservation suffices because the OS lock serializes both publication kinds.
        if (Files.exists(otherTemporary, LinkOption.NOFOLLOW_LINKS)) {
            if (Files.isRegularFile(otherTemporary, LinkOption.NOFOLLOW_LINKS)) Files.delete(otherTemporary);
            else if (!Files.isDirectory(otherTemporary, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Radio atomic-write staging path is not a regular file");
        }
        try (FileChannel channel = FileChannel.open(source, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            // FileChannel copies heap buffers through a direct buffer; keep that native allocation bounded.
            for (int offset = 0; offset < data.length; offset += 8192) {
                var buffer = java.nio.ByteBuffer.wrap(data, offset, Math.min(8192, data.length - offset));
                while (buffer.hasRemaining()) channel.write(buffer);
            }
            channel.force(true);
        }
        try { Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException failure) {
            Files.deleteIfExists(source);
            throw new IOException("Atomic radio state persistence unavailable; previous snapshot preserved", failure);
        }
    }

    private <T> T locked(IoSupplier<T> action) throws IOException {
        rejectSymlinks(lockFile);
        try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            try (var lock = channel.tryLock()) {
                if (lock == null) throw new IOException("Radio persistence is locked by another writer");
                return action.get();
            } catch (OverlappingFileLockException busy) {
                throw new IOException("Radio persistence is locked by another writer", busy);
            }
        }
    }

    private void rejectSymlinks(Path path) throws IOException {
        for (Path current = path; current != null; current = current.getParent())
            if (Files.isSymbolicLink(current)) throw new IOException("Radio state symlink path rejected");
    }

    private String hashOrAbsent(byte[] data) { return data == null ? "absent" : sha256(data); }
    private String sha256(byte[] data) { return HexFormat.of().formatHex(digest().digest(data)); }
    private MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private void validate(Snapshot snapshot) throws IOException {
        if (snapshot == null || snapshot.schemaVersion() != SNAPSHOT_VERSION
                || !radioProtocolId.equals(snapshot.radioProtocolId()) || !radioProtocolHash.equals(snapshot.radioProtocolHash())
                || !serviceProtocolId.equals(snapshot.serviceProtocolId()) || !serviceProtocolHash.equals(snapshot.serviceProtocolHash())
                || !contractId.equals(snapshot.contractId()) || !contractHash.equals(snapshot.contractHash())
                || snapshot.radio() == null || snapshot.contracts() == null || snapshot.radio().tick() < 0
                || snapshot.radio().tick() != snapshot.contracts().tick())
            throw new IOException("Radio state version, pinned design hash, or logical tick mismatch");
    }

    /** A dirty clock can advance without new durable history; preparing it must not first rewrite the active file. */
    private boolean sameHistoryAtOrAfter(Snapshot committed, Snapshot candidate) {
        return candidate.radio().tick() >= committed.radio().tick()
                && candidate.radio().evidence().equals(committed.radio().evidence())
                && candidate.radio().acknowledged().equals(committed.radio().acknowledged())
                && candidate.radio().pending().equals(committed.radio().pending())
                && candidate.radio().events().equals(committed.radio().events())
                && candidate.contracts().states().equals(committed.contracts().states())
                && candidate.contracts().received().equals(committed.contracts().received())
                && candidate.contracts().events().equals(committed.contracts().events());
    }

    public record Snapshot(int schemaVersion, String radioProtocolId, String radioProtocolHash,
                           String serviceProtocolId, String serviceProtocolHash, String contractId,
                           String contractHash, VirtualMessageBus.Snapshot radio,
                           ContractRuntime.Snapshot contracts) { }
    record ArchiveReference(int generation, String sha256, long bytes) { }
    record LoadedJournal(Optional<Snapshot> active, List<ArchiveReference> references) {
        LoadedJournal { references = List.copyOf(references); }
    }
    private record Journal(int schemaVersion, Snapshot active, List<ArchiveReference> archives) { }
    @FunctionalInterface private interface IoSupplier<T> { T get() throws IOException; }
}
