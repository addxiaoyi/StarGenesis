package dev.stargenesis.sim;
public enum ResourceType {
    ORE("ore", 2), BIOMASS("biomass", 1);
    private final String ledgerKey;
    private final int score;
    ResourceType(String ledgerKey, int score) { this.ledgerKey = ledgerKey; this.score = score; }
    public String ledgerKey() { return ledgerKey; }
    public int scorePerUnit() { return score; }
}
