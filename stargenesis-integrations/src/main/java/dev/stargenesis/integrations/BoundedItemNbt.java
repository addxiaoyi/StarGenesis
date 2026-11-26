package dev.stargenesis.integrations;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPInputStream;

/** Validates lengths before Paper's unlimited-heap NBT decoder can allocate arrays or lists. */
final class BoundedItemNbt {
    static final int MAX_EXPANDED_BYTES = 1_048_576, MAX_NODES = 4096, MAX_DEPTH = 32;
    // Compounds have no ordering semantics. All tag types, scalar bits, array/list order,
    // empty-list element types and the root name remain part of equality.
    record Header(int version, String material, int amount, String rootName, Tag root) {}
    private record Tag(int type, Object value) {}
    private record Sequence(int elementType, List<Tag> elements) {}
    private final DataInputStream input;
    private final byte[] expanded;
    private int remaining = MAX_NODES;
    private int version, amount = 1;
    private String material;

    private BoundedItemNbt(byte[] data) { expanded = data; input = new DataInputStream(new ByteArrayInputStream(data)); }

    static Header validate(byte[] compressed) {
        try (var gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
            byte[] expanded = gzip.readNBytes(MAX_EXPANDED_BYTES + 1);
            if (expanded.length > MAX_EXPANDED_BYTES) throw new IllegalArgumentException("Expanded item exceeds 1 MiB");
            var reader = new BoundedItemNbt(expanded);
            if (reader.input.readUnsignedByte() != 10) throw new IllegalArgumentException("Item NBT root must be a compound");
            String rootName = reader.input.readUTF();
            Tag root = reader.payload(10, 0, true);
            if (reader.input.available() != 0) throw new IllegalArgumentException("Trailing item NBT data");
            if (reader.version < 1 || reader.material == null || !reader.material.startsWith("minecraft:")
                    || reader.amount < 1 || reader.amount > 64)
                throw new IllegalArgumentException("Invalid item NBT header");
            return new Header(reader.version, ItemCatalog.requireId(reader.material), reader.amount, rootName, root);
        } catch (IOException invalid) {
            throw new IllegalArgumentException("Invalid or truncated item NBT", invalid);
        }
    }

    private Tag payload(int type, int depth, boolean root) throws IOException {
        if (depth > MAX_DEPTH || --remaining < 0) throw new IllegalArgumentException("Item NBT structure budget exceeded");
        Object value = switch (type) {
            case 1 -> bytes(1);
            case 2 -> bytes(2);
            case 3, 5 -> bytes(4);
            case 4, 6 -> bytes(8);
            case 7 -> array(1);
            case 8 -> input.readUTF();
            case 9 -> {
                int element = input.readUnsignedByte(), count = input.readInt();
                if (count < 0 || count > remaining || element > 12 || (element == 0 && count != 0))
                    throw new IllegalArgumentException("Invalid item NBT list");
                var elements = new ArrayList<Tag>(count);
                for (int i = 0; i < count; i++) elements.add(payload(element, depth + 1, false));
                yield new Sequence(element, List.copyOf(elements));
            }
            case 10 -> {
                var fields = new HashMap<String, Tag>();
                int child;
                while ((child = input.readUnsignedByte()) != 0) {
                    String name = input.readUTF();
                    if (fields.containsKey(name)) throw new IllegalArgumentException("Duplicate item NBT field");
                    Tag field = payload(child, depth + 1, false);
                    fields.put(name, field);
                    if (root && (name.equals("DataVersion") || name.equals("count") || name.equals("id"))) {
                        if (name.equals("id")) {
                            if (child != 8) throw new IllegalArgumentException("Invalid material field");
                            material = (String) field.value();
                        } else {
                            if (child != 3) throw new IllegalArgumentException("Invalid integer header field");
                            int integer = ((ByteBuffer) field.value()).getInt(0);
                            if (name.equals("DataVersion")) version = integer;
                            else amount = integer;
                        }
                    }
                }
                yield Map.copyOf(fields);
            }
            case 11 -> array(4);
            case 12 -> array(8);
            default -> throw new IllegalArgumentException("Unknown item NBT tag");
        };
        return new Tag(type, value);
    }

    private ByteBuffer array(int width) throws IOException {
        int length = input.readInt();
        if (length < 0) throw new IllegalArgumentException("Negative item NBT array length");
        return bytes((long) length * width);
    }

    private ByteBuffer bytes(long count) throws IOException {
        if (count > input.available()) throw new IllegalArgumentException("Item NBT length exceeds available data");
        var value = ByteBuffer.wrap(expanded, expanded.length - input.available(), (int) count).slice().asReadOnlyBuffer();
        input.skipNBytes(count);
        return value;
    }
}
