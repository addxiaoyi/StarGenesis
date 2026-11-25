package dev.stargenesis.sim;

import dev.stargenesis.design.ArenaRuleset;
import dev.stargenesis.design.ArenaRuleset.Cell;
import dev.stargenesis.design.ArenaRuleset.Team;
import dev.stargenesis.design.ArenaRulesetValidator;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/** Deterministic, bounded team match over a published arena layout. */
public final class ArenaMatchWorld {
    public static final String ENGINE_VERSION = "arena/1";

    private final OwnerThread owner = new OwnerThread();
    private final ArenaRuleset ruleset;
    private final boolean[] blocked;
    private final List<Cell> objectives;
    private final TreeMap<String, UnitState> units = new TreeMap<>();
    private final TreeMap<String, Long> teamPoints = new TreeMap<>();
    private long tick;
    private Outcome outcome = Outcome.ACTIVE;
    private List<String> winners = List.of();
    private boolean closed;

    public ArenaMatchWorld(ArenaRuleset ruleset) {
        this.ruleset = Objects.requireNonNull(ruleset, "ruleset");
        if (ruleset.metadata().schemaVersion() != ArenaRuleset.SCHEMA_VERSION
                || ruleset.layout() == null || ruleset.victory() == null)
            throw new IllegalArgumentException("Arena matches require a schema-2 ruleset");
        new ArenaRulesetValidator().requireValid(ruleset);
        blocked = new boolean[ruleset.width() * ruleset.height()];
        ruleset.layout().obstacles().forEach(cell -> blocked[index(cell)] = true);
        objectives = ruleset.layout().objectives();
        for (Team team : ruleset.layout().teams()) {
            teamPoints.put(team.id(), 0L);
            for (int index = 0; index < team.spawns().size(); index++) {
                Cell spawn = team.spawns().get(index);
                String id = team.id() + ":" + (index + 1);
                units.put(id, new UnitState(id, team.id(), spawn.x(), spawn.y()));
            }
        }
    }

    private ArenaMatchWorld(ArenaMatchWorld original) {
        ruleset = original.ruleset;
        blocked = original.blocked;
        objectives = original.objectives;
        original.units.forEach((id, unit) -> units.put(id, new UnitState(id, unit.teamId, unit.x, unit.y)));
        teamPoints.putAll(original.teamPoints);
        tick = original.tick;
        outcome = original.outcome;
        winners = original.winners;
    }

    /** Isolates a proposed transition until its checkpoint has been committed. */
    public ArenaMatchWorld copy() {
        requireOpen();
        return new ArenaMatchWorld(this);
    }

    public Checkpoint checkpoint() {
        Snapshot snapshot = snapshot();
        Map<String, Cell> positions = new TreeMap<>();
        units.forEach((id, unit) -> positions.put(id, new Cell(unit.x, unit.y)));
        return new Checkpoint(tick, positions, teamPoints, snapshot.stateHash());
    }

    public static ArenaMatchWorld restore(ArenaRuleset ruleset, Checkpoint checkpoint) {
        ArenaMatchWorld world = new ArenaMatchWorld(ruleset);
        if (checkpoint.tick() > ruleset.durationSteps() || !checkpoint.positions().keySet().equals(world.units.keySet())
                || !checkpoint.teamPoints().keySet().equals(world.teamPoints.keySet()))
            throw new IllegalArgumentException("Arena checkpoint does not match the ruleset");
        Set<Cell> occupied = new HashSet<>();
        checkpoint.positions().forEach((id, position) -> {
            UnitState unit = world.units.get(id);
            world.requireWalkable(unit, position);
            long distance = Math.abs(unit.x - position.x()) + Math.abs(unit.y - position.y());
            if (distance > checkpoint.tick() || !occupied.add(position))
                throw new IllegalArgumentException("Invalid arena checkpoint position for " + id);
            unit.x = position.x();
            unit.y = position.y();
        });
        long totalPoints = 0;
        long pointLimit = checkpoint.tick() * world.objectives.size();
        for (long points : checkpoint.teamPoints().values()) {
            if (points < 0 || points > pointLimit) throw new IllegalArgumentException("Invalid arena checkpoint points");
            totalPoints += points;
        }
        if (totalPoints > pointLimit) throw new IllegalArgumentException("Invalid arena checkpoint total points");
        world.teamPoints.putAll(checkpoint.teamPoints());
        world.tick = checkpoint.tick();
        if (world.tick > 0) {
            Map<String, Integer> counts = new TreeMap<>();
            world.objectiveControl().values().forEach(team -> counts.merge(team, 1, Integer::sum));
            world.updateOutcome(counts);
        }
        if (!world.snapshot().stateHash().equals(checkpoint.stateHash()))
            throw new IllegalArgumentException("Arena checkpoint state hash mismatch");
        return world;
    }

