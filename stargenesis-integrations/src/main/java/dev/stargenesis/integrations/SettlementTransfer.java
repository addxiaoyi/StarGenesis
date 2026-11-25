package dev.stargenesis.integrations;

/** One reversible, bounded transfer supplied by a verified settlement adapter. */
public record SettlementTransfer(String sourceAccount, String targetAccount, String assetId, long amount) {
    public static final int MAX_IDENTIFIER_LENGTH = 128;
    public static final long MAX_AMOUNT = 1_000_000_000_000L;

    public SettlementTransfer {
        sourceAccount = requireIdentifier(sourceAccount, "source account");
        targetAccount = requireIdentifier(targetAccount, "target account");
        assetId = requireIdentifier(assetId, "asset ID");
        if (sourceAccount.equals(targetAccount)) throw new IllegalArgumentException("Transfer accounts must differ");
        if (amount < 1 || amount > MAX_AMOUNT) throw new IllegalArgumentException("Transfer amount exceeds its finite bound");
    }

    private static String requireIdentifier(String value, String label) {
        if (value == null || value.isBlank() || value.length() > MAX_IDENTIFIER_LENGTH
                || value.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("Invalid " + label);
        return value;
    }
}
