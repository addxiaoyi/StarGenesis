package dev.stargenesis.design;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import dev.stargenesis.design.ArenaRuleset.Cell;
import dev.stargenesis.design.ArenaRuleset.Team;

/** Deterministic checks for arena budget, topology and objective feasibility. */
public final class ArenaRulesetValidator {
    public static final long MAX_WORK_UNITS = 16_777_216L;

    public Analysis analyze(ArenaRuleset ruleset) {
        Objects.requireNonNull(ruleset, "ruleset");
        int cells = ruleset.width() * ruleset.height();
        long workUnits = cells * ruleset.unitBudget() * ruleset.durationSteps();
        List<String> issues = new ArrayList<>();
        int reachableCells = ruleset.layout() == null ? cells : analyzeLayout(ruleset, issues);
        if (workUnits > MAX_WORK_UNITS)
            issues.add("Estimated arena work " + workUnits + " exceeds " + MAX_WORK_UNITS);
        return new Analysis(cells, workUnits, reachableCells, issues);
    }

    public Analysis requireValid(ArenaRuleset ruleset) {
        Analysis analysis = analyze(ruleset);
        if (!analysis.valid()) throw new IllegalArgumentException(String.join("; ", analysis.issues()));
        return analysis;
    }

    private static int analyzeLayout(ArenaRuleset ruleset, List<String> issues) {
        var layout = ruleset.layout();
        int width = ruleset.width();
        int height = ruleset.height();
        Map<Cell, String> occupied = new HashMap<>();
        layout.obstacles().forEach(cell -> checkCell(cell, "Obstacle", width, height, occupied, issues));
        for (Team team : layout.teams()) {
            for (Cell spawn : team.spawns()) checkCell(spawn, "Spawn for " + team.id(), width, height, occupied, issues);
        }
        layout.objectives().forEach(cell -> checkCell(cell, "Objective", width, height, occupied, issues));
        if (!issues.isEmpty()) return 0;

        int spawnCount = layout.teams().stream().mapToInt(team -> team.spawns().size()).sum();
        if (spawnCount > ruleset.unitBudget()) issues.add("Arena spawn count exceeds the unit budget");

        boolean[] blocked = new boolean[width * height];
        for (Cell obstacle : layout.obstacles()) blocked[index(obstacle, width)] = true;
        Cell first = layout.teams().getFirst().spawns().getFirst();
        int[] component = distances(width, blocked, List.of(first));
        for (Team team : layout.teams()) {
            for (Cell spawn : team.spawns()) {
                if (component[index(spawn, width)] < 0)
                    issues.add("Spawn for " + team.id() + " at " + coordinate(spawn) + " is disconnected");
            }
        }
        for (Cell objective : layout.objectives()) {
            if (component[index(objective, width)] < 0)
                issues.add("Objective at " + coordinate(objective) + " is unreachable from the starting area");
        }
        if (issues.isEmpty()) {
            for (Team team : layout.teams()) {
                int[] fromTeam = team.spawns().size() == 1 && team.spawns().getFirst().equals(first)
                        ? component : distances(width, blocked, team.spawns());
                if (!couldMeetVictory(ruleset, team, fromTeam))
                    issues.add("Team " + team.id() + " cannot meet the victory conditions within the round");
            }
        }
        return (int) Arrays.stream(component).filter(distance -> distance >= 0).count();
    }

    private static boolean couldMeetVictory(ArenaRuleset ruleset, Team team, int[] distances) {
        List<Long> possiblePointsByObjective = new ArrayList<>();
        for (Cell objective : ruleset.layout().objectives()) {
            int distance = distances[index(objective, ruleset.width())];
            if (distance > ruleset.durationSteps()) continue;
            possiblePointsByObjective.add(ruleset.durationSteps() - distance + 1);
        }
        int maxControlled = Math.min(possiblePointsByObjective.size(), team.spawns().size());
        long possiblePoints = possiblePointsByObjective.stream().sorted(java.util.Comparator.reverseOrder())
                .limit(maxControlled).mapToLong(Long::longValue).sum();
        return ruleset.victory().met(maxControlled, possiblePoints);
    }

    private static void checkCell(Cell cell, String label, int width, int height, Map<Cell, String> occupied,
                                  List<String> issues) {
        if (cell.x() >= width || cell.y() >= height) {
            issues.add(label + " at " + coordinate(cell) + " is outside the arena");
            return;
        }
        String previous = occupied.putIfAbsent(cell, label);
        if (previous != null) issues.add(label + " overlaps " + previous + " at " + coordinate(cell));
    }

    private static String coordinate(Cell cell) { return "(" + cell.x() + ", " + cell.y() + ")"; }

    private static int index(Cell cell, int width) { return cell.y() * width + cell.x(); }

    private static int[] distances(int width, boolean[] blocked, List<Cell> starts) {
        int[] distances = new int[blocked.length];
        Arrays.fill(distances, -1);
        int[] queue = new int[blocked.length];
        int tail = 0;
        for (Cell start : starts) {
            int at = index(start, width);
            distances[at] = 0;
            queue[tail++] = at;
        }
        for (int head = 0; head < tail; head++) {
            int at = queue[head];
            int nextDistance = distances[at] + 1;
            if (at % width > 0) tail = visit(at - 1, nextDistance, tail, blocked, distances, queue);
            if (at % width < width - 1) tail = visit(at + 1, nextDistance, tail, blocked, distances, queue);
            if (at >= width) tail = visit(at - width, nextDistance, tail, blocked, distances, queue);
            if (at < blocked.length - width) tail = visit(at + width, nextDistance, tail, blocked, distances, queue);
        }
        return distances;
    }

    private static int visit(int at, int distance, int tail, boolean[] blocked, int[] distances, int[] queue) {
        if (blocked[at] || distances[at] >= 0) return tail;
        distances[at] = distance;
        queue[tail] = at;
        return tail + 1;
    }

    /** Work units conservatively count every unit scanning every cell on every logical step. */
    public record Analysis(long cellCount, long estimatedWorkUnits, long reachableCellCount, List<String> issues) {
        public Analysis {
            if (cellCount < 1 || estimatedWorkUnits < 1 || reachableCellCount < 0)
                throw new IllegalArgumentException("Invalid arena analysis");
            issues = List.copyOf(issues);
        }

        public boolean valid() { return issues.isEmpty(); }
    }
}
