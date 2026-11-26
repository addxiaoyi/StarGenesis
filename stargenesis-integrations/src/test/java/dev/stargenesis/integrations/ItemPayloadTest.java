package dev.stargenesis.integrations;

import org.junit.jupiter.api.Test;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.Arrays;
import java.util.zip.GZIPOutputStream;
import static org.junit.jupiter.api.Assertions.*;

class ItemPayloadTest {
    @FunctionalInterface interface Fields { void write(DataOutputStream out) throws IOException; }
    private static byte[] gzip(byte[] raw) throws IOException {
        var bytes = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(bytes)) { gzip.write(raw); }
        return bytes.toByteArray();
    }
    private static byte[] nbt(Fields fields) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var out = new DataOutputStream(bytes);
        out.writeByte(10); out.writeUTF("");
        out.writeByte(3); out.writeUTF("DataVersion"); out.writeInt(4671);
        out.writeByte(8); out.writeUTF("id"); out.writeUTF("minecraft:stone");
        fields.write(out);
        out.writeByte(0);
        return gzip(bytes.toByteArray());
    }
    private static ItemPayload payload(byte[] data) { return new ItemPayload("test:sample", true, 1, data); }

    @Test void snapshotOwnsItsBytesAndRetainsFullOpaqueState() throws Exception {
        byte[] bytes = nbt(out -> { out.writeByte(8); out.writeUTF("custom"); out.writeUTF("opaque \u4e2d\u6587\0 data"); });
        var item = payload(bytes);
        assertEquals(4671, item.dataVersion());
        assertEquals("minecraft:stone", item.material());
        assertEquals(64, item.sha256().length());
        byte[] original = bytes.clone();
        Arrays.fill(bytes, (byte) 0); Arrays.fill(item.data(), (byte) 0);
        assertArrayEquals(original, item.data());
        assertEquals(payload(original).sha256(), item.sha256());
    }
    @Test void envelopeMustMatchCountAndVanillaMaterial() throws Exception {
        byte[] data = nbt(out -> {});
        assertThrows(IllegalArgumentException.class, () -> new ItemPayload("test:sample", true, 2, data));
        assertThrows(IllegalArgumentException.class, () -> new ItemPayload("minecraft:dirt", false, 1, data));
        assertDoesNotThrow(() -> new ItemPayload("minecraft:stone", false, 1, data));
        for (int amount : new int[]{0, 65, Integer.MAX_VALUE})
            assertThrows(IllegalArgumentException.class, () -> new ItemPayload("test:sample", true, amount, data));
        assertThrows(IllegalArgumentException.class, () -> payload(new byte[ItemPayload.MAX_BYTES + 1]));
    }
    @Test void declaredHugeOrNegativeArraysAreRejectedBeforeAllocation() throws Exception {
        for (int type : new int[]{7, 11, 12}) for (int length : new int[]{-1, Integer.MAX_VALUE, 0x40000000}) {
            byte[] data = nbt(out -> { out.writeByte(type); out.writeUTF("array"); out.writeInt(length); });
            assertThrows(IllegalArgumentException.class, () -> payload(data));
        }
    }
    @Test void listLengthsAndElementTagsAreBounded() throws Exception {
        for (int[] pair : new int[][]{{1, Integer.MAX_VALUE}, {1,-1}, {0,1}, {13,0}}) {
            byte[] data = nbt(out -> { out.writeByte(9); out.writeUTF("list"); out.writeByte(pair[0]); out.writeInt(pair[1]); });
            assertThrows(IllegalArgumentException.class, () -> payload(data));
        }
    }
    @Test void nestingCannotReachTheNativeDecoderWithUnboundedDepth() throws Exception {
        byte[] data = nbt(out -> {
            for (int i = 0; i < 33; i++) { out.writeByte(10); out.writeUTF("nested"); }
            for (int i = 0; i < 33; i++) out.writeByte(0);
        });
        assertThrows(IllegalArgumentException.class, () -> payload(data));
    }
    @Test void manySmallFieldsCannotBypassTheStructureBudget() throws Exception {
        byte[] data = nbt(out -> { for (int i = 0; i < 4096; i++) { out.writeByte(1); out.writeUTF("k" + i); out.writeByte(0); } });
        assertThrows(IllegalArgumentException.class, () -> payload(data));
    }
    @Test void duplicateFieldsAndHeaderTypeConfusionAreRejected() throws Exception {
        for (Fields fields : new Fields[]{
                out -> { out.writeByte(3); out.writeUTF("DataVersion"); out.writeInt(4671); },
                out -> { out.writeByte(8); out.writeUTF("count"); out.writeUTF("1"); },
                out -> { out.writeByte(10); out.writeUTF("nested"); for (int i = 0; i < 2; i++) { out.writeByte(1); out.writeUTF("x"); out.writeByte(i); } out.writeByte(0); }
        }) {
            byte[] data = nbt(fields);
            assertThrows(IllegalArgumentException.class, () -> payload(data));
        }
    }
    @Test void truncatedArrayPrimitiveAndUnknownTagAreRejected() throws Exception {
        for (Fields fields : new Fields[]{
                out -> { out.writeByte(7); out.writeUTF("bytes"); out.writeInt(4); out.writeByte(1); },
                out -> { out.writeByte(4); out.writeUTF("long"); out.writeByte(1); },
                out -> { out.writeByte(99); out.writeUTF("unknown"); }
        }) {
            byte[] data = nbt(fields);
            assertThrows(IllegalArgumentException.class, () -> payload(data));
        }
    }
    @Test void compressedBombIsRejectedAtTheExpansionBoundary() throws Exception {
        byte[] bomb = gzip(new byte[BoundedItemNbt.MAX_EXPANDED_BYTES + 1]);
        assertTrue(bomb.length < ItemPayload.MAX_BYTES);
        assertThrows(IllegalArgumentException.class, () -> payload(bomb));
    }
    @Test void malformedGzipChecksumAndTrailingNbtAreRejected() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> payload(new byte[]{1,2,3}));
        byte[] crc = nbt(out -> {}); crc[crc.length - 8] ^= 1;
        assertThrows(IllegalArgumentException.class, () -> payload(crc));
        byte[] extra = nbt(out -> { out.writeByte(0); out.writeByte(1); });
        assertThrows(IllegalArgumentException.class, () -> payload(extra));
    }
    @Test void rootAndRequiredHeadersAreChecked() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> payload(gzip(new byte[]{0})));
        byte[] empty = gzip(new byte[]{10,0,0,0});
        assertThrows(IllegalArgumentException.class, () -> payload(empty));
        byte[] count = nbt(out -> { out.writeByte(3); out.writeUTF("count"); out.writeInt(0); });
        assertThrows(IllegalArgumentException.class, () -> payload(count));
    }
    @Test void validNativeTagTypesAndEmptyListsRemainSupported() throws Exception {
        byte[] data = nbt(out -> {
            for (int type : new int[]{1,2,3,4,5,6,7,8,9,10,11,12}) {
                out.writeByte(type); out.writeUTF("field" + type);
                switch (type) {
                    case 1 -> out.writeByte(1);
                    case 2 -> out.writeShort(2);
                    case 3,5 -> out.writeInt(3);
                    case 4,6 -> out.writeLong(4);
                    case 7 -> { out.writeInt(2); out.write(new byte[]{1,2}); }
                    case 8 -> out.writeUTF("value");
                    case 9 -> { out.writeByte(0); out.writeInt(0); }
                    case 10 -> out.writeByte(0);
                    case 11 -> { out.writeInt(1); out.writeInt(9); }
                    case 12 -> { out.writeInt(1); out.writeLong(9); }
                }
            }
        });
        assertArrayEquals(data, payload(data).data());
    }

    @Test void compoundOrderAndCompressionHeadersDoNotChangeItemState() throws Exception {
        byte[] forward = nbt(out -> {
            out.writeByte(10); out.writeUTF("components");
            for (String key : new String[]{"a", "b"}) { out.writeByte(8); out.writeUTF(key); out.writeUTF(key + " value"); }
            out.writeByte(0);
        });
        byte[] reverse = nbt(out -> {
            out.writeByte(10); out.writeUTF("components");
            for (String key : new String[]{"b", "a"}) { out.writeByte(8); out.writeUTF(key); out.writeUTF(key + " value"); }
            out.writeByte(0);
        });
        reverse[4] = 42; // GZIP timestamp is transport metadata.
        assertFalse(Arrays.equals(forward, reverse));
        assertTrue(payload(forward).sameStateAs(payload(reverse)));
        assertNotEquals(payload(forward).sha256(), payload(reverse).sha256());
        assertArrayEquals(forward, payload(forward).data());
    }

    @Test void actualPaperCraftEngineSnapshotRetainsStateAcrossNativeReencoding() throws Exception {
        byte[] original, decoded;
        try (var source = getClass().getResourceAsStream("/craftengine/captured.bin");
             var restored = getClass().getResourceAsStream("/craftengine/decoded.bin")) {
            original = source.readAllBytes(); decoded = restored.readAllBytes();
        }
        assertFalse(Arrays.equals(original, decoded));
        var captured = new ItemPayload("stargenesis:item_probe", true, 3, original);
        assertEquals(4671, captured.dataVersion());
        assertEquals("minecraft:paper", captured.material());
        assertTrue(captured.sameStateAs(new ItemPayload("stargenesis:item_probe", true, 3, decoded)));
        assertArrayEquals(original, captured.data());
    }

    @Test void tagTypesScalarBitsAndMissingFieldsRemainSignificant() throws Exception {
        var integer = payload(nbt(out -> { out.writeByte(3); out.writeUTF("v"); out.writeInt(0); }));
        var floating = payload(nbt(out -> { out.writeByte(5); out.writeUTF("v"); out.writeInt(0); }));
        var negativeZero = payload(nbt(out -> { out.writeByte(5); out.writeUTF("v"); out.writeInt(0x80000000); }));
        assertFalse(integer.sameStateAs(floating));
        assertFalse(floating.sameStateAs(negativeZero));
        assertFalse(integer.sameStateAs(payload(nbt(out -> {}))));
        assertFalse(integer.sameStateAs(null));
        assertFalse(integer.sameStateAs(new ItemPayload("other:id", true, 1, integer.data())));
        assertFalse(integer.sameStateAs(new ItemPayload("minecraft:stone", false, 1, integer.data())));
    }

    @Test void listAndArrayOrderAndEmptyListTypesRemainSignificant() throws Exception {
        for (int type : new int[]{7, 9, 11, 12}) {
            ItemPayload[] items = new ItemPayload[2];
            for (int direction = 0; direction < 2; direction++) {
                int first = direction;
                items[direction] = payload(nbt(out -> {
                    out.writeByte(type); out.writeUTF("ordered");
                    if (type == 9) out.writeByte(3);
                    out.writeInt(2);
                    for (int value : new int[]{first, 1-first}) {
                        if (type == 7) out.writeByte(value);
                        else if (type == 12) out.writeLong(value);
                        else out.writeInt(value);
                    }
                }));
            }
            assertFalse(items[0].sameStateAs(items[1]), "ordered tag " + type);
        }
        var emptyStrings = payload(nbt(out -> { out.writeByte(9); out.writeUTF("empty"); out.writeByte(8); out.writeInt(0); }));
        var emptyCompounds = payload(nbt(out -> { out.writeByte(9); out.writeUTF("empty"); out.writeByte(10); out.writeInt(0); }));
        assertFalse(emptyStrings.sameStateAs(emptyCompounds));
    }
}
