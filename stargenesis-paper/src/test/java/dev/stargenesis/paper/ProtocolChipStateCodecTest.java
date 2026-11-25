package dev.stargenesis.paper;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class ProtocolChipStateCodecTest {
    private static final UUID OWNER = new UUID(0, 1);
    private static final UUID OTHER = new UUID(0, 2);

    @Test void chipUnlockAndEquipmentRoundTripIsOwnerBound() {
        ProtocolChipState state = ProtocolChipState.empty(OWNER)
                .unlock(ProtocolChipState.STARTER_PROTOCOL_ID)
                .equip(ProtocolChipState.STARTER_PROTOCOL_ID);
        byte[] encoded = ProtocolChipStateCodec.encode(state);
        assertEquals(state, ProtocolChipStateCodec.decode(encoded, OWNER));
        assertThrows(IllegalArgumentException.class, () -> ProtocolChipStateCodec.decode(encoded, OTHER));
    }

    @Test void chipStateEncodingIsCanonicalAndRejectsCorruptInput() {
        ProtocolChipState first = new ProtocolChipState(OWNER,
                Set.of("genesis:zeta", ProtocolChipState.STARTER_PROTOCOL_ID), ProtocolChipState.STARTER_PROTOCOL_ID);
        ProtocolChipState second = new ProtocolChipState(OWNER,
                Set.of(ProtocolChipState.STARTER_PROTOCOL_ID, "genesis:zeta"), ProtocolChipState.STARTER_PROTOCOL_ID);
        byte[] encoded = ProtocolChipStateCodec.encode(first);
        assertArrayEquals(encoded, ProtocolChipStateCodec.encode(second));
        assertThrows(IllegalArgumentException.class,
                () -> ProtocolChipStateCodec.decode(java.util.Arrays.copyOf(encoded, encoded.length - 1), OWNER));
        assertThrows(IllegalArgumentException.class,
                () -> ProtocolChipStateCodec.decode(java.util.Arrays.copyOf(encoded, encoded.length + 1), OWNER));
    }

    @Test void equippedChipMustAlreadyBeUnlocked() {
        assertThrows(IllegalArgumentException.class,
                () -> new ProtocolChipState(OWNER, Set.of(), ProtocolChipState.STARTER_PROTOCOL_ID));
    }
}