    public long tick() {
        requireOpen();
        return tick;
    }

    public Snapshot snapshot() {
        requireOpen();
        return snapshotInternal();
    }

    public void validateAction(String unitId, Action action) {
        requireOpen();
        UnitState unit = units.get(unitId);
        if (unit == null) throw new IllegalArgumentException("Unknown arena unit " + unitId);
        requireWalkable(unit, destination(unit, Objects.requireNonNull(action, "action")));
    }

    /** Resolves contested moves to waits, including any moves blocked by the units that now wait. */
    public StepResult stepContested(Map<String, Action> actions) {
        requireOpen();
        TreeMap<String, Action> resolved = new TreeMap<>(actions);
        if (!resolved.keySet().equals(units.keySet()))
            throw new IllegalArgumentException("Every arena unit must provide exactly one action");
        resolved.forEach(this::validateAction);
        boolean changed;
        do {
            Map<Cell, String> firstArrival = new HashMap<>();
            Set<String> contested = new HashSet<>();
            for (UnitState unit : units.values()) {
                Cell target = destination(unit, resolved.get(unit.id));
                String first = firstArrival.putIfAbsent(target, unit.id);
                if (first != null) {
                    contested.add(first);
                    contested.add(unit.id);
                }
            }
            changed = false;
            for (String unitId : contested) {
                changed |= resolved.put(unitId, Action.WAIT) != Action.WAIT;
            }
        } while (changed);
        return step(resolved);
    }

    public StepResult step(Map<String, Action> actions) {
        requireOpen();
        if (outcome != Outcome.ACTIVE) throw new IllegalStateException("Arena match has already ended");
        Objects.requireNonNull(actions, "actions");
        if (tick >= ruleset.durationSteps()) throw new IllegalStateException("Arena duration is exhausted");
        TreeMap<String, Action> orderedActions = new TreeMap<>(actions);
        if (orderedActions.size() != units.size() || !orderedActions.keySet().equals(units.keySet()))
            throw new IllegalArgumentException("Every arena unit must provide exactly one action");

        TreeMap<String, Cell> destinations = new TreeMap<>();
        Set<Long> occupied = new HashSet<>();
        for (UnitState unit : units.values()) {
            Action action = Objects.requireNonNull(orderedActions.get(unit.id), "action");
            Cell destination = destination(unit, action);
            requireWalkable(unit, destination);
            long position = positionKey(destination);
            if (!occupied.add(position)) throw new IllegalArgumentException("Arena units cannot occupy the same cell");
            destinations.put(unit.id, destination);
        }

        for (Map.Entry<String, Cell> entry : destinations.entrySet()) {
            UnitState unit = units.get(entry.getKey());
            unit.x = entry.getValue().x();
            unit.y = entry.getValue().y();
        }
        tick++;
        Map<Cell, String> control = objectiveControl();
        Map<String, Integer> controlledCounts = new TreeMap<>();
        control.values().forEach(team -> controlledCounts.merge(team, 1, Integer::sum));
        controlledCounts.forEach((team, count) -> teamPoints.merge(team, (long) count, Math::addExact));
        updateOutcome(controlledCounts);

        Snapshot snapshot = snapshotInternal(controlledCounts, control);
        return new StepResult(tick, outcome, winners, snapshot.teams(), snapshot.objectives(), snapshot.units(), snapshot.stateHash());
    }

    public void close() {
        requireOpen();
        closed = true;
        units.clear();
        teamPoints.clear();
    }

