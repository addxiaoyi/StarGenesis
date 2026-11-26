package dev.stargenesis.sim;

public record GridPoint(int x, int y) {
    public GridPoint {
        if (x < 0 || x > 31 || y < 0 || y > 31) throw new IllegalArgumentException("Position outside sandbox hard bounds");
    }
    public int distance(GridPoint other) { return Math.abs(x - other.x) + Math.abs(y - other.y); }
}
