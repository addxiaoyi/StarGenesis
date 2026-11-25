package dev.stargenesis.sim;

import dev.stargenesis.design.ArenaRuleset;
import dev.stargenesis.design.ArenaRuleset.Cell;
import dev.stargenesis.design.ArenaRuleset.Combination;
import dev.stargenesis.design.ArenaRuleset.Condition;
import dev.stargenesis.design.ArenaRuleset.Layout;
import dev.stargenesis.design.ArenaRuleset.Metric;
import dev.stargenesis.design.ArenaRuleset.Team;
import dev.stargenesis.design.ArenaRuleset.Victory;
import dev.stargenesis.design.DesignCodec;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ArenaMatchWorldTest {
    private final ArenaRuleset template = template();

    @Test
    void deterministicActionsCaptureObjectivesAccumulatePointsAndDeclareWinner() {
        ArenaMatchWorld world = new ArenaMatchWorld(arena(12,
                victory(Combination.ALL, new Condition(Metric.CONTROLLED_OBJECTIVES, 1),
                        new Condition(Metric.OBJECTIVE_POINTS, 2)), List.of(),
                List.of(new Cell(1, 1)), List.of(new Cell(0, 0), new Cell(3, 3))));
        assertEquals(ArenaMatchWorld.Outcome.ACTIVE, world.snapshot().outcome());

        assertEquals(ArenaMatchWorld.Outcome.ACTIVE, world.step(actions(ArenaMatchWorld.Action.EAST,
                ArenaMatchWorld.Action.WAIT)).outcome());
        ArenaMatchWorld.StepResult captured = world.step(actions(ArenaMatchWorld.Action.SOUTH,
                ArenaMatchWorld.Action.WAIT));
        assertEquals(1, points(captured, "red"));
        assertEquals(ArenaMatchWorld.Outcome.ACTIVE, captured.outcome());

        ArenaMatchWorld.StepResult won = world.step(actions(ArenaMatchWorld.Action.WAIT,
                ArenaMatchWorld.Action.WAIT));
        assertEquals(ArenaMatchWorld.Outcome.TEAM_WON, won.outcome());
        assertEquals(List.of("red"), won.winners());
        assertEquals(2, points(won, "red"));
        assertThrows(IllegalStateException.class, () -> world.step(actions(ArenaMatchWorld.Action.WAIT,
                ArenaMatchWorld.Action.WAIT)));
    }

    @Test
    void invalidOrCollidingActionsLeaveTheEntireTickUnchanged() {
        ArenaMatchWorld blocked = new ArenaMatchWorld(arena(12, victory(Combination.ANY,
                new Condition(Metric.CONTROLLED_OBJECTIVES, 1))));
        ArenaMatchWorld.Snapshot initial = blocked.snapshot();
        assertThrows(IllegalArgumentException.class, () -> blocked.step(actions(ArenaMatchWorld.Action.EAST,
                ArenaMatchWorld.Action.WAIT)));
        assertEquals(initial, blocked.snapshot());

        ArenaMatchWorld collision = new ArenaMatchWorld(arena(12, victory(Combination.ANY,
                new Condition(Metric.CONTROLLED_OBJECTIVES, 1)),
                List.of(), List.of(new Cell(1, 0)), List.of(new Cell(0, 1), new Cell(2, 1))));
        ArenaMatchWorld.Snapshot beforeCollision = collision.snapshot();
        assertThrows(IllegalArgumentException.class, () -> collision.step(Map.of(
                "red:1", ArenaMatchWorld.Action.EAST, "blue:1", ArenaMatchWorld.Action.WEST)));
        assertEquals(beforeCollision, collision.snapshot());
    }

    @Test
    void everyUnitMustActAndInputMapOrderDoesNotAffectResults() {
        ArenaMatchWorld first = new ArenaMatchWorld(arena(12, victory(Combination.ANY,
                new Condition(Metric.CONTROLLED_OBJECTIVES, 1))));
        ArenaMatchWorld second = new ArenaMatchWorld(arena(12, victory(Combination.ANY,
                new Condition(Metric.CONTROLLED_OBJECTIVES, 1))));
        assertThrows(IllegalArgumentException.class,
                () -> first.step(Map.of("red:1", ArenaMatchWorld.Action.WAIT)));

        Map<String, ArenaMatchWorld.Action> insertionOne = new LinkedHashMap<>();
        insertionOne.put("red:1", ArenaMatchWorld.Action.WAIT);
        insertionOne.put("blue:1", ArenaMatchWorld.Action.WAIT);
        Map<String, ArenaMatchWorld.Action> insertionTwo = new LinkedHashMap<>();
        insertionTwo.put("blue:1", ArenaMatchWorld.Action.WAIT);
        insertionTwo.put("red:1", ArenaMatchWorld.Action.WAIT);
        assertEquals(first.step(insertionOne), second.step(insertionTwo));
    }

    @Test
    void contestedTeamMovesResolveToWaitAndMatchCheckpointsVerifyOnRestore() {
        ArenaRuleset ruleset = arena(12, victory(Combination.ANY,
                new Condition(Metric.CONTROLLED_OBJECTIVES, 1)), List.of(), List.of(new Cell(1, 1)),
                List.of(new Cell(0, 0), new Cell(2, 0)));
        ArenaMatchWorld world = new ArenaMatchWorld(ruleset);
        ArenaMatchWorld.StepResult contested = world.stepContested(Map.of(
                "red:1", ArenaMatchWorld.Action.EAST, "blue:1", ArenaMatchWorld.Action.WEST));
        assertEquals(Map.of("blue:1", new Cell(2, 0), "red:1", new Cell(0, 0)),
                contested.units().stream().collect(java.util.stream.Collectors.toMap(
                        ArenaMatchWorld.Unit::id, unit -> new Cell(unit.x(), unit.y()))));
        assertEquals(1, contested.tick());
        ArenaMatchWorld.Checkpoint checkpoint = world.checkpoint();
        ArenaMatchWorld restored = ArenaMatchWorld.restore(ruleset, checkpoint);
        assertEquals(world.snapshot(), restored.snapshot());
        assertThrows(IllegalArgumentException.class, () -> ArenaMatchWorld.restore(ruleset,
                new ArenaMatchWorld.Checkpoint(checkpoint.tick(), checkpoint.positions(), checkpoint.teamPoints(), "0".repeat(64))));
    }

    @Test
    void durationEndsWithARepeatableDrawWhenScoresAndControlAreEqual() {
        ArenaMatchWorld world = new ArenaMatchWorld(arena(6, victory(Combination.ANY,
                new Condition(Metric.CONTROLLED_OBJECTIVES, 1))));
        ArenaMatchWorld.StepResult result = null;
        for (int step = 0; step < 6; step++) {
            result = world.step(actions(ArenaMatchWorld.Action.WAIT, ArenaMatchWorld.Action.WAIT));
        }
        assertNotNull(result);
        assertEquals(ArenaMatchWorld.Outcome.DRAW, result.outcome());
        assertTrue(result.winners().isEmpty());
        assertEquals(6, result.tick());
    }

    @Test
    void snapshotCarriesTheValidatedBoardAndIsOwnedByItsCreatingThread() throws Exception {
        ArenaMatchWorld world = new ArenaMatchWorld(arena(12, victory(Combination.ANY,
                new Condition(Metric.CONTROLLED_OBJECTIVES, 1))));
        ArenaMatchWorld.Snapshot snapshot = world.snapshot();
        assertTrue(snapshot.obstacles().contains(new Cell(1, 0)));
        assertEquals("arena/1", snapshot.engineVersion());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.units().clear());

        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread foreignThread = new Thread(() -> {
            try {
                world.snapshot();
            } catch (Throwable thrown) {
                failure.set(thrown);
            }
        });
        foreignThread.start();
        foreignThread.join();
        assertInstanceOf(IllegalStateException.class, failure.get());
    }

    private ArenaRuleset arena(long duration, Victory victory) {
        return arena(duration, victory, List.of(new Cell(1, 0)), List.of(new Cell(1, 1)),
                List.of(new Cell(0, 0), new Cell(3, 3)));
    }

    private ArenaRuleset arena(long duration, Victory victory, List<Cell> obstacles, List<Cell> objectives,
                               List<Cell> spawns) {
        Layout layout = new Layout(obstacles, List.of(new Team("red", List.of(spawns.getFirst())),
                new Team("blue", List.of(spawns.getLast()))), objectives);
        return new ArenaRuleset(template.metadata(), 4, 4, duration, 2, layout, victory);
    }

    private static Victory victory(Combination combination, Condition... conditions) {
        return new Victory(combination, List.of(conditions));
    }

    private static long points(ArenaMatchWorld.StepResult result, String teamId) {
        return result.teams().stream().filter(team -> team.id().equals(teamId))
                .mapToLong(ArenaMatchWorld.TeamScore::objectivePoints).findFirst().orElseThrow();
    }

    private static Map<String, ArenaMatchWorld.Action> actions(ArenaMatchWorld.Action red,
                                                                ArenaMatchWorld.Action blue) {
        return Map.of("red:1", red, "blue:1", blue);
    }

    private static ArenaRuleset template() {
        try (InputStream input = ArenaMatchWorldTest.class.getResourceAsStream("/examples/arena_ruleset-v2.json")) {
            if (input == null) throw new IllegalStateException("Arena ruleset test fixture is missing");
            return (ArenaRuleset) new DesignCodec().load(new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception failure) {
            throw new ExceptionInInitializerError(failure);
        }
    }
}