    private void updateOutcome(Map<String, Integer> controlledCounts) {
        List<String> qualifying = new ArrayList<>();
        for (Map.Entry<String, Long> team : teamPoints.entrySet()) {
            if (ruleset.victory().met(controlledCounts.getOrDefault(team.getKey(), 0), team.getValue()))
                qualifying.add(team.getKey());
        }
        if (!qualifying.isEmpty()) {
            outcome = qualifying.size() == 1 ? Outcome.TEAM_WON : Outcome.DRAW;
            winners = qualifying.size() == 1 ? List.copyOf(qualifying) : List.of();
            return;
        }
        if (tick < ruleset.durationSteps()) return;

        long highestPoints = teamPoints.values().stream().mapToLong(Long::longValue).max().orElse(0);
        int highestControlled = teamPoints.keySet().stream()
                .filter(team -> teamPoints.get(team) == highestPoints)
                .mapToInt(team -> controlledCounts.getOrDefault(team, 0)).max().orElse(0);
        List<String> ranked = teamPoints.keySet().stream()
                .filter(team -> teamPoints.get(team) == highestPoints
                        && controlledCounts.getOrDefault(team, 0) == highestControlled)
                .toList();
        outcome = ranked.size() == 1 ? Outcome.TEAM_WON : Outcome.DRAW;
        winners = ranked.size() == 1 ? ranked : List.of();
    }

    private Map<Cell, String> objectiveControl() {
        Map<Cell, String> occupancy = new HashMap<>();
        for (UnitState unit : units.values()) occupancy.put(new Cell(unit.x, unit.y), unit.teamId);
        Map<Cell, String> controlled = new HashMap<>();
        for (Cell objective : objectives) {
            String team = occupancy.get(objective);
            if (team != null) controlled.put(objective, team);
        }
        return controlled;
    }

    private Snapshot snapshotInternal() {
        Map<Cell, String> control = objectiveControl();
        Map<String, Integer> counts = new TreeMap<>();
        control.values().forEach(team -> counts.merge(team, 1, Integer::sum));
        return snapshotInternal(counts, control);
    }

    private Snapshot snapshotInternal(Map<String, Integer> controlledCounts, Map<Cell, String> control) {
        List<Unit> unitViews = units.values().stream()
                .map(unit -> new Unit(unit.id, unit.teamId, unit.x, unit.y)).toList();
        List<TeamScore> teamViews = teamPoints.entrySet().stream()
                .map(entry -> new TeamScore(entry.getKey(), controlledCounts.getOrDefault(entry.getKey(), 0), entry.getValue()))
                .toList();
        List<ObjectiveState> objectiveViews = objectives.stream()
                .map(cell -> new ObjectiveState(cell, control.get(cell))).toList();
        String hash = stateHash(unitViews, teamViews, objectiveViews);
        return new Snapshot(ENGINE_VERSION, ruleset.metadata().id().value(), ruleset.metadata().version(),
                ruleset.metadata().hash(), ruleset.durationSteps(), tick, ruleset.width(), ruleset.height(),
                outcome, winners, ruleset.layout().obstacles(), unitViews, teamViews, objectiveViews, hash);
    }

