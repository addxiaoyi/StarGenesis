package dev.stargenesis.paper;

import dev.stargenesis.sim.EcologyWorld;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class EcologyGroupCodecTest {
    @Test
    void groupRosterAndSpatialCheckpointRoundTripDeterministically() {
        EcologyGroupRecord group = group();

        byte[] encoded = EcologyGroupCodec.encode(group);
        EcologyGroupRecord restored = EcologyGroupCodec.decode(encoded);

        assertEquals(group, restored);
        assertArrayEquals(encoded, EcologyGroupCodec.encode(restored));
        assertEquals(EcologyWorld.ENGINE_VERSION, restored.checkpoint().snapshot().engineVersion());
    }

    @Test
    void rejectsTamperedAndOversizedGroupRecords() {
        byte[] encoded = EcologyGroupCodec.encode(group());
        byte[] tampered = Arrays.copyOf(encoded, encoded.length);
        tampered[20] ^= 1;

        assertThrows(IllegalArgumentException.class, () -> EcologyGroupCodec.decode(tampered));
        assertThrows(IllegalArgumentException.class,
                () -> EcologyGroupCodec.decode(new byte[EcologyGroupCodec.MAX_ENCODED_BYTES + 1]));
    }

    private static EcologyGroupRecord group() {
        UUID groupId = UUID.fromString("00000000-0000-0000-0000-000000000200");
        UUID owner = UUID.fromString("00000000-0000-0000-0000-000000000201");
        UUID member = UUID.fromString("00000000-0000-0000-0000-000000000202");
        try (var world = new EcologyWorld(EcologySession.sharedSettings(groupId))) {
            return new EcologyGroupRecord(groupId, owner, List.of(owner, member), world.checkpoint());
        }
    }
}
