package dev.stargenesis.integrations;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** Exact, bounded Paper item bytes. Integrity is not provenance, ownership or delivery authorization. */
public final class ItemPayload {
    public static final int MAX_BYTES = 262_144;
    public static final String FORMAT = "paper-item-nbt/1.21.11";
    private final String itemId;
    private final boolean custom;
    private final int amount;
    private final byte[] data;
    private final BoundedItemNbt.Header header;

    public ItemPayload(String itemId, boolean custom, int amount, byte[] data) {
        this.itemId = ItemCatalog.requireId(itemId);
        if (amount < 1 || amount > 64 || data == null || data.length < 1 || data.length > MAX_BYTES)
            throw new IllegalArgumentException("Item amount or encoded payload budget exceeded");
        this.custom = custom;
        this.amount = amount;
        this.data = data.clone();
        header = BoundedItemNbt.validate(this.data);
        if (header.amount() != amount || (!custom && !header.material().equals(itemId)))
            throw new IllegalArgumentException("Item envelope does not match its NBT");
    }

    public String itemId() { return itemId; }
    public boolean custom() { return custom; }
    public int amount() { return amount; }
    public String material() { return header.material(); }
    public int dataVersion() { return header.version(); }
    public byte[] data() { return data.clone(); }
    public int size() { return data.length; }
    /** Full typed NBT equality, ignoring compound-key order and GZIP representation only. */
    public boolean sameStateAs(ItemPayload other) {
        return other != null && custom == other.custom && amount == other.amount
                && itemId.equals(other.itemId) && header.equals(other.header);
    }
    public String sha256() {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