    private String stateHash(List<Unit> unitViews, List<TeamScore> teamViews, List<ObjectiveState> objectiveViews) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeUTF(ENGINE_VERSION);
                output.writeUTF(ruleset.metadata().id().value());
                output.writeUTF(ruleset.metadata().version());
                output.writeUTF(ruleset.metadata().hash());
                output.writeLong(ruleset.durationSteps());
                output.writeLong(tick);
                output.writeInt(ruleset.width());
                output.writeInt(ruleset.height());
                output.writeInt(ruleset.layout().obstacles().size());
                for (Cell obstacle : ruleset.layout().obstacles()) {
                    output.writeInt(obstacle.x());
                    output.writeInt(obstacle.y());
                }
                output.writeInt(ruleset.victory().combination().ordinal());
                output.writeInt(ruleset.victory().conditions().size());
                for (var condition : ruleset.victory().conditions()) {
                    output.writeInt(condition.metric().ordinal());
                    output.writeInt(condition.threshold());
                }
                output.writeInt(outcome.ordinal());
                output.writeInt(winners.size());
                for (String winner : winners) output.writeUTF(winner);
                output.writeInt(unitViews.size());
                for (Unit unit : unitViews) {
                    output.writeUTF(unit.id());
                    output.writeUTF(unit.teamId());
                    output.writeInt(unit.x());
                    output.writeInt(unit.y());
                }
                output.writeInt(teamViews.size());
                for (TeamScore team : teamViews) {
                    output.writeUTF(team.id());
                    output.writeInt(team.controlledObjectives());
                    output.writeLong(team.objectivePoints());
                }
                output.writeInt(objectiveViews.size());
                for (ObjectiveState objective : objectiveViews) {
                    output.writeInt(objective.cell().x());
                    output.writeInt(objective.cell().y());
                    output.writeBoolean(objective.controlledBy() != null);
                    if (objective.controlledBy() != null) output.writeUTF(objective.controlledBy());
                }
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException impossible) {
            throw new IllegalStateException("Cannot hash arena state", impossible);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private Cell destination(UnitState unit, Action action) {
        return switch (action) {
            case WAIT -> new Cell(unit.x, unit.y);
            case NORTH -> new Cell(unit.x, unit.y - 1);
            case EAST -> new Cell(unit.x + 1, unit.y);
            case SOUTH -> new Cell(unit.x, unit.y + 1);
            case WEST -> new Cell(unit.x - 1, unit.y);
        };
    }

    private void requireWalkable(UnitState unit, Cell position) {
        if (position.x() < 0 || position.y() < 0 || position.x() >= ruleset.width() || position.y() >= ruleset.height())
            throw new IllegalArgumentException("Arena unit action leaves the map: " + unit.id);
        if (blocked[index(position)]) throw new IllegalArgumentException("Arena unit action enters an obstacle: " + unit.id);
    }

    private int index(Cell cell) { return cell.y() * ruleset.width() + cell.x(); }

    private static long positionKey(Cell cell) { return ((long) cell.y() << 32) | Integer.toUnsignedLong(cell.x()); }

    private void requireOpen() {
        owner.check();
        if (closed) throw new IllegalStateException("Arena match world is closed");
    }

    public enum Action { WAIT, NORTH, EAST, SOUTH, WEST }
    public enum Outcome { ACTIVE, TEAM_WON, DRAW }

    public record Unit(String id, String teamId, int x, int y) { }

    public record TeamScore(String id, int controlledObjectives, long objectivePoints) { }

    public record ObjectiveState(Cell cell, String controlledBy) { }

    public record Checkpoint(long tick, Map<String, Cell> positions, Map<String, Long> teamPoints, String stateHash) {
        public Checkpoint {
            if (tick < 0 || stateHash == null || !stateHash.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid arena checkpoint");
            positions = Map.copyOf(positions);
            teamPoints = Map.copyOf(teamPoints);
        }
    }

    public record Snapshot(String engineVersion, String rulesetId, String rulesetVersion, String rulesetHash,
                           long durationSteps, long tick, int width, int height, Outcome outcome,
                           List<String> winners, List<Cell> obstacles, List<Unit> units, List<TeamScore> teams,
                           List<ObjectiveState> objectives, String stateHash) {
        public Snapshot {
            if (!ENGINE_VERSION.equals(engineVersion) || rulesetId == null || rulesetVersion == null
                    || rulesetHash == null || !rulesetHash.matches("[0-9a-f]{64}")
                    || durationSteps < 1 || tick < 0 || tick > durationSteps
                    || width < 4 || width > 128 || height < 4 || height > 128 || outcome == null
                    || stateHash == null || !stateHash.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid arena snapshot");
            winners = List.copyOf(winners);
            obstacles = List.copyOf(obstacles);
            units = List.copyOf(units);
            teams = List.copyOf(teams);
            objectives = List.copyOf(objectives);
            if (outcome == Outcome.ACTIVE && !winners.isEmpty()
                    || outcome == Outcome.TEAM_WON && winners.size() != 1
                    || outcome == Outcome.DRAW && !winners.isEmpty()
                    || outcome == Outcome.ACTIVE && tick == durationSteps
                    || outcome != Outcome.ACTIVE && tick == 0)
                throw new IllegalArgumentException("Invalid arena match result");
        }
    }

    public record StepResult(long tick, Outcome outcome, List<String> winners, List<TeamScore> teams,
                             List<ObjectiveState> objectives, List<Unit> units, String stateHash) {
        public StepResult {
            if (tick < 1 || outcome == null || stateHash == null || !stateHash.matches("[0-9a-f]{64}"))
                throw new IllegalArgumentException("Invalid arena step result");
            winners = List.copyOf(winners);
            teams = List.copyOf(teams);
            objectives = List.copyOf(objectives);
            units = List.copyOf(units);
            if (outcome == Outcome.ACTIVE && !winners.isEmpty()
                    || outcome == Outcome.TEAM_WON && winners.size() != 1
                    || outcome == Outcome.DRAW && !winners.isEmpty())
                throw new IllegalArgumentException("Invalid arena match result");
        }
    }

    private static final class UnitState {
        private final String id;
        private final String teamId;
        private int x;
        private int y;

        private UnitState(String id, String teamId, int x, int y) {
            this.id = id;
            this.teamId = teamId;
            this.x = x;
            this.y = y;
        }
    }
}
