package dev.stargenesis.paper.settlement;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.stargenesis.integrations.SettlementSaga;
import dev.stargenesis.integrations.SettlementSagaRepository;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/** Durable player-saga snapshots using UUID-derived paths, strict JSON and optimistic revisions. */
public final class FileSettlementSagaRepository implements SettlementSagaRepository {
    public static final int SCHEMA_VERSION = 1;
    public static final long MAX_FILE_BYTES = 64 * 1024;
    private static final Object[] JVM_LOCKS = new Object[64];
    private static final ThreadLocal<Path> ACTIVE_LOCK = new ThreadLocal<>();

    static {
        for (int index = 0; index < JVM_LOCKS.length; index++) JVM_LOCKS[index] = new Object();
    }

    private final Path directory;
    private final ObjectMapper mapper;

    public FileSettlementSagaRepository(Path directory) throws IOException {
        this.directory = directory.toAbsolutePath().normalize();
        Files.createDirectories(this.directory);
        if (Files.isSymbolicLink(this.directory)) throw new IOException("Settlement saga directory symlink rejected");
        mapper = new ObjectMapper(JsonFactory.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(24)
                        .maxStringLength(4096).maxNumberLength(20).build()).build());
        mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES,
                DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES,
                DeserializationFeature.FAIL_ON_MISSING_CREATOR_PROPERTIES,
                DeserializationFeature.FAIL_ON_TRAILING_TOKENS,
                DeserializationFeature.FAIL_ON_NUMBERS_FOR_ENUMS);
        mapper.disable(DeserializationFeature.ACCEPT_FLOAT_AS_INT);
        mapper.disable(MapperFeature.ALLOW_COERCION_OF_SCALARS);
    }

    @Override public Optional<SettlementSaga> load(UUID sagaId) {
        Path file = path(sagaId);
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        try {
            if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)
                    || Files.size(file) > MAX_FILE_BYTES) throw new IOException("Saga file exceeds its regular-file budget");
            byte[] data;
            try (var input = Files.newInputStream(file)) { data = input.readNBytes((int) MAX_FILE_BYTES + 1); }
            if (data.length > MAX_FILE_BYTES) throw new IOException("Saga file exceeds its byte budget");
            StoredSaga stored = mapper.readValue(data, StoredSaga.class);
            if (stored.schemaVersion() != SCHEMA_VERSION || stored.saga() == null
                    || !sagaId.equals(stored.saga().request().sagaId())) throw new IOException("Saga file identity mismatch");
            return Optional.of(stored.saga());
        } catch (IOException | RuntimeException failure) {
            throw new SagaStorageException("Settlement saga failed integrity validation", failure);
        }
    }

    @Override public SettlementSaga create(SettlementSaga saga) {
        saga.requireInitial();
        UUID sagaId = saga.request().sagaId();
        return withExclusiveSaga(sagaId, () -> {
            if (load(sagaId).isPresent()) throw new SagaStorageException("Settlement saga already exists");
            write(saga);
            return saga;
        });
    }

    @Override public SettlementSaga replace(SettlementSaga updated, long expectedRevision) {
        UUID sagaId = updated.request().sagaId();
        return withExclusiveSaga(sagaId, () -> {
            SettlementSaga previous = load(sagaId)
                    .orElseThrow(() -> new SagaStorageException("Settlement saga does not exist"));
            if (previous.revision() != expectedRevision || updated.revision() != Math.addExact(expectedRevision, 1))
                throw new SagaStorageException("Settlement saga revision conflict");
            updated.requireSuccessorOf(previous);
            write(updated);
            return updated;
        });
    }

    @Override public <T> T withExclusiveSaga(UUID sagaId, Supplier<T> action) {
        Objects.requireNonNull(sagaId, "sagaId");
        Objects.requireNonNull(action, "action");
        Path lockFile = directory.resolve(sagaId + ".lock");
        Path active = ACTIVE_LOCK.get();
        if (active != null) {
            if (!active.equals(lockFile)) throw new IllegalStateException("Cannot nest a different settlement saga lock");
            return action.get();
        }
        Object jvmLock = JVM_LOCKS[Math.floorMod(sagaId.hashCode(), JVM_LOCKS.length)];
        synchronized (jvmLock) {
            try {
                if (Files.isSymbolicLink(lockFile)) throw new IOException("Settlement saga lock symlink rejected");
                try (FileChannel channel = FileChannel.open(lockFile, StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                     FileLock ignored = channel.lock()) {
                    ACTIVE_LOCK.set(lockFile);
                    try { return action.get(); }
                    finally { ACTIVE_LOCK.remove(); }
                }
            } catch (IOException failure) {
                throw new SagaStorageException("Could not lock settlement saga", failure);
            }
        }
    }

    private void write(SettlementSaga saga) {
        Path file = path(saga.request().sagaId());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            byte[] data = mapper.writeValueAsBytes(new StoredSaga(SCHEMA_VERSION, saga));
            if (data.length > MAX_FILE_BYTES) throw new IOException("Settlement saga exceeds 64 KiB");
            if (Files.isSymbolicLink(file) || Files.isSymbolicLink(temporary))
                throw new IOException("Settlement saga symlink path rejected");
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                    StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                var buffer = java.nio.ByteBuffer.wrap(data);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException failure) {
            try { Files.deleteIfExists(temporary); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw new SagaStorageException("Atomic settlement saga persistence unavailable", failure);
        } catch (IOException failure) {
            throw new SagaStorageException("Could not persist settlement saga", failure);
        }
    }

    private Path path(UUID sagaId) {
        if (sagaId == null) throw new IllegalArgumentException("Missing settlement saga ID");
        return directory.resolve(sagaId + ".settlement.json");
    }

    public record StoredSaga(int schemaVersion, SettlementSaga saga) { }

    public static final class SagaStorageException extends RuntimeException {
        public SagaStorageException(String message) { super(message); }
        public SagaStorageException(String message, Throwable cause) { super(message, cause); }
    }
}
