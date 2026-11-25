package dev.stargenesis.paper.program;

import dev.stargenesis.api.sim.SimulationBudget;
import dev.stargenesis.design.ProgramIR;
import dev.stargenesis.sim.ReplayLog;
import dev.stargenesis.sim.ReplayVerifier;
import dev.stargenesis.sim.ResourceSandboxWorld;
import dev.stargenesis.sim.SandboxConfig;
import dev.stargenesis.sim.SandboxView;

import java.util.Map;

/** Bounded command simulation. The returned evidence is immutable and the world is always closed. */
final class ProgramSimulation {
    private ProgramSimulation() { }

    static Result run(String worldId, long seed, ProgramIR program, int ticks, SimulationBudget budget) {
        if (ticks < 1 || ticks > 20) throw new IllegalArgumentException("Simulation step count outside 1..20");
        var defaults = SandboxConfig.defaults();
        var config = new SandboxConfig(defaults.width(), defaults.height(), 1, defaults.actionIntervalSteps(),
                Math.min(defaults.durationSteps(), budget.maxSteps()), defaults.energyPerAgent(), defaults.nodeStock(),
                defaults.carryCapacity(), budget);
        try (var world = new ResourceSandboxWorld(worldId, seed, config)) {
            var runner = new ProgramSandboxRunner(world, Map.of(world.view().agents().getFirst().id(), program));
            int advanced = 0, actions = 0;
            ProgramSandboxRunner.RuntimeStep last = null;
            for (int i = 0; i < ticks; i++) {
                last = runner.step();
                if (last.status() != ProgramSandboxRunner.Status.ADVANCED) break;
                advanced++;
                actions += last.actions().size();
                if (last.worldResult().terminal()) break;
            }
            var replay = runner.replayLog();
            return new Result(runner.view(), last, advanced, actions, replay, ReplayVerifier.verify(replay));
        }
    }

    record Result(SandboxView view, ProgramSandboxRunner.RuntimeStep last, int advanced, int actions,
                  ReplayLog replay, ReplayVerifier.Verification verification) { }
}
