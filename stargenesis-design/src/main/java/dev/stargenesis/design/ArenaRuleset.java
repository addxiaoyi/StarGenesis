package dev.stargenesis.design;

import dev.stargenesis.api.design.DesignDocument;
import dev.stargenesis.api.design.DesignKind;
import dev.stargenesis.api.design.DesignMetadata;
import java.util.List;
import java.util.Objects;

/** Immutable arena design. Legacy schema 1 has no layout or victory conditions. */
public record ArenaRuleset(DesignMetadata metadata, int width, int height, long durationSteps,
                           int unitBudget, Layout layout, Victory victory) implements DesignDocument {
    public static final int SCHEMA_VERSION = 2;
    public static final int MAX_DIMENSION = 128;
    public static final int MAX_OBSTACLES = 512;
    public static final int MAX_TEAMS = 8;
    public static final int MAX_TEAM_SPAWNS = 16;
    public static final int MAX_OBJECTIVES = 16;
    public static final int MAX_OBJECTIVE_POINTS = 72_000 * MAX_OBJECTIVES;

    public ArenaRuleset {
        Objects.requireNonNull(metadata, "metadata");
        Checks.range(width, 4, MAX_DIMENSION, "width");
        Checks.range(height, 4, MAX_DIMENSION, "height");
        Checks.range(durationSteps, 1, 72_000, "durationSteps");
        Checks.range(unitBudget, 1, 64, "unitBudget");
        switch (metadata.schemaVersion()) {
            case DesignCodec.SCHEMA_VERSION -> {
                if (layout != null || victory != null) Checks.fail("Schema 1 has no arena layout or victory rules");
            }
            case SCHEMA_VERSION -> {
                Objects.requireNonNull(layout, "layout");
                Objects.requireNonNull(victory, "victory");
            }
            default -> Checks.fail("Unsupported arena schemaVersion");
        }
    }

    public ArenaRuleset(DesignMetadata metadata, int width, int height, long durationSteps, int unitBudget) {
        this(metadata, width, height, durationSteps, unitBudget, null, null);
    }

    @Override public DesignKind kind() { return DesignKind.ARENA_RULESET; }

    ArenaRuleset withMetadata(DesignMetadata updated) {
        return new ArenaRuleset(updated, width, height, durationSteps, unitBudget, layout, victory);
    }

    public record Cell(int x, int y) {
        public Cell {
            Checks.range(x, 0, MAX_DIMENSION - 1, "cell x");
            Checks.range(y, 0, MAX_DIMENSION - 1, "cell y");
        }
    }

    public record Team(String id, List<Cell> spawns) {
        public Team {
            if (id == null || !id.matches("[a-z][a-z0-9_-]{0,23}")) Checks.fail("Invalid arena team ID");
            spawns = List.copyOf(spawns);
            Checks.range(spawns.size(), 1, MAX_TEAM_SPAWNS, "team spawn count");
            Checks.unique(spawns, cell -> cell, "team spawn cell");
        }
    }

    public record Layout(List<Cell> obstacles, List<Team> teams, List<Cell> objectives) {
        public Layout {
            obstacles = List.copyOf(obstacles);
            teams = List.copyOf(teams);
            objectives = List.copyOf(objectives);
            Checks.range(obstacles.size(), 0, MAX_OBSTACLES, "obstacle count");
            Checks.range(teams.size(), 2, MAX_TEAMS, "team count");
            Checks.range(objectives.size(), 1, MAX_OBJECTIVES, "objective count");
            Checks.unique(obstacles, cell -> cell, "obstacle cell");
            Checks.unique(teams, Team::id, "team ID");
            Checks.unique(objectives, cell -> cell, "objective cell");
        }
    }

    public enum Combination { ANY, ALL }

    public enum Metric {
        CONTROLLED_OBJECTIVES(MAX_OBJECTIVES),
        OBJECTIVE_POINTS(MAX_OBJECTIVE_POINTS);

        private final int max;

        Metric(int max) { this.max = max; }
    }

    public record Condition(Metric metric, int threshold) {
        public Condition {
            Objects.requireNonNull(metric, "metric");
            Checks.range(threshold, 1, metric.max, "victory threshold");
        }

        boolean met(int controlledObjectives, long objectivePoints) {
            return switch (metric) {
                case CONTROLLED_OBJECTIVES -> controlledObjectives >= threshold;
                case OBJECTIVE_POINTS -> objectivePoints >= threshold;
            };
        }
    }

    public record Victory(Combination combination, List<Condition> conditions) {
        public Victory {
            Objects.requireNonNull(combination, "combination");
            conditions = List.copyOf(conditions);
            Checks.range(conditions.size(), 1, Metric.values().length, "victory condition count");
            Checks.unique(conditions, Condition::metric, "victory metric");
        }

        public boolean met(int controlledObjectives, long objectivePoints) {
            return switch (combination) {
                case ANY -> conditions.stream().anyMatch(condition -> condition.met(controlledObjectives, objectivePoints));
                case ALL -> conditions.stream().allMatch(condition -> condition.met(controlledObjectives, objectivePoints));
            };
        }
    }
}
