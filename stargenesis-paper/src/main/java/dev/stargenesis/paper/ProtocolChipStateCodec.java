package dev.stargenesis.paper;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

final class ProtocolChipStateCodec {
    static final int MAX_ENCODED_BYTES = 16_384;
    private static final int MAGIC = 0x53475043;
    private static final int FORMAT_VERSION = 1;

    private ProtocolChipStateCodec() { }

    static byte[] encode(ProtocolChipState state) {
        if (state == null) throw new IllegalArgumentException("Protocol chip state is required");
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeInt(MAGIC);
                output.writeInt(FORMAT_VERSION);
                output.writeLong(state.ownerId().getMostSignificantBits());
                output.writeLong(state.ownerId().getLeastSignificantBits());
                output.writeInt(state.unlockedProtocolIds().size());
                for (String protocolId : new TreeSet<>(state.unlockedProtocolIds())) writeText(output, protocolId);
                writeText(output, state.equippedProtocolId());
            }
            if (bytes.size() > MAX_ENCODED_BYTES)
                throw new IllegalArgumentException("Protocol chip checkpoint exceeds encoded size limit");
            return bytes.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("Cannot encode protocol chip state", impossible);
        }
    }

    static ProtocolChipState decode(byte[] encoded, UUID expectedOwner) {
        if (encoded == null || encoded.length < 32 || encoded.length > MAX_ENCODED_BYTES)
            throw new IllegalArgumentException("Protocol chip checkpoint size is outside the allowed range");
        if (expectedOwner == null) throw new IllegalArgumentException("Expected protocol chip owner is required");
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(encoded))) {
            if (input.readInt() != MAGIC || input.readInt() != FORMAT_VERSION)
                throw new IllegalArgumentException("Unsupported protocol chip checkpoint format");
            UUID owner = new UUID(input.readLong(), input.readLong());
            if (!owner.equals(expectedOwner)) throw new IllegalArgumentException("Protocol chips belong to another player");
            int count = input.readInt();
            if (count < 0 || count > ProtocolChipState.MAX_UNLOCKED_PROTOCOLS)
                throw new IllegalArgumentException("Invalid unlocked protocol chip count");
            Set<String> unlocked = new HashSet<>();
            for (int index = 0; index < count; index++) {
                if (!unlocked.add(readText(input))) throw new IllegalArgumentException("Duplicate unlocked protocol chip");
            }
            String equipped = readText(input);
            if (input.available() != 0) throw new IllegalArgumentException("Trailing protocol chip checkpoint data");
            return new ProtocolChipState(owner, unlocked, equipped);
        } catch (IOException failure) {
            throw new IllegalArgumentException("Malformed or truncated protocol chip checkpoint", failure);
        }
    }

    private static void writeText(DataOutputStream output, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 127) throw new IllegalArgumentException("Protocol chip identifier exceeds size limit");
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static String readText(DataInputStream input) throws IOException {
        int length = input.readInt();
        if (length < 0 || length > 127 || length > input.available())
            throw new IllegalArgumentException("Invalid protocol chip text length");
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(input.readNBytes(length))).toString();
    }
}
