package dev.stargenesis.paper.communication;

import com.fasterxml.jackson.core.JsonFactory;
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
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/** Versioned durable state for the simulation-only P4 radio and contract workflow. */
public final class RadioPersistence {
    public static final int SNAPSHOT_VERSION = 1;
    public static final long MAX_FILE_BYTES = 16L * 1024 * 1024;
    private final Path file;
    private final Path temporary;
    private final String radioProtocolId;
    private final String radioProtocolHash;
    private final String serviceProtocolId;
    private final String serviceProtocolHash;
    private final String contractId;
    private final String contractHash;
    private final ObjectMapper mapper;

    public RadioPersistence(Path file, Protocol radioProtocol, Protocol serviceProtocol, Contract contract) throws IOException {
        this.file = file.toAbsolutePath().normalize();
        temporary = this.file.resolveSibling(this.file.getFileName() + ".tmp");
        if (this.file.getParent() == null) throw new IOException("Radio persistence path has no parent");
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

    public synchronized Optional<Snapshot> load() throws IOException {
        if (!Files.exists(file)) return Optional.empty();
        if (Files.isSymbolicLink(file) || !Files.isRegularFile(file) || Files.size(file) > MAX_FILE_BYTES)
            throw new IOException("Radio state is not a regular file or exceeds 16 MiB");
        byte[] data;
        try (var input = Files.newInputStream(file)) {
            data = input.readNBytes((int) MAX_FILE_BYTES + 1);
        }
        if (data.length > MAX_FILE_BYTES) throw new IOException("Radio state exceeds 16 MiB");
        Snapshot snapshot = mapper.readValue(data, Snapshot.class);
        validate(snapshot);
        return Optional.of(snapshot);
    }

    public synchronized void save(VirtualMessageBus bus, ContractRuntime contracts) throws IOException {
        VirtualMessageBus.Snapshot radio = bus.snapshot();
        ContractRuntime.Snapshot contractState = contracts.snapshot();
        if (radio.tick() != contractState.tick()) throw new IOException("Radio and contract ticks are inconsistent");
        Snapshot snapshot = new Snapshot(SNAPSHOT_VERSION, radioProtocolId, radioProtocolHash,
                serviceProtocolId, serviceProtocolHash, contractId, contractHash, radio, contractState);
        byte[] data = mapper.writeValueAsBytes(snapshot);
        if (data.length > MAX_FILE_BYTES) throw new IOException("Radio state exceeds 16 MiB");
        if (Files.isSymbolicLink(file) || Files.isSymbolicLink(temporary))
            throw new IOException("Radio state symlink path rejected");
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
            var buffer = java.nio.ByteBuffer.wrap(data);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException failure) {
            Files.deleteIfExists(temporary);
            throw new IOException("Atomic radio state persistence unavailable; previous snapshot preserved", failure);
        }
    }

    private void validate(Snapshot snapshot) throws IOException {
        if (snapshot.schemaVersion() != SNAPSHOT_VERSION
                || !radioProtocolId.equals(snapshot.radioProtocolId())
                || !radioProtocolHash.equals(snapshot.radioProtocolHash())
                || !serviceProtocolId.equals(snapshot.serviceProtocolId())
                || !serviceProtocolHash.equals(snapshot.serviceProtocolHash())
                || !contractId.equals(snapshot.contractId()) || !contractHash.equals(snapshot.contractHash())
                || snapshot.radio() == null || snapshot.contracts() == null
                || snapshot.radio().tick() != snapshot.contracts().tick())
            throw new IOException("Radio state version, pinned design hash, or logical tick mismatch");
    }

    public record Snapshot(int schemaVersion, String radioProtocolId, String radioProtocolHash,
                           String serviceProtocolId, String serviceProtocolHash, String contractId,
                           String contractHash, VirtualMessageBus.Snapshot radio,
                           ContractRuntime.Snapshot contracts) { }
}
